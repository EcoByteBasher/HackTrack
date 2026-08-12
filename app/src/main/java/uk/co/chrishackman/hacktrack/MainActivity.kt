package uk.co.chrishackman.hacktrack

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton

@Suppress("InvalidFragmentVersionForActivityResult")
class MainActivity : ComponentActivity() {

    private var tracking by mutableStateOf(false)
    private var online by mutableStateOf(false)
    private var latitude by mutableStateOf<Double?>(null)
    private var longitude by mutableStateOf<Double?>(null)
    private var speedKph by mutableStateOf(0.0)
    private var battery by mutableStateOf(0)
    private var pending by mutableStateOf(0)

    private var bufferMinutes by mutableStateOf(
        HackTrackSettings.DEFAULT_BUFFER_MINUTES
    )

    private var bufferMenuExpanded by mutableStateOf(false)

    private val foregroundLocationLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val fineGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true

            val coarseGranted =
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (fineGranted || coarseGranted) {
                requestBackgroundLocation()
            }
        }

    private val backgroundLocationLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                startTracking()
            }
        }

    private val statusReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (
                    intent?.action !=
                    LocationService.ACTION_STATUS
                ) {
                    return
                }

                tracking =
                    intent.getBooleanExtra(
                        LocationService.EXTRA_TRACKING,
                        false
                    )

                online =
                    intent.getBooleanExtra(
                        LocationService.EXTRA_ONLINE,
                        false
                    )

                battery =
                    intent.getIntExtra(
                        LocationService.EXTRA_BATTERY,
                        0
                    )

                pending =
                    intent.getIntExtra(
                        LocationService.EXTRA_PENDING,
                        0
                    )

                if (
                    intent.hasExtra(
                        LocationService.EXTRA_LAT
                    )
                ) {

                    latitude =
                        intent.getDoubleExtra(
                            LocationService.EXTRA_LAT,
                            0.0
                        )

                    longitude =
                        intent.getDoubleExtra(
                            LocationService.EXTRA_LON,
                            0.0
                        )

                    speedKph =
                        intent.getDoubleExtra(
                            LocationService.EXTRA_SPEED_KPH,
                            0.0
                        )
                }
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        bufferMinutes =
            HackTrackSettings.getBufferMinutes(this)

        setContent {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment =
                    Alignment.CenterHorizontally,
                verticalArrangement =
                    Arrangement.Center
            ) {

                Text("HackTrack")

                Spacer(
                    modifier = Modifier.height(24.dp)
                )

                if (tracking) {

                    Text("● TRACKING")

                    Spacer(
                        modifier = Modifier.height(20.dp)
                    )

                    if (
                        latitude != null &&
                        longitude != null
                    ) {

                        Text(
                            String.format(
                                Locale.UK,
                                "GPS       %.6f, %.6f",
                                latitude,
                                longitude
                            )
                        )
                    }

                    Text(
                        String.format(
                            Locale.UK,
                            "Speed     %.1f kph",
                            speedKph
                        )
                    )

                    Text(
                        "Battery   $battery%"
                    )

                    Text(
                        if (online) {
                            "Network   Connected"
                        } else {
                            "Network   Offline"
                        }
                    )

                    Text(
                        "Pending   $pending points"
                    )

                    Spacer(
                        modifier = Modifier.height(16.dp)
                    )

                    Text("Offline buffer")

                    OutlinedButton(
                        onClick = {
                            bufferMenuExpanded = true
                        }
                    ) {
                        Text(
                            HackTrackSettings.formatBufferDuration(
                                bufferMinutes
                            )
                        )
                    }

                    DropdownMenu(
                        expanded = bufferMenuExpanded,
                        onDismissRequest = {
                            bufferMenuExpanded = false
                        }
                    ) {

                        HackTrackSettings.BUFFER_OPTIONS.forEach { minutes ->

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        HackTrackSettings.formatBufferDuration(
                                            minutes
                                        )
                                    )
                                },
                                onClick = {

                                    bufferMinutes = minutes

                                    HackTrackSettings.setBufferMinutes(
                                        this@MainActivity,
                                        minutes
                                    )

                                    bufferMenuExpanded = false

                                    /*
                                     * Trim an existing queue
                                     * if the new setting is smaller.
                                     */
                                    // CCH sendTrimBufferRequest() /* But new limit will be enforced when next point is added */
                                }
                            )
                        }
                    }

                    Spacer(
                        modifier = Modifier.height(24.dp)
                    )

                    Button(
                        onClick = {
                            stopTracking()
                        }
                    ) {
                        Text("STOP")
                    }

                } else {

                    Text("STOPPED")

                    Spacer(
                        modifier = Modifier.height(24.dp)
                    )

                    Button(
                        onClick = {
                            startTrackingWithPermissionCheck()
                        }
                    ) {
                        Text("START")
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()

        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(
                LocationService.ACTION_STATUS
            ),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {

        unregisterReceiver(statusReceiver)

        super.onStop()
    }

    private fun startTrackingWithPermissionCheck() {

        val fineGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {

            foregroundLocationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )

            return
        }

        requestBackgroundLocation()
    }

    private fun requestBackgroundLocation() {

        val granted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startTracking()
        } else {
            backgroundLocationLauncher.launch(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
        }
    }

    private fun startTracking() {

        val intent =
            Intent(
                this,
                LocationService::class.java
            )

        ContextCompat.startForegroundService(
            this,
            intent
        )
    }

    private fun stopTracking() {

        stopService(
            Intent(
                this,
                LocationService::class.java
            )
        )

        tracking = false
        online = false
        pending = 0
    }

    private fun sendTrimBufferRequest() {

        val intent =
            Intent(
                this,
                LocationService::class.java
            ).apply {

                action =
                    LocationService.ACTION_TRIM_BUFFER
            }

        startService(intent)
    }
}