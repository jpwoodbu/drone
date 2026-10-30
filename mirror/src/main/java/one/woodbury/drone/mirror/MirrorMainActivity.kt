package one.woodbury.drone.mirror

import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface as ComposeSurface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import one.woodbury.drone.flight.FlightDynamicsCalculator
import one.woodbury.drone.protocol.CameraTelemetry
import one.woodbury.drone.protocol.DumlProtocol
import one.woodbury.drone.video.H264VideoDecoder
import kotlin.math.roundToInt

/**
 * Landscape 2D viewer activity for the Mirror application (`one.woodbury.drone.mirror`).
 * Displays the live 16:9 H.264 video feed alongside a compact telemetry HUD bar
 * and a persistent manual Bridge IP input.
 */
class MirrorMainActivity : ComponentActivity() {

    private lateinit var bridgeReceiver: UdpBridgeReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bridgeReceiver = UdpBridgeReceiver(applicationContext)

        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val receiverState by bridgeReceiver.receiverState.collectAsState()
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF0B0E14),
                ) { innerPadding ->
                    MirrorCockpitScreen(
                        state = receiverState,
                        videoDecoder = bridgeReceiver.videoDecoder,
                        onSetBridgeIp = { ip -> bridgeReceiver.setConfiguredBridgeIp(ip) },
                        onSetUnitSystem = { unitSystem -> bridgeReceiver.setUnitSystem(unitSystem) },
                        onSetHudEnabled = { enabled -> bridgeReceiver.setHudEnabled(enabled) },
                        onSetHudOpacity = { opacity -> bridgeReceiver.setHudOpacity(opacity) },
                        onSetHudAspectRatio = { aspectRatio -> bridgeReceiver.setHudAspectRatio(aspectRatio) },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bridgeReceiver.start()
    }

    override fun onStop() {
        bridgeReceiver.stop()
        super.onStop()
    }

    override fun onDestroy() {
        bridgeReceiver.shutdown()
        super.onDestroy()
    }
}

@Composable
fun MirrorCockpitScreen(
    state: MirrorReceiverState,
    videoDecoder: H264VideoDecoder?,
    onSetBridgeIp: (String) -> Unit,
    onSetUnitSystem: (UnitSystem) -> Unit,
    onSetHudEnabled: (Boolean) -> Unit,
    onSetHudOpacity: (Float) -> Unit,
    onSetHudAspectRatio: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    var showNetworkDialog by remember { mutableStateOf(false) }
    var showFlightModesDialog by remember { mutableStateOf(false) }
    var showCameraModeDialog by remember { mutableStateOf(false) }

    val toggleUnitSystem = {
        val nextSystem = if (state.unitSystem == UnitSystem.METRIC) {
            UnitSystem.IMPERIAL
        } else {
            UnitSystem.METRIC
        }
        onSetUnitSystem(nextSystem)
    }

    if (showSettingsDialog) {
        MirrorSettingsDialog(
            configuredIp = state.configuredBridgeIp,
            unitSystem = state.unitSystem,
            isHudEnabled = state.isHudEnabled,
            hudOpacity = state.hudOpacity,
            hudAspectRatio = state.hudAspectRatio,
            onApplyBridgeIp = { ipAddress ->
                onSetBridgeIp(ipAddress)
                showSettingsDialog = false
            },
            onSelectUnitSystem = { unitSystem ->
                onSetUnitSystem(unitSystem)
            },
            onSetHudEnabled = onSetHudEnabled,
            onSetHudOpacity = onSetHudOpacity,
            onSetHudAspectRatio = onSetHudAspectRatio,
            onDismiss = { showSettingsDialog = false },
        )
    }

    if (showBatteryDialog) {
        BatteryHealthDialog(
            batteryTelemetry = state.batteryTelemetry,
            onDismiss = { showBatteryDialog = false },
        )
    }

    if (showNetworkDialog) {
        NetworkDiagnosticsDialog(
            state = state,
            onOpenSettings = {
                showNetworkDialog = false
                showSettingsDialog = true
            },
            onDismiss = { showNetworkDialog = false },
        )
    }

    if (showFlightModesDialog) {
        FlightModesDialog(
            currentFlightMode = state.flightControllerTelemetry?.flightMode,
            onDismiss = { showFlightModesDialog = false },
        )
    }

    if (showCameraModeDialog) {
        CameraModeDialog(
            cameraTelemetry = state.cameraTelemetry,
            onDismiss = { showCameraModeDialog = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!state.isHudEnabled) {
            TopStatusBar(
                state = state,
                onOpenBatteryHealth = { showBatteryDialog = true },
                onToggleUnitSystem = toggleUnitSystem,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.aspectRatio(16f / 9f),
                contentAlignment = Alignment.Center,
            ) {
                if (videoDecoder != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            SurfaceView(context).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        videoDecoder.attachSurface(holder.surface)
                                    }

                                    override fun surfaceChanged(
                                        holder: SurfaceHolder,
                                        format: Int,
                                        width: Int,
                                        height: Int,
                                    ) = Unit

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        videoDecoder.detachSurface()
                                    }
                                })
                            }
                        },
                    )
                }

                if (state.videoDecoderMetrics.framesDecoded == 0L) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            text = when {
                                state.bridgeIp != null ->
                                    "Connected to Bridge (${state.bridgeIp}) — awaiting H.264 keyframe…"
                                state.configuredBridgeIp.isNotEmpty() ->
                                    "Sending unicast heartbeat to ${state.configuredBridgeIp}:5600…"
                                else ->
                                    "Waiting for Bridge mDNS service (_drone-bridge._udp.)…"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White,
                        )
                        Text(
                            text = if (state.localIpv4Addresses.isEmpty()) {
                                "No Wi-Fi IPv4 address detected on Mirror"
                            } else {
                                "Mirror IP: ${state.localIpv4Addresses.joinToString(", ")}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Text(
                            text = "Socket: ${state.socketStatus} | mDNS: ${state.nsdStatus}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Text(
                            text = "Heartbeats TX: ${state.heartbeatsSent}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        if (state.lastSocketError != null) {
                            Text(
                                text = "Error: ${state.lastSocketError}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFFF7B72),
                            )
                        }
                    }
                }

                if (state.isReconnecting) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .background(Color(0xFF161B22).copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                        ) {
                            Text(
                                text = "CONNECTION LOST",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF85149),
                            )
                            Text(
                                text = "Reconnecting to Bridge…",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                    }
                }

                if (state.isGridOverlayEnabled) {
                    RuleOfThirdsGridOverlay()
                }

                if (state.isHudEnabled && !state.isGridOverlayEnabled) {
                    HudOverlay(
                        state = state,
                        onOpenBatteryHealth = { showBatteryDialog = true },
                        onToggleUnitSystem = toggleUnitSystem,
                    )
                }
            }
        }

        BottomStatusBar(
            state = state,
            onOpenNetworkDiagnostics = { showNetworkDialog = true },
            onOpenSettings = { showSettingsDialog = true },
            onOpenFlightModes = { showFlightModesDialog = true },
            onOpenCameraMode = { showCameraModeDialog = true },
        )
    }
}

@Composable
private fun MirrorSettingsDialog(
    configuredIp: String,
    unitSystem: UnitSystem,
    isHudEnabled: Boolean,
    hudOpacity: Float,
    hudAspectRatio: Float,
    onApplyBridgeIp: (String) -> Unit,
    onSelectUnitSystem: (UnitSystem) -> Unit,
    onSetHudEnabled: (Boolean) -> Unit,
    onSetHudOpacity: (Float) -> Unit,
    onSetHudAspectRatio: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var textValue by remember(configuredIp) { mutableStateOf(configuredIp) }
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Mirror Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Heads-Up Display (HUD)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Enable HUD Overlay",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = isHudEnabled,
                        onCheckedChange = onSetHudEnabled,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "HUD Opacity",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${(hudOpacity * 100f).roundToInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = hudOpacity,
                        onValueChange = onSetHudOpacity,
                        valueRange = 0.2f..1.0f,
                        enabled = isHudEnabled,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "HUD Aspect Ratio",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "%.2f : 1".format(hudAspectRatio),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = hudAspectRatio,
                        onValueChange = onSetHudAspectRatio,
                        valueRange = HUD_ASPECT_RATIO_MIN..HUD_ASPECT_RATIO_MAX,
                        // Snaps to 0.05 increments so the default (1.00) is reachable exactly.
                        steps = ((HUD_ASPECT_RATIO_MAX - HUD_ASPECT_RATIO_MIN) / 0.05f).roundToInt() - 1,
                        enabled = isHudEnabled,
                    )
                }

                HorizontalDivider()

                Text(
                    text = "Flight Measurement Units",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = unitSystem == UnitSystem.METRIC,
                        onClick = { onSelectUnitSystem(UnitSystem.METRIC) },
                        label = { Text("Metric") },
                    )
                    FilterChip(
                        selected = unitSystem == UnitSystem.IMPERIAL,
                        onClick = { onSelectUnitSystem(UnitSystem.IMPERIAL) },
                        label = { Text("Imperial") },
                    )
                }

                HorizontalDivider()

                Text(
                    text = "Bridge Connection",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "The app automatically discovers the Bridge over mDNS. If auto-discovery is unavailable on your Wi-Fi network, enter the bridge device's IPv4 address below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = {
                        Text(
                            text = "Bridge IPv4",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    placeholder = {
                        Text(
                            text = "e.g. 192.168.1.50",
                            fontFamily = FontFamily.Monospace,
                        )
                    },
                    trailingIcon = {
                        if (textValue.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    textValue = ""
                                },
                            ) {
                                Text(
                                    text = "✕",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            onApplyBridgeIp(textValue)
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onApplyBridgeIp(textValue)
                },
                enabled = textValue.isNotBlank(),
            ) {
                Text(text = "Connect IP")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (configuredIp.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            onApplyBridgeIp("")
                        },
                    ) {
                        Text(text = "Reset to Auto")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(text = "Close")
                }
            }
        },
    )
}

@Composable
private fun TopStatusBar(
    state: MirrorReceiverState,
    onOpenBatteryHealth: () -> Unit,
    onToggleUnitSystem: () -> Unit,
) {
    val telemetry = state.flightControllerTelemetry
    val isImperial = state.unitSystem.isImperial

    val hasValidVps = telemetry?.isUltrasonicValid == true && telemetry.ultrasonicHeightMeters != null
    val effectiveAltitude = if (hasValidVps) telemetry!!.ultrasonicHeightMeters!! else telemetry?.relativeAltitudeMeters
    val altitudeText = if (effectiveAltitude != null) {
        AltitudeFormatter.formatAltitude(effectiveAltitude, state.unitSystem)
    } else {
        "—"
    }
    val isAltitudeWarning = effectiveAltitude != null && AltitudeFormatter.isHighAltitudeWarning(effectiveAltitude)

    val activeAlert = when {
        state.isReconnecting -> "LINK LOST" to Color(0xFFF85149)
        telemetry?.isCompassError == true -> "COMPASS" to Color(0xFFF85149)
        telemetry?.isAutoLanding == true -> "LANDING" to Color(0xFFD29922)
        else -> null
    }

    val batteryPercentage = state.batteryTelemetry?.percentage
        ?: telemetry?.batteryPercentage?.takeIf { it > 0 }
    val powerWatts = state.batteryTelemetry?.powerWatts
    val batteryValue = when {
        batteryPercentage != null && powerWatts != null -> {
            "$batteryPercentage% · ${FlightDynamicsCalculator.formatPowerWatts(powerWatts)}"
        }
        batteryPercentage != null -> "$batteryPercentage%"
        else -> "—"
    }

    val estFlightTime = state.batteryTelemetry?.estimatedFlightTimeMinutes

    val vx = telemetry?.velocityXMetersPerSecond ?: 0f
    val vy = telemetry?.velocityYMetersPerSecond ?: 0f
    val vz = telemetry?.velocityZMetersPerSecond ?: 0f
    val groundSpeed = FlightDynamicsCalculator.calculateGroundSpeedMetersPerSecond(vx, vy)
    val climbRate = FlightDynamicsCalculator.calculateClimbRateMetersPerSecond(vz)
    val speedText = if (telemetry != null) {
        val horizontalText = FlightDynamicsCalculator.formatGroundSpeed(groundSpeed, isImperial)
        val verticalText = FlightDynamicsCalculator.formatVerticalSpeed(climbRate, isImperial)
        "$horizontalText · $verticalText"
    } else {
        "—"
    }

    val homeLat = state.homeLatitudeDegrees
    val homeLon = state.homeLongitudeDegrees
    val curLat = telemetry?.latitudeDegrees
    val curLon = telemetry?.longitudeDegrees
    val distanceText = if (homeLat != null && homeLon != null && curLat != null && curLon != null && curLat != 0.0 && curLon != 0.0) {
        val distanceMeters = FlightDynamicsCalculator.calculateHaversineDistanceMeters(homeLat, homeLon, curLat, curLon)
        FlightDynamicsCalculator.formatDistance(distanceMeters, isImperial)
    } else {
        "—"
    }

    ComposeSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF161B22),
        shape = RoundedCornerShape(8.dp),
    ) {
        // Wraps whole metrics onto additional lines when the bar is too narrow, rather than
        // squeezing trailing metrics until their labels wrap one character per line.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (activeAlert != null) {
                ComposeSurface(
                    color = activeAlert.second.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = activeAlert.first,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = activeAlert.second,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            HudMetricItem(
                label = "BATTERY",
                value = batteryValue,
                onClick = onOpenBatteryHealth,
            )
            HudMetricItem(
                label = "EST TIME",
                value = FlightDynamicsCalculator.formatEstimatedFlightTime(estFlightTime),
            )
            HudMetricItem(
                label = "ALTITUDE",
                value = altitudeText,
                valueColor = if (isAltitudeWarning) Color(0xFFF85149) else null,
                onClick = onToggleUnitSystem,
            )
            HudMetricItem(
                label = "SPEED (H/V)",
                value = speedText,
                onClick = onToggleUnitSystem,
            )
            HudMetricItem(
                label = "DISTANCE",
                value = distanceText,
                onClick = onToggleUnitSystem,
            )
            HudMetricItem(
                label = "GPS SATS",
                value = telemetry?.gpsSatellites?.toString() ?: "—",
            )
            HudMetricItem(
                label = "ATTITUDE (P/R/HDG)",
                value = if (telemetry != null) {
                    val heading = FlightDynamicsCalculator.formatCompassHeading(telemetry.yawDegrees)
                    "%.0f° / %.0f° / %s".format(
                        telemetry.pitchDegrees,
                        telemetry.rollDegrees,
                        heading,
                    )
                } else {
                    "—"
                },
            )
            val gimbal = state.gimbalTelemetry
            HudMetricItem(
                label = "GIMBAL",
                value = if (gimbal != null) "%.0f°".format(gimbal.pitchDegrees) else "—",
            )
        }
    }
}

@Composable
private fun BottomStatusBar(
    state: MirrorReceiverState,
    onOpenNetworkDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFlightModes: () -> Unit,
    onOpenCameraMode: () -> Unit,
) {
    val metrics = state.videoDecoderMetrics
    val fpsText = if (metrics.framesPerSecond > 0f) "%.1f fps".format(metrics.framesPerSecond) else "— fps"
    val mbpsText = if (metrics.bitrateKbps > 0L) "%.2f Mbps".format(metrics.bitrateKbps / 1000.0) else "— Mbps"
    val telemetry = state.flightControllerTelemetry
    val flightMode = telemetry?.flightMode ?: "—"
    val isMotorsRunning = telemetry?.motorsRunning == true
    val isFunctionButtonPressed = state.remoteControllerAxes?.isFunctionButtonPressed == true
    val isCButtonPressed = state.remoteControllerAxes?.isCButtonPressed == true
    val camera = state.cameraTelemetry
    val isRecording = camera?.isRecording == true
    val recordingTimeText = if (camera != null && camera.isRecording) {
        DumlProtocol.formatRecordingTime(camera.recordTimeSeconds)
    } else {
        null
    }

    val totalPackets = state.udpPacketsReceived + state.udpSequenceDrops
    val dropRate = if (totalPackets > 0L) {
        (state.udpSequenceDrops.toDouble() / totalPackets.toDouble()) * 100.0
    } else {
        0.0
    }
    val wifiIconColor = when {
        state.bridgeIp == null -> Color.White.copy(alpha = 0.4f)
        state.udpSequenceDrops == 0L || dropRate < 0.5 -> Color(0xFF3FB950)
        dropRate < 2.0 -> Color(0xFFD29922)
        else -> Color(0xFFF85149)
    }

    ComposeSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF161B22),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = onOpenNetworkDiagnostics,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_wifi),
                        contentDescription = "Bridge Network Diagnostics",
                        tint = wifiIconColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(28.dp),
                ) {
                    Text(
                        text = "⚙",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
                if (isRecording && recordingTimeText != null) {
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(8.dp)
                            .background(Color(0xFFF85149), shape = CircleShape),
                    )
                    Text(
                        text = recordingTimeText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF85149),
                        maxLines = 1,
                    )
                }
                Text(
                    text = "VIDEO: ${metrics.videoWidth}×${metrics.videoHeight} · $mbpsText · $fpsText",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = if (isRecording) 2.dp else 4.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isFunctionButtonPressed) {
                    ComposeSurface(
                        color = Color(0xFFD29922).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = "Fn",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD29922),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                if (isCButtonPressed) {
                    ComposeSurface(
                        color = Color(0xFFD29922).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = "C",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD29922),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                if (isMotorsRunning) {
                    ComposeSurface(
                        color = Color(0xFF3FB950).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = "MOTORS",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF3FB950),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                val isVideoMode = state.cameraTelemetry?.workMode == DumlProtocol.CAMERA_WORK_MODE_RECORD
                ComposeSurface(
                    color = Color(0xFF21262D),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onOpenCameraMode),
                ) {
                    Text(
                        text = if (isVideoMode) "VIDEO" else "PHOTO",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF58A6FF),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                ComposeSurface(
                    color = Color(0xFF21262D),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onOpenFlightModes),
                ) {
                    Text(
                        text = "MODE: $flightMode",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF58A6FF),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HudMetricItem(
    label: String,
    value: String,
    highlight: Boolean = false,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.Start,
        modifier = if (onClick != null) {
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 2.dp)
        } else {
            Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = Color.White.copy(alpha = 0.55f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = valueColor ?: if (highlight) Color(0xFF58A6FF) else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
