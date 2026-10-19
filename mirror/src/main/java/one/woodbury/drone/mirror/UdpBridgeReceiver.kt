package one.woodbury.drone.mirror

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.woodbury.drone.net.UdpBridgeProtocol
import one.woodbury.drone.protocol.DumlFrame
import one.woodbury.drone.protocol.DumlParseOutcome
import one.woodbury.drone.protocol.DumlProtocol
import one.woodbury.drone.protocol.FlightControllerTelemetry
import one.woodbury.drone.protocol.RemoteControllerAxes
import one.woodbury.drone.video.H264VideoDecoder
import one.woodbury.drone.video.VideoDecoderMetrics

/**
 * Immutable UI state for the Meta Quest 2 Viewer (`:mirror`).
 */
data class MirrorReceiverState(
    val isListening: Boolean = false,
    val localIpv4Addresses: List<String> = emptyList(),
    val phoneBridgeIp: String? = null,
    val udpPacketsReceived: Long = 0L,
    val udpBytesReceived: Long = 0L,
    val udpSequenceDrops: Long = 0L,
    val dumlFramesReceived: Long = 0L,
    val lastSourceModule: String? = null,
    val remoteControllerAxes: RemoteControllerAxes? = null,
    val flightControllerTelemetry: FlightControllerTelemetry? = null,
    val videoDecoderMetrics: VideoDecoderMetrics = VideoDecoderMetrics(),
)

/**
 * Broadcasts `"DJIH"` discovery heartbeats on UDP port [UdpBridgeProtocol.UDP_BRIDGE_PORT] (`5600`)
 * and receives `"DJID"` H.264 video and DUML v1 telemetry datagrams from the phone bridge (`:app`).
 */
class UdpBridgeReceiver {

    private val isRunning = AtomicBoolean(false)

    private val _receiverState = MutableStateFlow(MirrorReceiverState())
    val receiverState: StateFlow<MirrorReceiverState> = _receiverState.asStateFlow()

    val videoDecoder: H264VideoDecoder = H264VideoDecoder { metrics ->
        _receiverState.update { currentState ->
            currentState.copy(videoDecoderMetrics = metrics)
        }
    }

    @Volatile
    private var udpSocket: DatagramSocket? = null

    @Volatile
    private var receiverThread: Thread? = null

    @Volatile
    private var heartbeatScheduler: ScheduledExecutorService? = null

    @Volatile
    private var latestPhoneInetAddress: InetAddress? = null

    /**
     * Opens UDP port `5600`, starts the periodic `"DJIH"` discovery broadcaster, and starts
     * the `"DJID"` stream receiver thread.
     */
    @Synchronized
    fun start() {
        if (isRunning.get()) return
        try {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                receiveBufferSize = 2 * 1024 * 1024
                soTimeout = 1_000
                bind(InetSocketAddress(UdpBridgeProtocol.UDP_BRIDGE_PORT))
            }
            udpSocket = socket
            isRunning.set(true)
            refreshLocalInterfaces()

            val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "dji-mirror-heartbeat").apply { isDaemon = true }
            }
            heartbeatScheduler = scheduler
            scheduler.scheduleAtFixedRate(
                { sendDiscoveryHeartbeats(socket) },
                0L,
                1_000L,
                TimeUnit.MILLISECONDS,
            )

            receiverThread = Thread({ runReceiveLoop(socket) }, "dji-mirror-receiver").apply {
                isDaemon = true
                start()
            }
        } catch (_: Exception) {
            isRunning.set(false)
            refreshLocalInterfaces()
        }
    }

    /**
     * Stops the UDP receiver, heartbeat scheduler, and hardware video decoder.
     */
    @Synchronized
    fun shutdown() {
        isRunning.set(false)
        heartbeatScheduler?.shutdownNow()
        heartbeatScheduler = null
        receiverThread?.interrupt()
        receiverThread = null

        val socketToClose = udpSocket
        udpSocket = null
        socketToClose?.close()
        videoDecoder.shutdown()
    }

    private fun sendDiscoveryHeartbeats(socket: DatagramSocket) {
        if (!isRunning.get()) return
        val heartbeatBytes = UdpBridgeProtocol.buildHeartbeatPacket()
        val targetAddresses = LinkedHashSet<String>()

        val endpoints = UdpBridgeProtocol.queryLocalIpv4Addresses()
        for (endpoint in endpoints) {
            if (!endpoint.broadcastAddress.isNullOrBlank()) {
                targetAddresses.add(endpoint.broadcastAddress!!)
            }
            // Also include the .1 subnet gateway in case the phone is acting as a Wi-Fi hotspot.
            val octets = endpoint.ipv4Address.split('.')
            if (octets.size == 4) {
                targetAddresses.add("${octets[0]}.${octets[1]}.${octets[2]}.1")
            }
        }
        targetAddresses.add("255.255.255.255")
        latestPhoneInetAddress?.hostAddress?.let { targetAddresses.add(it) }

        for (host in targetAddresses) {
            try {
                val destination = InetAddress.getByName(host)
                val packet = DatagramPacket(
                    heartbeatBytes,
                    heartbeatBytes.size,
                    destination,
                    UdpBridgeProtocol.UDP_BRIDGE_PORT,
                )
                socket.send(packet)
            } catch (_: Exception) {
                // Ignore unreachable broadcast or gateway targets.
            }
        }
    }

    private fun runReceiveLoop(socket: DatagramSocket) {
        val receiveBuffer = ByteArray(2048)
        val datagramPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
        var lastSequenceNumber: Long? = null

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                socket.receive(datagramPacket)
                val length = datagramPacket.length
                if (UdpBridgeProtocol.isHeartbeatPacket(receiveBuffer, length)) {
                    continue
                }
                val dataPacket = UdpBridgeProtocol.tryParseDataPacket(receiveBuffer, length) ?: continue
                val senderAddress = datagramPacket.address
                if (senderAddress != null) {
                    latestPhoneInetAddress = senderAddress
                }
                val senderHost = senderAddress?.hostAddress

                val sequenceDrops = if (lastSequenceNumber != null) {
                    val expectedSequence = (lastSequenceNumber + 1L) and 0xFFFF_FFFFL
                    if (dataPacket.sequenceNumber > expectedSequence) {
                        dataPacket.sequenceNumber - expectedSequence
                    } else {
                        0L
                    }
                } else {
                    0L
                }
                lastSequenceNumber = dataPacket.sequenceNumber

                when (dataPacket.channelType) {
                    UdpBridgeProtocol.CHANNEL_VIDEO -> {
                        videoDecoder.feedRawVideoChunk(dataPacket.payload)
                        _receiverState.update { currentState ->
                            currentState.copy(
                                phoneBridgeIp = senderHost ?: currentState.phoneBridgeIp,
                                udpPacketsReceived = currentState.udpPacketsReceived + 1L,
                                udpBytesReceived = currentState.udpBytesReceived + length,
                                udpSequenceDrops = currentState.udpSequenceDrops + sequenceDrops,
                            )
                        }
                    }
                    UdpBridgeProtocol.CHANNEL_DUML -> {
                        val outcome = DumlProtocol.tryParseDumlFrame(
                            buffer = dataPacket.payload,
                            offset = 0,
                            availableLength = dataPacket.payload.size,
                        )
                        if (outcome is DumlParseOutcome.Parsed) {
                            handleParsedDumlFrame(
                                frame = outcome.frame,
                                senderHost = senderHost,
                                packetBytes = length,
                                sequenceDrops = sequenceDrops,
                            )
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {
                refreshLocalInterfaces()
            } catch (_: Exception) {
                if (!isRunning.get()) break
            }
        }
    }

    private fun handleParsedDumlFrame(
        frame: DumlFrame,
        senderHost: String?,
        packetBytes: Int,
        sequenceDrops: Long,
    ) {
        val sourceLabel = DumlProtocol.formatModuleName(frame.sourceModuleId)
        val decodedOsd = DumlProtocol.decodeFlightControllerOsdTelemetry(frame)
        val decodedAxes = DumlProtocol.decodeRemoteControllerAxes(frame)

        _receiverState.update { currentState ->
            currentState.copy(
                phoneBridgeIp = senderHost ?: currentState.phoneBridgeIp,
                udpPacketsReceived = currentState.udpPacketsReceived + 1L,
                udpBytesReceived = currentState.udpBytesReceived + packetBytes,
                udpSequenceDrops = currentState.udpSequenceDrops + sequenceDrops,
                dumlFramesReceived = currentState.dumlFramesReceived + 1L,
                lastSourceModule = sourceLabel,
                remoteControllerAxes = decodedAxes ?: currentState.remoteControllerAxes,
                flightControllerTelemetry = decodedOsd ?: currentState.flightControllerTelemetry,
            )
        }
    }

    private fun refreshLocalInterfaces() {
        val endpoints = UdpBridgeProtocol.queryLocalIpv4Addresses()
            .map { "${it.ipv4Address} (${it.interfaceName})" }
        _receiverState.update { currentState ->
            currentState.copy(
                isListening = isRunning.get(),
                localIpv4Addresses = endpoints,
            )
        }
    }
}
