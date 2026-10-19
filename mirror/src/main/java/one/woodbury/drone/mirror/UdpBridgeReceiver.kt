package one.woodbury.drone.mirror

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    val socketStatus: String = "Not started",
    val lastSocketError: String? = null,
    val localIpv4Addresses: List<String> = emptyList(),
    val configuredPhoneIp: String = "",
    val phoneBridgeIp: String? = null,
    val heartbeatsSent: Long = 0L,
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
 * Broadcasts and sends unicast `"DJIH"` discovery heartbeats on UDP port
 * [UdpBridgeProtocol.UDP_BRIDGE_PORT] (`5600`), listens for `"DJIB"` bridge announcements
 * from the phone, and receives `"DJID"` H.264 video and DUML v1 telemetry datagrams from
 * the phone bridge (`:app`).
 */
class UdpBridgeReceiver(
    private val applicationContext: Context,
) {

    private val sharedPreferences = applicationContext.getSharedPreferences(
        _preferencesFileName,
        Context.MODE_PRIVATE,
    )

    private val isRunning = AtomicBoolean(false)
    private val heartbeatsSentCounter = AtomicLong(0L)

    @Volatile
    private var manualPhoneIp: String =
        sharedPreferences.getString(_preferencePhoneBridgeIp, "")?.trim().orEmpty()

    @Volatile
    private var socketStatusText: String = "Not started"

    @Volatile
    private var lastErrorText: String? = null

    private val _receiverState = MutableStateFlow(
        MirrorReceiverState(configuredPhoneIp = manualPhoneIp),
    )
    val receiverState: StateFlow<MirrorReceiverState> = _receiverState.asStateFlow()

    val videoDecoder: H264VideoDecoder = H264VideoDecoder { metrics ->
        _receiverState.update { currentState ->
            currentState.copy(videoDecoderMetrics = metrics)
        }
    }

    @Volatile
    private var udpSocket: DatagramSocket? = null

    @Volatile
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var receiverThread: Thread? = null

    @Volatile
    private var heartbeatScheduler: ScheduledExecutorService? = null

    @Volatile
    private var latestPhoneInetAddress: InetAddress? = null

    /**
     * Updates and persists the manual Phone Bridge IPv4 address. Passing an empty string
     * clears the manual override and relies on automatic discovery.
     */
    fun setConfiguredPhoneIp(ipAddress: String) {
        val sanitized = ipAddress.trim()
        manualPhoneIp = sanitized
        sharedPreferences.edit()
            .putString(_preferencePhoneBridgeIp, sanitized)
            .apply()
        if (sanitized.isEmpty()) {
            latestPhoneInetAddress = null
        }
        lastErrorText = null
        _receiverState.update { currentState ->
            currentState.copy(
                configuredPhoneIp = sanitized,
                phoneBridgeIp = if (sanitized.isEmpty()) null else currentState.phoneBridgeIp,
                lastSocketError = null,
            )
        }
        val activeSocket = udpSocket
        val scheduler = heartbeatScheduler
        if (activeSocket != null && scheduler != null && !scheduler.isShutdown) {
            scheduler.execute {
                sendDiscoveryHeartbeats(activeSocket)
                refreshLocalInterfaces()
            }
        }
    }

    /**
     * Opens UDP port `5600`, acquires a Wi-Fi `MulticastLock`, starts the periodic `"DJIH"`
     * discovery broadcaster, and starts the `"DJID"` stream receiver thread.
     */
    @Synchronized
    fun start() {
        if (isRunning.get()) return
        try {
            acquireMulticastLock()
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                receiveBufferSize = 2 * 1024 * 1024
                soTimeout = 1_000
                bind(InetSocketAddress("0.0.0.0", UdpBridgeProtocol.UDP_BRIDGE_PORT))
            }
            val wifiNetwork = UdpBridgeProtocol.findWifiNetwork(applicationContext)
            if (wifiNetwork != null) {
                try {
                    wifiNetwork.bindSocket(socket)
                } catch (_: Exception) {
                    // Proceed with default routing if binding to the Wi-Fi Network fails.
                }
            }
            udpSocket = socket
            isRunning.set(true)
            socketStatusText = if (wifiNetwork != null) {
                "Bound to 0.0.0.0:${UdpBridgeProtocol.UDP_BRIDGE_PORT} (Wi-Fi)"
            } else {
                "Bound to 0.0.0.0:${UdpBridgeProtocol.UDP_BRIDGE_PORT}"
            }
            lastErrorText = null
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
        } catch (exception: Exception) {
            isRunning.set(false)
            socketStatusText = "Bind failed on :${UdpBridgeProtocol.UDP_BRIDGE_PORT}"
            recordSocketError("bind", exception)
            releaseMulticastLock()
            refreshLocalInterfaces()
        }
    }

    /**
     * Stops the UDP receiver, heartbeat scheduler, `MulticastLock`, and hardware video decoder.
     */
    @Synchronized
    fun shutdown() {
        isRunning.set(false)
        socketStatusText = "Stopped"
        heartbeatScheduler?.shutdownNow()
        heartbeatScheduler = null
        receiverThread?.interrupt()
        receiverThread = null

        val socketToClose = udpSocket
        udpSocket = null
        socketToClose?.close()
        releaseMulticastLock()
        videoDecoder.shutdown()
    }

    private fun sendDiscoveryHeartbeats(socket: DatagramSocket) {
        if (!isRunning.get()) return
        val heartbeatBytes = UdpBridgeProtocol.buildHeartbeatPacket()
        val targetAddresses = LinkedHashSet<String>()

        val configuredIp = manualPhoneIp
        if (configuredIp.isNotEmpty()) {
            targetAddresses.add(configuredIp)
        } else {
            val endpoints = UdpBridgeProtocol.queryLocalIpv4Addresses(applicationContext)
            for (endpoint in endpoints) {
                val broadcastAddress = endpoint.broadcastAddress
                if (!broadcastAddress.isNullOrBlank()) {
                    targetAddresses.add(broadcastAddress)
                }
                val gatewayAddress = endpoint.gatewayAddress
                if (!gatewayAddress.isNullOrBlank()) {
                    targetAddresses.add(gatewayAddress)
                }
                // Also include the .1 subnet gateway in case the phone is acting as a Wi-Fi hotspot.
                val octets = endpoint.ipv4Address.split('.')
                if (octets.size == 4) {
                    targetAddresses.add("${octets[0]}.${octets[1]}.${octets[2]}.1")
                }
            }
            if (endpoints.isEmpty()) {
                targetAddresses.add("255.255.255.255")
            }
        }
        latestPhoneInetAddress?.hostAddress?.let { targetAddresses.add(it) }

        var sentAny = false
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
                sentAny = true
            } catch (exception: Exception) {
                if (host == configuredIp) {
                    recordSocketError("send($host)", exception)
                }
            }
        }
        if (sentAny) {
            heartbeatsSentCounter.incrementAndGet()
        }
    }

    private fun sendUnicastHeartbeatTo(socket: DatagramSocket, destination: InetAddress) {
        try {
            val heartbeatBytes = UdpBridgeProtocol.buildHeartbeatPacket()
            val packet = DatagramPacket(
                heartbeatBytes,
                heartbeatBytes.size,
                destination,
                UdpBridgeProtocol.UDP_BRIDGE_PORT,
            )
            socket.send(packet)
            heartbeatsSentCounter.incrementAndGet()
        } catch (exception: Exception) {
            recordSocketError("send(${destination.hostAddress})", exception)
        }
    }

    private fun runReceiveLoop(socket: DatagramSocket) {
        val receiveBuffer = ByteArray(2048)
        val datagramPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
        var lastSequenceNumber: Long? = null

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                // Always reset the DatagramPacket buffer length before receive() because Java's
                // DatagramSocket.receive() shrinks datagramPacket.length to the last packet's size.
                datagramPacket.setData(receiveBuffer, 0, receiveBuffer.size)
                socket.receive(datagramPacket)
                val length = datagramPacket.length
                if (UdpBridgeProtocol.isHeartbeatPacket(receiveBuffer, length)) {
                    continue
                }
                val senderAddress = datagramPacket.address
                val senderHost = senderAddress?.hostAddress

                if (UdpBridgeProtocol.isBridgeAnnouncePacket(receiveBuffer, length)) {
                    val localAddresses = UdpBridgeProtocol.queryLocalIpv4Addresses(applicationContext)
                        .map { it.ipv4Address }
                        .toSet()
                    if (senderAddress != null && senderHost != null && senderHost !in localAddresses) {
                        latestPhoneInetAddress = senderAddress
                        _receiverState.update { currentState ->
                            currentState.copy(
                                phoneBridgeIp = senderHost,
                                heartbeatsSent = heartbeatsSentCounter.get(),
                                lastSocketError = lastErrorText,
                            )
                        }
                        sendUnicastHeartbeatTo(socket, senderAddress)
                    }
                    continue
                }

                val dataPacket = UdpBridgeProtocol.tryParseDataPacket(receiveBuffer, length) ?: continue
                if (senderAddress != null) {
                    latestPhoneInetAddress = senderAddress
                }

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
                                heartbeatsSent = heartbeatsSentCounter.get(),
                                udpPacketsReceived = currentState.udpPacketsReceived + 1L,
                                udpBytesReceived = currentState.udpBytesReceived + length,
                                udpSequenceDrops = currentState.udpSequenceDrops + sequenceDrops,
                                lastSocketError = lastErrorText,
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
            } catch (exception: Exception) {
                if (!isRunning.get()) break
                recordSocketError("receive", exception)
                refreshLocalInterfaces()
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
                heartbeatsSent = heartbeatsSentCounter.get(),
                udpPacketsReceived = currentState.udpPacketsReceived + 1L,
                udpBytesReceived = currentState.udpBytesReceived + packetBytes,
                udpSequenceDrops = currentState.udpSequenceDrops + sequenceDrops,
                dumlFramesReceived = currentState.dumlFramesReceived + 1L,
                lastSourceModule = sourceLabel,
                remoteControllerAxes = decodedAxes ?: currentState.remoteControllerAxes,
                flightControllerTelemetry = decodedOsd ?: currentState.flightControllerTelemetry,
                lastSocketError = lastErrorText,
            )
        }
    }

    private fun recordSocketError(operation: String, exception: Exception) {
        lastErrorText = "$operation: ${exception.javaClass.simpleName}: ${exception.message ?: "error"}"
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager =
                applicationContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifiManager?.createMulticastLock("one.woodbury.drone.mirror.multicast")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
        } catch (_: Exception) {
            // Ignore if multicast lock cannot be acquired.
        }
    }

    private fun releaseMulticastLock() {
        try {
            val lock = multicastLock
            multicastLock = null
            if (lock != null && lock.isHeld) {
                lock.release()
            }
        } catch (_: Exception) {
            // Ignore release errors.
        }
    }

    private fun refreshLocalInterfaces() {
        val endpoints = UdpBridgeProtocol.queryLocalIpv4Addresses(applicationContext)
            .map { "${it.ipv4Address} (${it.interfaceName})" }
        _receiverState.update { currentState ->
            currentState.copy(
                isListening = isRunning.get(),
                socketStatus = socketStatusText,
                lastSocketError = lastErrorText,
                heartbeatsSent = heartbeatsSentCounter.get(),
                localIpv4Addresses = endpoints,
            )
        }
    }

    companion object {
        private const val _preferencesFileName = "one.woodbury.drone.mirror.preferences"
        private const val _preferencePhoneBridgeIp = "preference_phone_bridge_ip"
    }
}
