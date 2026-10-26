package one.woodbury.drone.mirror

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Renders a photography "rule of thirds" grid overlay over the live video stream.
 *
 * The grid divides the viewport into nine equal rectangles with two equally spaced horizontal
 * lines and two vertical lines. Lines are drawn with translucent white strokes and a subtle
 * dark drop shadow offset by 1 dp so they remain clearly visible over bright skies or white backgrounds.
 */
@Composable
fun RuleOfThirdsGridOverlay(
    modifier: Modifier = Modifier,
    lineColor: Color = Color.White.copy(alpha = 0.65f),
    shadowColor: Color = Color.Black.copy(alpha = 0.45f),
    strokeWidth: Dp = 1.dp,
    shadowOffset: Dp = 1.dp,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val strokeWidthPx = strokeWidth.toPx()
        val shadowOffsetPx = shadowOffset.toPx()
        val w = size.width
        val h = size.height

        val oneThirdX = w / 3f
        val twoThirdsX = w * 2f / 3f
        val oneThirdY = h / 3f
        val twoThirdsY = h * 2f / 3f

        // Draw subtle drop shadows first.
        drawLine(
            color = shadowColor,
            start = Offset(oneThirdX + shadowOffsetPx, shadowOffsetPx),
            end = Offset(oneThirdX + shadowOffsetPx, h + shadowOffsetPx),
            strokeWidth = strokeWidthPx,
        )
        drawLine(
            color = shadowColor,
            start = Offset(twoThirdsX + shadowOffsetPx, shadowOffsetPx),
            end = Offset(twoThirdsX + shadowOffsetPx, h + shadowOffsetPx),
            strokeWidth = strokeWidthPx,
        )
        drawLine(
            color = shadowColor,
            start = Offset(shadowOffsetPx, oneThirdY + shadowOffsetPx),
            end = Offset(w + shadowOffsetPx, oneThirdY + shadowOffsetPx),
            strokeWidth = strokeWidthPx,
        )
        drawLine(
            color = shadowColor,
            start = Offset(shadowOffsetPx, twoThirdsY + shadowOffsetPx),
            end = Offset(w + shadowOffsetPx, twoThirdsY + shadowOffsetPx),
            strokeWidth = strokeWidthPx,
        )

        // Draw translucent white rule-of-thirds lines.
        drawLine(
            color = lineColor,
            start = Offset(oneThirdX, 0f),
            end = Offset(oneThirdX, h),
            strokeWidth = strokeWidthPx,
        )
        drawLine(
            color = lineColor,
            start = Offset(twoThirdsX, 0f),
            end = Offset(twoThirdsX, h),
            strokeWidth = strokeWidthPx,
        )
        drawLine(
            color = lineColor,
            start = Offset(0f, oneThirdY),
            end = Offset(w, oneThirdY),
            strokeWidth = strokeWidthPx,
        )
        drawLine(
            color = lineColor,
            start = Offset(0f, twoThirdsY),
            end = Offset(w, twoThirdsY),
            strokeWidth = strokeWidthPx,
        )
    }
}
