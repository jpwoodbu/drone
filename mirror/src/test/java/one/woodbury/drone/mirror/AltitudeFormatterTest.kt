package one.woodbury.drone.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AltitudeFormatterTest {

    @Test
    fun formatAltitude_formatsMetersWithOneDecimalPlace() {
        assertEquals("0.0 m", AltitudeFormatter.formatAltitude(0.0f, AltitudeUnit.METERS))
        assertEquals("12.5 m", AltitudeFormatter.formatAltitude(12.54f, AltitudeUnit.METERS))
        assertEquals("121.9 m", AltitudeFormatter.formatAltitude(121.92f, AltitudeUnit.METERS))
        assertEquals("-1.5 m", AltitudeFormatter.formatAltitude(-1.5f, AltitudeUnit.METERS))
    }

    @Test
    fun formatAltitude_formatsFeetAsInteger() {
        assertEquals("0 ft", AltitudeFormatter.formatAltitude(0.0f, AltitudeUnit.FEET))
        assertEquals("41 ft", AltitudeFormatter.formatAltitude(12.5f, AltitudeUnit.FEET))
        assertEquals("400 ft", AltitudeFormatter.formatAltitude(121.92f, AltitudeUnit.FEET))
        assertEquals("-5 ft", AltitudeFormatter.formatAltitude(-1.5f, AltitudeUnit.FEET))
    }

    @Test
    fun isHighAltitudeWarning_triggersAtAndAboveFourHundredFeet() {
        assertFalse(AltitudeFormatter.isHighAltitudeWarning(0.0f))
        assertFalse(AltitudeFormatter.isHighAltitudeWarning(50.0f))
        assertFalse(AltitudeFormatter.isHighAltitudeWarning(121.91f))

        assertTrue(AltitudeFormatter.isHighAltitudeWarning(121.92f))
        assertTrue(AltitudeFormatter.isHighAltitudeWarning(122.0f))
        assertTrue(AltitudeFormatter.isHighAltitudeWarning(200.0f))
    }

    @Test
    fun isHighAltitudeWarning_returnsFalseForNegativeAltitudes() {
        assertFalse(AltitudeFormatter.isHighAltitudeWarning(-5.0f))
    }
}
