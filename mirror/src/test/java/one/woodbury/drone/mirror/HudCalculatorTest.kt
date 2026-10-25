package one.woodbury.drone.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class HudCalculatorTest {

    @Test
    fun shortestAngularDeltaDegrees_handlesWrapAroundAcrossNorth() {
        assertEquals(20f, HudCalculator.shortestAngularDeltaDegrees(350f, 10f), 0.001f)
        assertEquals(-20f, HudCalculator.shortestAngularDeltaDegrees(10f, 350f), 0.001f)
        assertEquals(0f, HudCalculator.shortestAngularDeltaDegrees(180f, 180f), 0.001f)
        assertEquals(90f, HudCalculator.shortestAngularDeltaDegrees(270f, 0f), 0.001f)
    }

    @Test
    fun unwrapTargetHeadingDegrees_maintainsContinuousAngleAcrossNorth() {
        val step1 = HudCalculator.unwrapTargetHeadingDegrees(350f, 10f)
        assertEquals(370f, step1, 0.001f)

        val step2 = HudCalculator.unwrapTargetHeadingDegrees(step1, 355f)
        assertEquals(355f, step2, 0.001f)

        val step3 = HudCalculator.unwrapTargetHeadingDegrees(5f, 350f)
        assertEquals(-10f, step3, 0.001f)
    }

    @Test
    fun formatHeadingTickLabel_formatsCardinalsAndTwoDigitNumbers() {
        assertEquals("N", HudCalculator.formatHeadingTickLabel(0))
        assertEquals("N", HudCalculator.formatHeadingTickLabel(360))
        assertEquals("03", HudCalculator.formatHeadingTickLabel(30))
        assertEquals("E", HudCalculator.formatHeadingTickLabel(90))
        assertEquals("12", HudCalculator.formatHeadingTickLabel(120))
        assertEquals("S", HudCalculator.formatHeadingTickLabel(180))
        assertEquals("W", HudCalculator.formatHeadingTickLabel(270))
        assertEquals("33", HudCalculator.formatHeadingTickLabel(-30))
    }

    @Test
    fun formatSpeedBoxValue_formatsUnitlessSpeedsForMetricAndImperial() {
        assertEquals("5.0", HudCalculator.formatSpeedBoxValue(5.0f, UnitSystem.METRIC))
        assertEquals("11", HudCalculator.formatSpeedBoxValue(5.0f, UnitSystem.IMPERIAL))
        assertEquals("0.0", HudCalculator.formatSpeedBoxValue(-2.0f, UnitSystem.METRIC))
    }

    @Test
    fun formatAltitudeBoxValue_formatsUnitlessAltitudesForMetricAndImperial() {
        assertEquals("12.5", HudCalculator.formatAltitudeBoxValue(12.5f, UnitSystem.METRIC))
        assertEquals("41", HudCalculator.formatAltitudeBoxValue(12.5f, UnitSystem.IMPERIAL))
        assertEquals("-1.5", HudCalculator.formatAltitudeBoxValue(-1.5f, UnitSystem.METRIC))
        assertEquals("-5", HudCalculator.formatAltitudeBoxValue(-1.5f, UnitSystem.IMPERIAL))
    }
}
