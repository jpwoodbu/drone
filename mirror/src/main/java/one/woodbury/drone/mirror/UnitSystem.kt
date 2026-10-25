package one.woodbury.drone.mirror

/**
 * Supported measurement unit systems for flight telemetry display.
 */
enum class UnitSystem {
    METRIC,
    IMPERIAL;

    val isImperial: Boolean
        get() = this == IMPERIAL
}
