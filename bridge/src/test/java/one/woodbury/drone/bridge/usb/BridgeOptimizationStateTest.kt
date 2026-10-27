package one.woodbury.drone.bridge.usb

import one.woodbury.drone.protocol.FlightControllerTelemetry
import one.woodbury.drone.protocol.RemoteControllerAxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying state representation and equality semantics used for CPU optimization.
 */
class BridgeOptimizationStateTest {

    @Test
    fun defaultMainScreenState_hasExpectedDefaults() {
        val state = BridgeMainScreenState()
        assertEquals(ConnectionPhase.DISCONNECTED, state.connectionPhase)
        assertNull(state.accessoryDescriptor)
        assertFalse(state.hasVideoEnvelopes)
    }

    @Test
    fun defaultStreamDebugState_hasExpectedDefaults() {
        val state = StreamDebugState()
        assertEquals(0L, state.totalBytesReceived)
        assertEquals(0L, state.totalBytesSent)
        assertEquals(0L, state.dumlFramesReceived)
        assertEquals(0L, state.aoaDumlEnvelopesReceived)
        assertEquals(0L, state.aoaVideoEnvelopesReceived)
        assertEquals(0L, state.videoBytesReceived)
        assertTrue(state.observedModules.isEmpty())
        assertTrue(state.moduleVersions.isEmpty())
        assertNull(state.lastCommandInteraction)
        assertNull(state.lastDumlPacket)
        assertNull(state.lastRawHexPreview)
    }

    @Test
    fun telemetryState_detectsAxisChangesDeterministically() {
        val initialAxes = RemoteControllerAxes(
            leftStickHorizontal = 1024,
            leftStickVertical = 1024,
            rightStickHorizontal = 1024,
            rightStickVertical = 1024,
            gimbalWheel = 0,
            isFunctionButtonPressed = false,
            isRecordButtonPressed = false,
            isShutterButtonPressed = false,
        )
        val initialTelemetry = TelemetryState(remoteControllerAxes = initialAxes)

        val identicalAxes = RemoteControllerAxes(
            leftStickHorizontal = 1024,
            leftStickVertical = 1024,
            rightStickHorizontal = 1024,
            rightStickVertical = 1024,
            gimbalWheel = 0,
            isFunctionButtonPressed = false,
            isRecordButtonPressed = false,
            isShutterButtonPressed = false,
        )
        val unchangedTelemetry = initialTelemetry.copy(remoteControllerAxes = identicalAxes)
        assertEquals(initialTelemetry, unchangedTelemetry)

        val movedAxes = initialAxes.copy(leftStickHorizontal = 1150)
        val changedTelemetry = initialTelemetry.copy(remoteControllerAxes = movedAxes)
        assertNotEquals(initialTelemetry, changedTelemetry)
    }

    @Test
    fun telemetryState_detectsFlightControllerTelemetryChanges() {
        val initialTelemetry = TelemetryState()
        val flightTelemetry = FlightControllerTelemetry(
            flightMode = "GPS",
            batteryPercentage = 85,
            gpsSatellites = 14,
            latitudeDegrees = 37.7749,
            longitudeDegrees = -122.4194,
            relativeAltitudeMeters = 15.5f,
            ultrasonicHeightMeters = null,
            isUltrasonicValid = false,
            velocityXMetersPerSecond = 0.0f,
            velocityYMetersPerSecond = 0.0f,
            velocityZMetersPerSecond = 0.0f,
            pitchDegrees = 0.0f,
            rollDegrees = 0.0f,
            yawDegrees = 90.0f,
            motorsRunning = true,
            aircraftInAir = true,
            isRthActive = false,
            isAutoLanding = false,
            isCompassError = false,
            isSportMode = false,
        )
        val updatedTelemetry = initialTelemetry.copy(flightControllerTelemetry = flightTelemetry)
        assertNotEquals(initialTelemetry, updatedTelemetry)
    }
}
