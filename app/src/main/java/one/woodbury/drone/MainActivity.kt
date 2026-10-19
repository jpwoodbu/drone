package one.woodbury.drone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import one.woodbury.drone.ui.theme.DroneTheme
import one.woodbury.drone.usb.ConnectionPhase
import one.woodbury.drone.usb.ControllerConnectionState
import one.woodbury.drone.usb.LastDumlPacketInfo
import one.woodbury.drone.usb.UsbAccessoryConnectionManager
import one.woodbury.drone.usb.UsbAccessoryDescriptor

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
                        onScanClicked = { connectionManager.scanAndConnect() },
                        onQueryVersionClicked = { connectionManager.sendVersionQuery() },
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
    onScanClicked: () -> Unit,
    onQueryVersionClicked: () -> Unit,
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
            text = "Mavic Air (S01A) USB Inspector",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        ConnectionStatusCard(
            state = state,
            onScanClicked = onScanClicked,
            onQueryVersionClicked = onQueryVersionClicked,
        )

        UsbDescriptorCard(descriptor = state.accessoryDescriptor)

        StreamMetricsCard(state = state)

        TelemetryDetailsCard(state = state)
    }
}

@Composable
private fun ConnectionStatusCard(
    state: ControllerConnectionState,
    onScanClicked: () -> Unit,
    onQueryVersionClicked: () -> Unit,
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

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onScanClicked,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "Scan USB")
                }
                OutlinedButton(
                    onClick = onQueryVersionClicked,
                    enabled = state.connectionPhase == ConnectionPhase.CONNECTED,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "Ping DUML")
                }
            }
        }
    }
}

@Composable
private fun UsbDescriptorCard(descriptor: UsbAccessoryDescriptor?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
                KeyValueRow(label = "Manufacturer", value = descriptor.manufacturer)
                KeyValueRow(label = "Model", value = descriptor.model)
                KeyValueRow(label = "Description", value = descriptor.description)
                KeyValueRow(label = "Version", value = descriptor.version)
                KeyValueRow(label = "URI", value = descriptor.uri)
                KeyValueRow(label = "Serial", value = descriptor.serial)
            }
        }
    }
}

@Composable
private fun StreamMetricsCard(state: ControllerConnectionState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Live AOA / DUML Stream",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider()
            KeyValueRow(label = "Bytes Received", value = "${state.totalBytesReceived} B")
            KeyValueRow(label = "Bytes Sent", value = "${state.totalBytesSent} B")
            KeyValueRow(
                label = "AOA DUML Envelopes (0x5749)",
                value = state.aoaDumlEnvelopesReceived.toString(),
            )
            KeyValueRow(
                label = "AOA Video Envelopes (0x574A)",
                value = "${state.aoaVideoEnvelopesReceived} (${state.videoBytesReceived} B)",
            )
            KeyValueRow(
                label = "Valid DUML v1 Packets",
                value = state.dumlFramesReceived.toString(),
            )
            KeyValueRow(
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
                    KeyValueRow(label = moduleName, value = versionText)
                }
            }
        }
    }
}

@Composable
private fun TelemetryDetailsCard(state: ControllerConnectionState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
                KeyValueRow(
                    label = "Left Stick (Yaw / Throttle)",
                    value = "${axes.leftStickHorizontal} / ${axes.leftStickVertical}",
                )
                KeyValueRow(
                    label = "Right Stick (Roll / Pitch)",
                    value = "${axes.rightStickHorizontal} / ${axes.rightStickVertical}",
                )
                KeyValueRow(
                    label = "Gimbal Wheel",
                    value = axes.gimbalWheel.toString(),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            val telemetry = state.flightControllerTelemetry
            if (telemetry != null) {
                Text(
                    text = "Aircraft OSD Telemetry (0x03/0x43)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                KeyValueRow(label = "Battery", value = "${telemetry.batteryPercentage}%")
                KeyValueRow(label = "GPS Satellites", value = telemetry.gpsSatellites.toString())
                KeyValueRow(
                    label = "Coordinates",
                    value = "%.6f, %.6f".format(telemetry.latitudeDegrees, telemetry.longitudeDegrees),
                )
                KeyValueRow(
                    label = "Relative Altitude",
                    value = "%.1f m".format(telemetry.relativeAltitudeMeters),
                )
                KeyValueRow(
                    label = "Attitude (P / R / Y)",
                    value = "%.1f° / %.1f° / %.1f°".format(
                        telemetry.pitchDegrees,
                        telemetry.rollDegrees,
                        telemetry.yawDegrees,
                    ),
                )
                KeyValueRow(
                    label = "Motors / In Air",
                    value = "Motors=${telemetry.motorsRunning}, InAir=${telemetry.aircraftInAir}",
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            Text(
                text = "Last DUML Packet",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val lastPacket = state.lastDumlPacket
            KeyValueRow(
                label = "Source",
                value = lastPacket?.sourceModule ?: "—",
            )
            KeyValueRow(
                label = "Destination",
                value = lastPacket?.destinationModule ?: "—",
            )
            KeyValueRow(
                label = "Command",
                value = lastPacket?.commandSetAndId ?: "—",
            )
            KeyValueRow(
                label = "Seq / Length",
                value = lastPacket?.sequenceAndLength ?: "—",
            )
            Text(
                text = "Payload (Hex)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = lastPacket?.payloadHex ?: "Waiting for DUML frames…",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Last Raw USB Read (Hex)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.lastRawHexPreview ?: "No bytes read yet.",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun KeyValueRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.45f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.55f),
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
            onScanClicked = {},
            onQueryVersionClicked = {},
        )
    }
}