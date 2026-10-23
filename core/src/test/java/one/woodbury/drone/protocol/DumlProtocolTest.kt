package one.woodbury.drone.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import one.woodbury.drone.net.UdpBridgeProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        payload[30] = 5          // P-GPS flight mode
        buffer.putInt(32, 0x0C)  // motorsRunning + inAir
        payload[36] = 14         // 14 satellites
        buffer.putShort(38, 14)  // 1.4 m ultrasonic height (14 decimeters)
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
        assertEquals("P-GPS", decoded.flightMode)
        assertTrue(decoded.isUltrasonicValid)
        assertEquals(1.4f, decoded.ultrasonicHeightMeters!!, 1e-3f)
    }

    @Test
    fun decodeRemoteControllerAxes_extractsSticksSwitchesAndButtons() {
        val payload = ByteArray(12)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0, 1024) // Right stick H
        buffer.putShort(2, 1100) // Right stick V
        buffer.putShort(4, 1024) // Left stick V
        buffer.putShort(6, 950)  // Left stick H
        buffer.putShort(8, 1024) // Gimbal wheel
        payload[10] = 2          // Sport mode switch
        payload[11] = 0x14       // RTH (0x10) + C1 (0x04)

        val frame = DumlFrame(
            sourceModuleId = DumlProtocol.MODULE_REMOTE_CONTROLLER,
            destinationModuleId = DumlProtocol.MODULE_MOBILE_APP,
            sequenceNumber = 77,
            commandType = 0x00,
            commandSet = DumlProtocol.MODULE_REMOTE_CONTROLLER,
            commandId = 0x05,
            payload = payload,
        )

        val decoded = DumlProtocol.decodeRemoteControllerAxes(frame)
        assertNotNull(decoded)
        assertEquals(1024, decoded!!.rightStickHorizontal)
        assertEquals(1100, decoded.rightStickVertical)
        assertEquals(1024, decoded.leftStickVertical)
        assertEquals(950, decoded.leftStickHorizontal)
        assertEquals(1024, decoded.gimbalWheel)
        assertEquals("SPORT", decoded.flightModeSwitch)
        assertTrue(decoded.rthButtonPressed)
        assertFalse(decoded.pauseButtonPressed)
        assertTrue(decoded.c1ButtonPressed)
        assertFalse(decoded.c2ButtonPressed)
    }

    @Test
    fun decodeBatteryTelemetry_extractsDynamicDataWithPrefix() {
        val payload = ByteArray(32)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0, 0x0100.toShort()) // 2-byte prefix
        buffer.putInt(2, 11400)              // 11.4 V (11400 mV)
        buffer.putInt(6, -1500)              // -1.5 A discharge
        buffer.putInt(10, 2450)              // 2450 mAh full capacity
        buffer.putInt(14, 2050)              // 2050 mAh remaining capacity
        buffer.putShort(18, 265)             // 26.5 °C
        payload[20] = 3                      // 3S battery
        payload[21] = 84                     // 84% SoC

        val frame = DumlFrame(
            sourceModuleId = DumlProtocol.MODULE_BATTERY,
            destinationModuleId = DumlProtocol.MODULE_MOBILE_APP,
            sequenceNumber = 42,
            commandType = 0x00,
            commandSet = DumlProtocol.COMMAND_SET_BATTERY,
            commandId = DumlProtocol.CMD_BATTERY_DYNAMIC_DATA,
            payload = payload,
        )

        val decoded = DumlProtocol.decodeBatteryTelemetry(frame)
        assertNotNull(decoded)
        assertEquals(84, decoded!!.percentage)
        assertEquals(11400, decoded.packVoltageMillivolts)
        assertEquals(-1500, decoded.currentMilliamps)
        assertEquals(2450, decoded.fullCapacityMilliampHours)
        assertEquals(2050, decoded.remainingCapacityMilliampHours)
        assertEquals(26.5f, decoded.temperatureCelsius!!, 0.01f)
        assertEquals(3, decoded.cellCount)
    }

    @Test
    fun decodeBatteryTelemetry_extractsDynamicDataWithoutPrefix() {
        val payload = ByteArray(24)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 11400)  // 11.4 V
        buffer.putInt(4, -1500)  // -1.5 A
        buffer.putInt(8, 2450)   // 2450 mAh
        buffer.putInt(12, 2050)  // 2050 mAh
        buffer.putShort(16, 280) // 28.0 °C
        payload[18] = 3          // 3S
        payload[19] = 84         // 84%

        val frame = DumlFrame(
            sourceModuleId = DumlProtocol.MODULE_BATTERY,
            destinationModuleId = DumlProtocol.MODULE_MOBILE_APP,
            sequenceNumber = 43,
            commandType = 0x00,
            commandSet = DumlProtocol.COMMAND_SET_BATTERY,
            commandId = DumlProtocol.CMD_BATTERY_DYNAMIC_DATA,
            payload = payload,
        )

        val decoded = DumlProtocol.decodeBatteryTelemetry(frame)
        assertNotNull(decoded)
        assertEquals(84, decoded!!.percentage)
        assertEquals(11400, decoded.packVoltageMillivolts)
        assertEquals(28.0f, decoded.temperatureCelsius!!, 0.01f)
    }

    @Test
    fun decodeBatteryTelemetry_calculatesFallbackPercentageFromCapacity() {
        val payload = ByteArray(32)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0, 0)
        buffer.putInt(2, 11200)
        buffer.putInt(6, -1000)
        buffer.putInt(10, 2000) // 2000 mAh full
        buffer.putInt(14, 1500) // 1500 mAh remaining -> 75%
        buffer.putShort(18, 250)
        payload[20] = 3
        payload[21] = 0         // Reported SoC is 0

        val frame = DumlFrame(
            sourceModuleId = DumlProtocol.MODULE_BATTERY,
            destinationModuleId = DumlProtocol.MODULE_MOBILE_APP,
            sequenceNumber = 44,
            commandType = 0x00,
            commandSet = DumlProtocol.COMMAND_SET_BATTERY,
            commandId = DumlProtocol.CMD_BATTERY_DYNAMIC_DATA,
            payload = payload,
        )

        val decoded = DumlProtocol.decodeBatteryTelemetry(frame)
        assertNotNull(decoded)
        assertEquals(75, decoded!!.percentage)
    }

    @Test
    fun decodeBatteryTelemetry_extractsCellVoltages_setsPercentageNull() {
        val payload = ByteArray(9)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0, 0)      // Prefix
        payload[2] = 3             // 3 cells
        buffer.putShort(3, 3850)   // Cell 1: 3850 mV
        buffer.putShort(5, 3860)   // Cell 2: 3860 mV
        buffer.putShort(7, 3840)   // Cell 3: 3840 mV

        val frame = DumlFrame(
            sourceModuleId = DumlProtocol.MODULE_BATTERY,
            destinationModuleId = DumlProtocol.MODULE_MOBILE_APP,
            sequenceNumber = 45,
            commandType = 0x00,
            commandSet = DumlProtocol.COMMAND_SET_BATTERY,
            commandId = DumlProtocol.CMD_BATTERY_CELL_VOLTAGE,
            payload = payload,
        )

        val decoded = DumlProtocol.decodeBatteryTelemetry(frame)
        assertNotNull(decoded)
        assertEquals(3, decoded!!.cellCount)
        assertEquals(11550, decoded.packVoltageMillivolts)
        assertEquals(listOf(3850, 3860, 3840), decoded.cellVoltagesMillivolts)
        assertNull(decoded.percentage)
    }

    @Test
    fun decodeBatteryTelemetry_extractsStandard16BitCenterBattery() {
        val payload = ByteArray(24)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        payload[0] = 45                               // 45% State of Charge (2 LEDs)
        buffer.putShort(1, 11400.toShort())           // 11.4 V pack voltage
        buffer.putShort(3, 1720.toShort())            // 1720 mAh remaining
        buffer.putShort(5, 3830.toShort())            // 3830 mAh full capacity
        payload[7] = 98.toByte()                      // Life
        buffer.putShort(8, 25.toShort())              // 25 cycles
        buffer.putInt(10, 0)                          // No errors
        buffer.putShort(14, (-1200).toShort())        // -1.2 A current draw
        buffer.putShort(16, 3800.toShort())           // Cell 1: 3800 mV
        buffer.putShort(18, 3805.toShort())           // Cell 2: 3805 mV
        buffer.putShort(20, 3795.toShort())           // Cell 3: 3795 mV

        val frame = DumlFrame(
            sourceModuleId = DumlProtocol.MODULE_CENTER_BOARD,
            destinationModuleId = DumlProtocol.MODULE_MOBILE_APP,
            sequenceNumber = 46,
            commandType = 0x00,
            commandSet = 0x00,
            commandId = DumlProtocol.CMD_BATTERY_COMMON,
            payload = payload,
        )

        val decoded = DumlProtocol.decodeBatteryTelemetry(frame)
        assertNotNull(decoded)
        assertEquals(45, decoded!!.percentage)
        assertEquals(11400, decoded.packVoltageMillivolts)
        assertEquals(1720, decoded.remainingCapacityMilliampHours)
        assertEquals(3830, decoded.fullCapacityMilliampHours)
        assertEquals(-1200, decoded.currentMilliamps)
        assertEquals(3, decoded.cellCount)
        assertEquals(listOf(3800, 3805, 3795), decoded.cellVoltagesMillivolts)
    }

    @Test
    fun batteryTelemetry_mergeWith_preservesBmsPercentageAcrossCellVoltageUpdates() {
        val bmsTelemetry = BatteryTelemetry(
            percentage = 45,
            packVoltageMillivolts = 11400,
            currentMilliamps = -1200,
            remainingCapacityMilliampHours = 1720,
            fullCapacityMilliampHours = 3830,
        )
        val cellVoltageTelemetry = BatteryTelemetry(
            percentage = null,
            packVoltageMillivolts = 11350,
            cellCount = 3,
            cellVoltagesMillivolts = listOf(3780, 3785, 3785),
        )

        val merged = bmsTelemetry.mergeWith(cellVoltageTelemetry)
        assertEquals(45, merged.percentage)
        assertEquals(11350, merged.packVoltageMillivolts)
        assertEquals(-1200, merged.currentMilliamps)
        assertEquals(1720, merged.remainingCapacityMilliampHours)
        assertEquals(3830, merged.fullCapacityMilliampHours)
        assertEquals(3, merged.cellCount)
        assertEquals(listOf(3780, 3785, 3785), merged.cellVoltagesMillivolts)
    }

    @Test
    fun buildBatteryDynamicDataQuery_createsValidAoaWrappedDumlPacket() {
        val bytes = DumlProtocol.buildBatteryDynamicDataQuery(sequenceNumber = 100)
        assertEquals(0x55.toByte(), bytes[0])
        assertEquals(0xCC.toByte(), bytes[1])
        assertEquals(0x49.toByte(), bytes[2])
        assertEquals(0x57.toByte(), bytes[3])

        val outcome = DumlProtocol.tryParseDumlFrame(
            buffer = bytes,
            offset = 8,
            availableLength = bytes.size - 8,
        )
        assertTrue(outcome is DumlParseOutcome.Parsed)
        val frame = (outcome as DumlParseOutcome.Parsed).frame
        assertEquals(DumlProtocol.MODULE_MOBILE_APP, frame.sourceModuleId)
        assertEquals(DumlProtocol.MODULE_BATTERY, frame.destinationModuleId)
        assertEquals(DumlProtocol.COMMAND_SET_BATTERY, frame.commandSet)
        assertEquals(DumlProtocol.CMD_BATTERY_DYNAMIC_DATA, frame.commandId)
        assertEquals(100, frame.sequenceNumber)
        assertArrayEquals(byteArrayOf(), frame.payload)
    }

    @Test
    fun decodeGimbalTelemetry_extractsPitchRollYawAndMode() {
        val payload = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort((-450).toShort()) // -45.0 degrees pitch
            putShort(12.toShort())     // +1.2 degrees roll
            putShort((-50).toShort())  // -5.0 degrees yaw
            put(0x02.toByte())         // mode 2
        }.array()

        val frame = DumlFrame(
            sourceModuleId = DumlProtocol.MODULE_GIMBAL,
            destinationModuleId = DumlProtocol.MODULE_MOBILE_APP,
            sequenceNumber = 42,
            commandType = 0x00,
            commandSet = DumlProtocol.MODULE_GIMBAL,
            commandId = DumlProtocol.CMD_GIMBAL_PUSH_STATUS,
            payload = payload,
        )

        val telemetry = DumlProtocol.decodeGimbalTelemetry(frame)
        assertNotNull(telemetry)
        assertEquals(-45.0f, telemetry!!.pitchDegrees, 0.01f)
        assertEquals(1.2f, telemetry.rollDegrees, 0.01f)
        assertEquals(-5.0f, telemetry.yawDegrees, 0.01f)
        assertEquals(2, telemetry.mode)
    }

    @Test
    fun decodeGimbalTelemetry_handlesLevelHorizonAndNadir() {
        val levelPayload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0.toShort())
            putShort(0.toShort())
            putShort(0.toShort())
        }.array()

        val levelFrame = DumlFrame(
            sourceModuleId = DumlProtocol.MODULE_GIMBAL,
            destinationModuleId = DumlProtocol.MODULE_MOBILE_APP,
            sequenceNumber = 43,
            commandType = 0x00,
            commandSet = DumlProtocol.MODULE_GIMBAL,
            commandId = DumlProtocol.CMD_GIMBAL_PUSH_STATUS,
            payload = levelPayload,
        )
        val levelTelemetry = DumlProtocol.decodeGimbalTelemetry(levelFrame)
        assertNotNull(levelTelemetry)
        assertEquals(0.0f, levelTelemetry!!.pitchDegrees, 0.01f)
        assertNull(levelTelemetry.mode)

        val nadirPayload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort((-900).toShort()) // -90.0 degrees (straight down)
            putShort(0.toShort())
            putShort(0.toShort())
        }.array()

        val nadirFrame = DumlFrame(
            sourceModuleId = DumlProtocol.MODULE_GIMBAL,
            destinationModuleId = DumlProtocol.MODULE_MOBILE_APP,
            sequenceNumber = 44,
            commandType = 0x00,
            commandSet = DumlProtocol.MODULE_GIMBAL,
            commandId = DumlProtocol.CMD_GIMBAL_PUSH_STATUS,
            payload = nadirPayload,
        )
        val nadirTelemetry = DumlProtocol.decodeGimbalTelemetry(nadirFrame)
        assertNotNull(nadirTelemetry)
        assertEquals(-90.0f, nadirTelemetry!!.pitchDegrees, 0.01f)
    }

    @Test
    fun decodeGimbalTelemetry_returnsNullForNonGimbalFramesOrCorruptPayloads() {
        val validPayload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort((-300).toShort())
            putShort(0.toShort())
            putShort(0.toShort())
        }.array()

        val nonGimbalFrame = DumlFrame(
            sourceModuleId = DumlProtocol.MODULE_FLIGHT_CONTROLLER,
            destinationModuleId = DumlProtocol.MODULE_MOBILE_APP,
            sequenceNumber = 45,
            commandType = 0x00,
            commandSet = DumlProtocol.MODULE_FLIGHT_CONTROLLER,
            commandId = 0x43,
            payload = validPayload,
        )
        assertNull(DumlProtocol.decodeGimbalTelemetry(nonGimbalFrame))

        val shortPayloadFrame = DumlFrame(
            sourceModuleId = DumlProtocol.MODULE_GIMBAL,
            destinationModuleId = DumlProtocol.MODULE_MOBILE_APP,
            sequenceNumber = 46,
            commandType = 0x00,
            commandSet = DumlProtocol.MODULE_GIMBAL,
            commandId = DumlProtocol.CMD_GIMBAL_PUSH_STATUS,
            payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05),
        )
        assertNull(DumlProtocol.decodeGimbalTelemetry(shortPayloadFrame))

        val outOfRangePayload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort((-2000).toShort()) // -200.0 degrees (out of plausible range)
            putShort(0.toShort())
            putShort(0.toShort())
        }.array()

        val outOfRangeFrame = DumlFrame(
            sourceModuleId = DumlProtocol.MODULE_GIMBAL,
            destinationModuleId = DumlProtocol.MODULE_MOBILE_APP,
            sequenceNumber = 47,
            commandType = 0x00,
            commandSet = DumlProtocol.MODULE_GIMBAL,
            commandId = DumlProtocol.CMD_GIMBAL_PUSH_STATUS,
            payload = outOfRangePayload,
        )
        assertNull(DumlProtocol.decodeGimbalTelemetry(outOfRangeFrame))
    }

    @Test
    fun h264AnnexBStreamExtractor_stripsDjiSeiAndGroupsMultiSliceAccessUnits() {
        val emittedAccessUnits = mutableListOf<Pair<ByteArray, Boolean>>()
        val extractor = one.woodbury.drone.video.H264AnnexBStreamExtractor { bytes, isKeyframe ->
            emittedAccessUnits.add(bytes to isKeyframe)
        }

        val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        val sps = startCode + byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = startCode + byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        // DJI SEI (nal_unit_type = 6, payloadType = 0xF0, payloadSize = 6) containing an
        // unescaped 00 00 00 01 sequence inside its payload, plus trailing 0x80.
        val djiSeiWithUnescapedStartCode = startCode + byteArrayOf(
            0x06,
            0xF0.toByte(),
            0x06,
            0x00, 0x00, 0x00, 0x01, 0x65, 0xAA.toByte(),
            0x80.toByte(),
        )
        // Frame 1 (IDR): two slices (first slice has first_mb_in_slice = 0 -> MSB 1: 0x88;
        // second slice has first_mb_in_slice > 0 -> MSB 0: 0x42).
        val idrSlice1 = startCode + byteArrayOf(0x65, 0x88.toByte(), 0x11, 0x22)
        val idrSlice2 = startCode + byteArrayOf(0x65, 0x42, 0x33, 0x44)
        // Frame 2 (Non-IDR P-frame): first_mb_in_slice = 0 -> MSB 1: 0x9A, followed by AUD.
        val pSlice1 = startCode + byteArrayOf(0x41, 0x9A.toByte(), 0x55, 0x66)
        val trailingAud = startCode + byteArrayOf(0x09, 0x10)

        val stream = sps + pps + djiSeiWithUnescapedStartCode + idrSlice1 + idrSlice2 + pSlice1 + trailingAud

        var offset = 0
        while (offset < stream.size) {
            val step = minOf(7, stream.size - offset)
            extractor.feedChunk(stream.copyOfRange(offset, offset + step))
            offset += step
        }

        assertEquals(2, emittedAccessUnits.size)
        val (firstFrameBytes, firstIsKeyframe) = emittedAccessUnits[0]
        assertTrue(firstIsKeyframe)
        assertArrayEquals(sps + pps + idrSlice1 + idrSlice2, firstFrameBytes)

        val (secondFrameBytes, secondIsKeyframe) = emittedAccessUnits[1]
        assertEquals(false, secondIsKeyframe)
        assertArrayEquals(pSlice1, secondFrameBytes)
    }

    @Test
    fun udpBridgeProtocol_packetizesAndReassemblesLargeVideoChunk() {
        val sequenceCounter = AtomicInteger(100)
        val originalVideoChunk = ByteArray(3_200) { index -> (index and 0xFF).toByte() }
        val datagrams = UdpBridgeProtocol.packetizePayload(
            channelType = UdpBridgeProtocol.CHANNEL_VIDEO,
            payload = originalVideoChunk,
            nextSequenceNumber = { sequenceCounter.getAndIncrement() },
        )

        assertEquals(3, datagrams.size)
        assertTrue(datagrams.all { it.size <= 1400 })

        val reassembled = datagrams.map { datagram ->
            val parsed = UdpBridgeProtocol.tryParseDataPacket(datagram, datagram.size)
            assertNotNull(parsed)
            assertEquals(UdpBridgeProtocol.CHANNEL_VIDEO, parsed!!.channelType)
            parsed.payload
        }.reduce { accumulator, bytes -> accumulator + bytes }

        assertArrayEquals(originalVideoChunk, reassembled)
    }

    @Test
    fun udpBridgeProtocol_identifiesHeartbeatAndAnnounceAndComputesBroadcast() {
        val heartbeat = UdpBridgeProtocol.buildHeartbeatPacket()
        assertEquals(4, heartbeat.size)
        assertTrue(UdpBridgeProtocol.isHeartbeatPacket(heartbeat, heartbeat.size))

        val announce = UdpBridgeProtocol.buildBridgeAnnouncePacket()
        assertEquals(4, announce.size)
        assertTrue(UdpBridgeProtocol.isBridgeAnnouncePacket(announce, announce.size))

        val broadcast24 = UdpBridgeProtocol.computeBroadcastIpv4(
            ipv4Bytes = byteArrayOf(192.toByte(), 168.toByte(), 1, 42),
            prefixLength = 24,
        )
        assertEquals("192.168.1.255", broadcast24)
    }
}
