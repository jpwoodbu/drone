package one.woodbury.drone.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Defines the lightweight UDP framing protocol used between the Phone Bridge (`:bridge`)
 * and the Meta Quest 2 Viewer (`:mirror`) on UDP port [UDP_BRIDGE_PORT].
 *
 * - Viewer Discovery Heartbeat (`"DJIH"`): 4-byte ASCII magic sent by the Quest 2 every 1,000 ms
 *   to the subnet broadcast address (`x.x.x.255:5600`), default gateway, and known phone IP.
 *   The phone records the sender IPv4 address as an active subscriber.
 * - Bridge Discovery Announce (`"DJIB"`): 4-byte ASCII magic broadcast by the phone every 1,000 ms
 *   to the subnet broadcast address (`x.x.x.255:5600`). When the Quest 2 receives `"DJIB"`, it
 *   learns the phone's unicast IP and immediately replies with a unicast `"DJIH"` heartbeat.
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

    /** Service type for mDNS / DNS-SD Network Service Discovery. */
    const val NSD_SERVICE_TYPE: String = "_drone-bridge._udp."

    /** Default service name advertised by the phone bridge over NSD. */
    const val NSD_SERVICE_NAME: String = "Drone-Phone-Bridge"

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

    private val BRIDGE_ANNOUNCE_MAGIC = byteArrayOf(
        'D'.code.toByte(),
        'J'.code.toByte(),
        'I'.code.toByte(),
        'B'.code.toByte(),
    )

    private const val DATA_MAGIC_0: Byte = 'D'.code.toByte()
    private const val DATA_MAGIC_1: Byte = 'J'.code.toByte()
    private const val DATA_MAGIC_2: Byte = 'I'.code.toByte()
    private const val DATA_MAGIC_3: Byte = 'D'.code.toByte()

    /**
     * Returns a 4-byte `"DJIH"` discovery heartbeat packet sent by the Quest 2 viewer.
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
     * Returns a 4-byte `"DJIB"` bridge announce packet sent by the phone bridge.
     */
    fun buildBridgeAnnouncePacket(): ByteArray = BRIDGE_ANNOUNCE_MAGIC.copyOf()

    /**
     * Returns true if the datagram buffer in [buffer] of [length] bytes starts with `"DJIB"`.
     */
    fun isBridgeAnnouncePacket(buffer: ByteArray, length: Int = buffer.size): Boolean {
        if (length < 4) return false
        return buffer[0] == BRIDGE_ANNOUNCE_MAGIC[0] &&
            buffer[1] == BRIDGE_ANNOUNCE_MAGIC[1] &&
            buffer[2] == BRIDGE_ANNOUNCE_MAGIC[2] &&
            buffer[3] == BRIDGE_ANNOUNCE_MAGIC[3]
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
     * Computes the subnet broadcast IPv4 address string (e.g. `"192.168.1.255"`) from a 4-byte
     * IPv4 address and CIDR [prefixLength] (`1..30`).
     */
    fun computeBroadcastIpv4(ipv4Bytes: ByteArray, prefixLength: Int): String? {
        if (ipv4Bytes.size != 4 || prefixLength !in 1..30) return null
        val ipInt = ((ipv4Bytes[0].toInt() and 0xFF) shl 24) or
            ((ipv4Bytes[1].toInt() and 0xFF) shl 16) or
            ((ipv4Bytes[2].toInt() and 0xFF) shl 8) or
            (ipv4Bytes[3].toInt() and 0xFF)
        val netmask = -1 shl (32 - prefixLength)
        val broadcastInt = ipInt or netmask.inv()
        return "%d.%d.%d.%d".format(
            (broadcastInt ushr 24) and 0xFF,
            (broadcastInt ushr 16) and 0xFF,
            (broadcastInt ushr 8) and 0xFF,
            broadcastInt and 0xFF,
        )
    }

    /**
     * Queries active IPv4 network interfaces via [ConnectivityManager] (compatible with
     * Android 11+ / `targetSdk >= 30` where netlink `getNetworkInterfaces()` is restricted).
     */
    fun queryLocalIpv4Addresses(context: Context): List<LocalIpv4Endpoint> {
        val endpointsByIp = LinkedHashMap<String, LocalIpv4Endpoint>()
        try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return emptyList()

            val candidateNetworks = LinkedHashSet<Network>()
            connectivityManager.activeNetwork?.let { candidateNetworks.add(it) }
            @Suppress("DEPRECATION")
            connectivityManager.allNetworks.forEach { candidateNetworks.add(it) }

            for (network in candidateNetworks) {
                val linkProperties = connectivityManager.getLinkProperties(network) ?: continue
                collectEndpointsFromLinkProperties(linkProperties, endpointsByIp)
            }
        } catch (_: Exception) {
            // Ignore network query exceptions.
        }
        return endpointsByIp.values.toList()
    }

    /**
     * Finds the active Wi-Fi [Network] so a `DatagramSocket` can be explicitly bound to `wlan0`
     * via [Network.bindSocket], bypassing cellular data or VPN default routes.
     */
    fun findWifiNetwork(context: Context): Network? {
        try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return null
            val candidateNetworks = LinkedHashSet<Network>()
            connectivityManager.activeNetwork?.let { candidateNetworks.add(it) }
            @Suppress("DEPRECATION")
            connectivityManager.allNetworks.forEach { candidateNetworks.add(it) }

            for (network in candidateNetworks) {
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return network
                }
            }
        } catch (_: Exception) {
            // Ignore network lookup errors.
        }
        return null
    }

    private fun collectEndpointsFromLinkProperties(
        linkProperties: LinkProperties,
        output: LinkedHashMap<String, LocalIpv4Endpoint>,
    ) {
        val interfaceName = linkProperties.interfaceName ?: "net"
        val gatewayAddress = linkProperties.routes
            .mapNotNull { route -> route.gateway }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { gateway -> !gateway.isLoopbackAddress && !gateway.isAnyLocalAddress }
            ?.hostAddress

        for (linkAddress in linkProperties.linkAddresses) {
            val address: InetAddress = linkAddress.address
            if (address is Inet4Address && !address.isLoopbackAddress) {
                val hostAddress = address.hostAddress ?: continue
                val broadcastAddress = computeBroadcastIpv4(address.address, linkAddress.prefixLength)
                output[hostAddress] = LocalIpv4Endpoint(
                    interfaceName = interfaceName,
                    ipv4Address = hostAddress,
                    broadcastAddress = broadcastAddress,
                    gatewayAddress = gatewayAddress,
                )
            }
        }
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
 * Represents a local IPv4 interface address and its associated subnet broadcast and gateway addresses.
 */
data class LocalIpv4Endpoint(
    val interfaceName: String,
    val ipv4Address: String,
    val broadcastAddress: String?,
    val gatewayAddress: String? = null,
)
