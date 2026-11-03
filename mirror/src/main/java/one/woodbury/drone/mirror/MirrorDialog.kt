package one.woodbury.drone.mirror

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Reusable landscape-optimized [AlertDialog] wrapper for the Drone Mirror application.
 *
 * In a landscape-only application, the default Android platform width constraint
 * ([DialogProperties.usePlatformDefaultWidth] = true) over-constrains dialog width to narrow phone
 * portrait bounds, forcing excessive vertical scrolling and cramped controls.
 *
 * This wrapper disables the platform default width and applies sizing constraints following the
 * Material Design 3 basic dialog specification (https://m3.material.io/components/dialogs/specs):
 * - Targets 55% of the landscape screen width to balance comfortable content layout with backdrop visibility.
 * - Enforces the Material Design 3 minimum container width of 280dp.
 * - Caps the width at the Material Design 3 maximum container width of 560dp so dialogs remain focused
 *   and do not become overly wide banners on tablets or VR virtual panels.
 */
@Composable
fun MirrorDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        title = title,
        text = text,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = modifier
            .fillMaxWidth(0.55f)
            .widthIn(min = 280.dp, max = 560.dp),
    )
}
