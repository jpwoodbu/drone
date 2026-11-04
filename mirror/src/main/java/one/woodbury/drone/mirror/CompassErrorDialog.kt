package one.woodbury.drone.mirror

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Landscape-optimized alert dialog explaining that a flight controller compass error
 * or calibration requirement is blocking motor arming and takeoff.
 */
@Composable
fun CompassErrorDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MirrorDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Close")
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Compass Calibration Required",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF85149),
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "The flight controller has detected severe magnetic interference or a compass discrepancy. " +
                        "The aircraft cannot take off and motors cannot be armed while this condition persists.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "To resolve this, move the drone to an open area (such as grass or dirt at least 10 feet away from reinforced concrete, metal railings, or cars).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Compass calibration can be triggered and guided in the Drone Bridge app on the connected device.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        modifier = modifier,
    )
}
