package one.woodbury.drone.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Snapshot of the Phone-to-Quest 2 UDP bridge status displayed in the `:app` UI.
 */
data class UdpBridgeSenderMetrics(
    val isListening: Boolean = false,
    val localIpv4Addresses: List<String> = emptyList(),
    val activeSubscribers: List<String> = emptyList(),
    val udpPacketsSent: Long = 0L,
    val udpBytesSent: Long = 0L,
    val heartbeatsReceived: Long = 0L,
)

/**
 * Listens on UDP port [UdpBridgeProtocol.UDP_BRIDGE_PORT] (`5600`) for `"DJIH"` discovery
 * heartbeats from the Meta Quest 2 (`:mirror`) and forwards MTU-safe `"DJID"` H.264 video
 * and DUML v1 telemetry packets to all active Quest 2 subscribers.
 */
class UdpBridgeSender(
    private val onMetricsChanged: (UdpBridgeSenderMetrics) -> Unit,
) {
    private val isRunning = AtomicBoolean(false)
    private val sequenceCounter = AtomicInteger(1)
    private val outgoingDatagramQueue = LinkedBlockingQueue<ByteArray>(1024)
    private val subscribersByHost = ConcurrentHashMap<String, SubscriberEntry>()

    private val packetsSentCounter = AtomicLong(0L)
    private val bytesSentCounter = AtomicLong(0L)
    private val heartbeatsReceivedCounter = AtomicLong(0L)

    @Volatile
    private var udpSocket: DatagramSocket? = null

    @Volatile
    private var listenerThread: Thread? = null

    @Volatile
    private var senderThread: Thread? = null

    private data class SubscriberEntry(
        val address: InetAddress,
        @Volatile var lastSeenEpochMillis: Long,
    )

    /**
     * Opens UDP port `5600` and starts the discovery listener and non-blocking packet sender threads.
     */
    @Synchronized
    fun start() {
        if (isRunning.get()) return
        try {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1_000
                bind(InetSocketAddress(UdpBridgeProtocol.UDP_BRIDGE_PORT))
            }
            udpSocket = socket
            isRunning.set(true)

            listenerThread = Thread({ runDiscoveryListenerLoop(socket) }, "dji-udp-discovery").apply {
                isDaemon = true
                start()
            }
            senderThread = Thread({ runSenderLoop(socket) }, "dji-udp-sender").apply {
                isDaemon = true
                start()
            }
            publishMetrics()
        } catch (_: Exception) {
            isRunning.set(false)
            publishMetrics()
        }
    }

    /**
     * Stops the UDP bridge and closes the socket.
     */
    @Synchronized
    fun shutdown() {
        isRunning.set(false)
        listenerThread?.interrupt()
        senderThread?.interrupt()
        listenerThread = null
        senderThread = null

        val socketToClose = udpSocket
        udpSocket = null
        socketToClose?.close()
        outgoingDatagramQueue.clear()
        subscribersByHost.clear()
        publishMetrics()
    }

    /**
     * Packetizes and enqueues a raw H.264 Annex-B video chunk (`0x574A`) for transmission
     * if at least one Quest 2 subscriber is currently active.
     */
    fun enqueueVideoChunk(videoChunk: ByteArray) {
        if (!isRunning.get() || subscribersByHost.isEmpty() || videoChunk.isEmpty()) {
            return
        }
        enqueueChannelPayload(UdpBridgeProtocol.CHANNEL_VIDEO, videoChunk)
    }

    /**
     * Packetizes and enqueues a verified DUML v1 packet (`0x5749`) for transmission
     * if at least one Quest 2 subscriber is currently active.
     */
    fun enqueueDumlFrameBytes(rawDumlFrame: ByteArray) {
        if (!isRunning.get() || subscribersByHost.isEmpty() || rawDumlFrame.isEmpty()) {
            return
        }
        enqueueChannelPayload(UdpBridgeProtocol.CHANNEL_DUML, rawDumlFrame)
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

    private fun runDiscoveryListenerLoop(socket: DatagramSocket) {
        val receiveBuffer = ByteArray(256)
        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
        var lastMetricsRefreshMillis = 0L

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                socket.receive(receivePacket)
                val length = receivePacket.length
                val senderAddress = receivePacket.address
                if (senderAddress != null && UdpBridgeProtocol.isHeartbeatPacket(receiveBuffer, length)) {
                    val hostAddress = senderAddress.hostAddress
                    val localAddresses = UdpBridgeProtocol.queryLocalIpv4Addresses().map { it.ipv4Address }.toSet()
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
                if (removedAny || now - lastMetricsRefreshMillis >= 2_500L) {
                    publishMetrics()
                    lastMetricsRefreshMillis = now
                }
            } catch (_: Exception) {
                if (!isRunning.get()) break
            }
        }
    }

    private fun runSenderLoop(socket: DatagramSocket) {
        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                val datagramBytes = outgoingDatagramQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (subscribersByHost.isEmpty()) continue

                val now = System.currentTimeMillis()
                var sentCountForPacket = 0
                for ((hostKey, subscriber) in subscribersByHost) {
                    if (now - subscriber.lastSeenEpochMillis > UdpBridgeProtocol.SUBSCRIBER_TIMEOUT_MILLIS) {
                        subscribersByHost.remove(hostKey)
                        continue
                    }
                    val packet = DatagramPacket(
                        datagramBytes,
                        datagramBytes.size,
                        subscriber.address,
                        UdpBridgeProtocol.UDP_BRIDGE_PORT,
                    )
                    socket.send(packet)
                    sentCountForPacket++
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
            } catch (_: Exception) {
                // Ignore transient network send errors (e.g. Wi-Fi handoff).
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

    private fun publishMetrics() {
        val localEndpoints = UdpBridgeProtocol.queryLocalIpv4Addresses()
            .map { "${it.ipv4Address} (${it.interfaceName})" }
        val activeSubscriberHosts = subscribersByHost.keys().toList().sorted()
        onMetricsChanged(
            UdpBridgeSenderMetrics(
                isListening = isRunning.get(),
                localIpv4Addresses = localEndpoints,
                activeSubscribers = activeSubscriberHosts,
                udpPacketsSent = packetsSentCounter.get(),
                udpBytesSent = bytesSentCounter.get(),
                heartbeatsReceived = heartbeatsReceivedCounter.get(),
            ),
        )
    }
}
