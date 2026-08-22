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

        // Don't store another point while the location has
        // moved less than this distance during an outage.
        private const val OFFLINE_DISTANCE_METRES = 2.0

        const val EXTRA_TRACKING =
            "tracking"

        const val EXTRA_LAT =
            "lat"

        const val EXTRA_LON =
            "lon"

        const val EXTRA_ACCURACY =
            "accuracy"

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

        const val ACTION_GET_STATUS =
            "uk.co.chrishackman.hacktrack.GET_STATUS"
    }

    private lateinit var fusedLocationClient:
            FusedLocationProviderClient

    private lateinit var locationRequest:
            LocationRequest

    private lateinit var database:
            PendingPointDatabase

    @Volatile
    private var latestLocation: Location? = null

    private var lastStoredOfflineLocation: Location? = null

    private var uploaderJob: Job? = null

    @Volatile
    private var isStopping = false

    @Volatile
    private var isTrackingActive = false

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

                for (location in result.locations) {
                    latestLocation = location
                    storeIfMeaningful(location)
                }

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

        when (intent?.action) {
            ACTION_START -> {
                database.clear()
                isStopping = false
                isTrackingActive = true
                
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Waiting for GPS…", true)
                )

                startLocationUpdates()
                startUploader()
            }

            ACTION_STOP_ACQUISITION -> {
                fusedLocationClient.removeLocationUpdates(
                    locationCallback
                )

                isStopping = true
                isTrackingActive = false

                /*
                 * Wake up the uploader immediately.
                 */
                workSignal.trySend(Unit)

                updateNotification()

                // If no points to upload, we can stop now
                if (database.count() == 0) {
                    stopSelf()
                }
            }

            ACTION_TRIM_BUFFER -> {
                trimOfflineBuffer()
                updateNotification()
            }

            ACTION_GET_STATUS -> {
                updateNotification()
                // If the service was started just for status and isn't doing anything, stop it
                if (!isTrackingActive && !isStopping) {
                    stopSelf()
                }
            }
            
            else -> {
                // Handle cases where service might be restarted by system
                if (isTrackingActive) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification("Resuming tracking…", true)
                    )
                    startLocationUpdates()
                    startUploader()
                } else if (!isStopping) {
                    stopSelf()
                }
            }
        }

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
                    try {
                        /*
                         * Wait for work.
                         */
                        try {
                            withTimeout(15000L) {
                                workSignal.receive()
                            }
                        } catch (e: Exception) {
                            // Timeout or cancellation - just check the DB anyway.
                        }

                        /*
                         * Drain the database.
                         */
                        while (isActive) {

                            val point = try {
                                database.oldest()
                            } catch (e: Exception) {
                                null
                            }

                            if (point == null) {
                                /*
                                 * Double check count to ensure we aren't 
                                 * exiting due to a database lock.
                                 */
                                val remaining = try {
                                    database.count()
                                } catch (e: Exception) {
                                    -1
                                }

                                if (remaining == 0) {
                                    if (isStopping) {
                                        updateNotification(0)
                                        stopSelf()
                                        return@launch
                                    }
                                    break
                                } else {
                                    // Lock occurred or points still exist.
                                    delay(2000L)
                                    continue
                                }
                            }

                            val success =
                                sendPendingPoint(point)

                            if (!success) {
                                updateNotification()
                                delay(5000L)
                                continue
                            }

                            try {
                                database.delete(point.id)
                            } catch (e: Exception) {
                                // Deletion failed, retry this point.
                                delay(1000L)
                                continue
                            }

                            updateNotification()
                        }
                    } catch (e: Exception) {
                        /*
                         * Fatal loop error (rare). 
                         * Wait and restart the outer loop.
                         */
                        delay(5000L)
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

            val timeDelta =
                location.time - previous.time

            /*
             * Store if we've moved 2m OR if it's been
             * more than 60 seconds (heartbeat).
             */
            if (distance < OFFLINE_DISTANCE_METRES &&
                timeDelta < 60_000L
            ) {
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
                hdop =
                    if (location.hasAccuracy()) {
                        location.accuracy.toDouble()
                    } else {
                        0.0
                    },
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
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                
                // Explicitly disable connection pooling to ensure fresh TCP handshake
                connection.setRequestProperty("Connection", "close")

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

    private fun updateNotification(
        forcedPendingCount: Int? = null
    ) {

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
            forcedPendingCount ?: database.count()

        broadcastStatus(pending)

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
            buildNotification(text, !isStopping)
        )
    }

    private fun buildNotification(
        text: String,
        ongoing: Boolean
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
            .setOngoing(ongoing)
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

        return GeoidConverter.getMslAltitude(location)
    }

    override fun onDestroy() {
        sendBroadcast(
            Intent(ACTION_STATUS).apply {
                setPackage(packageName)
                putExtra(EXTRA_TRACKING, false)
            }
        )

        val manager =
            getSystemService(
                NotificationManager::class.java
            )
        manager.cancel(NOTIFICATION_ID)

        stopForeground(STOP_FOREGROUND_REMOVE)

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

    private fun broadcastStatus(
        pendingCount: Int
    ) {

        val location =
            latestLocation

        val intent =
            Intent(ACTION_STATUS).apply {

                setPackage(packageName)

                putExtra(EXTRA_TRACKING, isTrackingActive)
                putExtra(EXTRA_STOPPING, isStopping)

                /*
                 * We consider the service "online" if there
                 * are no pending points, OR if we are
                 * actively draining the database.
                 * For simplicity in the UI, we'll just
                 * send the pending count.
                 */
                putExtra(EXTRA_ONLINE, pendingCount == 0)

                putExtra(
                    EXTRA_BATTERY,
                    getBatteryPercentage()
                )

                putExtra(
                    EXTRA_PENDING,
                    pendingCount
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
                        EXTRA_ACCURACY,
                        if (location.hasAccuracy()) {
                            location.accuracy.toDouble()
                        } else {
                            0.0
                        }
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
