package uk.co.chrishackman.hacktrack

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

class LocationService : Service() {

    companion object {
        const val CHANNEL_ID = "hacktrack_tracking"
        const val NOTIFICATION_ID = 1

        const val ACTION_STATUS =
            "uk.co.chrishackman.hacktrack.STATUS"

        private const val TRACKER_URL =
            "https://walker-tracker.chris-hackman.workers.dev/update"

        private const val HDOP = 5.0

        // Don't store another point while the location has
        // moved less than this distance during an outage.
        private const val OFFLINE_DISTANCE_METRES = 2.0

        const val EXTRA_TRACKING =
            "tracking"

        const val EXTRA_LAT =
            "lat"

        const val EXTRA_LON =
            "lon"

        const val EXTRA_SPEED_KPH =
            "speedKph"

        const val EXTRA_BATTERY =
            "battery"

        const val EXTRA_PENDING =
            "pending"

        const val EXTRA_ONLINE =
            "online"

        const val EXTRA_STOPPING =
            "stopping"

        const val ACTION_TRIM_BUFFER =
            "uk.co.chrishackman.hacktrack.TRIM_BUFFER"

        const val ACTION_START =
            "uk.co.chrishackman.hacktrack.START"

        const val ACTION_STOP_ACQUISITION =
            "uk.co.chrishackman.hacktrack.STOP_ACQUISITION"
    }

    private lateinit var fusedLocationClient:
            FusedLocationProviderClient

    private lateinit var locationRequest:
            LocationRequest

    private lateinit var database:
            PendingPointDatabase

    private var latestLocation: Location? = null

    private var lastStoredOfflineLocation: Location? = null

    private var uploaderJob: Job? = null

    private var isStopping = false

    private val workSignal =
        kotlinx.coroutines.channels.Channel<Unit>(
            kotlinx.coroutines.channels.Channel.CONFLATED
        )

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )

    private val locationCallback =
        object : LocationCallback() {

            override fun onLocationResult(
                result: LocationResult
            ) {

                val location =
                    result.lastLocation ?: return

                latestLocation = location

                storeIfMeaningful(location)

                updateNotification()
            }
        }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        database =
            PendingPointDatabase(this)

        fusedLocationClient =
            LocationServices
                .getFusedLocationProviderClient(this)

        locationRequest =
            LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                5000L
            )
                .setMinUpdateIntervalMillis(5000L)
                .setMaxUpdateDelayMillis(5000L)
                .build()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_START) {
            database.clear()
        }

        if (intent?.action == ACTION_STOP_ACQUISITION) {

            fusedLocationClient.removeLocationUpdates(
                locationCallback
            )

            isStopping = true

            if (database.count() == 0) {
                stopSelf()
            } else {
                updateNotification()
            }

            return START_STICKY
        }

        if (
            intent?.action ==
            ACTION_TRIM_BUFFER
        ) {

            trimOfflineBuffer()

            updateNotification()

            return START_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Waiting for GPS…")
        )

        startLocationUpdates()
        startUploader()

        return START_STICKY
    }

    private fun startLocationUpdates() {

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            mainLooper
        )
    }

    private fun startUploader() {

        if (uploaderJob?.isActive == true) {
            return
        }

        uploaderJob =
            serviceScope.launch {

                while (isActive) {

                    /*
                     * Wait for work.
                     */
                    workSignal.receive()

                    /*
                     * Drain the database.
                     */
                    while (isActive) {

                        val point =
                            database.oldest()

                        if (point == null) {

                            if (isStopping) {
                                stopSelf()
                            }

                            break
                        }

                        val success =
                            sendPendingPoint(point)

                        if (!success) {

                            /*
                             * Network failure. Wait 5 seconds
                             * before retrying.
                             */
                            updateNotification()
                            delay(5000L)
                            continue
                        }

                        database.delete(point.id)

                        updateNotification()
                    }
                }
            }

        /*
         * Initial poke to drain anything left
         * from a previous run.
         */
        workSignal.trySend(Unit)
    }

    private fun storeIfMeaningful(
        location: Location
    ) {

        val previous =
            lastStoredOfflineLocation

        if (previous != null) {

            val distance =
                previous.distanceTo(location)

            if (distance < OFFLINE_DISTANCE_METRES) {
                return
            }
        }

        storeOfflinePoint(location)
    }

    private fun storeOfflinePoint(
        location: Location
    ) {

        val battery =
            getBatteryPercentage()

        val point =
            PendingPoint(
                id = 0,
                timestamp = location.time,
                lat = location.latitude,
                lon = location.longitude,
                hdop = HDOP,
                altitude = getBestAltitude(location),
                speed =
                    if (location.hasSpeed()) {
                        location.speed.toDouble()
                    } else {
                        0.0
                    },
                bearing =
                    if (location.hasBearing()) {
                        location.bearing.toDouble()
                    } else {
                        0.0
                    },
                battery = battery
            )

        database.add(point)

        lastStoredOfflineLocation =
            Location(location)

        trimOfflineBuffer()

        workSignal.trySend(Unit)
    }

    private fun trimOfflineBuffer() {

        val maximum =
            HackTrackSettings.getBufferPoints(this)

        database.trimTo(maximum)
    }

    private suspend fun sendPendingPoint(
        point: PendingPoint
    ): Boolean {

        return sendValues(
            timestamp = point.timestamp,
            lat = point.lat,
            lon = point.lon,
            hdop = point.hdop,
            altitude = point.altitude,
            speed = point.speed,
            bearing = point.bearing,
            battery = point.battery
        )
    }

    private suspend fun sendValues(
        timestamp: Long,
        lat: Double,
        lon: Double,
        hdop: Double,
        altitude: Double,
        speed: Double,
        bearing: Double,
        battery: Int
    ): Boolean {

        return withContext(Dispatchers.IO) {

            try {

                val url =
                    buildString {

                        append(TRACKER_URL)

                        append("?key=")
                        append(
                            URLEncoder.encode(
                                BuildConfig.TRACKER_KEY,
                                "UTF-8"
                            )
                        )

                        append("&lat=")
                        append(lat)

                        append("&lon=")
                        append(lon)

                        append("&timestamp=")
                        append(timestamp)

                        append("&hdop=")
                        append(hdop)

                        append("&altitude=")
                        append(altitude)

                        append("&speed=")
                        append(speed)

                        append("&bearing=")
                        append(bearing)

                        append("&batproc=")
                        append(battery)
                    }

                val connection =
                    URL(url)
                        .openConnection()
                            as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode =
                    connection.responseCode

                connection.disconnect()

                responseCode in 200..299

            } catch (
                e: Exception
            ) {

                false
            }
        }
    }

    private fun getBatteryPercentage(): Int {

        val batteryManager =
            getSystemService(
                Context.BATTERY_SERVICE
            ) as BatteryManager

        return batteryManager.getIntProperty(
            BatteryManager.BATTERY_PROPERTY_CAPACITY
        )
    }

    private fun updateNotification() {

        val speedKph =
            latestLocation?.let {
                if (it.hasSpeed()) {
                    it.speed * 3.6
                } else {
                    0.0
                }
            } ?: 0.0

        val battery =
            getBatteryPercentage()

        val pending =
            database.count()

        broadcastStatus()

        val prefix =
            if (isStopping) {
                "Stopping…"
            } else {
                ""
            }

        val text =
            String.format(
                Locale.UK,
                "%s %.1f kph • %d%% • %d pending",
                prefix,
                speedKph,
                battery,
                pending
            )

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            NOTIFICATION_ID,
            buildNotification(text)
        )
    }

    private fun buildNotification(
        text: String
    ): Notification {

        return Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle("HackTrack")
            .setContentText(text)
            .setSmallIcon(
                android.R.drawable.ic_menu_mylocation
            )
            .setOngoing(true)
            .build()
    }

    private fun getBestAltitude(
        location: Location
    ): Double {

        if (
            android.os.Build.VERSION.SDK_INT >= 34 &&
            location.hasMslAltitude()
        ) {
            return location.mslAltitudeMeters
        }

        return if (location.hasAltitude()) {
            location.altitude
        } else {
            0.0
        }
    }

    override fun onDestroy() {
        sendBroadcast(
            Intent(ACTION_STATUS).apply {
                setPackage(packageName)
                putExtra(EXTRA_TRACKING, false)
            }
        )

        uploaderJob?.cancel()

        fusedLocationClient
            .removeLocationUpdates(
                locationCallback
            )

        serviceScope.cancel()

        database.close()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }

    private fun createNotificationChannel() {

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "HackTrack tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Shows when HackTrack is tracking your location"
            }

        getSystemService(
            NotificationManager::class.java
        ).createNotificationChannel(channel)
    }

    private fun broadcastStatus() {

        val location =
            latestLocation

        val pending =
            database.count()

        val intent =
            Intent(ACTION_STATUS).apply {

                setPackage(packageName)

                putExtra(EXTRA_TRACKING, true)
                putExtra(EXTRA_STOPPING, isStopping)

                /*
                 * We consider the service "online" if there
                 * are no pending points, OR if we are
                 * actively draining the database.
                 * For simplicity in the UI, we'll just
                 * send the pending count.
                 */
                putExtra(EXTRA_ONLINE, pending == 0)

                putExtra(
                    EXTRA_BATTERY,
                    getBatteryPercentage()
                )

                putExtra(
                    EXTRA_PENDING,
                    pending
                )

                if (location != null) {

                    putExtra(
                        EXTRA_LAT,
                        location.latitude
                    )

                    putExtra(
                        EXTRA_LON,
                        location.longitude
                    )

                    putExtra(
                        EXTRA_SPEED_KPH,
                        if (location.hasSpeed()) {
                            location.speed * 3.6
                        } else {
                            0.0
                        }
                    )
                }
            }
        sendBroadcast(intent)
    }
}
