package one.woodbury.drone.mirror

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
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
import one.woodbury.drone.net.NsdBridgeClient
import one.woodbury.drone.net.UdpBridgeProtocol
import one.woodbury.drone.protocol.BatteryTelemetry
import one.woodbury.drone.protocol.CameraTelemetry
import one.woodbury.drone.protocol.DumlFrame
import one.woodbury.drone.protocol.DumlParseOutcome
import one.woodbury.drone.protocol.DumlProtocol
import one.woodbury.drone.protocol.FlightControllerTelemetry
import one.woodbury.drone.protocol.GimbalTelemetry
import one.woodbury.drone.protocol.RemoteControllerAxes
import one.woodbury.drone.video.H264VideoDecoder
import one.woodbury.drone.video.VideoDecoderMetrics

/**
 * Immutable UI state for the Meta Quest 2 Viewer (`:mirror`).
 */
data class MirrorReceiverState(
    val isListening: Boolean = false,
    val socketStatus: String = "Not started",
    val nsdStatus: String = "Searching...",
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
    val batteryTelemetry: BatteryTelemetry? = null,
    val gimbalTelemetry: GimbalTelemetry? = null,
    val cameraTelemetry: CameraTelemetry? = null,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val isHudEnabled: Boolean = false,
    val hudOpacity: Float = 0.85f,
    val isGridOverlayEnabled: Boolean = false,
    val videoDecoderMetrics: VideoDecoderMetrics = VideoDecoderMetrics(),
    val homeLatitudeDegrees: Double? = null,
    val homeLongitudeDegrees: Double? = null,
) {
    /**
     * True if the receiver has previously decoded frames from a connected bridge,
     * but packet reception has ceased and the app is actively attempting to reconnect.
     */
    val isReconnecting: Boolean
        get() = phoneBridgeIp == null && videoDecoderMetrics.framesDecoded > 0L

    /**
     * True if an active stream is currently established with the phone bridge.
     */
    val isConnected: Boolean
        get() = phoneBridgeIp != null
}

/**
 * Sends unicast `"DBHB"` discovery heartbeats on UDP port [UdpBridgeProtocol.UDP_BRIDGE_PORT] (`5600`),
 * listens for `"DBAN"` bridge announcements from the phone, and receives `"DBDA"` H.264 video
 * and DUML v1 telemetry datagrams from the phone bridge (`:bridge`).
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
    private val lastPacketReceivedTimestampMillis = AtomicLong(0L)
    private val udpPacketsReceivedCounter = AtomicLong(0L)
    private val udpBytesReceivedCounter = AtomicLong(0L)
    private val udpSequenceDropsCounter = AtomicLong(0L)
    private val dumlFramesReceivedCounter = AtomicLong(0L)

    @Volatile
    private var manualPhoneIp: String =
        sharedPreferences.getString(_preferencePhoneBridgeIp, "")?.trim().orEmpty()

    @Volatile
    private var currentUnitSystem: UnitSystem = run {
        val saved = sharedPreferences.getString(_preferenceUnitSystem, null)
        if (saved != null) {
            try {
                UnitSystem.valueOf(saved)
            } catch (_: IllegalArgumentException) {
                UnitSystem.METRIC
            }
        } else {
            val legacy = sharedPreferences.getString(_preferenceAltitudeUnit, null)
            if (legacy == "FEET") UnitSystem.IMPERIAL else UnitSystem.METRIC
        }
    }

    @Volatile
    private var currentHudEnabled: Boolean =
        sharedPreferences.getBoolean(_preferenceHudEnabled, false)

    @Volatile
    private var currentHudOpacity: Float =
        sharedPreferences.getFloat(_preferenceHudOpacity, 0.85f).coerceIn(0.2f, 1.0f)

    @Volatile
    private var socketStatusText: String = "Not started"

    @Volatile
    private var lastErrorText: String? = null

    @Volatile
    private var lastCButtonPressed: Boolean = false

    private val _receiverState = MutableStateFlow(
        MirrorReceiverState(
            configuredPhoneIp = manualPhoneIp,
            unitSystem = currentUnitSystem,
            isHudEnabled = currentHudEnabled,
            hudOpacity = currentHudOpacity,
        ),
    )
    val receiverState: StateFlow<MirrorReceiverState> = _receiverState.asStateFlow()

    @Volatile
    private var latestVideoDecoderMetrics: VideoDecoderMetrics = VideoDecoderMetrics()

    val videoDecoder: H264VideoDecoder = H264VideoDecoder { metrics ->
        val previousFramesDecoded = latestVideoDecoderMetrics.framesDecoded
        latestVideoDecoderMetrics = metrics
        if (previousFramesDecoded == 0L && metrics.framesDecoded > 0L) {
            _receiverState.update { currentState ->
                currentState.copy(videoDecoderMetrics = metrics)
            }
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
    private var nsdClient: NsdBridgeClient? = null

    @Volatile
    private var latestPhoneInetAddress: InetAddress? = null

    @Volatile
    private var lastKnownPhoneAddress: InetAddress? = null

    private val disconnectedCyclesCounter = AtomicLong(0L)

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
            lastKnownPhoneAddress = null
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
     * Updates and persists the display measurement unit system for flight telemetry.
     */
    fun setUnitSystem(unitSystem: UnitSystem) {
        currentUnitSystem = unitSystem
        sharedPreferences.edit()
            .putString(_preferenceUnitSystem, unitSystem.name)
            .apply()
        _receiverState.update { currentState ->
            currentState.copy(unitSystem = unitSystem)
        }
    }

    /**
     * Updates and persists whether the aircraft HUD overlay is enabled.
     */
    fun setHudEnabled(enabled: Boolean) {
        currentHudEnabled = enabled
        sharedPreferences.edit()
            .putBoolean(_preferenceHudEnabled, enabled)
            .apply()
        _receiverState.update { currentState ->
            currentState.copy(isHudEnabled = enabled)
        }
    }

    /**
     * Updates and persists the opacity of the aircraft HUD overlay.
     */
    fun setHudOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(0.2f, 1.0f)
        currentHudOpacity = clamped
        sharedPreferences.edit()
            .putFloat(_preferenceHudOpacity, clamped)
            .apply()
        _receiverState.update { currentState ->
            currentState.copy(hudOpacity = clamped)
        }
    }

    /**
     * Toggles whether the rule-of-thirds photograph grid overlay is displayed over the live video.
     */
    fun toggleGridOverlay() {
        _receiverState.update { currentState ->
            currentState.copy(isGridOverlayEnabled = !currentState.isGridOverlayEnabled)
        }
    }

    /**
     * Opens UDP port `5600`, acquires a Wi-Fi `MulticastLock`, starts the periodic `"DBHB"`
     * discovery broadcaster, and starts the `"DBDA"` stream receiver thread.
     */
    @Synchronized
    fun start() {
        if (isRunning.get()) return
        lastPacketReceivedTimestampMillis.set(0L)
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
                {
                    sendDiscoveryHeartbeats(socket)
                    checkConnectionTimeout()
                },
                0L,
                1_000L,
                TimeUnit.MILLISECONDS,
            )

            receiverThread = Thread({ runReceiveLoop(socket) }, "dji-mirror-receiver").apply {
                isDaemon = true
                start()
            }

            val client = NsdBridgeClient(
                context = applicationContext,
                onDeviceResolved = { hostAddress, _ ->
                    try {
                        val inetAddress = InetAddress.getByName(hostAddress)
                        latestPhoneInetAddress = inetAddress
                        lastKnownPhoneAddress = inetAddress
                        _receiverState.update { currentState ->
                            currentState.copy(
                                phoneBridgeIp = hostAddress,
                                nsdStatus = "Resolved $hostAddress via mDNS",
                            )
                        }
                        val activeSocket = udpSocket
                        if (activeSocket != null) {
                            sendUnicastHeartbeatTo(activeSocket, inetAddress)
                        }
                    } catch (_: Exception) {
                        // Ignore resolution errors.
                    }
                },
                onStatusChanged = { status ->
                    _receiverState.update { currentState ->
                        currentState.copy(nsdStatus = status)
                    }
                },
            )
            nsdClient = client
            client.startDiscovery()
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
        lastPacketReceivedTimestampMillis.set(0L)
        latestPhoneInetAddress = null
        lastKnownPhoneAddress = null
        disconnectedCyclesCounter.set(0L)
        socketStatusText = "Stopped"
        nsdClient?.stopDiscovery()
        nsdClient = null
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
        }

        val activePhoneIp = _receiverState.value.phoneBridgeIp
        if (activePhoneIp != null) {
            targetAddresses.add(activePhoneIp)
        } else {
            // When disconnected or searching, probe the last-known phone address if available.
            lastKnownPhoneAddress?.hostAddress?.let { targetAddresses.add(it) }

            // Also broadcast to all local subnets to re-establish contact with the bridge.
            val localEndpoints = UdpBridgeProtocol.queryLocalIpv4Addresses(applicationContext)
            for (endpoint in localEndpoints) {
                endpoint.broadcastAddress?.let { targetAddresses.add(it) }
                endpoint.gatewayAddress?.let { targetAddresses.add(it) }
            }
        }

        if (targetAddresses.isEmpty()) return

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
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Exception) {
            // Fallback to default thread priority.
        }
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
                        lastPacketReceivedTimestampMillis.set(SystemClock.elapsedRealtime())
                        latestPhoneInetAddress = senderAddress
                        lastKnownPhoneAddress = senderAddress
                        _receiverState.update { currentState ->
                            currentState.copy(
                                phoneBridgeIp = senderHost,
                                heartbeatsSent = heartbeatsSentCounter.get(),
                                lastSocketError = lastErrorText,
                            )
                        }
                    }
                    continue
                }

                val dataPacket = UdpBridgeProtocol.tryParseDataPacket(receiveBuffer, length) ?: continue
                lastPacketReceivedTimestampMillis.set(SystemClock.elapsedRealtime())
                if (senderAddress != null) {
                    latestPhoneInetAddress = senderAddress
                    lastKnownPhoneAddress = senderAddress
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

                udpPacketsReceivedCounter.incrementAndGet()
                udpBytesReceivedCounter.addAndGet(length.toLong())
                if (sequenceDrops > 0L) {
                    udpSequenceDropsCounter.addAndGet(sequenceDrops)
                }

                when (dataPacket.channelType) {
                    UdpBridgeProtocol.CHANNEL_VIDEO -> {
                        videoDecoder.feedRawVideoChunk(dataPacket.payload)
                    }
                    UdpBridgeProtocol.CHANNEL_DUML -> {
                        val outcome = DumlProtocol.tryParseDumlFrame(
                            buffer = dataPacket.payload,
                            offset = 0,
                            availableLength = dataPacket.payload.size,
                        )
                        if (outcome is DumlParseOutcome.Parsed) {
                            dumlFramesReceivedCounter.incrementAndGet()
                            handleParsedDumlFrame(
                                frame = outcome.frame,
                                senderHost = senderHost,
                            )
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {
                checkConnectionTimeout()
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
    ) {
        val sourceLabel = DumlProtocol.formatModuleName(frame.sourceModuleId)
        val decodedOsd = DumlProtocol.decodeFlightControllerOsdTelemetry(frame)
        val decodedBattery = DumlProtocol.decodeBatteryTelemetry(frame)
        val decodedAxes = DumlProtocol.decodeRemoteControllerAxes(frame)
        val decodedGimbal = DumlProtocol.decodeGimbalTelemetry(frame)
        val decodedCamera = DumlProtocol.decodeCameraTelemetry(frame)

        if (decodedAxes != null) {
            val isPressed = decodedAxes.isCButtonPressed
            if (isPressed && !lastCButtonPressed) {
                _receiverState.update { currentState ->
                    currentState.copy(isGridOverlayEnabled = !currentState.isGridOverlayEnabled)
                }
            }
            lastCButtonPressed = isPressed
        }

        _receiverState.update { currentState ->
            val updatedBattery = when {
                currentState.batteryTelemetry != null && decodedBattery != null -> {
                    currentState.batteryTelemetry.mergeWith(decodedBattery)
                }
                decodedBattery != null -> decodedBattery
                else -> currentState.batteryTelemetry
            }
            val bmsPercentage = updatedBattery?.percentage
            val effectiveBatteryPercentage = when {
                bmsPercentage != null && bmsPercentage in 0..100 -> bmsPercentage
                decodedOsd != null && decodedOsd.batteryPercentage in 1..100 -> decodedOsd.batteryPercentage
                else -> currentState.flightControllerTelemetry?.batteryPercentage ?: 0
            }
            val updatedTelemetry = when {
                decodedOsd != null -> decodedOsd.copy(batteryPercentage = effectiveBatteryPercentage)
                effectiveBatteryPercentage > 0 && currentState.flightControllerTelemetry != null -> {
                    currentState.flightControllerTelemetry.copy(batteryPercentage = effectiveBatteryPercentage)
                }
                else -> currentState.flightControllerTelemetry
            }
            val updatedHomeLat = currentState.homeLatitudeDegrees ?: if (
                decodedOsd != null &&
                decodedOsd.latitudeDegrees != 0.0 &&
                (decodedOsd.motorsRunning || decodedOsd.aircraftInAir || decodedOsd.gpsSatellites >= 6)
            ) {
                decodedOsd.latitudeDegrees
            } else {
                null
            }
            val updatedHomeLon = currentState.homeLongitudeDegrees ?: if (
                decodedOsd != null &&
                decodedOsd.longitudeDegrees != 0.0 &&
                (decodedOsd.motorsRunning || decodedOsd.aircraftInAir || decodedOsd.gpsSatellites >= 6)
            ) {
                decodedOsd.longitudeDegrees
            } else {
                null
            }

            currentState.copy(
                phoneBridgeIp = senderHost ?: currentState.phoneBridgeIp,
                heartbeatsSent = heartbeatsSentCounter.get(),
                udpPacketsReceived = udpPacketsReceivedCounter.get(),
                udpBytesReceived = udpBytesReceivedCounter.get(),
                udpSequenceDrops = udpSequenceDropsCounter.get(),
                dumlFramesReceived = dumlFramesReceivedCounter.get(),
                videoDecoderMetrics = latestVideoDecoderMetrics,
                lastSourceModule = sourceLabel,
                remoteControllerAxes = decodedAxes ?: currentState.remoteControllerAxes,
                flightControllerTelemetry = updatedTelemetry,
                batteryTelemetry = updatedBattery,
                gimbalTelemetry = decodedGimbal ?: currentState.gimbalTelemetry,
                cameraTelemetry = decodedCamera ?: currentState.cameraTelemetry,
                homeLatitudeDegrees = updatedHomeLat,
                homeLongitudeDegrees = updatedHomeLon,
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

    /**
     * Checks if packet reception has ceased from the phone bridge.
     *
     * If no announce or data packets have arrived within [CONNECTION_INACTIVITY_TIMEOUT_MILLIS],
     * the active connection state is reset to disconnected (`phoneBridgeIp = null`).
     */
    private fun checkConnectionTimeout() {
        val lastReceivedTimestampMillis = lastPacketReceivedTimestampMillis.get()
        val now = SystemClock.elapsedRealtime()
        if (lastReceivedTimestampMillis > 0L &&
            (now - lastReceivedTimestampMillis) > CONNECTION_INACTIVITY_TIMEOUT_MILLIS
        ) {
            val wasConnected = _receiverState.value.phoneBridgeIp != null
            if (wasConnected) {
                latestPhoneInetAddress = null
                videoDecoder.resetStream()
                _receiverState.update { currentState ->
                    currentState.copy(
                        phoneBridgeIp = null,
                        lastSocketError = lastErrorText,
                    )
                }
            }
            val cycles = disconnectedCyclesCounter.incrementAndGet()
            // Periodically restart mDNS discovery every 10 seconds while disconnected to refresh service resolutions.
            if (cycles % 10L == 0L) {
                nsdClient?.restartDiscovery()
            }
        } else if (lastReceivedTimestampMillis > 0L) {
            disconnectedCyclesCounter.set(0L)
            // Periodically refresh transport and decoder metrics when active if telemetry has paused.
            _receiverState.update { currentState ->
                currentState.copy(
                    heartbeatsSent = heartbeatsSentCounter.get(),
                    udpPacketsReceived = udpPacketsReceivedCounter.get(),
                    udpBytesReceived = udpBytesReceivedCounter.get(),
                    udpSequenceDrops = udpSequenceDropsCounter.get(),
                    dumlFramesReceived = dumlFramesReceivedCounter.get(),
                    videoDecoderMetrics = latestVideoDecoderMetrics,
                )
            }
        }
    }

    companion object {
        private const val CONNECTION_INACTIVITY_TIMEOUT_MILLIS: Long = 2_500L
        private const val _preferencesFileName = "one.woodbury.drone.mirror.preferences"
        private const val _preferencePhoneBridgeIp = "preference_phone_bridge_ip"
        private const val _preferenceUnitSystem = "preference_unit_system"
        private const val _preferenceAltitudeUnit = "preference_altitude_unit"
        private const val _preferenceHudEnabled = "preference_hud_enabled"
        private const val _preferenceHudOpacity = "preference_hud_opacity"
    }
}
