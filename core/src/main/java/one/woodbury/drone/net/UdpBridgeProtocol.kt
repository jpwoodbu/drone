package one.woodbury.drone.net

import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Defines the lightweight UDP framing protocol used between the Phone Bridge (`:app`)
 * and the Meta Quest 2 Viewer (`:mirror`) on UDP port [UDP_BRIDGE_PORT].
 *
 * - Discovery Heartbeat (`"DJIH"`): 4-byte ASCII magic sent by the Quest 2 every 1,000 ms
 *   to the subnet broadcast address (`x.x.x.255:5600`) and `255.255.255.255:5600`.
 *   The phone records the sender IPv4 address as an active subscriber.
 * - Stream Data Packet (`"DJID"`): 12-byte header followed by up to [MAX_UDP_PAYLOAD_BYTES]
 *   (`1,388` bytes) of payload so the entire UDP payload (`<= 1,400` bytes) never fragments
 *   at the Wi-Fi IP layer.
 *   Header layout:
 *   - Bytes `0..3`: `"DJID"` (`0x44 0x4A 0x49 0x44`)
 *   - Byte `4`: [channelType] (`0x01` = H.264 Annex-B video chunk, `0x02` = raw DUML v1 frame)
 *   - Byte `5`: reserved (`0x00`)
 *   - Bytes `6..9`: uint32 little-endian [sequenceNumber]
 *   - Bytes `10..11`: uint16 little-endian payload length (`1..1388`)
 */
object UdpBridgeProtocol {

    /** UDP port used by both the phone discovery listener and Quest 2 stream receiver. */
    const val UDP_BRIDGE_PORT: Int = 5600

    /** Maximum inner payload bytes per UDP datagram so total datagram length is `<= 1,400` bytes. */
    const val MAX_UDP_PAYLOAD_BYTES: Int = 1388

    /** Header size in bytes for `"DJID"` stream data datagrams. */
    const val DATA_HEADER_BYTES: Int = 12

    /** Channel identifier for H.264 Annex-B video chunks (`0x01`). */
    const val CHANNEL_VIDEO: Int = 0x01

    /** Channel identifier for verified DUML v1 frames (`0x02`). */
    const val CHANNEL_DUML: Int = 0x02

    /** How long a Quest 2 subscriber remains active after its last `"DJIH"` heartbeat. */
    const val SUBSCRIBER_TIMEOUT_MILLIS: Long = 5_000L

    private val HEARTBEAT_MAGIC = byteArrayOf(
        'D'.code.toByte(),
        'J'.code.toByte(),
        'I'.code.toByte(),
        'H'.code.toByte(),
    )

    private const val DATA_MAGIC_0: Byte = 'D'.code.toByte()
    private const val DATA_MAGIC_1: Byte = 'J'.code.toByte()
    private const val DATA_MAGIC_2: Byte = 'I'.code.toByte()
    private const val DATA_MAGIC_3: Byte = 'D'.code.toByte()

    /**
     * Returns a 4-byte `"DJIH"` discovery heartbeat packet.
     */
    fun buildHeartbeatPacket(): ByteArray = HEARTBEAT_MAGIC.copyOf()

    /**
     * Returns true if the datagram buffer in [buffer] of [length] bytes starts with `"DJIH"`.
     */
    fun isHeartbeatPacket(buffer: ByteArray, length: Int = buffer.size): Boolean {
        if (length < 4) return false
        return buffer[0] == HEARTBEAT_MAGIC[0] &&
            buffer[1] == HEARTBEAT_MAGIC[1] &&
            buffer[2] == HEARTBEAT_MAGIC[2] &&
            buffer[3] == HEARTBEAT_MAGIC[3]
    }

    /**
     * Splits [payload] into one or more `"DJID"` UDP datagrams of at most [MAX_UDP_PAYLOAD_BYTES]
     * payload bytes each, using [nextSequenceNumber] to assign a monotonically increasing uint32
     * sequence number to each datagram.
     */
    fun packetizePayload(
        channelType: Int,
        payload: ByteArray,
        nextSequenceNumber: () -> Int,
    ): List<ByteArray> {
        if (payload.isEmpty()) return emptyList()
        val datagrams = ArrayList<ByteArray>((payload.size + MAX_UDP_PAYLOAD_BYTES - 1) / MAX_UDP_PAYLOAD_BYTES)
        var offset = 0
        while (offset < payload.size) {
            val chunkLength = minOf(MAX_UDP_PAYLOAD_BYTES, payload.size - offset)
            val sequenceNumber = nextSequenceNumber()
            val packet = ByteArray(DATA_HEADER_BYTES + chunkLength)
            val headerBuffer = ByteBuffer.wrap(packet, 0, DATA_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            headerBuffer.put(0, DATA_MAGIC_0)
            headerBuffer.put(1, DATA_MAGIC_1)
            headerBuffer.put(2, DATA_MAGIC_2)
            headerBuffer.put(3, DATA_MAGIC_3)
            headerBuffer.put(4, (channelType and 0xFF).toByte())
            headerBuffer.put(5, 0)
            headerBuffer.putInt(6, sequenceNumber)
            headerBuffer.putShort(10, (chunkLength and 0xFFFF).toShort())
            payload.copyInto(
                destination = packet,
                destinationOffset = DATA_HEADER_BYTES,
                startIndex = offset,
                endIndex = offset + chunkLength,
            )
            datagrams.add(packet)
            offset += chunkLength
        }
        return datagrams
    }

    /**
     * Parses a received `"DJID"` UDP datagram of [length] bytes from [buffer].
     * Returns null if the packet is malformed or does not start with `"DJID"`.
     */
    fun tryParseDataPacket(buffer: ByteArray, length: Int = buffer.size): UdpDataPacket? {
        if (length < DATA_HEADER_BYTES) return null
        if (buffer[0] != DATA_MAGIC_0 ||
            buffer[1] != DATA_MAGIC_1 ||
            buffer[2] != DATA_MAGIC_2 ||
            buffer[3] != DATA_MAGIC_3
        ) {
            return null
        }
        val headerBuffer = ByteBuffer.wrap(buffer, 0, DATA_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val channelType = headerBuffer.get(4).toInt() and 0xFF
        val sequenceNumber = headerBuffer.getInt(6).toLong() and 0xFFFF_FFFFL
        val payloadLength = headerBuffer.getShort(10).toInt() and 0xFFFF
        if (payloadLength <= 0 || DATA_HEADER_BYTES + payloadLength > length) {
            return null
        }
        val payload = buffer.copyOfRange(DATA_HEADER_BYTES, DATA_HEADER_BYTES + payloadLength)
        return UdpDataPacket(
            channelType = channelType,
            sequenceNumber = sequenceNumber,
            payload = payload,
        )
    }

    /**
     * Queries active non-loopback IPv4 network interfaces on this Android device and returns
     * their local IPv4 addresses and subnet broadcast addresses.
     */
    fun queryLocalIpv4Addresses(): List<LocalIpv4Endpoint> {
        val endpoints = mutableListOf<LocalIpv4Endpoint>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val inetAddress = interfaceAddress.address
                    if (inetAddress is Inet4Address && !inetAddress.isLoopbackAddress) {
                        val hostAddress = inetAddress.hostAddress ?: continue
                        val broadcastAddress = interfaceAddress.broadcast?.hostAddress
                        endpoints.add(
                            LocalIpv4Endpoint(
                                interfaceName = networkInterface.name ?: "net",
                                ipv4Address = hostAddress,
                                broadcastAddress = broadcastAddress,
                            ),
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore network interface enumeration errors.
        }
        return endpoints
    }
}

/**
 * Parsed `"DJID"` UDP stream packet.
 */
data class UdpDataPacket(
    val channelType: Int,
    val sequenceNumber: Long,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UdpDataPacket) return false
        return channelType == other.channelType &&
            sequenceNumber == other.sequenceNumber &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = channelType
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Represents a local IPv4 interface address and its associated subnet broadcast address.
 */
data class LocalIpv4Endpoint(
    val interfaceName: String,
    val ipv4Address: String,
    val broadcastAddress: String?,
)
