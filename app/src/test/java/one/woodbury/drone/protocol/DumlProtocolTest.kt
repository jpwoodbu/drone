package one.woodbury.drone.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DumlProtocolTest {

    @Test
    fun buildAndParseDumlFrame_roundTripsFieldsAndChecksums() {
        val payload = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val rawBytes = DumlProtocol.buildDumlFrame(
            sourceModuleId = DumlProtocol.MODULE_MOBILE_APP,
            destinationModuleId = DumlProtocol.MODULE_REMOTE_CONTROLLER,
            sequenceNumber = 0x1234,
            commandType = DumlProtocol.COMMAND_TYPE_REQUEST_WITH_ACK,
            commandSet = 0x06,
            commandId = 0x05,
            payload = payload,
        )

        val outcome = DumlProtocol.tryParseDumlFrame(
            buffer = rawBytes,
            offset = 0,
            availableLength = rawBytes.size,
        )
        assertTrue(outcome is DumlParseOutcome.Parsed)
        val parsed = outcome as DumlParseOutcome.Parsed
        assertEquals(rawBytes.size, parsed.bytesConsumed)
        assertEquals(DumlProtocol.MODULE_MOBILE_APP, parsed.frame.sourceModuleId)
        assertEquals(DumlProtocol.MODULE_REMOTE_CONTROLLER, parsed.frame.destinationModuleId)
        assertEquals(0x1234, parsed.frame.sequenceNumber)
        assertEquals(DumlProtocol.COMMAND_TYPE_REQUEST_WITH_ACK, parsed.frame.commandType)
        assertEquals(0x06, parsed.frame.commandSet)
        assertEquals(0x05, parsed.frame.commandId)
        assertArrayEquals(payload, parsed.frame.payload)
    }

    @Test
    fun aoaStreamParser_demultiplexesDumlAndVideoAcrossFragmentedReads() {
        val parsedFrames = mutableListOf<DumlFrame>()
        val videoChunks = mutableListOf<ByteArray>()
        val parser = AoaStreamParser(
            onDumlFrame = { parsedFrames.add(it) },
            onVideoChunk = { videoChunks.add(it) },
        )

        val dumlFrameBytes = DumlProtocol.buildDumlFrame(
            sourceModuleId = DumlProtocol.MODULE_REMOTE_CONTROLLER,
            destinationModuleId = DumlProtocol.MODULE_MOBILE_APP,
            sequenceNumber = 7,
            commandType = DumlProtocol.COMMAND_TYPE_RESPONSE_ACK,
            commandSet = 0x00,
            commandId = 0x01,
            payload = "S01A_FW_01.00.0400".encodeToByteArray(),
        )
        val dumlEnvelope = DumlProtocol.wrapInAoaEnvelope(
            innerPayload = dumlFrameBytes,
            channelPort = DumlProtocol.AOA_CHANNEL_DUML,
        )
        val expectedVideoPayload = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E)
        val videoEnvelope = DumlProtocol.wrapInAoaEnvelope(
            innerPayload = expectedVideoPayload,
            channelPort = DumlProtocol.AOA_CHANNEL_VIDEO,
        )

        val combinedStream = dumlEnvelope + videoEnvelope

        // Feed the stream in small 5-byte fragments to verify stateful reassembly.
        var offset = 0
        while (offset < combinedStream.size) {
            val step = minOf(5, combinedStream.size - offset)
            val slice = combinedStream.copyOfRange(offset, offset + step)
            parser.feedBytes(slice, slice.size)
            offset += step
        }

        assertEquals(1L, parser.aoaDumlEnvelopesReceived)
        assertEquals(1L, parser.aoaVideoEnvelopesReceived)
        assertEquals(1, parsedFrames.size)
        assertEquals(1, videoChunks.size)
        assertArrayEquals(expectedVideoPayload, videoChunks.first())
        assertEquals(
            "S01A_FW_01.00.0400",
            DumlProtocol.decodeDeviceVersionString(parsedFrames.first()),
        )
    }

    @Test
    fun decodeFlightControllerOsdTelemetry_extractsExpectedValues() {
        val payload = ByteArray(50)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val expectedLonDeg = -122.4194
        val expectedLatDeg = 37.7749
        buffer.putDouble(0, expectedLonDeg * PI / 180.0)
        buffer.putDouble(8, expectedLatDeg * PI / 180.0)
        buffer.putShort(16, 125) // 12.5 m
        buffer.putShort(24, 15)  // 1.5 deg pitch
        buffer.putShort(26, -20) // -2.0 deg roll
        buffer.putShort(28, 900) // 90.0 deg yaw
        buffer.putInt(32, 0x0C)  // motorsRunning + inAir
        payload[36] = 14         // 14 satellites
        payload[40] = 84         // 84% battery

        val frame = DumlFrame(
            sourceModuleId = DumlProtocol.MODULE_FLIGHT_CONTROLLER,
            destinationModuleId = DumlProtocol.MODULE_MOBILE_APP,
            sequenceNumber = 99,
            commandType = 0x00,
            commandSet = DumlProtocol.MODULE_FLIGHT_CONTROLLER,
            commandId = 0x43,
            payload = payload,
        )

        val decoded = DumlProtocol.decodeFlightControllerOsdTelemetry(frame)
        assertNotNull(decoded)
        assertEquals(expectedLatDeg, decoded!!.latitudeDegrees, 1e-4)
        assertEquals(expectedLonDeg, decoded.longitudeDegrees, 1e-4)
        assertEquals(12.5f, decoded.relativeAltitudeMeters, 1e-3f)
        assertEquals(1.5f, decoded.pitchDegrees, 1e-3f)
        assertEquals(-2.0f, decoded.rollDegrees, 1e-3f)
        assertEquals(90.0f, decoded.yawDegrees, 1e-3f)
        assertEquals(14, decoded.gpsSatellites)
        assertEquals(84, decoded.batteryPercentage)
        assertTrue(decoded.motorsRunning)
        assertTrue(decoded.aircraftInAir)
    }
}
