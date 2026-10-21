package one.woodbury.drone.mirror

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface as ComposeSurface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import one.woodbury.drone.video.H264VideoDecoder

/**
 * Landscape 2D viewer activity for the Meta Quest 2 (`one.woodbury.drone.mirror`).
 * Displays the live 16:9 H.264 video feed from the Mavic Air alongside a compact telemetry HUD bar
 * and a persistent manual Phone Bridge IP input.
 */
class MirrorMainActivity : ComponentActivity() {

    private lateinit var bridgeReceiver: UdpBridgeReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bridgeReceiver = UdpBridgeReceiver(applicationContext)
        bridgeReceiver.start()

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
                        onSetPhoneBridgeIp = { ip -> bridgeReceiver.setConfiguredPhoneIp(ip) },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
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
    onSetPhoneBridgeIp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TopTelemetryHudBar(state = state)

        PhoneBridgeIpBar(
            configuredIp = state.configuredPhoneIp,
            onApplyPhoneIp = onSetPhoneBridgeIp,
        )

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
                            TextureView(context).apply {
                                var activeSurface: Surface? = null
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(
                                        surfaceTexture: SurfaceTexture,
                                        width: Int,
                                        height: Int,
                                    ) {
                                        val surface = Surface(surfaceTexture)
                                        activeSurface = surface
                                        videoDecoder.attachSurface(surface)
                                    }

                                    override fun onSurfaceTextureSizeChanged(
                                        surfaceTexture: SurfaceTexture,
                                        width: Int,
                                        height: Int,
                                    ) = Unit

                                    override fun onSurfaceTextureDestroyed(
                                        surfaceTexture: SurfaceTexture,
                                    ): Boolean {
                                        videoDecoder.detachSurface()
                                        activeSurface?.release()
                                        activeSurface = null
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(
                                        surfaceTexture: SurfaceTexture,
                                    ) = Unit
                                }
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
                                state.phoneBridgeIp != null ->
                                    "Connected to Phone Bridge (${state.phoneBridgeIp}) — awaiting H.264 keyframe…"
                                state.configuredPhoneIp.isNotEmpty() ->
                                    "Sending unicast heartbeat to ${state.configuredPhoneIp}:5600…"
                                else ->
                                    "Broadcasting discovery heartbeat on UDP :5600…"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White,
                        )
                        Text(
                            text = if (state.localIpv4Addresses.isEmpty()) {
                                "No Wi-Fi IPv4 address detected on Quest 2"
                            } else {
                                "Quest 2 IP: ${state.localIpv4Addresses.joinToString(", ")}"
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
            }
        }

        BottomDiagnosticsBar(state = state)
    }
}

@Composable
private fun PhoneBridgeIpBar(
    configuredIp: String,
    onApplyPhoneIp: (String) -> Unit,
) {
    var textValue by remember(configuredIp) { mutableStateOf(configuredIp) }
    val focusManager = LocalFocusManager.current

    ComposeSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF161B22),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = textValue,
                onValueChange = { textValue = it },
                label = {
                    Text(
                        text = "Phone Bridge IPv4 (optional — leave blank for auto-discovery)",
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
                    if (textValue.isNotEmpty() || configuredIp.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                textValue = ""
                                focusManager.clearFocus()
                                onApplyPhoneIp("")
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
                        onApplyPhoneIp(textValue)
                    },
                ),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onApplyPhoneIp(textValue)
                },
            ) {
                Text(text = "Connect IP")
            }
            OutlinedButton(
                onClick = {
                    textValue = ""
                    focusManager.clearFocus()
                    onApplyPhoneIp("")
                },
                enabled = textValue.isNotBlank() || configuredIp.isNotBlank(),
            ) {
                Text(text = "Auto")
            }
        }
    }
}

@Composable
private fun TopTelemetryHudBar(state: MirrorReceiverState) {
    val telemetry = state.flightControllerTelemetry
    val axes = state.remoteControllerAxes

    ComposeSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF161B22),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HudMetricItem(
                label = "BRIDGE",
                value = state.phoneBridgeIp
                    ?: if (state.configuredPhoneIp.isNotEmpty()) {
                        "PING ${state.configuredPhoneIp}:5600"
                    } else {
                        "SEARCHING :5600"
                    },
                highlight = state.phoneBridgeIp != null,
            )
            HudMetricItem(
                label = "BATTERY",
                value = if (telemetry != null) "${telemetry.batteryPercentage}%" else "—",
            )
            HudMetricItem(
                label = "ALTITUDE",
                value = if (telemetry != null) "%.1f m".format(telemetry.relativeAltitudeMeters) else "—",
            )
            HudMetricItem(
                label = "GPS SATS",
                value = telemetry?.gpsSatellites?.toString() ?: "—",
            )
            HudMetricItem(
                label = "ATTITUDE (P/R/Y)",
                value = if (telemetry != null) {
                    "%.0f° / %.0f° / %.0f°".format(
                        telemetry.pitchDegrees,
                        telemetry.rollDegrees,
                        telemetry.yawDegrees,
                    )
                } else {
                    "—"
                },
            )
            HudMetricItem(
                label = "RC STICKS (L/R)",
                value = if (axes != null) {
                    "${axes.leftStickHorizontal},${axes.leftStickVertical} | ${axes.rightStickHorizontal},${axes.rightStickVertical}"
                } else {
                    "—"
                },
            )
        }
    }
}

@Composable
private fun BottomDiagnosticsBar(state: MirrorReceiverState) {
    val metrics = state.videoDecoderMetrics
    ComposeSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF161B22),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "VIDEO: ${metrics.videoWidth}×${metrics.videoHeight} (${metrics.framesDecoded} frames, ${metrics.keyframesReceived} IDR)",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "UDP TX: ${state.heartbeatsSent} | RX: ${state.udpPacketsReceived} pkts (${state.udpBytesReceived / 1024} KB) | DUML: ${state.dumlFramesReceived} | Drops: ${state.udpSequenceDrops}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HudMetricItem(
    label: String,
    value: String,
    highlight: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.Start) {
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
            color = if (highlight) Color(0xFF58A6FF) else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
