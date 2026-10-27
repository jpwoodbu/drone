package one.woodbury.drone.bridge

import android.content.Intent
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import one.woodbury.drone.bridge.ui.theme.DroneTheme
import one.woodbury.drone.bridge.usb.ConnectionPhase
import one.woodbury.drone.bridge.usb.ControllerConnectionState
import one.woodbury.drone.bridge.usb.LastDumlPacketInfo
import one.woodbury.drone.bridge.usb.UsbAccessoryConnectionManager
import one.woodbury.drone.flight.FlightDynamicsCalculator
import one.woodbury.drone.protocol.DumlProtocol
import one.woodbury.drone.bridge.usb.UsbAccessoryDescriptor
import one.woodbury.drone.video.H264VideoDecoder

/**
 * Main diagnostic activity for connecting to the DJI S01A remote controller over USB AOA
 * and displaying hardware descriptor and DUML protocol telemetry.
 */
class MainActivity : ComponentActivity() {

    private lateinit var connectionManager: UsbAccessoryConnectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectionManager = UsbAccessoryConnectionManager(applicationContext)
        connectionManager.onAccessoryAttachedIntent(intent)

        enableEdgeToEdge()
        setContent {
            DroneTheme {
                val connectionState by connectionManager.connectionState.collectAsState()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ControllerDiagnosticsScreen(
                        state = connectionState,
                        videoDecoder = connectionManager.videoDecoder,
                        onSetVideoPreviewEnabled = { isEnabled ->
                            connectionManager.isVideoPreviewEnabled = isEnabled
                        },
                        onDisconnectSubscribersClicked = { connectionManager.disconnectAllSubscribers() },
                        onResumeSubscribersClicked = { connectionManager.resumeSubscribers() },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        connectionManager.onAccessoryAttachedIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        connectionManager.scanAndConnect()
    }

    override fun onDestroy() {
        connectionManager.shutdown()
        super.onDestroy()
    }
}

@Composable
fun ControllerDiagnosticsScreen(
    state: ControllerConnectionState,
    videoDecoder: H264VideoDecoder? = null,
    onSetVideoPreviewEnabled: (Boolean) -> Unit,
    onDisconnectSubscribersClicked: () -> Unit,
    onResumeSubscribersClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Drone Bridge",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        ConnectionStatusCard(
            state = state,
        )

        NetworkServerCard(
            state = state,
            onDisconnectSubscribersClicked = onDisconnectSubscribersClicked,
            onResumeSubscribersClicked = onResumeSubscribersClicked,
        )

        LiveVideoFeedCard(
            state = state,
            videoDecoder = videoDecoder,
            onSetVideoPreviewEnabled = onSetVideoPreviewEnabled,
        )

        UsbDescriptorCard(descriptor = state.accessoryDescriptor)

        StreamMetricsCard(state = state)

        TelemetryDetailsCard(state = state)
    }
}

@Composable
private fun NetworkServerCard(
    state: ControllerConnectionState,
    onDisconnectSubscribersClicked: () -> Unit,
    onResumeSubscribersClicked: () -> Unit,
) {
    val bridge = state.udpBridgeMetrics
    val hasActiveTarget = bridge.activeSubscribers.isNotEmpty()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Network Server",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!bridge.isAcceptingSubscribers) {
                    Text(
                        text = "PAUSED",
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (hasActiveTarget) {
                    Text(
                        text = "STREAMING",
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            HorizontalDivider()
            KeyValueItem(
                label = "Socket Status",
                value = bridge.socketStatus,
            )
            KeyValueItem(
                label = "mDNS Service",
                value = bridge.nsdStatus,
            )
            KeyValueItem(
                label = "Phone Local IP(s)",
                value = if (bridge.localIpv4Addresses.isEmpty()) {
                    "No Wi-Fi / Hotspot IPv4"
                } else {
                    bridge.localIpv4Addresses.joinToString(", ")
                },
            )
            KeyValueItem(
                label = "Mirror Subscribers",
                value = when {
                    !bridge.isAcceptingSubscribers -> "Subscriptions paused"
                    bridge.activeSubscribers.isNotEmpty() -> bridge.activeSubscribers.joinToString(", ")
                    else -> "Waiting for mirror heartbeat…"
                },
            )
            KeyValueItem(
                label = "Pings TX / Heartbeats RX",
                value = "${bridge.announcePacketsSent} TX / ${bridge.heartbeatsReceived} RX",
            )
            KeyValueItem(
                label = "UDP Packets Sent",
                value = "${bridge.udpPacketsSent} (${bridge.udpBytesSent} B)",
            )
            KeyValueItem(
                label = "Last Socket Error",
                value = bridge.lastSocketError ?: "None",
                valueColor = if (bridge.lastSocketError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            Spacer(modifier = Modifier.height(4.dp))
            if (!bridge.isAcceptingSubscribers) {
                Button(
                    onClick = onResumeSubscribersClicked,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "Allow Subscribers")
                }
            } else {
                OutlinedButton(
                    onClick = onDisconnectSubscribersClicked,
                    enabled = hasActiveTarget,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "Disconnect All Subscribers")
                }
            }
        }
    }
}

@Composable
private fun LiveVideoFeedCard(
    state: ControllerConnectionState,
    videoDecoder: H264VideoDecoder?,
    onSetVideoPreviewEnabled: (Boolean) -> Unit,
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    val metrics = state.videoDecoderMetrics

    LaunchedEffect(isExpanded) {
        onSetVideoPreviewEnabled(isExpanded)
        if (!isExpanded) {
            videoDecoder?.detachSurface()
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Live Video Feed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (isExpanded) "Hide ▲" else "Show ▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (isExpanded) {
                HorizontalDivider()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
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

                    if (metrics.framesDecoded == 0L) {
                        Text(
                            text = if (state.aoaVideoEnvelopesReceived > 0L) {
                                "Awaiting H.264 SPS/Keyframe…"
                            } else {
                                "No video stream (power on aircraft)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
                }

                KeyValueItem(
                    label = "Decoder Output",
                    value = if (metrics.framesDecoded > 0L) {
                        val fps = if (metrics.framesPerSecond > 0f) " · %.1f fps".format(metrics.framesPerSecond) else ""
                        val mbps = if (metrics.bitrateKbps > 0L) " · %.2f Mbps".format(metrics.bitrateKbps / 1000.0) else ""
                        "${metrics.videoWidth}×${metrics.videoHeight} (${metrics.framesDecoded} frames$fps$mbps)"
                    } else {
                        "Idle (0 frames)"
                    },
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    state: ControllerConnectionState,
) {
    val containerColor = when (state.connectionPhase) {
        ConnectionPhase.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
        ConnectionPhase.AWAITING_PERMISSION -> MaterialTheme.colorScheme.secondaryContainer
        ConnectionPhase.ERROR -> MaterialTheme.colorScheme.errorContainer
        ConnectionPhase.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "USB Link Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = state.connectionPhase.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = state.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun UsbDescriptorCard(descriptor: UsbAccessoryDescriptor?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "USB Accessory Descriptor (AOA 2.0)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider()
            if (descriptor == null) {
                Text(
                    text = "No USB accessory descriptor available.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                KeyValueItem(label = "Manufacturer", value = descriptor.manufacturer)
                KeyValueItem(label = "Model", value = descriptor.model)
                KeyValueItem(label = "Description", value = descriptor.description)
                KeyValueItem(label = "URI", value = descriptor.uri)
            }
        }
    }
}

@Composable
private fun StreamMetricsCard(state: ControllerConnectionState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Live AOA / DUML Stream",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider()
            KeyValueItem(label = "Bytes Received", value = "${state.totalBytesReceived} B")
            KeyValueItem(label = "Bytes Sent", value = "${state.totalBytesSent} B")
            KeyValueItem(
                label = "AOA DUML Envelopes (0x5749)",
                value = state.aoaDumlEnvelopesReceived.toString(),
            )
            KeyValueItem(
                label = "AOA Video Envelopes (0x574A)",
                value = "${state.aoaVideoEnvelopesReceived} (${state.videoBytesReceived} B)",
            )
            KeyValueItem(
                label = "Valid DUML v1 Packets",
                value = state.dumlFramesReceived.toString(),
            )
            KeyValueItem(
                label = "Active Bus Modules",
                value = if (state.observedModules.isEmpty()) {
                    "None observed yet"
                } else {
                    state.observedModules.joinToString(", ")
                },
            )

            if (state.moduleVersions.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "Reported Module Versions",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                state.moduleVersions.forEach { (moduleName, versionText) ->
                    KeyValueItem(label = moduleName, value = versionText)
                }
            }
        }
    }
}

@Composable
private fun TelemetryDetailsCard(state: ControllerConnectionState) {
    var isPayloadHexExpanded by rememberSaveable { mutableStateOf(false) }
    var isRawHexExpanded by rememberSaveable { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Controller & Aircraft Telemetry",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider()

            val axes = state.remoteControllerAxes
            if (axes != null) {
                Text(
                    text = "Remote Controller Inputs (Center = 1024)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                KeyValueItem(
                    label = "Left Stick (Yaw / Throttle)",
                    value = "${axes.leftStickHorizontal} / ${axes.leftStickVertical}",
                )
                KeyValueItem(
                    label = "Right Stick (Roll / Pitch)",
                    value = "${axes.rightStickHorizontal} / ${axes.rightStickVertical}",
                )
                KeyValueItem(
                    label = "Gimbal Wheel",
                    value = axes.gimbalWheel.toString(),
                )
                KeyValueItem(
                    label = "Fn Button",
                    value = if (axes.isFunctionButtonPressed) "Pressed" else "Released",
                )
                KeyValueItem(
                    label = "Record Button",
                    value = if (axes.isRecordButtonPressed) "Pressed" else "Released",
                )
                KeyValueItem(
                    label = "Shutter Button",
                    value = if (axes.isShutterButtonPressed) "Pressed" else "Released",
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            val telemetry = state.flightControllerTelemetry
            val battery = state.batteryTelemetry
            val gimbal = state.gimbalTelemetry
            val camera = state.cameraTelemetry
            val batteryPercentage = battery?.percentage ?: telemetry?.batteryPercentage?.takeIf { it > 0 }
            if (telemetry != null || battery != null || gimbal != null || camera != null) {
                Text(
                    text = "Aircraft OSD Telemetry",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                KeyValueItem(
                    label = "Battery",
                    value = if (batteryPercentage != null) "$batteryPercentage%" else "—",
                )
                if (battery != null) {
                    val voltageText = battery.packVoltageMillivolts?.let { millivolts ->
                        val volts = millivolts / 1000.0
                        if (battery.cellCount != null) {
                            "%.2f V (%dS)".format(volts, battery.cellCount)
                        } else {
                            "%.2f V".format(volts)
                        }
                    }
                    if (voltageText != null) {
                        KeyValueItem(label = "Battery Voltage", value = voltageText)
                    }
                    val capacityText = if (battery.remainingCapacityMilliampHours != null && battery.fullCapacityMilliampHours != null) {
                        "%,d / %,d mAh".format(battery.remainingCapacityMilliampHours, battery.fullCapacityMilliampHours)
                    } else if (battery.remainingCapacityMilliampHours != null) {
                        "%,d mAh".format(battery.remainingCapacityMilliampHours)
                    } else {
                        null
                    }
                    if (capacityText != null) {
                        KeyValueItem(label = "Battery Capacity", value = capacityText)
                    }
                    if (battery.powerWatts != null) {
                        KeyValueItem(label = "Power Draw", value = "%.1f W".format(battery.powerWatts))
                    }
                    if (battery.estimatedFlightTimeMinutes != null) {
                        KeyValueItem(
                            label = "Estimated Flight Time",
                            value = FlightDynamicsCalculator.formatEstimatedFlightTime(battery.estimatedFlightTimeMinutes),
                        )
                    }
                    if (battery.temperatureCelsius != null) {
                        KeyValueItem(label = "Battery Temp", value = "%.1f °C".format(battery.temperatureCelsius))
                    }
                    if (battery.lifetimeHealthPercentage != null) {
                        KeyValueItem(label = "Battery Health (SOH)", value = "${battery.lifetimeHealthPercentage}%")
                    }
                    if (battery.cycleCount != null) {
                        KeyValueItem(label = "Discharge Cycles", value = "${battery.cycleCount}")
                    }
                    if (battery.cellVoltagesMillivolts.isNotEmpty()) {
                        KeyValueItem(
                            label = "Cell Voltages",
                            value = battery.cellVoltagesMillivolts.joinToString(" / ") { "${it}mV" },
                        )
                        if (battery.cellDeltaMillivolts != null) {
                            KeyValueItem(label = "Cell Delta (Δ)", value = "${battery.cellDeltaMillivolts} mV")
                        }
                    }
                }
                if (gimbal != null) {
                    KeyValueItem(
                        label = "Gimbal Pitch",
                        value = "%.1f°".format(gimbal.pitchDegrees),
                    )
                    KeyValueItem(
                        label = "Gimbal Attitude (P / R / Y)",
                        value = "%.1f° / %.1f° / %.1f°".format(
                            gimbal.pitchDegrees,
                            gimbal.rollDegrees,
                            gimbal.yawDegrees,
                        ),
                    )
                }
                if (camera != null) {
                    KeyValueItem(label = "Camera Mode", value = camera.workModeDescription)
                    val recordingText = if (camera.isRecording) {
                        "Recording (${DumlProtocol.formatRecordingTime(camera.recordTimeSeconds)})"
                    } else {
                        "Idle"
                    }
                    KeyValueItem(label = "Camera Video", value = recordingText)
                    val sdCardSummary = if (camera.hasSdCard) {
                        if (camera.sdCardTotalCapacityMb > 0) {
                            "${camera.sdCardStateDescription} (${camera.sdCardRemainCapacityMb} / ${camera.sdCardTotalCapacityMb} MB)"
                        } else {
                            camera.sdCardStateDescription
                        }
                    } else {
                        "No SD Card"
                    }
                    KeyValueItem(label = "Camera Storage", value = sdCardSummary)
                }
                if (telemetry != null) {
                    KeyValueItem(label = "Flight Mode", value = telemetry.flightMode)
                    val statusFlags = mutableListOf<String>()
                    if (telemetry.isRthActive) statusFlags.add("RTH")
                    if (telemetry.isAutoLanding) statusFlags.add("Auto-Landing")
                    if (telemetry.isCompassError) statusFlags.add("Compass Error")
                    if (telemetry.isSportMode) statusFlags.add("Sport Mode")
                    if (statusFlags.isNotEmpty()) {
                        KeyValueItem(label = "Autopilot Status", value = statusFlags.joinToString(", "))
                    }
                    KeyValueItem(label = "GPS Satellites", value = telemetry.gpsSatellites.toString())
                    KeyValueItem(
                        label = "Coordinates",
                        value = "%.6f, %.6f".format(telemetry.latitudeDegrees, telemetry.longitudeDegrees),
                    )
                    val altSource = if (telemetry.isUltrasonicValid && telemetry.ultrasonicHeightMeters != null) "VPS" else "Barometer"
                    val effectiveAlt = if (telemetry.isUltrasonicValid && telemetry.ultrasonicHeightMeters != null) telemetry.ultrasonicHeightMeters!! else telemetry.relativeAltitudeMeters
                    KeyValueItem(label = "Altitude ($altSource)", value = "%.1f m".format(effectiveAlt))
                    if (telemetry.ultrasonicHeightMeters != null) {
                        KeyValueItem(label = "Ultrasonic Clearance", value = "%.2f m".format(telemetry.ultrasonicHeightMeters))
                    }
                    val groundSpeed = FlightDynamicsCalculator.calculateGroundSpeedMetersPerSecond(
                        telemetry.velocityXMetersPerSecond,
                        telemetry.velocityYMetersPerSecond,
                    )
                    val climbRate = FlightDynamicsCalculator.calculateClimbRateMetersPerSecond(telemetry.velocityZMetersPerSecond)
                    val horizontalSpeedText = FlightDynamicsCalculator.formatGroundSpeed(groundSpeed, isImperial = false)
                    val verticalSpeedText = FlightDynamicsCalculator.formatVerticalSpeed(climbRate, isImperial = false)
                    KeyValueItem(
                        label = "Speed (H / V)",
                        value = "$horizontalSpeedText · $verticalSpeedText",
                    )
                    val heading = FlightDynamicsCalculator.formatCompassHeading(telemetry.yawDegrees)
                    KeyValueItem(
                        label = "Attitude (P / R / HDG)",
                        value = "%.1f° / %.1f° / %s".format(
                            telemetry.pitchDegrees,
                            telemetry.rollDegrees,
                            heading,
                        ),
                    )
                    KeyValueItem(
                        label = "Motors / In Air",
                        value = "Motors=${telemetry.motorsRunning}, InAir=${telemetry.aircraftInAir}",
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            Text(
                text = "Last Command Interaction",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.lastCommandInteraction ?: "No commands sent or ACKs received yet.",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = if (state.lastCommandInteraction != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = "Last DUML Packet",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val lastPacket = state.lastDumlPacket
            KeyValueItem(
                label = "Source",
                value = lastPacket?.sourceModule ?: "—",
            )
            KeyValueItem(
                label = "Destination",
                value = lastPacket?.destinationModule ?: "—",
            )
            KeyValueItem(
                label = "Command",
                value = lastPacket?.commandSetAndId ?: "—",
            )
            KeyValueItem(
                label = "Seq / Length",
                value = lastPacket?.sequenceAndLength ?: "—",
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isPayloadHexExpanded = !isPayloadHexExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Payload (Hex)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (isPayloadHexExpanded) "Hide ▲" else "Show ▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (isPayloadHexExpanded) {
                Text(
                    text = lastPacket?.payloadHex ?: "Waiting for DUML frames…",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isRawHexExpanded = !isRawHexExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Last Raw USB Read (Hex)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (isRawHexExpanded) "Hide ▲" else "Show ▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (isRawHexExpanded) {
                Text(
                    text = state.lastRawHexPreview ?: "No bytes read yet.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun KeyValueItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = valueColor,
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ControllerDiagnosticsScreenPreview() {
    DroneTheme {
        ControllerDiagnosticsScreen(
            state = ControllerConnectionState(
                connectionPhase = ConnectionPhase.CONNECTED,
                statusMessage = "Connected to DJI T600 over Android Open Accessory (AOA).",
                accessoryDescriptor = UsbAccessoryDescriptor(
                    manufacturer = "DJI",
                    model = "T600",
                    description = "DJI USB Accessory",
                    version = "1.0",
                    uri = "http://www.dji.com",
                    serial = "S01A-DEMO",
                ),
                totalBytesReceived = 48_192L,
                totalBytesSent = 42L,
                aoaDumlEnvelopesReceived = 310L,
                aoaVideoEnvelopesReceived = 120L,
                videoBytesReceived = 38_400L,
                dumlFramesReceived = 310L,
                observedModules = setOf("Remote Controller (0x06)", "Flight Controller (0x03)"),
                lastDumlPacket = LastDumlPacketInfo(
                    sourceModule = "Remote Controller (0x06)",
                    destinationModule = "Mobile App (0x02)",
                    commandSetAndId = "Set 0x06 / ID 0x05",
                    sequenceAndLength = "#00042 ( 10 B)",
                    payloadHex = "00 04 00 04 00 04 00 04 00 04",
                ),
                lastRawHexPreview = "55 CC 49 57 17 00 00 00 55 17 04 92",
            ),
            onSetVideoPreviewEnabled = {},
            onDisconnectSubscribersClicked = {},
            onResumeSubscribersClicked = {},
        )
    }
}