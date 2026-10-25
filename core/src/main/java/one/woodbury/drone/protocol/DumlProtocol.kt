package one.woodbury.drone.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import one.woodbury.drone.flight.FlightDynamicsCalculator

/**
 * Implements DJI's Android Open Accessory (AOA) multiplex framing (`0x55 0xCC`)
 * and the binary DUML v1 (DJI Universal Markup Language) packet protocol.
 */
object DumlProtocol {

    /** Start-of-frame magic byte for DUML v1 frames and AOA outer envelopes. */
    const val MAGIC_BYTE: Int = 0x55

    /** Second byte of the 8-byte AOA multiplex envelope header (`0x55 0xCC`). */
    const val AOA_ENVELOPE_SECOND_BYTE: Int = 0xCC

    /** AOA multiplex logical port for DUML command and telemetry packets (`22345` / `0x5749` LE). */
    const val AOA_CHANNEL_DUML: Int = 0x5749

    /** AOA multiplex logical port for H.264 video stream packets (`22346` / `0x574A` LE). */
    const val AOA_CHANNEL_VIDEO: Int = 0x574A

    /** DUML module IDs on the internal bus. */
    const val MODULE_CAMERA: Int = 0x01
    const val MODULE_MOBILE_APP: Int = 0x02
    const val MODULE_FLIGHT_CONTROLLER: Int = 0x03
    const val MODULE_GIMBAL: Int = 0x04
    const val MODULE_REMOTE_CONTROLLER: Int = 0x06
    const val MODULE_VIDEO_TRANSMISSION: Int = 0x09
    const val MODULE_BATTERY: Int = 0x0B
    const val MODULE_ESC: Int = 0x0C
    const val MODULE_CENTER_BOARD: Int = 0x05

    /** DUML command sets for subsystems that differ from their module ID. */
    const val COMMAND_SET_BATTERY: Int = 0x0D

    /** DUML command IDs for battery management. */
    const val CMD_BATTERY_DYNAMIC_DATA: Int = 0x02
    const val CMD_BATTERY_CELL_VOLTAGE: Int = 0x03
    const val CMD_BATTERY_COMMON: Int = 0x06

    /** DUML command IDs for camera gimbal management. */
    const val CMD_GIMBAL_CONTROL: Int = 0x01
    const val CMD_GIMBAL_PUSH_STATUS: Int = 0x05

    /** DUML command types. */
    const val COMMAND_TYPE_REQUEST_WITH_ACK: Int = 0x40
    const val COMMAND_TYPE_RESPONSE_ACK: Int = 0x80

    /** Lookup table for the DUML 8-bit header CRC (seed `0x77`). */
    private val CRC8_LOOKUP_TABLE = intArrayOf(
        0x00, 0x5E, 0xBC, 0xE2, 0x61, 0x3F, 0xDD, 0x83, 0xC2, 0x9C, 0x7E, 0x20, 0xA3, 0xFD, 0x1F, 0x41,
        0x9D, 0xC3, 0x21, 0x7F, 0xFC, 0xA2, 0x40, 0x1E, 0x5F, 0x01, 0xE3, 0xBD, 0x3E, 0x60, 0x82, 0xDC,
        0x23, 0x7D, 0x9F, 0xC1, 0x42, 0x1C, 0xFE, 0xA0, 0xE1, 0xBF, 0x5D, 0x03, 0x80, 0xDE, 0x3C, 0x62,
        0xBE, 0xE0, 0x02, 0x5C, 0xDF, 0x81, 0x63, 0x3D, 0x7C, 0x22, 0xC0, 0x9E, 0x1D, 0x43, 0xA1, 0xFF,
        0x46, 0x18, 0xFA, 0xA4, 0x27, 0x79, 0x9B, 0xC5, 0x84, 0xDA, 0x38, 0x66, 0xE5, 0xBB, 0x59, 0x07,
        0xDB, 0x85, 0x67, 0x39, 0xBA, 0xE4, 0x06, 0x58, 0x19, 0x47, 0xA5, 0xFB, 0x78, 0x26, 0xC4, 0x9A,
        0x65, 0x3B, 0xD9, 0x87, 0x04, 0x5A, 0xB8, 0xE6, 0xA7, 0xF9, 0x1B, 0x45, 0xC6, 0x98, 0x7A, 0x24,
        0xF8, 0xA6, 0x44, 0x1A, 0x99, 0xC7, 0x25, 0x7B, 0x3A, 0x64, 0x86, 0xD8, 0x5B, 0x05, 0xE7, 0xB9,
        0x8C, 0xD2, 0x30, 0x6E, 0xED, 0xB3, 0x51, 0x0F, 0x4E, 0x10, 0xF2, 0xAC, 0x2F, 0x71, 0x93, 0xCD,
        0x11, 0x4F, 0xAD, 0xF3, 0x70, 0x2E, 0xCC, 0x92, 0xD3, 0x8D, 0x6F, 0x31, 0xB2, 0xEC, 0x0E, 0x50,
        0xAF, 0xF1, 0x13, 0x4D, 0xCE, 0x90, 0x72, 0x2C, 0x6D, 0x33, 0xD1, 0x8F, 0x0C, 0x52, 0xB0, 0xEE,
        0x32, 0x6C, 0x8E, 0xD0, 0x53, 0x0D, 0xEF, 0xB1, 0xF0, 0xAE, 0x4C, 0x12, 0x91, 0xCF, 0x2D, 0x73,
        0xCA, 0x94, 0x76, 0x28, 0xAB, 0xF5, 0x17, 0x49, 0x08, 0x56, 0xB4, 0xEA, 0x69, 0x37, 0xD5, 0x8B,
        0x57, 0x09, 0xEB, 0xB5, 0x36, 0x68, 0x8A, 0xD4, 0x95, 0xCB, 0x29, 0x77, 0xF4, 0xAA, 0x48, 0x16,
        0xE9, 0xB7, 0x55, 0x0B, 0x88, 0xD6, 0x34, 0x6A, 0x2B, 0x75, 0x97, 0xC9, 0x4A, 0x14, 0xF6, 0xA8,
        0x74, 0x2A, 0xC8, 0x96, 0x15, 0x4B, 0xA9, 0xF7, 0xB6, 0xE8, 0x0A, 0x54, 0xD7, 0x89, 0x6B, 0x35,
    )

    /** Lookup table for the DUML 16-bit full-frame CRC (seed `0x3692`). */
    private val CRC16_LOOKUP_TABLE = intArrayOf(
        0x0000, 0x1189, 0x2312, 0x329B, 0x4624, 0x57AD, 0x6536, 0x74BF,
        0x8C48, 0x9DC1, 0xAF5A, 0xBED3, 0xCA6C, 0xDBE5, 0xE97E, 0xF8F7,
        0x1081, 0x0108, 0x3393, 0x221A, 0x56A5, 0x472C, 0x75B7, 0x643E,
        0x9CC9, 0x8D40, 0xBFDB, 0xAE52, 0xDAED, 0xCB64, 0xF9FF, 0xE876,
        0x2102, 0x308B, 0x0210, 0x1399, 0x6726, 0x76AF, 0x4434, 0x55BD,
        0xAD4A, 0xBCC3, 0x8E58, 0x9FD1, 0xEB6E, 0xFAE7, 0xC87C, 0xD9F5,
        0x3183, 0x200A, 0x1291, 0x0318, 0x77A7, 0x662E, 0x54B5, 0x453C,
        0xBDCB, 0xAC42, 0x9ED9, 0x8F50, 0xFBEF, 0xEA66, 0xD8FD, 0xC974,
        0x4204, 0x538D, 0x6116, 0x709F, 0x0420, 0x15A9, 0x2732, 0x36BB,
        0xCE4C, 0xDFC5, 0xED5E, 0xFCD7, 0x8868, 0x99E1, 0xAB7A, 0xBAF3,
        0x5285, 0x430C, 0x7197, 0x601E, 0x14A1, 0x0528, 0x37B3, 0x263A,
        0xDECD, 0xCF44, 0xFDDF, 0xEC56, 0x98E9, 0x8960, 0xBBFB, 0xAA72,
        0x6306, 0x728F, 0x4014, 0x519D, 0x2522, 0x34AB, 0x0630, 0x17B9,
        0xEF4E, 0xFEC7, 0xCC5C, 0xDDD5, 0xA96A, 0xB8E3, 0x8A78, 0x9BF1,
        0x7387, 0x620E, 0x5095, 0x411C, 0x35A3, 0x242A, 0x16B1, 0x0738,
        0xFFCF, 0xEE46, 0xDCDD, 0xCD54, 0xB9EB, 0xA862, 0x9AF9, 0x8B70,
        0x8408, 0x9581, 0xA71A, 0xB693, 0xC22C, 0xD3A5, 0xE13E, 0xF0B7,
        0x0840, 0x19C9, 0x2B52, 0x3ADB, 0x4E64, 0x5FED, 0x6D76, 0x7CFF,
        0x9489, 0x8500, 0xB79B, 0xA612, 0xD2AD, 0xC324, 0xF1BF, 0xE036,
        0x18C1, 0x0948, 0x3BD3, 0x2A5A, 0x5EE5, 0x4F6C, 0x7DF7, 0x6C7E,
        0xA50A, 0xB483, 0x8618, 0x9791, 0xE32E, 0xF2A7, 0xC03C, 0xD1B5,
        0x2942, 0x38CB, 0x0A50, 0x1BD9, 0x6F66, 0x7EEF, 0x4C74, 0x5DFD,
        0xB58B, 0xA402, 0x9699, 0x8710, 0xF3AF, 0xE226, 0xD0BD, 0xC134,
        0x39C3, 0x284A, 0x1AD1, 0x0B58, 0x7FE7, 0x6E6E, 0x5CF5, 0x4D7C,
        0xC60C, 0xD785, 0xE51E, 0xF497, 0x8028, 0x91A1, 0xA33A, 0xB2B3,
        0x4A44, 0x5BCD, 0x6956, 0x78DF, 0x0C60, 0x1DE9, 0x2F72, 0x3EFB,
        0xD68D, 0xC704, 0xF59F, 0xE416, 0x90A9, 0x8120, 0xB3BB, 0xA232,
        0x5AC5, 0x4B4C, 0x79D7, 0x685E, 0x1CE1, 0x0D68, 0x3FF3, 0x2E7A,
        0xE70E, 0xF687, 0xC41C, 0xD595, 0xA12A, 0xB0A3, 0x8238, 0x93B1,
        0x6B46, 0x7ACF, 0x4854, 0x59DD, 0x2D62, 0x3CEB, 0x0E70, 0x1FF9,
        0xF78F, 0xE606, 0xD49D, 0xC514, 0xB1AB, 0xA022, 0x92B9, 0x8330,
        0x7BC7, 0x6A4E, 0x58D5, 0x495C, 0x3DE3, 0x2C6A, 0x1EF1, 0x0F78,
    )

    /**
     * Computes the DUML 8-bit header checksum over [bytes] from [offset] for [length] bytes.
     */
    fun calculateCrc8(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
        seed: Int = 0x77,
    ): Int {
        var crc = seed
        for (i in offset until (offset + length)) {
            val b = bytes[i].toInt() and 0xFF
            crc = CRC8_LOOKUP_TABLE[(crc xor b) and 0xFF]
        }
        return crc and 0xFF
    }

    /**
     * Computes the DUML 16-bit frame checksum over [bytes] from [offset] for [length] bytes.
     */
    fun calculateCrc16(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
        seed: Int = 0x3692,
    ): Int {
        var crc = seed
        for (i in offset until (offset + length)) {
            val b = bytes[i].toInt() and 0xFF
            crc = (crc ushr 8) xor CRC16_LOOKUP_TABLE[(b xor crc) and 0xFF]
        }
        return crc and 0xFFFF
    }

    /**
     * Builds a complete DUML v1 packet including header CRC-8 and trailing CRC-16.
     */
    fun buildDumlFrame(
        sourceModuleId: Int = MODULE_MOBILE_APP,
        destinationModuleId: Int = MODULE_REMOTE_CONTROLLER,
        sequenceNumber: Int,
        commandType: Int = COMMAND_TYPE_REQUEST_WITH_ACK,
        commandSet: Int,
        commandId: Int,
        payload: ByteArray = byteArrayOf(),
    ): ByteArray {
        val frameLength = 11 + payload.size + 2
        val rawFrame = ByteArray(frameLength)
        rawFrame[0] = MAGIC_BYTE.toByte()
        rawFrame[1] = (frameLength and 0xFF).toByte()
        rawFrame[2] = ((1 shl 2) or ((frameLength ushr 8) and 0x03)).toByte()
        rawFrame[3] = calculateCrc8(rawFrame, offset = 0, length = 3).toByte()
        rawFrame[4] = (sourceModuleId and 0xFF).toByte()
        rawFrame[5] = (destinationModuleId and 0xFF).toByte()
        rawFrame[6] = (sequenceNumber and 0xFF).toByte()
        rawFrame[7] = ((sequenceNumber ushr 8) and 0xFF).toByte()
        rawFrame[8] = (commandType and 0xFF).toByte()
        rawFrame[9] = (commandSet and 0xFF).toByte()
        rawFrame[10] = (commandId and 0xFF).toByte()
        payload.copyInto(rawFrame, destinationOffset = 11)
        val crc16 = calculateCrc16(rawFrame, offset = 0, length = frameLength - 2)
        rawFrame[frameLength - 2] = (crc16 and 0xFF).toByte()
        rawFrame[frameLength - 1] = ((crc16 ushr 8) and 0xFF).toByte()
        return rawFrame
    }

    /**
     * Serializes a [DumlFrame] back into its binary DUML v1 packet representation.
     */
    fun encodeDumlFrame(frame: DumlFrame): ByteArray {
        return buildDumlFrame(
            sourceModuleId = frame.sourceModuleId,
            destinationModuleId = frame.destinationModuleId,
            sequenceNumber = frame.sequenceNumber,
            commandType = frame.commandType,
            commandSet = frame.commandSet,
            commandId = frame.commandId,
            payload = frame.payload,
        )
    }

    /**
     * Wraps a raw DUML packet inside DJI's 8-byte AOA multiplex header (`0x55 0xCC 0x49 0x57 <len-LE32>`).
     */
    fun wrapInAoaEnvelope(
        innerPayload: ByteArray,
        channelPort: Int = AOA_CHANNEL_DUML,
    ): ByteArray {
        val envelope = ByteArray(8 + innerPayload.size)
        envelope[0] = MAGIC_BYTE.toByte()
        envelope[1] = AOA_ENVELOPE_SECOND_BYTE.toByte()
        envelope[2] = (channelPort and 0xFF).toByte()
        envelope[3] = ((channelPort ushr 8) and 0xFF).toByte()
        val length = innerPayload.size
        envelope[4] = (length and 0xFF).toByte()
        envelope[5] = ((length ushr 8) and 0xFF).toByte()
        envelope[6] = ((length ushr 16) and 0xFF).toByte()
        envelope[7] = ((length ushr 24) and 0xFF).toByte()
        innerPayload.copyInto(envelope, destinationOffset = 8)
        return envelope
    }

    /**
     * Builds a DUML device version inquiry (`commandSet = 0x00, commandId = 0x01`) wrapped for AOA.
     */
    fun buildDeviceVersionQuery(
        destinationModuleId: Int,
        sequenceNumber: Int,
    ): ByteArray {
        val dumlFrame = buildDumlFrame(
            sourceModuleId = MODULE_MOBILE_APP,
            destinationModuleId = destinationModuleId,
            sequenceNumber = sequenceNumber,
            commandType = COMMAND_TYPE_REQUEST_WITH_ACK,
            commandSet = 0x00,
            commandId = 0x01,
        )
        return wrapInAoaEnvelope(dumlFrame, AOA_CHANNEL_DUML)
    }

    /**
     * Builds a DUML Battery Dynamic Data query (`0x0D / 0x02`) wrapped for AOA.
     *
     * Queries the Intelligent Flight Battery (`MODULE_BATTERY`) directly for
     * real-time state of charge, remaining capacity, and operational status.
     * Only read-only GET queries are issued to prevent interfering with flight
     * controller motor-start routines.
     */
    fun buildBatteryDynamicDataQuery(sequenceNumber: Int): ByteArray {
        val dumlFrame = buildDumlFrame(
            sourceModuleId = MODULE_MOBILE_APP,
            destinationModuleId = MODULE_BATTERY,
            sequenceNumber = sequenceNumber,
            commandType = COMMAND_TYPE_REQUEST_WITH_ACK,
            commandSet = COMMAND_SET_BATTERY,
            commandId = CMD_BATTERY_DYNAMIC_DATA,
        )
        return wrapInAoaEnvelope(dumlFrame, AOA_CHANNEL_DUML)
    }

    /**
     * Returns a human-readable module name for a DUML module ID byte (`bits 0..4` = device type, `bits 5..7` = sub-index).
     */
    fun formatModuleName(moduleId: Int): String {
        val deviceType = moduleId and 0x1F
        val subIndex = (moduleId ushr 5) and 0x07
        val baseName = when (deviceType) {
            0x00 -> "Any / Broadcast"
            MODULE_CAMERA -> "Camera"
            MODULE_MOBILE_APP -> "Mobile App"
            MODULE_FLIGHT_CONTROLLER -> "Flight Controller"
            MODULE_GIMBAL -> "Gimbal"
            0x05 -> "Center Board"
            MODULE_REMOTE_CONTROLLER -> "Remote Controller"
            0x07 -> "Wi-Fi Air Side"
            0x08 -> "DM36x Air Side"
            MODULE_VIDEO_TRANSMISSION -> "HD Link MCU Air"
            0x0A -> "PC Assistant"
            MODULE_BATTERY -> "Smart Battery"
            MODULE_ESC -> "Motor ESC"
            0x0D -> "DM36x Ground Side"
            0x0E -> "HD Link MCU Ground"
            0x0F -> "USB Ctrl Air Side"
            0x10 -> "USB Ctrl Ground Side"
            0x11 -> "Monocular Vision"
            0x12 -> "Binocular Vision"
            0x13 -> "HD Link FPGA Air"
            0x14 -> "HD Link FPGA Ground"
            0x15 -> "Simulator"
            0x16 -> "Base Station"
            0x17 -> "Airborne Computer"
            0x18 -> "RC Battery"
            0x19 -> "IMU"
            0x1A -> "GPS / RTK"
            0x1B -> "Wi-Fi Ground Side"
            0x1C -> "Signal Converter"
            0x1D -> "PMU"
            else -> "Module"
        }
        return if (subIndex == 0) {
            "$baseName (0x%02X)".format(moduleId and 0xFF)
        } else {
            "$baseName #$subIndex (0x%02X)".format(moduleId and 0xFF)
        }
    }

    /**
     * Attempts to parse a single DUML v1 frame starting at [offset] in [buffer].
     * Returns [DumlParseOutcome] indicating whether a valid frame was parsed or more bytes are needed.
     */
    fun tryParseDumlFrame(
        buffer: ByteArray,
        offset: Int,
        availableLength: Int,
    ): DumlParseOutcome {
        if (availableLength < 4) return DumlParseOutcome.NeedMoreBytes
        if ((buffer[offset].toInt() and 0xFF) != MAGIC_BYTE) {
            return DumlParseOutcome.InvalidFrame
        }
        val lengthLow = buffer[offset + 1].toInt() and 0xFF
        val lengthAndVersion = buffer[offset + 2].toInt() and 0xFF
        val frameLength = lengthLow or ((lengthAndVersion and 0x03) shl 8)
        val protocolVersion = (lengthAndVersion ushr 2) and 0x3F
        if (protocolVersion != 1 || frameLength < 13 || frameLength > 1024) {
            return DumlParseOutcome.InvalidFrame
        }
        val expectedHeaderCrc8 = calculateCrc8(buffer, offset = offset, length = 3)
        val actualHeaderCrc8 = buffer[offset + 3].toInt() and 0xFF
        if (expectedHeaderCrc8 != actualHeaderCrc8) {
            return DumlParseOutcome.InvalidFrame
        }
        if (availableLength < frameLength) {
            return DumlParseOutcome.NeedMoreBytes
        }
        val expectedCrc16 = calculateCrc16(buffer, offset = offset, length = frameLength - 2)
        val crcLow = buffer[offset + frameLength - 2].toInt() and 0xFF
        val crcHigh = buffer[offset + frameLength - 1].toInt() and 0xFF
        val actualCrc16 = crcLow or (crcHigh shl 8)
        if (expectedCrc16 != actualCrc16) {
            return DumlParseOutcome.InvalidFrame
        }

        val sourceModuleId = buffer[offset + 4].toInt() and 0xFF
        val destinationModuleId = buffer[offset + 5].toInt() and 0xFF
        val sequenceNumber = (buffer[offset + 6].toInt() and 0xFF) or
            ((buffer[offset + 7].toInt() and 0xFF) shl 8)
        val commandType = buffer[offset + 8].toInt() and 0xFF
        val commandSet = buffer[offset + 9].toInt() and 0xFF
        val commandId = buffer[offset + 10].toInt() and 0xFF
        val payloadLength = frameLength - 13
        val payload = buffer.copyOfRange(offset + 11, offset + 11 + payloadLength)

        return DumlParseOutcome.Parsed(
            frame = DumlFrame(
                sourceModuleId = sourceModuleId,
                destinationModuleId = destinationModuleId,
                sequenceNumber = sequenceNumber,
                commandType = commandType,
                commandSet = commandSet,
                commandId = commandId,
                payload = payload,
            ),
            bytesConsumed = frameLength,
        )
    }

    /**
     * Decodes a Flight Controller OSD General packet (`commandSet = 0x03, commandId = 0x43`).
     */
    fun decodeFlightControllerOsdTelemetry(frame: DumlFrame): FlightControllerTelemetry? {
        if (frame.commandSet != MODULE_FLIGHT_CONTROLLER || frame.commandId != 0x43) {
            return null
        }
        val payload = frame.payload
        if (payload.size < 41) return null

        val byteBuffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val longitudeRadians = byteBuffer.getDouble(0)
        val latitudeRadians = byteBuffer.getDouble(8)
        val longitudeDegrees = longitudeRadians * 180.0 / PI
        val latitudeDegrees = latitudeRadians * 180.0 / PI
        val relativeAltitudeMeters = byteBuffer.getShort(16) * 0.1f
        val velocityX = byteBuffer.getShort(18) * 0.1f
        val velocityY = byteBuffer.getShort(20) * 0.1f
        val velocityZ = byteBuffer.getShort(22) * 0.1f
        val pitchDegrees = byteBuffer.getShort(24) * 0.1f
        val rollDegrees = byteBuffer.getShort(26) * 0.1f
        val yawDegrees = byteBuffer.getShort(28) * 0.1f
        val modeByte = payload[30].toInt() and 0xFF
        val controllerStateFlags = byteBuffer.getInt(32)
        val gpsSatellites = payload[36].toInt() and 0xFF
        val batteryPercentage = payload[40].toInt() and 0xFF
        val motorsOn = (controllerStateFlags and 0x08) != 0
        val inAir = (controllerStateFlags and 0x04) != 0
        val rthActive = (controllerStateFlags and 0x20) != 0 || modeByte == 15
        val autoLanding = (controllerStateFlags and 0x80) != 0 || modeByte == 12 || modeByte == 13 || modeByte == 33
        val lowBatteryWarning = false
        val compassError = (controllerStateFlags and 0x400) != 0
        val sportMode = (controllerStateFlags and 0x800) != 0 || modeByte == 31

        val rawUltrasonic = if (payload.size >= 40) {
            byteBuffer.getShort(38).toInt() and 0xFFFF
        } else {
            0
        }
        val isUltrasonicValid = rawUltrasonic in 1..800
        val ultrasonicHeightMeters = if (isUltrasonicValid) {
            if (rawUltrasonic > 50) rawUltrasonic * 0.01f else rawUltrasonic * 0.1f
        } else {
            null
        }

        val flightMode = when (modeByte) {
            0 -> "MANUAL"
            1 -> "ATTI"
            2 -> "ATTI-CL"
            3 -> "ATTI-HOVER"
            4 -> "HOVER"
            5, 6 -> "P-GPS"
            7 -> "GPS-CL"
            8 -> "GPS-HL"
            9 -> "GPS-HOTPOINT"
            10, 11 -> "AUTO-TAKEOFF"
            12, 33 -> "AUTO-LAND"
            13 -> "ATTI-LAND"
            14 -> "WAYPOINT"
            15 -> "RTH"
            16 -> "CLICK-GO"
            17 -> "JOYSTICK"
            23 -> "ATTI-LIMITED"
            24 -> "DRAW"
            25 -> "FOLLOW-ME"
            26 -> "ACTIVE-TRACK"
            27 -> "TAP-FLY"
            28 -> "PANO"
            29 -> "FARMING"
            30 -> "FPV"
            31 -> "SPORT"
            32 -> "NOVICE"
            35 -> "TERRAIN-FOLLOW"
            36 -> "PALM-CONTROL"
            37 -> "QUICK-SHOT"
            38 -> "TRIPOD"
            39 -> "SPOTLIGHT"
            41 -> "MOTORS-START"
            43 -> "TIMELAPSE"
            else -> if (sportMode) "SPORT" else if (rthActive) "RTH" else if (autoLanding) "AUTO-LAND" else "P-GPS"
        }

        return FlightControllerTelemetry(
            latitudeDegrees = latitudeDegrees,
            longitudeDegrees = longitudeDegrees,
            relativeAltitudeMeters = relativeAltitudeMeters,
            velocityXMetersPerSecond = velocityX,
            velocityYMetersPerSecond = velocityY,
            velocityZMetersPerSecond = velocityZ,
            pitchDegrees = pitchDegrees,
            rollDegrees = rollDegrees,
            yawDegrees = yawDegrees,
            gpsSatellites = gpsSatellites,
            batteryPercentage = batteryPercentage,
            motorsRunning = motorsOn,
            aircraftInAir = inAir,
            flightMode = flightMode,
            ultrasonicHeightMeters = ultrasonicHeightMeters,
            isUltrasonicValid = isUltrasonicValid,
            isRthActive = rthActive,
            isAutoLanding = autoLanding,
            isLowBatteryWarning = lowBatteryWarning,
            isCompassError = compassError,
            isSportMode = sportMode,
        )
    }

    /**
     * Decodes a Smart Battery telemetry packet (`commandSet = 0x0D`, `0x0B`, or `0x00`).
     */
    fun decodeBatteryTelemetry(frame: DumlFrame): BatteryTelemetry? {
        val isBatteryCommandSet = frame.commandSet == COMMAND_SET_BATTERY ||
            frame.commandSet == MODULE_BATTERY ||
            frame.commandSet == 0x00
        val isBatterySourceModule = (frame.sourceModuleId and 0x1F) == MODULE_BATTERY ||
            (frame.sourceModuleId and 0x1F) == MODULE_CENTER_BOARD ||
            (frame.sourceModuleId and 0x1F) == MODULE_FLIGHT_CONTROLLER
        if (!isBatteryCommandSet && !isBatterySourceModule) {
            return null
        }

        val payload = frame.payload
        return when (frame.commandId) {
            CMD_BATTERY_DYNAMIC_DATA, CMD_BATTERY_COMMON -> decodeBatteryDynamicData(payload)
            CMD_BATTERY_CELL_VOLTAGE -> decodeBatteryCellVoltage(payload)
            else -> null
        }
    }

    private fun decodeBatteryDynamicData(payload: ByteArray): BatteryTelemetry? {
        if (payload.size < 7) return null
        val byteBuffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        // 1. 32-bit capacity layout with 2-byte prefix (offset = 2).
        if (payload.size >= 22) {
            val voltageMillivolts = byteBuffer.getInt(2)
            val currentMilliamps = byteBuffer.getInt(6)
            val fullCapacity = byteBuffer.getInt(10)
            val remainingCapacity = byteBuffer.getInt(14)
            val temperatureRaw = byteBuffer.getShort(18).toInt() and 0xFFFF
            val cellCount = payload[20].toInt() and 0xFF
            val stateOfCharge = payload[21].toInt() and 0xFF

            val isPlausiblePrefix = (voltageMillivolts in 3000..35000) &&
                (cellCount in 1..8) &&
                (stateOfCharge in 0..100) &&
                (fullCapacity in 500..50000 && remainingCapacity in 0..fullCapacity)
            if (isPlausiblePrefix) {
                val percentage = if (stateOfCharge in 1..100) {
                    stateOfCharge
                } else if (fullCapacity > 0) {
                    ((remainingCapacity.toLong() * 100L) / fullCapacity.toLong()).toInt().coerceIn(0, 100)
                } else {
                    0
                }
                val temperatureCelsius = if (temperatureRaw in 100..1000) temperatureRaw * 0.1f else null
                val cycleCount32P = if (payload.size >= 24) byteBuffer.getShort(22).toInt() and 0xFFFF else null
                return BatteryTelemetry(
                    percentage = percentage,
                    packVoltageMillivolts = voltageMillivolts,
                    currentMilliamps = currentMilliamps,
                    remainingCapacityMilliampHours = remainingCapacity,
                    fullCapacityMilliampHours = fullCapacity,
                    temperatureCelsius = temperatureCelsius,
                    cellCount = cellCount,
                    cycleCount = cycleCount32P,
                )
            }
        }

        // 2. 32-bit capacity layout prefixless (offset = 0).
        if (payload.size >= 20) {
            val voltageMillivolts0 = byteBuffer.getInt(0)
            val currentMilliamps0 = byteBuffer.getInt(4)
            val fullCapacity0 = byteBuffer.getInt(8)
            val remainingCapacity0 = byteBuffer.getInt(12)
            val temperatureRaw0 = byteBuffer.getShort(16).toInt() and 0xFFFF
            val cellCount0 = payload[18].toInt() and 0xFF
            val stateOfCharge0 = payload[19].toInt() and 0xFF

            val isPlausiblePrefixless = (voltageMillivolts0 in 3000..35000) &&
                (cellCount0 in 1..8) &&
                (stateOfCharge0 in 0..100) &&
                (fullCapacity0 in 500..50000 && remainingCapacity0 in 0..fullCapacity0)
            if (isPlausiblePrefixless) {
                val percentage = if (stateOfCharge0 in 1..100) {
                    stateOfCharge0
                } else if (fullCapacity0 > 0) {
                    ((remainingCapacity0.toLong() * 100L) / fullCapacity0.toLong()).toInt().coerceIn(0, 100)
                } else {
                    0
                }
                val temperatureCelsius = if (temperatureRaw0 in 100..1000) temperatureRaw0 * 0.1f else null
                val cycleCount32 = if (payload.size >= 22) byteBuffer.getShort(20).toInt() and 0xFFFF else null
                return BatteryTelemetry(
                    percentage = percentage,
                    packVoltageMillivolts = voltageMillivolts0,
                    currentMilliamps = currentMilliamps0,
                    remainingCapacityMilliampHours = remainingCapacity0,
                    fullCapacityMilliampHours = fullCapacity0,
                    temperatureCelsius = temperatureCelsius,
                    cellCount = cellCount0,
                    cycleCount = cycleCount32,
                )
            }
        }

        // 3. Standard DJI 16-bit Center Battery layout (CENTER_BATTERY record / Center Battery Common).
        val soc16 = payload[0].toInt() and 0xFF
        val packMv16 = byteBuffer.getShort(1).toInt() and 0xFFFF
        val remMah16 = byteBuffer.getShort(3).toInt() and 0xFFFF
        val fullMah16 = byteBuffer.getShort(5).toInt() and 0xFFFF
        val life16 = if (payload.size >= 8) payload[7].toInt() and 0xFF else null
        val cycleCount16 = if (payload.size >= 10) byteBuffer.getShort(8).toInt() and 0xFFFF else null
        val isPlausible16Bit = (soc16 in 0..100) &&
            (packMv16 in 3000..35000) &&
            (fullMah16 in 500..50000 && remMah16 in 0..fullMah16) &&
            (life16 == null || life16 in 0..100)
        if (isPlausible16Bit) {
            val currentMa16 = if (payload.size >= 16) byteBuffer.getShort(14).toInt() else null
            val cellVoltages16 = mutableListOf<Int>()
            if (payload.size >= 22) {
                for (offset in 16 until minOf(payload.size - 1, 28) step 2) {
                    val cellMv = byteBuffer.getShort(offset).toInt() and 0xFFFF
                    if (cellMv in 2000..5000) {
                        cellVoltages16.add(cellMv)
                    }
                }
            }
            val percentage16 = if (soc16 in 1..100) {
                soc16
            } else if (fullMah16 > 0) {
                ((remMah16.toLong() * 100L) / fullMah16.toLong()).toInt().coerceIn(0, 100)
            } else {
                0
            }
            return BatteryTelemetry(
                percentage = percentage16,
                packVoltageMillivolts = packMv16,
                currentMilliamps = currentMa16,
                remainingCapacityMilliampHours = remMah16,
                fullCapacityMilliampHours = fullMah16,
                cellCount = cellVoltages16.size.takeIf { it > 0 },
                cellVoltagesMillivolts = cellVoltages16,
                lifetimeHealthPercentage = life16,
                cycleCount = cycleCount16,
            )
        }

        // 4. Standard DJI 16-bit layout with 2-byte prefix.
        if (payload.size >= 9) {
            val soc16P = payload[2].toInt() and 0xFF
            val packMv16P = byteBuffer.getShort(3).toInt() and 0xFFFF
            val remMah16P = byteBuffer.getShort(5).toInt() and 0xFFFF
            val fullMah16P = byteBuffer.getShort(7).toInt() and 0xFFFF
            val isPlausible16BitPrefixed = (soc16P in 0..100) &&
                (packMv16P in 3000..35000) &&
                (fullMah16P in 500..50000 && remMah16P in 0..fullMah16P)
            if (isPlausible16BitPrefixed) {
                val currentMa16P = if (payload.size >= 18) byteBuffer.getShort(16).toInt() else null
                val life16P = if (payload.size >= 10) payload[9].toInt() and 0xFF else null
                val cycleCount16P = if (payload.size >= 12) byteBuffer.getShort(10).toInt() and 0xFFFF else null
                val percentage16P = if (soc16P in 1..100) {
                    soc16P
                } else if (fullMah16P > 0) {
                    ((remMah16P.toLong() * 100L) / fullMah16P.toLong()).toInt().coerceIn(0, 100)
                } else {
                    0
                }
                return BatteryTelemetry(
                    percentage = percentage16P,
                    packVoltageMillivolts = packMv16P,
                    currentMilliamps = currentMa16P,
                    remainingCapacityMilliampHours = remMah16P,
                    fullCapacityMilliampHours = fullMah16P,
                    lifetimeHealthPercentage = life16P,
                    cycleCount = cycleCount16P,
                )
            }
        }

        // 5. If capacity fields are unpopulated but payload has standard size and valid voltage.
        if (payload.size >= 22) {
            val voltageMillivolts = byteBuffer.getInt(2)
            if (voltageMillivolts in 3000..35000) {
                return BatteryTelemetry(
                    percentage = (payload[21].toInt() and 0xFF).takeIf { it in 0..100 },
                    packVoltageMillivolts = voltageMillivolts,
                    currentMilliamps = byteBuffer.getInt(6),
                    cellCount = (payload[20].toInt() and 0xFF).takeIf { it in 1..8 },
                )
            }
        }

        return null
    }

    private fun decodeBatteryCellVoltage(payload: ByteArray): BatteryTelemetry? {
        if (payload.size < 4) return null
        val (cellCountIndex, voltagesStartIndex) = when {
            payload.size >= 9 && (payload[2].toInt() and 0xFF) in 1..8 -> 2 to 3
            payload.size >= 3 && (payload[1].toInt() and 0xFF) in 1..8 -> 1 to 2
            (payload[0].toInt() and 0xFF) in 1..8 -> 0 to 1
            else -> return null
        }
        val cellCount = payload[cellCountIndex].toInt() and 0xFF
        if (payload.size < voltagesStartIndex + cellCount * 2) return null

        val byteBuffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val cellVoltages = mutableListOf<Int>()
        for (index in 0 until cellCount) {
            val cellMv = byteBuffer.getShort(voltagesStartIndex + index * 2).toInt() and 0xFFFF
            if (cellMv in 2000..5000) {
                cellVoltages.add(cellMv)
            }
        }
        if (cellVoltages.isEmpty()) return null
        val totalVoltage = cellVoltages.sum()

        return BatteryTelemetry(
            percentage = null,
            packVoltageMillivolts = totalVoltage,
            cellCount = cellVoltages.size,
            cellVoltagesMillivolts = cellVoltages,
        )
    }

    /**
     * Decodes a Remote Controller stick/wheel state packet (`commandSet = 0x06, commandId = 0x05`).
     * In DUML v1, stick axes are 11-bit or 16-bit values centered at `1024` (range `364..1684`).
     */
    fun decodeRemoteControllerAxes(frame: DumlFrame): RemoteControllerAxes? {
        if (frame.commandSet != MODULE_REMOTE_CONTROLLER || frame.commandId != 0x05) {
            return null
        }
        val payload = frame.payload
        if (payload.size < 10) return null
        val byteBuffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val rightStickHorizontal = byteBuffer.getShort(0).toInt() and 0xFFFF
        val rightStickVertical = byteBuffer.getShort(2).toInt() and 0xFFFF
        val leftStickVertical = byteBuffer.getShort(4).toInt() and 0xFFFF
        val leftStickHorizontal = byteBuffer.getShort(6).toInt() and 0xFFFF
        val gimbalWheel = byteBuffer.getShort(8).toInt() and 0xFFFF
        if (rightStickHorizontal !in 200..1900 || rightStickVertical !in 200..1900) {
            return null
        }

        return RemoteControllerAxes(
            rightStickHorizontal = rightStickHorizontal,
            rightStickVertical = rightStickVertical,
            leftStickVertical = leftStickVertical,
            leftStickHorizontal = leftStickHorizontal,
            gimbalWheel = gimbalWheel,
        )
    }

    /**
     * Decodes a Camera Gimbal telemetry packet (`commandSet = 0x04` or `sourceModuleId = 0x04`).
     *
     * In DUML v1, the gimbal controller streams unsolicited telemetry packets (typically `commandId = 0x05`)
     * containing physical pitch, roll, and yaw angles as little-endian signed 16-bit integers with 0.1° scaling.
     * Negative pitch corresponds to downward tilt toward the ground, while 0° corresponds to level horizon.
     */
    fun decodeGimbalTelemetry(frame: DumlFrame): GimbalTelemetry? {
        val isGimbalSubsystem = frame.commandSet == MODULE_GIMBAL ||
            (frame.sourceModuleId and 0x1F) == MODULE_GIMBAL
        if (!isGimbalSubsystem) {
            return null
        }
        if (frame.commandId != CMD_GIMBAL_PUSH_STATUS && frame.commandId != CMD_GIMBAL_CONTROL) {
            return null
        }
        val payload = frame.payload
        if (payload.size < 6) {
            return null
        }

        val byteBuffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val rawPitch = byteBuffer.getShort(0).toInt()
        val rawRoll = byteBuffer.getShort(2).toInt()
        val rawYaw = byteBuffer.getShort(4).toInt()

        val pitchDegrees = rawPitch * 0.1f
        val rollDegrees = rawRoll * 0.1f
        val yawDegrees = rawYaw * 0.1f

        if (pitchDegrees !in -135.0f..90.0f || rollDegrees !in -90.0f..90.0f || yawDegrees !in -360.0f..360.0f) {
            return null
        }

        val mode = if (payload.size >= 7) payload[6].toInt() and 0xFF else null

        return GimbalTelemetry(
            pitchDegrees = pitchDegrees,
            rollDegrees = rollDegrees,
            yawDegrees = yawDegrees,
            mode = mode,
        )
    }

    /**
     * Extracts a readable hardware/firmware version string from a `0x00/0x01` version response.
     */
    fun decodeDeviceVersionString(frame: DumlFrame): String? {
        if (frame.commandSet != 0x00 || frame.commandId != 0x01 || frame.payload.size < 6) {
            return null
        }
        val asciiSegment = buildString {
            for (b in frame.payload) {
                val code = b.toInt() and 0xFF
                if (code in 32..126) {
                    append(code.toChar())
                } else if (isNotEmpty() && last() != ' ') {
                    append(' ')
                }
            }
        }.trim()
        return asciiSegment.ifEmpty { null }
    }

    /**
     * Formats the first [maxBytes] of [bytes] as an uppercase hexadecimal string.
     */
    fun formatHexPreview(bytes: ByteArray, maxBytes: Int = 24): String {
        val count = minOf(bytes.size, maxBytes)
        val hex = (0 until count).joinToString(" ") { index ->
            "%02X".format(bytes[index].toInt() and 0xFF)
        }
        return if (bytes.size > maxBytes) "$hex … (${bytes.size} B)" else hex
    }
}

/**
 * Result of attempting to parse a DUML v1 frame from a byte buffer.
 */
sealed interface DumlParseOutcome {
    data object NeedMoreBytes : DumlParseOutcome
    data object InvalidFrame : DumlParseOutcome
    data class Parsed(
        val frame: DumlFrame,
        val bytesConsumed: Int,
    ) : DumlParseOutcome
}

/**
 * Represents a parsed and CRC-verified DUML v1 packet.
 */
data class DumlFrame(
    val sourceModuleId: Int,
    val destinationModuleId: Int,
    val sequenceNumber: Int,
    val commandType: Int,
    val commandSet: Int,
    val commandId: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DumlFrame) return false
        return sourceModuleId == other.sourceModuleId &&
            destinationModuleId == other.destinationModuleId &&
            sequenceNumber == other.sequenceNumber &&
            commandType == other.commandType &&
            commandSet == other.commandSet &&
            commandId == other.commandId &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = sourceModuleId
        result = 31 * result + destinationModuleId
        result = 31 * result + sequenceNumber
        result = 31 * result + commandType
        result = 31 * result + commandSet
        result = 31 * result + commandId
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Basic Flight Controller OSD telemetry (`0x03/0x43`).
 */
data class FlightControllerTelemetry(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val relativeAltitudeMeters: Float,
    val velocityXMetersPerSecond: Float,
    val velocityYMetersPerSecond: Float,
    val velocityZMetersPerSecond: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float,
    val yawDegrees: Float,
    val gpsSatellites: Int,
    val batteryPercentage: Int,
    val motorsRunning: Boolean,
    val aircraftInAir: Boolean,
    val flightMode: String = "P-GPS",
    val ultrasonicHeightMeters: Float? = null,
    val isUltrasonicValid: Boolean = false,
    val isRthActive: Boolean = false,
    val isAutoLanding: Boolean = false,
    val isLowBatteryWarning: Boolean = false,
    val isCompassError: Boolean = false,
    val isSportMode: Boolean = false,
)

/**
 * Remote Controller physical stick and gimbal wheel positions (`0x06/0x05`).
 */
data class RemoteControllerAxes(
    val rightStickHorizontal: Int,
    val rightStickVertical: Int,
    val leftStickVertical: Int,
    val leftStickHorizontal: Int,
    val gimbalWheel: Int,
)

/**
 * Camera Gimbal telemetry (`commandSet = 0x04` or `module = 0x04`).
 */
data class GimbalTelemetry(
    val pitchDegrees: Float,
    val rollDegrees: Float,
    val yawDegrees: Float,
    val mode: Int? = null,
)

/**
 * Smart Battery telemetry (`commandSet = 0x0D` or `module = 0x0B`).
 */
data class BatteryTelemetry(
    val percentage: Int? = null,
    val packVoltageMillivolts: Int? = null,
    val currentMilliamps: Int? = null,
    val remainingCapacityMilliampHours: Int? = null,
    val fullCapacityMilliampHours: Int? = null,
    val temperatureCelsius: Float? = null,
    val cellCount: Int? = null,
    val cellVoltagesMillivolts: List<Int> = emptyList(),
    val lifetimeHealthPercentage: Int? = null,
    val cycleCount: Int? = null,
) {
    val powerWatts: Float?
        get() = FlightDynamicsCalculator.calculatePowerWatts(packVoltageMillivolts, currentMilliamps)

    val maxCellVoltageMillivolts: Int?
        get() = cellVoltagesMillivolts.maxOrNull()

    val minCellVoltageMillivolts: Int?
        get() = cellVoltagesMillivolts.minOrNull()

    val cellDeltaMillivolts: Int?
        get() = FlightDynamicsCalculator.calculateCellDeltaMillivolts(cellVoltagesMillivolts)

    val estimatedFlightTimeMinutes: Float?
        get() = FlightDynamicsCalculator.calculateEstimatedFlightTimeMinutes(remainingCapacityMilliampHours, currentMilliamps)

    /**
     * Non-destructively merges another [BatteryTelemetry] into this instance.
     * Non-null fields from [newer] take precedence, preserving existing fields.
     */
    fun mergeWith(newer: BatteryTelemetry): BatteryTelemetry {
        return BatteryTelemetry(
            percentage = newer.percentage ?: this.percentage,
            packVoltageMillivolts = newer.packVoltageMillivolts ?: this.packVoltageMillivolts,
            currentMilliamps = newer.currentMilliamps ?: this.currentMilliamps,
            remainingCapacityMilliampHours = newer.remainingCapacityMilliampHours ?: this.remainingCapacityMilliampHours,
            fullCapacityMilliampHours = newer.fullCapacityMilliampHours ?: this.fullCapacityMilliampHours,
            temperatureCelsius = newer.temperatureCelsius ?: this.temperatureCelsius,
            cellCount = newer.cellCount ?: this.cellCount,
            cellVoltagesMillivolts = if (newer.cellVoltagesMillivolts.isNotEmpty()) {
                newer.cellVoltagesMillivolts
            } else {
                this.cellVoltagesMillivolts
            },
            lifetimeHealthPercentage = newer.lifetimeHealthPercentage ?: this.lifetimeHealthPercentage,
            cycleCount = newer.cycleCount ?: this.cycleCount,
        )
    }
}

/**
 * Stateful stream parser that demultiplexes DJI AOA outer envelopes (`0x55 0xCC`)
 * and extracts inner DUML v1 packets and H.264 video chunks.
 */
class AoaStreamParser(
    private val onDumlFrame: (DumlFrame) -> Unit,
    private val onVideoChunk: (ByteArray) -> Unit,
) {
    private val accumulator = ByteArrayOutputStream()
    private val dumlAccumulator = ByteArrayOutputStream()

    var aoaDumlEnvelopesReceived: Long = 0L
        private set

    var aoaVideoEnvelopesReceived: Long = 0L
        private set

    /**
     * Feeds a chunk of raw bytes read from the USB Accessory input stream.
     */
    fun feedBytes(chunk: ByteArray, length: Int) {
        if (length <= 0) return
        accumulator.write(chunk, 0, length)
        val buffer = accumulator.toByteArray()
        var offset = 0
        val total = buffer.size

        while (offset < total) {
            val firstByte = buffer[offset].toInt() and 0xFF
            if (firstByte != DumlProtocol.MAGIC_BYTE) {
                offset++
                continue
            }

            if (offset + 1 >= total) {
                break
            }

            val secondByte = buffer[offset + 1].toInt() and 0xFF
            if (secondByte == DumlProtocol.AOA_ENVELOPE_SECOND_BYTE) {
                if (offset + 8 > total) {
                    break
                }
                val channelPort = (buffer[offset + 2].toInt() and 0xFF) or
                    ((buffer[offset + 3].toInt() and 0xFF) shl 8)
                val innerLength = (buffer[offset + 4].toInt() and 0xFF) or
                    ((buffer[offset + 5].toInt() and 0xFF) shl 8) or
                    ((buffer[offset + 6].toInt() and 0xFF) shl 16) or
                    ((buffer[offset + 7].toInt() and 0xFF) shl 24)

                if (innerLength < 0 || innerLength > 262_144) {
                    offset += 2
                    continue
                }
                if (offset + 8 + innerLength > total) {
                    break
                }

                val innerPayload = buffer.copyOfRange(offset + 8, offset + 8 + innerLength)
                when (channelPort) {
                    DumlProtocol.AOA_CHANNEL_DUML -> {
                        aoaDumlEnvelopesReceived++
                        feedDumlSubStream(innerPayload)
                    }
                    DumlProtocol.AOA_CHANNEL_VIDEO -> {
                        aoaVideoEnvelopesReceived++
                        onVideoChunk(innerPayload)
                    }
                    else -> {
                        feedDumlSubStream(innerPayload)
                    }
                }
                offset += 8 + innerLength
            } else {
                when (
                    val outcome = DumlProtocol.tryParseDumlFrame(
                        buffer = buffer,
                        offset = offset,
                        availableLength = total - offset,
                    )
                ) {
                    is DumlParseOutcome.Parsed -> {
                        onDumlFrame(outcome.frame)
                        offset += outcome.bytesConsumed
                    }
                    DumlParseOutcome.NeedMoreBytes -> break
                    DumlParseOutcome.InvalidFrame -> offset++
                }
            }
        }

        accumulator.reset()
        if (offset < total) {
            accumulator.write(buffer, offset, total - offset)
        }
    }

    private fun feedDumlSubStream(innerBytes: ByteArray) {
        dumlAccumulator.write(innerBytes, 0, innerBytes.size)
        val buffer = dumlAccumulator.toByteArray()
        var offset = 0
        val total = buffer.size

        while (offset < total) {
            if ((buffer[offset].toInt() and 0xFF) != DumlProtocol.MAGIC_BYTE) {
                offset++
                continue
            }
            when (
                val outcome = DumlProtocol.tryParseDumlFrame(
                    buffer = buffer,
                    offset = offset,
                    availableLength = total - offset,
                )
            ) {
                is DumlParseOutcome.Parsed -> {
                    onDumlFrame(outcome.frame)
                    offset += outcome.bytesConsumed
                }
                DumlParseOutcome.NeedMoreBytes -> break
                DumlParseOutcome.InvalidFrame -> offset++
            }
        }

        dumlAccumulator.reset()
        if (offset < total) {
            dumlAccumulator.write(buffer, offset, total - offset)
        }
    }
}
