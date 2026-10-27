package one.woodbury.drone.mirror

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.woodbury.drone.flight.FlightDynamicsCalculator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pure calculation and formatting helpers for the aircraft HUD tapes and indicators.
 */
object HudCalculator {

    /**
     * Computes the signed shortest angular difference in degrees (`[-180.0, 180.0]`) from
     * [centerDegrees] to [targetDegrees].
     */
    fun shortestAngularDeltaDegrees(centerDegrees: Float, targetDegrees: Float): Float {
        var delta = (targetDegrees - centerDegrees) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    /**
     * Minimum angular delta in degrees required to update the heading tape target, deadbanding sub-degree jitter.
     */
    const val HEADING_DEADBAND_DEGREES = 0.25f

    /**
     * Advances a continuous unwrapped heading angle in degrees by the shortest angular delta
     * (`[-180.0, 180.0]`) to [newHeadingDegrees], preventing `360°` spin-back when animating
     * across North (`0° / 360°`).
     *
     * If the angular change from [previousUnwrappedDegrees] is within [deadbandDegrees],
     * returns [previousUnwrappedDegrees] unchanged to filter sensor noise.
     */
    fun unwrapTargetHeadingDegrees(
        previousUnwrappedDegrees: Float,
        newHeadingDegrees: Float,
        deadbandDegrees: Float = 0f,
    ): Float {
        val delta = shortestAngularDeltaDegrees(
            centerDegrees = previousUnwrappedDegrees,
            targetDegrees = newHeadingDegrees,
        )
        return if (abs(delta) >= deadbandDegrees) {
            previousUnwrappedDegrees + delta
        } else {
            previousUnwrappedDegrees
        }
    }

    /**
     * Formats a major compass tick (every 10 degrees) using cardinal letters (`N`, `E`, `S`, `W`)
     * or 2-digit aviation heading numbers (`01` through `35`).
     */
    fun formatHeadingTickLabel(degrees: Int): String {
        val normalized = ((degrees % 360) + 360) % 360
        return when (normalized) {
            0 -> "N"
            90 -> "E"
            180 -> "S"
            270 -> "W"
            else -> "%02d".format(normalized / 10)
        }
    }

    /**
     * Converts horizontal ground speed in meters per second to the active [unitSystem] value.
     */
    fun convertSpeedToDisplayUnits(
        groundSpeedMetersPerSecond: Float,
        unitSystem: UnitSystem,
    ): Float {
        val clamped = groundSpeedMetersPerSecond.coerceAtLeast(0f)
        return when (unitSystem) {
            UnitSystem.METRIC -> clamped
            UnitSystem.IMPERIAL -> clamped * FlightDynamicsCalculator.METERS_PER_SECOND_TO_MPH
        }
    }

    /**
     * Formats horizontal ground speed as a unitless numeric string for the speed tape readout box.
     */
    fun formatSpeedBoxValue(
        groundSpeedMetersPerSecond: Float,
        unitSystem: UnitSystem,
    ): String {
        val value = convertSpeedToDisplayUnits(groundSpeedMetersPerSecond, unitSystem)
        return when (unitSystem) {
            UnitSystem.METRIC -> "%.1f".format(value)
            UnitSystem.IMPERIAL -> "%.0f".format(value)
        }
    }

    /**
     * Converts altitude in meters to the active [unitSystem] value.
     */
    fun convertAltitudeToDisplayUnits(
        altitudeMeters: Float,
        unitSystem: UnitSystem,
    ): Float {
        return when (unitSystem) {
            UnitSystem.METRIC -> altitudeMeters
            UnitSystem.IMPERIAL -> altitudeMeters * AltitudeFormatter.FEET_PER_METER
        }
    }

    /**
     * Formats altitude as a unitless numeric string for the altitude tape readout box.
     */
    fun formatAltitudeBoxValue(
        altitudeMeters: Float,
        unitSystem: UnitSystem,
    ): String {
        val value = convertAltitudeToDisplayUnits(altitudeMeters, unitSystem)
        return when (unitSystem) {
            UnitSystem.METRIC -> "%.1f".format(value)
            UnitSystem.IMPERIAL -> "%.0f".format(value)
        }
    }
}

private val BaseHudGreen = Color(0xFF00FF66)
private val BaseHudAlertRed = Color(0xFFF85149)
private val BaseHudAlertAmber = Color(0xFFD29922)

/**
 * Duration in milliseconds for interpolating 10 Hz (`100 ms`) aircraft OSD telemetry updates
 * across native display VSYNC frames.
 */
private const val HUD_TAPE_INTERPOLATION_MILLIS = 100

/**
 * Aircraft-style Heads-Up Display (HUD) overlaid on the video viewport.
 */
@Composable
fun HudOverlay(
    state: MirrorReceiverState,
    onOpenBatteryHealth: () -> Unit,
    onToggleUnitSystem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val opacity = state.hudOpacity.coerceIn(0.2f, 1.0f)
    val hudColor = BaseHudGreen.copy(alpha = opacity)
    val boxBackgroundColor = Color.Black.copy(alpha = 0.4f * opacity)

    val telemetry = state.flightControllerTelemetry
    val isImperial = state.unitSystem.isImperial

    val hasValidVps = telemetry?.isUltrasonicValid == true && telemetry.ultrasonicHeightMeters != null
    val effectiveAltitudeMeters = if (hasValidVps) {
        telemetry!!.ultrasonicHeightMeters!!
    } else {
        telemetry?.relativeAltitudeMeters
    }
    val isAltitudeWarning = effectiveAltitudeMeters != null &&
        AltitudeFormatter.isHighAltitudeWarning(effectiveAltitudeMeters)

    val activeAlert = when {
        state.isReconnecting -> "LINK LOST" to BaseHudAlertRed.copy(alpha = opacity)
        telemetry?.isCompassError == true -> "COMPASS" to BaseHudAlertRed.copy(alpha = opacity)
        telemetry?.isAutoLanding == true -> "LANDING" to BaseHudAlertAmber.copy(alpha = opacity)
        else -> null
    }

    val vx = telemetry?.velocityXMetersPerSecond ?: 0f
    val vy = telemetry?.velocityYMetersPerSecond ?: 0f
    val vz = telemetry?.velocityZMetersPerSecond ?: 0f
    val groundSpeedMetersPerSecond = if (telemetry != null) {
        FlightDynamicsCalculator.calculateGroundSpeedMetersPerSecond(vx, vy)
    } else {
        null
    }
    val climbRateMetersPerSecond = if (telemetry != null) {
        FlightDynamicsCalculator.calculateClimbRateMetersPerSecond(vz)
    } else {
        null
    }
    val verticalSpeedText = if (climbRateMetersPerSecond != null) {
        FlightDynamicsCalculator.formatVerticalSpeed(climbRateMetersPerSecond, isImperial)
    } else {
        "—"
    }

    val headingDegrees = if (telemetry != null) {
        FlightDynamicsCalculator.normalizeCompassHeadingDegrees(telemetry.yawDegrees)
    } else {
        null
    }
    val headingBoxText = if (telemetry != null) {
        FlightDynamicsCalculator.formatCompassHeading(telemetry.yawDegrees)
    } else {
        "—"
    }

    val batteryPercentage = state.batteryTelemetry?.percentage
        ?: telemetry?.batteryPercentage?.takeIf { it > 0 }
    val powerWatts = state.batteryTelemetry?.powerWatts
    val batteryValue = when {
        batteryPercentage != null && powerWatts != null -> {
            "$batteryPercentage% · ${FlightDynamicsCalculator.formatPowerWatts(powerWatts)}"
        }
        batteryPercentage != null -> "$batteryPercentage%"
        else -> "—"
    }
    val estFlightTimeText = FlightDynamicsCalculator.formatEstimatedFlightTime(
        state.batteryTelemetry?.estimatedFlightTimeMinutes,
    )
    val gpsSatsText = telemetry?.gpsSatellites?.toString() ?: "—"

    val homeLat = state.homeLatitudeDegrees
    val homeLon = state.homeLongitudeDegrees
    val curLat = telemetry?.latitudeDegrees
    val curLon = telemetry?.longitudeDegrees
    val distanceText = if (
        homeLat != null && homeLon != null &&
        curLat != null && curLon != null &&
        curLat != 0.0 && curLon != 0.0
    ) {
        val distanceMeters = FlightDynamicsCalculator.calculateHaversineDistanceMeters(
            homeLat,
            homeLon,
            curLat,
            curLon,
        )
        FlightDynamicsCalculator.formatDistance(distanceMeters, isImperial)
    } else {
        "—"
    }

    val pitchText = if (telemetry != null) "%.0f°".format(telemetry.pitchDegrees) else "—"
    val rollText = if (telemetry != null) "%.0f°".format(telemetry.rollDegrees) else "—"
    val gimbalText = state.gimbalTelemetry?.let { "%.0f°".format(it.pitchDegrees) } ?: "—"

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1.1f)
                .padding(16.dp),
        ) {
            // Top-Center: Heading tape and active alert banner.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                HeadingTape(
                    headingDegrees = headingDegrees,
                    headingBoxText = headingBoxText,
                    hudColor = hudColor,
                    boxBackgroundColor = boxBackgroundColor,
                )
                if (activeAlert != null) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .background(boxBackgroundColor, RoundedCornerShape(2.dp))
                            .border(1.5.dp, activeAlert.second, RoundedCornerShape(2.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = activeAlert.first,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = activeAlert.second,
                        )
                    }
                }
            }

            // Top-Right: Gimbal pitch indicator.
            HudDataRow(
                label = "GIMBAL",
                value = gimbalText,
                hudColor = hudColor,
                boxBackgroundColor = boxBackgroundColor,
                modifier = Modifier.align(Alignment.TopEnd),
            )

            // Center: Total velocity vector symbol (-O-).
            VelocityVectorSymbol(
                hudColor = hudColor,
                modifier = Modifier.align(Alignment.Center),
            )

            // Left-Center: Vertical speed tape.
            SpeedTape(
                groundSpeedMetersPerSecond = groundSpeedMetersPerSecond,
                unitSystem = state.unitSystem,
                hudColor = hudColor,
                boxBackgroundColor = boxBackgroundColor,
                onClick = onToggleUnitSystem,
                modifier = Modifier.align(Alignment.CenterStart),
            )

            // Right-Center: Vertical altitude tape and vertical speed readout.
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                val altitudeColor = if (isAltitudeWarning) {
                    BaseHudAlertRed.copy(alpha = opacity)
                } else {
                    hudColor
                }
                AltitudeTape(
                    altitudeMeters = effectiveAltitudeMeters,
                    unitSystem = state.unitSystem,
                    hudColor = hudColor,
                    readoutColor = altitudeColor,
                    boxBackgroundColor = boxBackgroundColor,
                    onClick = onToggleUnitSystem,
                )
                HudDataRow(
                    label = "V/S",
                    value = verticalSpeedText,
                    hudColor = hudColor,
                    boxBackgroundColor = boxBackgroundColor,
                    onClick = onToggleUnitSystem,
                )
            }

            // Bottom-Left: Battery, Estimated Time, and GPS Satellites.
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                HudDataRow(
                    label = "BAT",
                    value = batteryValue,
                    hudColor = hudColor,
                    boxBackgroundColor = boxBackgroundColor,
                    onClick = onOpenBatteryHealth,
                )
                HudDataRow(
                    label = "TIME",
                    value = estFlightTimeText,
                    hudColor = hudColor,
                    boxBackgroundColor = boxBackgroundColor,
                )
                HudDataRow(
                    label = "GPS",
                    value = gpsSatsText,
                    hudColor = hudColor,
                    boxBackgroundColor = boxBackgroundColor,
                )
            }

            // Bottom-Right: Distance to Home, Pitch, and Roll.
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                HudDataRow(
                    label = "DIST",
                    value = distanceText,
                    hudColor = hudColor,
                    boxBackgroundColor = boxBackgroundColor,
                    onClick = onToggleUnitSystem,
                )
                HudDataRow(
                    label = "PITCH",
                    value = pitchText,
                    hudColor = hudColor,
                    boxBackgroundColor = boxBackgroundColor,
                )
                HudDataRow(
                    label = "ROLL",
                    value = rollText,
                    hudColor = hudColor,
                    boxBackgroundColor = boxBackgroundColor,
                )
            }
        }
    }
}

/**
 * Draws the velocity vector / flight path marker (`-O-` with vertical tail fin).
 */
@Composable
private fun VelocityVectorSymbol(
    hudColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.size(width = 44.dp, height = 28.dp),
    ) {
        val strokeWidthPx = 2.dp.toPx()
        val centerX = size.width / 2f
        val centerY = size.height / 2f + 2.dp.toPx()
        val radiusPx = 6.dp.toPx()
        val wingLengthPx = 12.dp.toPx()
        val finHeightPx = 7.dp.toPx()

        // Central circle.
        drawCircle(
            color = hudColor,
            radius = radiusPx,
            center = Offset(centerX, centerY),
            style = Stroke(width = strokeWidthPx),
        )
        // Left horizontal wing.
        drawLine(
            color = hudColor,
            start = Offset(centerX - radiusPx - wingLengthPx, centerY),
            end = Offset(centerX - radiusPx, centerY),
            strokeWidth = strokeWidthPx,
        )
        // Right horizontal wing.
        drawLine(
            color = hudColor,
            start = Offset(centerX + radiusPx, centerY),
            end = Offset(centerX + radiusPx + wingLengthPx, centerY),
            strokeWidth = strokeWidthPx,
        )
        // Top vertical tail fin.
        drawLine(
            color = hudColor,
            start = Offset(centerX, centerY - radiusPx - finHeightPx),
            end = Offset(centerX, centerY - radiusPx),
            strokeWidth = strokeWidthPx,
        )
    }
}

/**
 * Horizontal scrolling compass heading tape with cardinal/numeric ticks and a center digital readout box.
 */
@Composable
private fun HeadingTape(
    headingDegrees: Float?,
    headingBoxText: String,
    hudColor: Color,
    boxBackgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textSizePx = with(density) { 11.sp.toPx() }
    val textPaint = remember(hudColor, textSizePx) {
        Paint().apply {
            isAntiAlias = true
            color = hudColor.toArgb()
            textSize = textSizePx
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }

    var unwrappedTargetHeading by remember { mutableStateOf<Float?>(null) }
    val currentTargetHeading = if (headingDegrees != null) {
        val previous = unwrappedTargetHeading
        val next = if (previous != null) {
            HudCalculator.unwrapTargetHeadingDegrees(
                previousUnwrappedDegrees = previous,
                newHeadingDegrees = headingDegrees,
                deadbandDegrees = HudCalculator.HEADING_DEADBAND_DEGREES,
            )
        } else {
            headingDegrees
        }
        if (previous != next) {
            unwrappedTargetHeading = next
        }
        next
    } else {
        unwrappedTargetHeading = null
        0f
    }

    val animatedUnwrappedHeading by animateFloatAsState(
        targetValue = currentTargetHeading,
        animationSpec = tween(
            durationMillis = HUD_TAPE_INTERPOLATION_MILLIS,
            easing = LinearEasing,
        ),
        label = "headingTapeDegrees",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .width(240.dp)
                .height(34.dp)
                .background(boxBackgroundColor, RoundedCornerShape(2.dp))
                .clipToBounds(),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidthPx = 1.5.dp.toPx()
                val bottomY = size.height - 2.dp.toPx()
                val majorTickHeight = 8.dp.toPx()
                val minorTickHeight = 4.dp.toPx()
                val halfSpanDegrees = 35f

                // Baseline across the bottom of the heading tape.
                drawLine(
                    color = hudColor,
                    start = Offset(0f, bottomY),
                    end = Offset(size.width, bottomY),
                    strokeWidth = strokeWidthPx,
                )

                val centerHeading = if (headingDegrees != null) {
                    FlightDynamicsCalculator.normalizeCompassHeadingDegrees(animatedUnwrappedHeading)
                } else {
                    0f
                }
                val pixelsPerDegree = size.width / (halfSpanDegrees * 2f)
                val baseFive = (centerHeading / 5f).roundToInt() * 5

                for (offsetStep in -8..8) {
                    val tickDegrees = baseFive + offsetStep * 5
                    val deltaDegrees = HudCalculator.shortestAngularDeltaDegrees(
                        centerHeading,
                        tickDegrees.toFloat(),
                    )
                    if (deltaDegrees < -halfSpanDegrees || deltaDegrees > halfSpanDegrees) continue

                    val x = (size.width / 2f) + deltaDegrees * pixelsPerDegree
                    val isMajor = tickDegrees % 10 == 0
                    val tickHeight = if (isMajor) majorTickHeight else minorTickHeight

                    drawLine(
                        color = hudColor,
                        start = Offset(x, bottomY - tickHeight),
                        end = Offset(x, bottomY),
                        strokeWidth = strokeWidthPx,
                    )

                    if (isMajor && headingDegrees != null) {
                        val label = HudCalculator.formatHeadingTickLabel(tickDegrees)
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            x,
                            bottomY - majorTickHeight - 4.dp.toPx(),
                            textPaint,
                        )
                    }
                }
            }
        }

        // Lubber caret pointing up at the center of the heading tape.
        Canvas(modifier = Modifier.size(width = 12.dp, height = 6.dp)) {
            val path = Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(0f, size.height)
                lineTo(size.width, size.height)
                close()
            }
            drawPath(path = path, color = hudColor, style = Stroke(width = 1.5.dp.toPx()))
        }

        // Digital heading readout box.
        Box(
            modifier = Modifier
                .background(boxBackgroundColor, RoundedCornerShape(2.dp))
                .border(1.5.dp, hudColor, RoundedCornerShape(2.dp))
                .padding(horizontal = 8.dp, vertical = 1.dp),
        ) {
            Text(
                text = headingBoxText,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = hudColor,
            )
        }
    }
}

/**
 * Vertical scrolling ground speed tape on the left side of the HUD.
 */
@Composable
private fun SpeedTape(
    groundSpeedMetersPerSecond: Float?,
    unitSystem: UnitSystem,
    hudColor: Color,
    boxBackgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textSizePx = with(density) { 10.sp.toPx() }
    val textPaint = remember(hudColor, textSizePx) {
        Paint().apply {
            isAntiAlias = true
            color = hudColor.toArgb()
            textSize = textSizePx
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
    }

    val animatedSpeedMetersPerSecond by animateFloatAsState(
        targetValue = groundSpeedMetersPerSecond ?: 0f,
        animationSpec = tween(
            durationMillis = HUD_TAPE_INTERPOLATION_MILLIS,
            easing = LinearEasing,
        ),
        label = "speedTapeMetersPerSecond",
    )
    val displaySpeed = if (groundSpeedMetersPerSecond != null) {
        HudCalculator.convertSpeedToDisplayUnits(animatedSpeedMetersPerSecond, unitSystem)
    } else {
        null
    }
    val readoutText = if (groundSpeedMetersPerSecond != null) {
        HudCalculator.formatSpeedBoxValue(groundSpeedMetersPerSecond, unitSystem)
    } else {
        "—"
    }

    val minorStep = if (unitSystem == UnitSystem.METRIC) 1 else 2
    val majorStep = if (unitSystem == UnitSystem.METRIC) 5 else 10
    val halfSpanUnits = if (unitSystem == UnitSystem.METRIC) 12f else 24f

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .width(68.dp)
            .height(210.dp)
            .clip(RoundedCornerShape(2.dp))
            .clickable(onClick = onClick),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(boxBackgroundColor)
                .clipToBounds(),
        ) {
            val strokeWidthPx = 1.5.dp.toPx()
            val spineX = size.width - 2.dp.toPx()
            val majorTickWidth = 10.dp.toPx()
            val minorTickWidth = 5.dp.toPx()

            // Vertical spine along the right edge of the speed tape.
            drawLine(
                color = hudColor,
                start = Offset(spineX, 0f),
                end = Offset(spineX, size.height),
                strokeWidth = strokeWidthPx,
            )

            val centerValue = displaySpeed ?: 0f
            val pixelsPerUnit = size.height / (halfSpanUnits * 2f)
            val minTick = (floor((centerValue - halfSpanUnits) / minorStep).toInt() * minorStep)
                .coerceAtLeast(0)
            val maxTick = ceil((centerValue + halfSpanUnits) / minorStep).toInt() * minorStep

            var tick = minTick
            while (tick <= maxTick) {
                val delta = tick - centerValue
                val y = (size.height / 2f) - delta * pixelsPerUnit
                if (y in 0f..size.height) {
                    val isMajor = tick % majorStep == 0
                    val tickWidth = if (isMajor) majorTickWidth else minorTickWidth
                    drawLine(
                        color = hudColor,
                        start = Offset(spineX - tickWidth, y),
                        end = Offset(spineX, y),
                        strokeWidth = strokeWidthPx,
                    )
                    if (isMajor && displaySpeed != null) {
                        drawContext.canvas.nativeCanvas.drawText(
                            tick.toString(),
                            spineX - majorTickWidth - 4.dp.toPx(),
                            y + (textSizePx * 0.35f),
                            textPaint,
                        )
                    }
                }
                tick += minorStep
            }
        }

        // Speed unit on the outer edge.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 4.dp, top = 4.dp)
                .background(boxBackgroundColor, RoundedCornerShape(2.dp))
                .border(1.dp, hudColor.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                text = if (unitSystem.isImperial) "MPH" else "M/S",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = hudColor,
            )
        }

        // Fixed center digital speed box.
        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(end = 6.dp)
                .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(2.dp))
                .border(1.5.dp, hudColor, RoundedCornerShape(2.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = readoutText,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = hudColor,
            )
        }
    }
}

/**
 * Vertical scrolling altitude tape on the right side of the HUD.
 */
@Composable
private fun AltitudeTape(
    altitudeMeters: Float?,
    unitSystem: UnitSystem,
    hudColor: Color,
    readoutColor: Color,
    boxBackgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textSizePx = with(density) { 10.sp.toPx() }
    val textPaint = remember(hudColor, textSizePx) {
        Paint().apply {
            isAntiAlias = true
            color = hudColor.toArgb()
            textSize = textSizePx
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
    }

    val animatedAltitudeMeters by animateFloatAsState(
        targetValue = altitudeMeters ?: 0f,
        animationSpec = tween(
            durationMillis = HUD_TAPE_INTERPOLATION_MILLIS,
            easing = LinearEasing,
        ),
        label = "altitudeTapeMeters",
    )
    val displayAltitude = if (altitudeMeters != null) {
        HudCalculator.convertAltitudeToDisplayUnits(animatedAltitudeMeters, unitSystem)
    } else {
        null
    }
    val readoutText = if (altitudeMeters != null) {
        HudCalculator.formatAltitudeBoxValue(altitudeMeters, unitSystem)
    } else {
        "—"
    }

    val minorStep = if (unitSystem == UnitSystem.METRIC) 2 else 10
    val majorStep = if (unitSystem == UnitSystem.METRIC) 10 else 50
    val halfSpanUnits = if (unitSystem == UnitSystem.METRIC) 25f else 100f

    Box(
        contentAlignment = Alignment.CenterEnd,
        modifier = modifier
            .width(74.dp)
            .height(210.dp)
            .clip(RoundedCornerShape(2.dp))
            .clickable(onClick = onClick),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(boxBackgroundColor)
                .clipToBounds(),
        ) {
            val strokeWidthPx = 1.5.dp.toPx()
            val spineX = 2.dp.toPx()
            val majorTickWidth = 10.dp.toPx()
            val minorTickWidth = 5.dp.toPx()

            // Vertical spine along the left edge of the altitude tape.
            drawLine(
                color = hudColor,
                start = Offset(spineX, 0f),
                end = Offset(spineX, size.height),
                strokeWidth = strokeWidthPx,
            )

            val centerValue = displayAltitude ?: 0f
            val pixelsPerUnit = size.height / (halfSpanUnits * 2f)
            val minTick = floor((centerValue - halfSpanUnits) / minorStep).toInt() * minorStep
            val maxTick = ceil((centerValue + halfSpanUnits) / minorStep).toInt() * minorStep

            var tick = minTick
            while (tick <= maxTick) {
                val delta = tick - centerValue
                val y = (size.height / 2f) - delta * pixelsPerUnit
                if (y in 0f..size.height) {
                    val isMajor = tick % majorStep == 0
                    val tickWidth = if (isMajor) majorTickWidth else minorTickWidth
                    drawLine(
                        color = hudColor,
                        start = Offset(spineX, y),
                        end = Offset(spineX + tickWidth, y),
                        strokeWidth = strokeWidthPx,
                    )
                    if (isMajor && displayAltitude != null) {
                        drawContext.canvas.nativeCanvas.drawText(
                            tick.toString(),
                            spineX + majorTickWidth + 4.dp.toPx(),
                            y + (textSizePx * 0.35f),
                            textPaint,
                        )
                    }
                }
                tick += minorStep
            }
        }

        // Altitude unit on the outer edge.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 4.dp, top = 4.dp)
                .background(boxBackgroundColor, RoundedCornerShape(2.dp))
                .border(1.dp, hudColor.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                text = if (unitSystem.isImperial) "FT" else "M",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = hudColor,
            )
        }

        // Fixed center digital altitude box.
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(start = 6.dp)
                .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(2.dp))
                .border(1.5.dp, readoutColor, RoundedCornerShape(2.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = readoutText,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = readoutColor,
            )
        }
    }
}

/**
 * Compact HUD corner telemetry label and value pair.
 */
@Composable
private fun HudDataRow(
    label: String,
    value: String,
    hudColor: Color,
    boxBackgroundColor: Color,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val rowModifier = if (onClick != null) {
        modifier
            .clip(RoundedCornerShape(2.dp))
            .background(boxBackgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    } else {
        modifier
            .clip(RoundedCornerShape(2.dp))
            .background(boxBackgroundColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = rowModifier,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = hudColor.copy(alpha = hudColor.alpha * 0.75f),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = hudColor,
        )
    }
}
