package one.woodbury.drone.mirror

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Modal dialog describing DJI autopilot flight modes, behavior distinctions, and app aliases.
 */
@Composable
fun FlightModesDialog(
    currentFlightMode: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Flight Modes Reference",
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (currentFlightMode != null) {
                    Text(
                        text = "Active Mode: $currentFlightMode",
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF58A6FF),
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                }

                FlightModeItem(
                    name = "P-GPS",
                    summary = "Positioning Mode (GPS)",
                    description = "The flight controller uses GPS satellites and IMUs to hold precise 3D position and hover automatically when control sticks are centered. Steering is nose-relative: pushing forward on the pitch stick flies in the direction the aircraft's nose points. Commercial DJI apps (DJI Fly/GO) group both GPS Brake and GPS Attitude hold under the name 'P-GPS'.",
                )
                FlightModeItem(
                    name = "GPS-CL",
                    summary = "Positioning Mode (Course Lock)",
                    description = "Full GPS positioning and hover stabilization are active, but control stick steering is locked to a reference compass heading. Pushing forward flies along the locked heading regardless of which way the drone's nose is facing.",
                )
                FlightModeItem(
                    name = "P-OPTI / OPTI",
                    summary = "Positioning Mode (Vision / VPS)",
                    description = "Positioning uses downward stereo cameras and infrared Time-of-Flight (ToF) sensors to lock horizontal position and altitude. Active indoors or in GPS-denied environments within sensor range (< 8m).",
                )
                FlightModeItem(
                    name = "ATTI",
                    summary = "Attitude Mode",
                    description = "Neither GPS nor vision positioning is used for horizontal hold. The barometer maintains altitude, but the aircraft drifts with wind and momentum. The pilot must manually correct for drift.",
                )
                FlightModeItem(
                    name = "SPORT",
                    summary = "Sport Mode",
                    description = "Retains GPS positioning but increases max pitch angle and response speed up to 42 mph (68 km/h). Vision positioning and obstacle avoidance are disabled.",
                )
                FlightModeItem(
                    name = "RTH",
                    summary = "Return to Home",
                    description = "Automated failsafe or pilot-commanded flight back to the recorded Home Point. The aircraft climbs to the preset RTH altitude, flies back, and lands automatically.",
                )
                FlightModeItem(
                    name = "AUTO-LAND",
                    summary = "Automated Landing",
                    description = "Automated descent at a controlled vertical rate, shutting off motors upon ground contact. Can be engaged in flight by holding the Fn button on the DJI remote controller for 2 seconds. The remote controller emits a repeating two-beep pattern throughout the landing sequence.",
                )
                FlightModeItem(
                    name = "FORCE-LAND",
                    summary = "Forced / Confirmed Landing",
                    description = "Forced touchdown descent, shutting off the motors upon ground contact. Active during critical low-battery forced landings or when the pilot holds the left stick (throttle) down to override Landing Protection.",
                )
                FlightModeItem(
                    name = "AUTO-TAKEOFF",
                    summary = "Automated Takeoff",
                    description = "Automated motor start and ascent to a stable hover at approximately 1.2 meters (4 feet).",
                )
                FlightModeItem(
                    name = "TRIPOD",
                    summary = "Tripod Mode",
                    description = "Dampens control stick sensitivity and caps maximum flight speed for smooth, stable video recording.",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Close")
            }
        },
    )
}

@Composable
private fun FlightModeItem(
    name: String,
    summary: String,
    description: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "$name · $summary",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}
