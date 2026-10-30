package one.woodbury.drone.mirror

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Modal dialog displaying detailed Bridge-to-Mirror UDP bridge connection and network diagnostics.
 */
@Composable
fun NetworkDiagnosticsDialog(
    state: MirrorReceiverState,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Bridge Network Diagnostics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val bridgeIpText = state.bridgeIp?.let { "$it:5600" }
                    ?: if (state.configuredBridgeIp.isNotEmpty()) {
                        "Searching for ${state.configuredBridgeIp}:5600"
                    } else {
                        "Searching on :5600…"
                    }
                NetworkRow(
                    label = "Bridge Connection IP",
                    value = bridgeIpText,
                    highlight = state.bridgeIp != null,
                )

                val totalPackets = state.udpPacketsReceived + state.udpSequenceDrops
                val dropRate = if (totalPackets > 0L) {
                    (state.udpSequenceDrops.toDouble() / totalPackets.toDouble()) * 100.0
                } else {
                    0.0
                }
                val (qualityText, qualityColor) = when {
                    state.bridgeIp == null -> "DISCONNECTED" to Color.White.copy(alpha = 0.5f)
                    state.udpSequenceDrops == 0L -> "EXCELLENT (0 drops)" to Color(0xFF3FB950)
                    dropRate < 1.0 -> "GOOD (%.2f%% drop rate)".format(dropRate) to Color(0xFF3FB950)
                    dropRate < 2.0 -> "DEGRADED (%.1f%% loss)".format(dropRate) to Color(0xFFD29922)
                    else -> "LOSS (%.1f%% loss)".format(dropRate) to Color(0xFFF85149)
                }
                NetworkRow(
                    label = "Stream Quality",
                    value = qualityText,
                    valueColor = qualityColor,
                    highlight = state.bridgeIp != null && dropRate < 1.0,
                )

                NetworkRow(
                    label = "Manual Target IP",
                    value = if (state.configuredBridgeIp.isNotEmpty()) state.configuredBridgeIp else "None (Auto mDNS)",
                )

                val localIpText = if (state.localIpv4Addresses.isEmpty()) {
                    "None detected"
                } else {
                    state.localIpv4Addresses.joinToString(", ")
                }
                NetworkRow(label = "Local IP Address", value = localIpText)

                NetworkRow(label = "UDP Socket", value = state.socketStatus)
                NetworkRow(label = "mDNS Discovery", value = state.nsdStatus)
                NetworkRow(label = "Heartbeats TX", value = state.heartbeatsSent.toString())
                NetworkRow(label = "UDP Packets RX", value = "%,d".format(state.udpPacketsReceived))
                NetworkRow(label = "UDP Data RX", value = "%,d KB".format(state.udpBytesReceived / 1024))
                NetworkRow(label = "DUML Packets RX", value = "%,d".format(state.dumlFramesReceived))

                val dropsColor = if (state.udpSequenceDrops > 0L) Color(0xFFFF7B72) else Color.Unspecified
                NetworkRow(
                    label = "UDP Sequence Drops",
                    value = "%,d".format(state.udpSequenceDrops),
                    valueColor = dropsColor,
                )

                if (state.lastSocketError != null) {
                    NetworkRow(
                        label = "Last Socket Error",
                        value = state.lastSocketError,
                        valueColor = Color(0xFFFF7B72),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Close")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onOpenSettings()
                },
            ) {
                Text(text = "Configure IP")
            }
        },
    )
}

@Composable
private fun NetworkRow(
    label: String,
    value: String,
    highlight: Boolean = false,
    valueColor: Color = Color.Unspecified,
) {
    // Keeps the label and value on one line when they fit, and otherwise wraps the value onto its
    // own line below the label rather than squeezing or clipping it.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) Color(0xFF58A6FF) else valueColor,
        )
    }
}
