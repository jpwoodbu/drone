package one.woodbury.drone.flight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlightDynamicsCalculatorTest {

    @Test
    fun calculateGroundSpeed_computesEuclideanNorm() {
        val speed345 = FlightDynamicsCalculator.calculateGroundSpeedMetersPerSecond(3.0f, 4.0f)
        assertEquals(5.0f, speed345, 1e-4f)

        val speedZero = FlightDynamicsCalculator.calculateGroundSpeedMetersPerSecond(0.0f, 0.0f)
        assertEquals(0.0f, speedZero, 1e-4f)

        val speedNegative = FlightDynamicsCalculator.calculateGroundSpeedMetersPerSecond(-6.0f, 8.0f)
        assertEquals(10.0f, speedNegative, 1e-4f)
    }

    @Test
    fun calculateHaversineDistance_matchesKnownGeoDistance() {
        // San Francisco (37.7749, -122.4194) to Oakland (37.8044, -122.2711) is ~13.5 km.
        val distanceMeters = FlightDynamicsCalculator.calculateHaversineDistanceMeters(
            startLatitude = 37.7749,
            startLongitude = -122.4194,
            endLatitude = 37.8044,
            endLongitude = -122.2711,
        )
        assertEquals(13480.0, distanceMeters, 150.0)

        // Same point distance is 0.
        val zeroDistance = FlightDynamicsCalculator.calculateHaversineDistanceMeters(
            startLatitude = 37.7749,
            startLongitude = -122.4194,
            endLatitude = 37.7749,
            endLongitude = -122.4194,
        )
        assertEquals(0.0, zeroDistance, 1e-3)
    }

    @Test
    fun calculatePowerWatts_computesProductCorrectly() {
        // 11.4 V at 12.5 A discharge = 142.5 W.
        val power = FlightDynamicsCalculator.calculatePowerWatts(11400, -12500)
        assertEquals(142.5f, power!!, 1e-2f)

        assertNull(FlightDynamicsCalculator.calculatePowerWatts(null, -12500))
        assertNull(FlightDynamicsCalculator.calculatePowerWatts(11400, null))
    }

    @Test
    fun calculateEstimatedFlightTime_computesMinutes() {
        // 2000 mAh remaining at 6000 mA (6 A) discharge = 20 minutes.
        val minutes = FlightDynamicsCalculator.calculateEstimatedFlightTimeMinutes(2000, -6000)
        assertEquals(20.0f, minutes!!, 1e-2f)

        // Disregard low idle currents (< 200 mA).
        val idle = FlightDynamicsCalculator.calculateEstimatedFlightTimeMinutes(2000, -100)
        assertNull(idle)
    }

    @Test
    fun calculateCellDelta_identifiesMaxDelta() {
        val delta = FlightDynamicsCalculator.calculateCellDeltaMillivolts(listOf(3850, 3865, 3840))
        assertEquals(25, delta)

        val singleCell = FlightDynamicsCalculator.calculateCellDeltaMillivolts(listOf(3850))
        assertNull(singleCell)
    }

    @Test
    fun formatters_respectMetricAndImperialFlags() {
        val speedMps = FlightDynamicsCalculator.formatGroundSpeed(5.0f, isImperial = false)
        assertEquals("5.0 m/s", speedMps)

        val speedMph = FlightDynamicsCalculator.formatGroundSpeed(5.0f, isImperial = true)
        assertEquals("11 mph", speedMph)

        val climbMps = FlightDynamicsCalculator.formatVerticalSpeed(1.2f, isImperial = false)
        assertEquals("+1.2 m/s", climbMps)

        val descentMps = FlightDynamicsCalculator.formatVerticalSpeed(-1.2f, isImperial = false)
        assertEquals("-1.2 m/s", descentMps)

        val climbFpm = FlightDynamicsCalculator.formatVerticalSpeed(1.2f, isImperial = true)
        assertEquals("+236 ft/m", climbFpm)

        val descentFpm = FlightDynamicsCalculator.formatVerticalSpeed(-1.2f, isImperial = true)
        assertEquals("-236 ft/m", descentFpm)

        val distanceM = FlightDynamicsCalculator.formatDistance(125.0, isImperial = false)
        assertEquals("125 m", distanceM)

        val distanceFt = FlightDynamicsCalculator.formatDistance(125.0, isImperial = true)
        assertEquals("410 ft", distanceFt)

        val flightTime = FlightDynamicsCalculator.formatEstimatedFlightTime(18.4f)
        assertEquals("18m", flightTime)

        val powerWatts = FlightDynamicsCalculator.formatPowerWatts(142.5f)
        assertEquals("143W", powerWatts)
    }

    @Test
    fun climbRateAndCompassHeading_calculateCorrectly() {
        val climb = FlightDynamicsCalculator.calculateClimbRateMetersPerSecond(-1.5f)
        assertEquals(1.5f, climb, 1e-4f)

        val descent = FlightDynamicsCalculator.calculateClimbRateMetersPerSecond(1.5f)
        assertEquals(-1.5f, descent, 1e-4f)

        assertEquals("000°", FlightDynamicsCalculator.formatCompassHeading(0f))
        assertEquals("090°", FlightDynamicsCalculator.formatCompassHeading(90f))
        assertEquals("180°", FlightDynamicsCalculator.formatCompassHeading(180f))
        assertEquals("180°", FlightDynamicsCalculator.formatCompassHeading(-180f))
        assertEquals("270°", FlightDynamicsCalculator.formatCompassHeading(-90f))
        assertEquals("315°", FlightDynamicsCalculator.formatCompassHeading(-45f))
    }
}
