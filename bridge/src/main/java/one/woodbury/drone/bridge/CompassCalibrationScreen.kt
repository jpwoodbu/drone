package one.woodbury.drone.bridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Screen providing step-by-step guidance on performing a compass calibration on the aircraft
 * using the rear status indicator LED on the tail of the drone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompassCalibrationScreen(
    onBackClicked: () -> Unit,
    onStartCalibrationClicked: () -> Unit,
    onCancelCalibrationClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Compass Calibration",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBackClicked) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "Navigate back",
                    )
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Preparation",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    HorizontalDivider()
                    Text(
                        text = "1. Move the aircraft to an open outdoor area (such as grass or dirt), at least 10 to 20 feet away from reinforced concrete, cars, metal fences, or buried utilities.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "2. Remove smartwatches, phones, keys, or other metal objects from your hands and pockets to prevent sensor interference.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "3. Ensure motors are disarmed and propellers are stopped.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Calibration Controls",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    HorizontalDivider()
                    Text(
                        text = "Tap 'Start Compass Calibration' to send the calibration command to the aircraft. If calibration fails or needs to be aborted, tap 'Cancel Calibration' to reset the flight controller back to normal standby.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onStartCalibrationClicked,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = "Start Compass Calibration")
                    }
                    OutlinedButton(
                        onClick = onCancelCalibrationClicked,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Cancel Calibration",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Step 1: Horizontal Rotation",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFD29922),
                    )
                    HorizontalDivider()
                    Text(
                        text = "Hold the aircraft horizontally flat with both hands. The taillight will be solid yellow.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Rotate your body and the drone in a smooth circle. Keep rotating until the taillight changes to solid green.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Step 2: Vertical Rotation",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF3FB950),
                    )
                    HorizontalDivider()
                    Text(
                        text = "Tilt the aircraft with its nose pointing straight down toward the ground. The taillight will be solid green.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Rotate your body and the drone around the vertical axis. Keep rotating until the taillight flashes to confirm the result.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Tail Indicator Light Results",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    HorizontalDivider()
                    Text(
                        text = "• Rapid Green Flashing: Calibration succeeded. The flight controller has saved the calibration and the compass error will clear.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF3FB950),
                    )
                    Text(
                        text = "• Flashing Red: Calibration failed. Strong magnetic interference was detected during rotation. Relocate to another spot and repeat.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFF85149),
                    )
                    Text(
                        text = "• Alternating Red & Yellow: Calibration required; flight controller lockout active.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD29922),
                    )
                }
            }
        }
    }
}
