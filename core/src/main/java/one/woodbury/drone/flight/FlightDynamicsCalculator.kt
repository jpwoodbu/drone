package one.woodbury.drone.flight

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Common flight mechanics, navigation, and power calculations.
 */
object FlightDynamicsCalculator {

    /** Earth mean radius in meters for Haversine distance calculations. */
    private const val EARTH_RADIUS_METERS: Double = 6_371_000.0

    /** Conversion multiplier from meters per second to miles per hour. */
    const val METERS_PER_SECOND_TO_MPH: Float = 2.2369363f

    /** Conversion multiplier from meters per second to feet per minute. */
    const val METERS_PER_SECOND_TO_FEET_PER_MINUTE: Float = 196.8504f

    /** Conversion multiplier from meters to feet. */
    const val METERS_TO_FEET: Float = 3.28084f

    /**
     * Calculates 2D horizontal ground speed from orthogonal velocity components.
     *
     * @param velocityX Horizontal velocity along the X-axis in meters per second.
     * @param velocityY Horizontal velocity along the Y-axis in meters per second.
     * @return Resulting horizontal ground speed in meters per second.
     */
    fun calculateGroundSpeedMetersPerSecond(velocityX: Float, velocityY: Float): Float {
        return sqrt(velocityX * velocityX + velocityY * velocityY)
    }

    /**
     * Calculates great-circle ground distance between two geographic coordinates using the Haversine formula.
     *
     * @param startLatitude Latitude of the initial origin coordinate in degrees.
     * @param startLongitude Longitude of the initial origin coordinate in degrees.
     * @param endLatitude Latitude of the destination coordinate in degrees.
     * @param endLongitude Longitude of the destination coordinate in degrees.
     * @return Straight-line distance across the surface of the Earth in meters.
     */
    fun calculateHaversineDistanceMeters(
        startLatitude: Double,
        startLongitude: Double,
        endLatitude: Double,
        endLongitude: Double,
    ): Double {
        val deltaLatitudeRadians = Math.toRadians(endLatitude - startLatitude)
        val deltaLongitudeRadians = Math.toRadians(endLongitude - startLongitude)
        val startLatitudeRadians = Math.toRadians(startLatitude)
        val endLatitudeRadians = Math.toRadians(endLatitude)

        val sinHalfDeltaLatitude = sin(deltaLatitudeRadians / 2.0)
        val sinHalfDeltaLongitude = sin(deltaLongitudeRadians / 2.0)

        val haversineComponent = sinHalfDeltaLatitude * sinHalfDeltaLatitude +
            cos(startLatitudeRadians) * cos(endLatitudeRadians) *
            sinHalfDeltaLongitude * sinHalfDeltaLongitude

        val angularDistance = 2.0 * atan2(sqrt(haversineComponent), sqrt(1.0 - haversineComponent))
        return EARTH_RADIUS_METERS * angularDistance
    }

    /**
     * Calculates instantaneous electrical power consumed from voltage and current.
     *
     * @param packVoltageMillivolts Total battery pack potential in millivolts.
     * @param currentMilliamps Instantaneous current draw in milliamps (discharge is typically negative).
     * @return Consumed power in Watts, or null if voltage or current are missing.
     */
    fun calculatePowerWatts(packVoltageMillivolts: Int?, currentMilliamps: Int?): Float? {
        if (packVoltageMillivolts == null || currentMilliamps == null) return null
        val voltageVolts = packVoltageMillivolts / 1000f
        val currentAmperes = Math.abs(currentMilliamps) / 1000f
        return voltageVolts * currentAmperes
    }

    /**
     * Calculates estimated remaining flight time from available battery capacity and current draw.
     *
     * @param remainingCapacityMilliampHours Remaining battery charge in milliamp-hours.
     * @param currentMilliamps Current electrical draw in milliamps.
     * @return Estimated remaining operating time in minutes, or null if not discharging.
     */
    fun calculateEstimatedFlightTimeMinutes(
        remainingCapacityMilliampHours: Int?,
        currentMilliamps: Int?,
    ): Float? {
        if (remainingCapacityMilliampHours == null || currentMilliamps == null) return null
        val dischargeMilliamps = Math.abs(currentMilliamps)
        // Disregard idle/quiescent currents below 200 mA to prevent division artifacts when landed.
        if (dischargeMilliamps < 200) return null
        return (remainingCapacityMilliampHours.toFloat() / dischargeMilliamps.toFloat()) * 60f
    }

    /**
     * Calculates maximum cell voltage difference across all monitored battery cells.
     *
     * @param cellVoltagesMillivolts Monitored voltages for individual battery cells in millivolts.
     * @return Difference between the highest and lowest cell voltages in millivolts, or null if fewer than 2 cells.
     */
    fun calculateCellDeltaMillivolts(cellVoltagesMillivolts: List<Int>): Int? {
        if (cellVoltagesMillivolts.size < 2) return null
        val maximum = cellVoltagesMillivolts.maxOrNull() ?: return null
        val minimum = cellVoltagesMillivolts.minOrNull() ?: return null
        return maximum - minimum
    }

    /**
     * Formats horizontal ground speed.
     *
     * @param groundSpeedMetersPerSecond Speed in meters per second.
     * @param isImperial Whether to format in miles per hour (`mph`) instead of meters per second (`m/s`).
     */
    fun formatGroundSpeed(groundSpeedMetersPerSecond: Float, isImperial: Boolean): String {
        return if (isImperial) {
            val mph = groundSpeedMetersPerSecond * METERS_PER_SECOND_TO_MPH
            "%.0f mph".format(mph)
        } else {
            "%.1f m/s".format(groundSpeedMetersPerSecond)
        }
    }

    /**
     * Converts NED down velocity into climb rate in meters per second.
     * In the NED coordinate system, positive velocity Z is down (descent) and negative is up (climb).
     */
    fun calculateClimbRateMetersPerSecond(velocityZMetersPerSecond: Float): Float {
        return -velocityZMetersPerSecond
    }

    /**
     * Formats vertical climb rate without directional arrow prefixes.
     *
     * @param climbRateMetersPerSecond Climb rate in meters per second (positive for climb, negative for descent).
     * @param isImperial Whether to format in feet per minute (`ft/m`) instead of meters per second (`m/s`).
     */
    fun formatVerticalSpeed(climbRateMetersPerSecond: Float, isImperial: Boolean): String {
        return if (isImperial) {
            val feetPerMinute = climbRateMetersPerSecond * METERS_PER_SECOND_TO_FEET_PER_MINUTE
            val sign = if (feetPerMinute > 0.5f) "+" else ""
            "%s%.0f ft/m".format(sign, feetPerMinute)
        } else {
            val sign = if (climbRateMetersPerSecond > 0.05f) "+" else ""
            "%s%.1f m/s".format(sign, climbRateMetersPerSecond)
        }
    }

    /**
     * Normalizes signed Euler yaw degrees ([-180.0, 180.0]) into a standard True North compass heading in degrees ([0.0, 359.0]).
     */
    fun normalizeCompassHeadingDegrees(yawDegrees: Float): Float {
        val normalized = ((yawDegrees % 360f) + 360f) % 360f
        return if (normalized >= 360f) 0f else normalized
    }

    /**
     * Formats a True North compass heading into a standard 3-digit degree string (e.g. `000°` to `359°`).
     *
     * @param yawDegrees Euler yaw angle in degrees.
     */
    fun formatCompassHeading(yawDegrees: Float): String {
        val heading = normalizeCompassHeadingDegrees(yawDegrees)
        var integerDegrees = heading.roundToInt()
        if (integerDegrees >= 360) {
            integerDegrees = 0
        }
        return "%03d°".format(integerDegrees)
    }

    /**
     * Formats straight-line distance to home.
     *
     * @param distanceMeters Distance in meters.
     * @param isImperial Whether to format in feet (`ft`) instead of meters (`m`).
     */
    fun formatDistance(distanceMeters: Double, isImperial: Boolean): String {
        return if (isImperial) {
            val feet = distanceMeters * METERS_TO_FEET
            "%,.0f ft".format(feet)
        } else {
            "%,.0f m".format(distanceMeters)
        }
    }

    /**
     * Formats estimated flight time into integer minutes.
     *
     * @param flightTimeMinutes Estimated flight duration in minutes.
     */
    fun formatEstimatedFlightTime(flightTimeMinutes: Float?): String {
        if (flightTimeMinutes == null || flightTimeMinutes <= 0f) return "—"
        val totalMinutes = flightTimeMinutes.toInt()
        return "${totalMinutes}m"
    }

    /**
     * Formats instantaneous battery power in Watts.
     *
     * @param powerWatts Power in Watts.
     */
    fun formatPowerWatts(powerWatts: Float?): String {
        if (powerWatts == null || powerWatts < 0f) return "—"
        return "%.0fW".format(powerWatts)
    }
}
