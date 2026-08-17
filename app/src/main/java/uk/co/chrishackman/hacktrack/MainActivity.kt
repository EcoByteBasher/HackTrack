package uk.co.chrishackman.hacktrack

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale

@Suppress("InvalidFragmentVersionForActivityResult")
class MainActivity : ComponentActivity() {

    private var tracking by mutableStateOf(false)
    private var stopping by mutableStateOf(false)
    private var online by mutableStateOf(false)
    private var latitude by mutableStateOf<Double?>(null)
    private var longitude by mutableStateOf<Double?>(null)
    private var accuracy by mutableStateOf(0.0)
    private var speedKph by mutableStateOf(0.0)
    private var battery by mutableStateOf(0)
    private var pending by mutableStateOf(0)
    private var updateInfo by mutableStateOf(UpdateInfo(isAvailable = false))

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
                checkNotificationPermissionAndStart()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { _ ->
            // Whether granted or not, we try to start.
            // If denied, the service just won't show a notification
            // (and might be killed earlier).
            requestBackgroundLocation()
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

                stopping =
                    intent.getBooleanExtra(
                        LocationService.EXTRA_STOPPING,
                        false
                    )

                online =
                    intent.getBooleanExtra(
                        LocationService.EXTRA_ONLINE,
                        false
                    )

                val newBattery =
                    intent.getIntExtra(
                        LocationService.EXTRA_BATTERY,
                        -1
                    )

                if (newBattery != -1) {
                    battery = newBattery
                }

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

                    accuracy =
                        intent.getDoubleExtra(
                            LocationService.EXTRA_ACCURACY,
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

        battery = getInitialBatteryLevel()

        setContent {
            MaterialTheme {
                DashboardScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun DashboardScreen() {
        val context = androidx.compose.ui.platform.LocalContext.current
        val versionName = try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName
        } catch (e: Exception) {
            "1.0"
        }

        LaunchedEffect(Unit) {
            updateInfo = UpdateChecker.checkForUpdate(context)
        }

        Scaffold(
            topBar = {
                LargeTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "HackTrack",
                                color = Color(0xFFF57C00) // Orange
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "v$versionName",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Black,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    },
                    actions = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 16.dp)
                        ) {
                            Icon(
                                Icons.Default.BatteryFull,
                                contentDescription = null,
                                tint = if (battery < 20) Color.Red else Color.Unspecified
                            )
                            Text(
                                " $battery%",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    },
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Status Section
                StatusHeader()

                AnimatedVisibility(visible = updateInfo.isAvailable) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        UpdateBanner(updateInfo)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Primary Data Card (Speed/Ready)
                if (tracking) {
                    DataCard(
                        icon = Icons.Default.Speed,
                        label = "Speed",
                        value = String.format(Locale.UK, "%.1f", speedKph),
                        unit = "kph",
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.LocationOn,
                                null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Ready to Track",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Secondary Data Grid (Accuracy/Pending)
                Row(modifier = Modifier.fillMaxWidth()) {
                    DataCard(
                        icon = Icons.Default.LocationOn,
                        label = "Accuracy",
                        value = if (tracking && accuracy > 0.0) String.format(
                            Locale.UK,
                            "±%.1f",
                            accuracy
                        ) else "--",
                        unit = "m",
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    DataCard(
                        icon = if (online && pending == 0) Icons.Default.CloudDone
                        else if (pending > 0) Icons.Default.CloudUpload
                        else Icons.Default.CloudOff,
                        label = "Pending",
                        value = "$pending",
                        unit = "pts",
                        modifier = Modifier.weight(1f),
                        color = if (pending > 0) MaterialTheme.colorScheme.error else Color.Unspecified
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Location Details Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Last Known Coordinates",
                            style = MaterialTheme.typography.labelMedium
                        )
                        if (latitude != null && longitude != null) {
                            Text(
                                String.format(
                                    Locale.UK,
                                    "%.6f, %.6f",
                                    latitude,
                                    longitude
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            Text("No GPS lock yet")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Buffer Setting
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Offline Buffer Length",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Box {
                        OutlinedButton(
                            onClick = { bufferMenuExpanded = true },
                            enabled = !stopping
                        ) {
                            Icon(Icons.Default.Settings, null)
                            Spacer(Modifier.width(8.dp))
                            Text(HackTrackSettings.formatBufferDuration(bufferMinutes))
                        }
                        DropdownMenu(
                            expanded = bufferMenuExpanded,
                            onDismissRequest = { bufferMenuExpanded = false }
                        ) {
                            HackTrackSettings.BUFFER_OPTIONS.forEach { minutes ->
                                DropdownMenuItem(
                                    text = { Text(HackTrackSettings.formatBufferDuration(minutes)) },
                                    onClick = {
                                        bufferMinutes = minutes
                                        HackTrackSettings.setBufferMinutes(this@MainActivity, minutes)
                                        bufferMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Glove-friendly Action Button
                MainActionButton()
            }
        }
    }

    @Composable
    fun StatusHeader() {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )

        val isRecovering = pending > 0 && online

        val containerColor = when {
            isRecovering -> MaterialTheme.colorScheme.tertiaryContainer
            stopping -> MaterialTheme.colorScheme.errorContainer
            tracking -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }

        val contentColor = when {
            isRecovering -> MaterialTheme.colorScheme.onTertiaryContainer
            stopping -> MaterialTheme.colorScheme.onErrorContainer
            tracking -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor
            )
        ) {
            Row(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isRecovering || stopping) {
                    Icon(
                        Icons.Default.CloudUpload,
                        null,
                        modifier = Modifier
                            .size(28.dp)
                            .graphicsLayer(alpha = alpha),
                        tint = contentColor
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                color = if (tracking) Color.Green else Color.Gray,
                                shape = CircleShape
                            )
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = when {
                        isRecovering -> "RECOVERING…"
                        stopping -> "STOPPING…"
                        tracking -> "TRACKING ACTIVE"
                        else -> "STOPPED"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    fun DataCard(
        icon: ImageVector,
        label: String,
        value: String,
        unit: String,
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified
    ) {
        Card(
            modifier = modifier
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, modifier = Modifier.size(16.dp), tint = color)
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        value,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Black,
                        color = color
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        unit,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun UpdateBanner(info: UpdateInfo) {
        val context = androidx.compose.ui.platform.LocalContext.current
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
                context.startActivity(intent)
            }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.SystemUpdate,
                    null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Update Available",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Version ${info.latestVersion} is ready to download.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Icon(Icons.Default.ChevronRight, null)
            }
        }
    }

    @Composable
    fun MainActionButton() {
        Button(
            onClick = {
                if (tracking) stopTracking() else startTrackingWithPermissionCheck()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp), // Extra tall for gloves
            shape = MaterialTheme.shapes.large,
            enabled = !stopping,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (tracking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                if (tracking) Icons.Default.Stop else Icons.Default.PlayArrow,
                null,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (tracking) "STOP TRACKING" else "START TRACKING",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
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

    private fun getInitialBatteryLevel(): Int {
        val batteryManager =
            getSystemService(
                Context.BATTERY_SERVICE
            ) as BatteryManager

        return batteryManager.getIntProperty(
            BatteryManager.BATTERY_PROPERTY_CAPACITY
        )
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

        checkNotificationPermissionAndStart()
    }

    private fun checkNotificationPermissionAndStart() {

        if (android.os.Build.VERSION.SDK_INT >= 33) {

            val granted =
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
                return
            }
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

        stopping = false

        val intent =
            Intent(
                this,
                LocationService::class.java
            ).apply {
                action =
                    LocationService.ACTION_START
            }

        ContextCompat.startForegroundService(
            this,
            intent
        )
    }

    private fun stopTracking() {

        val intent =
            Intent(
                this,
                LocationService::class.java
            ).apply {
                action =
                    LocationService.ACTION_STOP_ACQUISITION
            }

        startService(intent)
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
