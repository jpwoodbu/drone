package one.woodbury.drone.net

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Snapshot of the Phone-to-Quest 2 UDP bridge status displayed in the `:app` UI.
 */
data class UdpBridgeSenderMetrics(
    val isListening: Boolean = false,
    val socketStatus: String = "Stopped",
    val nsdStatus: String = "Not registered",
    val lastSocketError: String? = null,
    val localIpv4Addresses: List<String> = emptyList(),
    val configuredQuestIp: String = "",
    val activeSubscribers: List<String> = emptyList(),
    val announcePacketsSent: Long = 0L,
    val udpPacketsSent: Long = 0L,
    val udpBytesSent: Long = 0L,
    val heartbeatsReceived: Long = 0L,
)

/**
 * Listens on UDP port [UdpBridgeProtocol.UDP_BRIDGE_PORT] (`5600`) for `"DJIH"` discovery
 * heartbeats from the Meta Quest 2 (`:mirror`), sends periodic `"DJIB"` bridge announcements
 * to active subscribers or a configured Quest 2 IP, and forwards MTU-safe
 * `"DJID"` H.264 video and DUML v1 telemetry packets to the Quest 2.
 */
class UdpBridgeSender(
    private val applicationContext: Context,
    private val onMetricsChanged: (UdpBridgeSenderMetrics) -> Unit,
) {
    private val sharedPreferences = applicationContext.getSharedPreferences(
        _preferencesFileName,
        Context.MODE_PRIVATE,
    )

    private val isRunning = AtomicBoolean(false)
    private val sequenceCounter = AtomicInteger(1)
    private val outgoingDatagramQueue = LinkedBlockingQueue<ByteArray>(1024)
    private val subscribersByHost = ConcurrentHashMap<String, SubscriberEntry>()

    private val announceSentCounter = AtomicLong(0L)
    private val packetsSentCounter = AtomicLong(0L)
    private val bytesSentCounter = AtomicLong(0L)
    private val heartbeatsReceivedCounter = AtomicLong(0L)

    @Volatile
    private var manualQuestIp: String =
        sharedPreferences.getString(_preferenceQuestViewerIp, "")?.trim().orEmpty()

    @Volatile
    private var manualQuestInetAddress: InetAddress? = null

    @Volatile
    private var socketStatusText: String = "Not started"

    @Volatile
    private var lastErrorText: String? = null

    @Volatile
    private var nsdStatusText: String = "Not registered"

    @Volatile
    private var nsdServer: NsdBridgeServer? = null

    @Volatile
    private var udpSocket: DatagramSocket? = null

    @Volatile
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var listenerThread: Thread? = null

    @Volatile
    private var senderThread: Thread? = null

    @Volatile
    private var announceScheduler: ScheduledExecutorService? = null

    private data class SubscriberEntry(
        val address: InetAddress,
        @Volatile var lastSeenEpochMillis: Long,
    )

    init {
        publishMetrics()
    }

    /**
     * Updates and persists the manual Quest 2 IPv4 address. Passing an empty string clears
     * the manual IP and relies on automatic discovery.
     */
    fun setConfiguredQuestIp(ipAddress: String) {
        val sanitized = ipAddress.trim()
        manualQuestIp = sanitized
        manualQuestInetAddress = null
        sharedPreferences.edit()
            .putString(_preferenceQuestViewerIp, sanitized)
            .apply()
        lastErrorText = null
        publishMetrics()

        val activeSocket = udpSocket
        val scheduler = announceScheduler
        if (activeSocket != null && scheduler != null && !scheduler.isShutdown) {
            scheduler.execute {
                resolveManualQuestAddressIfNeeded()
                broadcastBridgeAnnounce(activeSocket)
                publishMetrics()
            }
        }
    }

    /**
     * Opens UDP port `5600`, acquires a Wi-Fi `MulticastLock`, and starts the discovery
     * and packet sender threads.
     */
    @Synchronized
    fun start() {
        if (isRunning.get()) return
        try {
            acquireMulticastLock()
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
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

            val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "dji-udp-announce").apply { isDaemon = true }
            }
            announceScheduler = scheduler
            scheduler.scheduleAtFixedRate(
                {
                    resolveManualQuestAddressIfNeeded()
                    broadcastBridgeAnnounce(socket)
                },
                0L,
                1_000L,
                TimeUnit.MILLISECONDS,
            )

            listenerThread = Thread({ runDiscoveryListenerLoop(socket) }, "dji-udp-discovery").apply {
                isDaemon = true
                start()
            }
            senderThread = Thread({ runSenderLoop(socket) }, "dji-udp-sender").apply {
                isDaemon = true
                start()
            }

            val server = NsdBridgeServer(
                context = applicationContext,
                onRegistrationChanged = { isRegistered, serviceName ->
                    nsdStatusText = if (isRegistered) {
                        "Registered ($serviceName)"
                    } else {
                        "Unregistered"
                    }
                    publishMetrics()
                },
            )
            nsdServer = server
            server.register()

            publishMetrics()
        } catch (exception: Exception) {
            isRunning.set(false)
            socketStatusText = "Bind failed on :${UdpBridgeProtocol.UDP_BRIDGE_PORT}"
            recordSocketError("bind", exception)
            releaseMulticastLock()
            publishMetrics()
        }
    }

    /**
     * Stops the UDP bridge, releases the `MulticastLock`, and closes the socket.
     */
    @Synchronized
    fun shutdown() {
        isRunning.set(false)
        socketStatusText = "Stopped"
        nsdServer?.unregister()
        nsdServer = null
        nsdStatusText = "Stopped"
        announceScheduler?.shutdownNow()
        announceScheduler = null
        listenerThread?.interrupt()
        senderThread?.interrupt()
        listenerThread = null
        senderThread = null

        val socketToClose = udpSocket
        udpSocket = null
        socketToClose?.close()
        releaseMulticastLock()
        outgoingDatagramQueue.clear()
        subscribersByHost.clear()
        publishMetrics()
    }

    /**
     * Packetizes and enqueues a raw H.264 Annex-B video chunk (`0x574A`) for transmission
     * if at least one Quest 2 subscriber or manual Quest 2 IP is active.
     */
    fun enqueueVideoChunk(videoChunk: ByteArray) {
        if (!isRunning.get() || !hasAnyDestination() || videoChunk.isEmpty()) {
            return
        }
        enqueueChannelPayload(UdpBridgeProtocol.CHANNEL_VIDEO, videoChunk)
    }

    /**
     * Packetizes and enqueues a verified DUML v1 packet (`0x5749`) for transmission
     * if at least one Quest 2 subscriber or manual Quest 2 IP is active.
     */
    fun enqueueDumlFrameBytes(rawDumlFrame: ByteArray) {
        if (!isRunning.get() || !hasAnyDestination() || rawDumlFrame.isEmpty()) {
            return
        }
        enqueueChannelPayload(UdpBridgeProtocol.CHANNEL_DUML, rawDumlFrame)
    }

    private fun hasAnyDestination(): Boolean {
        return subscribersByHost.isNotEmpty() || manualQuestIp.isNotEmpty()
    }

    private fun resolveManualQuestAddressIfNeeded(): InetAddress? {
        val configured = manualQuestIp
        if (configured.isEmpty()) {
            manualQuestInetAddress = null
            return null
        }
        val cached = manualQuestInetAddress
        if (cached != null && cached.hostAddress == configured) {
            return cached
        }
        return try {
            val resolved = InetAddress.getByName(configured)
            manualQuestInetAddress = resolved
            resolved
        } catch (exception: Exception) {
            recordSocketError("resolve($configured)", exception)
            null
        }
    }

    private fun enqueueChannelPayload(channelType: Int, payload: ByteArray) {
        val datagrams = UdpBridgeProtocol.packetizePayload(
            channelType = channelType,
            payload = payload,
            nextSequenceNumber = { sequenceCounter.getAndIncrement() },
        )
        for (datagram in datagrams) {
            if (!outgoingDatagramQueue.offer(datagram)) {
                outgoingDatagramQueue.poll()
                outgoingDatagramQueue.offer(datagram)
            }
        }
    }

    private fun broadcastBridgeAnnounce(socket: DatagramSocket) {
        if (!isRunning.get()) return
        val announceBytes = UdpBridgeProtocol.buildBridgeAnnouncePacket()
        val targetAddresses = LinkedHashSet<String>()

        val configured = manualQuestIp
        if (configured.isNotEmpty()) {
            targetAddresses.add(configured)
        } else {
            // Unicast announcement / keepalive to any active Quest 2 subscribers.
            for (subscriber in subscribersByHost.values) {
                val host = subscriber.address.hostAddress
                if (host != null) {
                    targetAddresses.add(host)
                }
            }
        }

        if (targetAddresses.isEmpty()) return

        var sentAny = false
        for (host in targetAddresses) {
            try {
                val destination = InetAddress.getByName(host)
                val packet = DatagramPacket(
                    announceBytes,
                    announceBytes.size,
                    destination,
                    UdpBridgeProtocol.UDP_BRIDGE_PORT,
                )
                socket.send(packet)
                sentAny = true
            } catch (exception: Exception) {
                if (host == configured && configured.isNotEmpty()) {
                    recordSocketError("send($host)", exception)
                }
            }
        }
        if (sentAny) {
            announceSentCounter.incrementAndGet()
        }
    }

    private fun runDiscoveryListenerLoop(socket: DatagramSocket) {
        val receiveBuffer = ByteArray(256)
        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
        var lastMetricsRefreshMillis = 0L

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                receivePacket.setData(receiveBuffer, 0, receiveBuffer.size)
                socket.receive(receivePacket)
                val length = receivePacket.length
                val senderAddress = receivePacket.address
                if (senderAddress != null && UdpBridgeProtocol.isHeartbeatPacket(receiveBuffer, length)) {
                    val hostAddress = senderAddress.hostAddress
                    val localAddresses = UdpBridgeProtocol.queryLocalIpv4Addresses(applicationContext)
                        .map { it.ipv4Address }
                        .toSet()
                    if (hostAddress != null && hostAddress !in localAddresses) {
                        val now = System.currentTimeMillis()
                        subscribersByHost[hostAddress] = SubscriberEntry(
                            address = senderAddress,
                            lastSeenEpochMillis = now,
                        )
                        heartbeatsReceivedCounter.incrementAndGet()
                        pruneExpiredSubscribers(now)
                        publishMetrics()
                        lastMetricsRefreshMillis = now
                    }
                }
            } catch (_: SocketTimeoutException) {
                val now = System.currentTimeMillis()
                val removedAny = pruneExpiredSubscribers(now)
                if (removedAny || now - lastMetricsRefreshMillis >= 2_000L) {
                    publishMetrics()
                    lastMetricsRefreshMillis = now
                }
            } catch (exception: Exception) {
                if (!isRunning.get()) break
                recordSocketError("receive", exception)
            }
        }
    }

    private fun runSenderLoop(socket: DatagramSocket) {
        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                val datagramBytes = outgoingDatagramQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val destinations = LinkedHashMap<String, InetAddress>()

                val now = System.currentTimeMillis()
                for ((hostKey, subscriber) in subscribersByHost) {
                    if (now - subscriber.lastSeenEpochMillis > UdpBridgeProtocol.SUBSCRIBER_TIMEOUT_MILLIS) {
                        subscribersByHost.remove(hostKey)
                        continue
                    }
                    destinations[hostKey] = subscriber.address
                }

                val manualAddress = resolveManualQuestAddressIfNeeded()
                if (manualAddress != null) {
                    val hostKey = manualAddress.hostAddress ?: manualQuestIp
                    destinations[hostKey] = manualAddress
                }

                if (destinations.isEmpty()) continue

                var sentCountForPacket = 0
                for ((hostKey, targetAddress) in destinations) {
                    try {
                        val packet = DatagramPacket(
                            datagramBytes,
                            datagramBytes.size,
                            targetAddress,
                            UdpBridgeProtocol.UDP_BRIDGE_PORT,
                        )
                        socket.send(packet)
                        sentCountForPacket++
                    } catch (exception: Exception) {
                        recordSocketError("stream($hostKey)", exception)
                    }
                }

                if (sentCountForPacket > 0) {
                    val totalPackets = packetsSentCounter.addAndGet(sentCountForPacket.toLong())
                    bytesSentCounter.addAndGet(datagramBytes.size.toLong() * sentCountForPacket)
                    if (totalPackets == 1L || totalPackets % 25L == 0L) {
                        publishMetrics()
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (exception: Exception) {
                recordSocketError("senderLoop", exception)
            }
        }
    }

    private fun pruneExpiredSubscribers(nowMillis: Long): Boolean {
        var removed = false
        for ((hostKey, subscriber) in subscribersByHost) {
            if (nowMillis - subscriber.lastSeenEpochMillis > UdpBridgeProtocol.SUBSCRIBER_TIMEOUT_MILLIS) {
                subscribersByHost.remove(hostKey)
                removed = true
            }
        }
        return removed
    }

    private fun recordSocketError(operation: String, exception: Exception) {
        lastErrorText = "$operation: ${exception.javaClass.simpleName}: ${exception.message ?: "error"}"
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager =
                applicationContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifiManager?.createMulticastLock("one.woodbury.drone.bridge.multicast")?.apply {
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

    private fun publishMetrics() {
        val localEndpoints = UdpBridgeProtocol.queryLocalIpv4Addresses(applicationContext)
            .map { "${it.ipv4Address} (${it.interfaceName})" }
        val activeSubscriberHosts = subscribersByHost.keys().toList().sorted()
        onMetricsChanged(
            UdpBridgeSenderMetrics(
                isListening = isRunning.get(),
                socketStatus = socketStatusText,
                nsdStatus = nsdStatusText,
                lastSocketError = lastErrorText,
                localIpv4Addresses = localEndpoints,
                configuredQuestIp = manualQuestIp,
                activeSubscribers = activeSubscriberHosts,
                announcePacketsSent = announceSentCounter.get(),
                udpPacketsSent = packetsSentCounter.get(),
                udpBytesSent = bytesSentCounter.get(),
                heartbeatsReceived = heartbeatsReceivedCounter.get(),
            ),
        )
    }

    companion object {
        private const val _preferencesFileName = "one.woodbury.drone.bridge.preferences"
        private const val _preferenceQuestViewerIp = "preference_quest_viewer_ip"
    }
}
