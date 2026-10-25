package one.woodbury.drone.mirror

/**
 * Utility for converting, formatting, and evaluating altitude measurements.
 */
object AltitudeFormatter {

    /** Number of meters per international foot (exact). */
    const val METERS_PER_FOOT: Float = 0.3048f

    /** Multiplier to convert meters into international feet. */
    const val FEET_PER_METER: Float = 1f / 0.3048f

    /** Legal flight ceiling warning threshold in meters (400 feet AGL). */
    const val WARNING_THRESHOLD_METERS: Float = 400f * METERS_PER_FOOT

    /**
     * Formats [altitudeMeters] according to the selected [unitSystem].
     *
     * Meters are formatted with 1 decimal place (e.g. `12.5 m`), while feet are formatted
     * as integers (e.g. `400 ft`).
     */
    fun formatAltitude(altitudeMeters: Float, unitSystem: UnitSystem): String {
        return when (unitSystem) {
            UnitSystem.METRIC -> "%.1f m".format(altitudeMeters)
            UnitSystem.IMPERIAL -> "%.0f ft".format(altitudeMeters * FEET_PER_METER)
        }
    }

    /**
     * Returns true if [altitudeMeters] is at or above the 400-foot ceiling threshold (121.92 meters).
     */
    fun isHighAltitudeWarning(altitudeMeters: Float): Boolean {
        return altitudeMeters >= WARNING_THRESHOLD_METERS
    }
}
