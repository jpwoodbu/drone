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
import one.woodbury.drone.protocol.CameraTelemetry
import one.woodbury.drone.protocol.DumlProtocol

/**
 * Modal dialog describing camera operating modes, physical remote controller switching, and field of view differences.
 */
@Composable
fun CameraModeDialog(
    cameraTelemetry: CameraTelemetry?,
    onDismiss: () -> Unit,
) {
    val isVideoMode = cameraTelemetry?.workMode == DumlProtocol.CAMERA_WORK_MODE_RECORD
    val activeModeName = if (isVideoMode) "VIDEO" else "PHOTO"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Camera Mode Reference",
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
                Text(
                    text = "Active Mode: $activeModeName",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF58A6FF),
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                CameraModeSection(
                    title = "Current Mode Display",
                    description = "The status chip in the bottom status bar displays the current camera mode (PHOTO or VIDEO).",
                )
                CameraModeSection(
                    title = "Remote Controller Controls",
                    description = "The camera mode can be switched using the physical remote controller: press the video button to switch to video mode, or the shutter button to switch to photo mode.",
                )
                CameraModeSection(
                    title = "Field of View",
                    description = "Photo mode has a somewhat wider field of view than video mode.",
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
private fun CameraModeSection(
    title: String,
    description: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
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
