package one.woodbury.drone.bridge.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import one.woodbury.drone.bridge.net.UdpBridgeSender
import one.woodbury.drone.bridge.net.UdpBridgeSenderMetrics
import one.woodbury.drone.bridge.service.BridgeForegroundService
import one.woodbury.drone.protocol.AoaStreamParser
import one.woodbury.drone.protocol.BatteryTelemetry
import one.woodbury.drone.protocol.DumlFrame
import one.woodbury.drone.protocol.DumlProtocol
import one.woodbury.drone.protocol.FlightControllerTelemetry
import one.woodbury.drone.protocol.RemoteControllerAxes
import one.woodbury.drone.video.H264VideoDecoder
import one.woodbury.drone.video.VideoDecoderMetrics

/**
 * High-level connection phase for the USB link to the DJI remote controller.
 */
enum class ConnectionPhase {
    DISCONNECTED,
    AWAITING_PERMISSION,
    CONNECTED,
    ERROR,
}

/**
 * Snapshot of the hardware strings reported by Android's [UsbAccessory] descriptor.
 */
data class UsbAccessoryDescriptor(
    val manufacturer: String,
    val model: String,
    val description: String,
    val version: String,
    val uri: String,
    val serial: String,
)

/**
 * Structured, fixed-width fields for the most recently parsed DUML packet so UI lines do not shift.
 */
data class LastDumlPacketInfo(
    val sourceModule: String,
    val destinationModule: String,
    val commandSetAndId: String,
    val sequenceAndLength: String,
    val payloadHex: String,
)

/**
 * Immutable UI state representing everything retrieved over USB from the remote controller.
 */
data class ControllerConnectionState(
    val connectionPhase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val statusMessage: String = "No USB accessory connected. Unplug the side slider cable on the S01A remote and connect its bottom USB-A port to this phone.",
    val accessoryDescriptor: UsbAccessoryDescriptor? = null,
    val totalBytesReceived: Long = 0L,
    val totalBytesSent: Long = 0L,
    val dumlFramesReceived: Long = 0L,
    val aoaDumlEnvelopesReceived: Long = 0L,
    val aoaVideoEnvelopesReceived: Long = 0L,
    val videoBytesReceived: Long = 0L,
    val observedModules: Set<String> = emptySet(),
    val moduleVersions: Map<String, String> = emptyMap(),
    val remoteControllerAxes: RemoteControllerAxes? = null,
    val flightControllerTelemetry: FlightControllerTelemetry? = null,
    val batteryTelemetry: BatteryTelemetry? = null,
    val lastDumlPacket: LastDumlPacketInfo? = null,
    val lastRawHexPreview: String? = null,
    val videoDecoderMetrics: VideoDecoderMetrics = VideoDecoderMetrics(),
    val udpBridgeMetrics: UdpBridgeSenderMetrics = UdpBridgeSenderMetrics(),
)

/**
 * Manages Android Open Accessory (AOA) discovery, permission handling, and bidirectional
 * DUML/video stream I/O with the DJI remote controller.
 */
class UsbAccessoryConnectionManager(
    private val applicationContext: Context,
) {
    private val usbManager: UsbManager =
        applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sequenceCounter = AtomicInteger(1)
    private val isReceiverRegistered = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow(ControllerConnectionState())
    val connectionState: StateFlow<ControllerConnectionState> = _connectionState.asStateFlow()

    val videoDecoder: H264VideoDecoder = H264VideoDecoder { metrics ->
        _connectionState.update { currentState ->
            currentState.copy(videoDecoderMetrics = metrics)
        }
    }

    private val udpBridgeSender: UdpBridgeSender = UdpBridgeSender(applicationContext) { bridgeMetrics ->
        _connectionState.update { currentState ->
            currentState.copy(udpBridgeMetrics = bridgeMetrics)
        }
    }

    @Volatile
    private var activeFileDescriptor: ParcelFileDescriptor? = null

    @Volatile
    private var activeOutputStream: FileOutputStream? = null

    @Volatile
    private var readerJob: Job? = null

    @Volatile
    private var batteryPollingJob: Job? = null

    private val usbBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val usbAccessory = extractAccessoryFromIntent(intent)
                    val permissionGranted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false,
                    )
                    if (permissionGranted && usbAccessory != null) {
                        openAccessoryStream(usbAccessory)
                    } else {
                        _connectionState.update { currentState ->
                            currentState.copy(
                                connectionPhase = ConnectionPhase.ERROR,
                                statusMessage = "USB accessory permission was denied by the user.",
                            )
                        }
                    }
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    closeActiveStream(
                        statusMessage = "USB accessory detached.",
                    )
                }
            }
        }
    }

    init {
        registerUsbReceiver()
        udpBridgeSender.start()
    }

    /**
     * Scans `UsbManager.accessoryList` and either opens the stream or requests permission.
     */
    fun scanAndConnect() {
        val accessoryList = usbManager.accessoryList
        val usbAccessory = accessoryList?.firstOrNull()
        if (usbAccessory == null) {
            closeActiveStream(
                statusMessage = "No USB accessory detected on the bus. Ensure the S01A remote is powered on and its left-side short cable is unplugged.",
            )
            return
        }

        val descriptor = extractDescriptor(usbAccessory)
        if (activeFileDescriptor != null && _connectionState.value.connectionPhase == ConnectionPhase.CONNECTED) {
            _connectionState.update { currentState ->
                currentState.copy(accessoryDescriptor = descriptor)
            }
            return
        }

        if (usbManager.hasPermission(usbAccessory)) {
            openAccessoryStream(usbAccessory)
        } else {
            _connectionState.update { currentState ->
                currentState.copy(
                    connectionPhase = ConnectionPhase.AWAITING_PERMISSION,
                    statusMessage = "DJI USB accessory detected (${descriptor.manufacturer} ${descriptor.model}). Requesting USB permission…",
                    accessoryDescriptor = descriptor,
                )
            }
            requestAccessoryPermission(usbAccessory)
        }
    }

    /**
     * Handles an incoming `ACTION_USB_ACCESSORY_ATTACHED` intent delivered to `MainActivity`.
     */
    fun onAccessoryAttachedIntent(intent: Intent?) {
        val usbAccessory = intent?.let { extractAccessoryFromIntent(it) }
        if (usbAccessory != null && usbManager.hasPermission(usbAccessory)) {
            openAccessoryStream(usbAccessory)
        } else {
            scanAndConnect()
        }
    }

    /**
     * Sends DUML device version queries (`0x00/0x01`) to the Remote Controller and Flight Controller.
     */
    fun sendVersionQuery() {
        managerScope.launch {
            val remoteQuery = DumlProtocol.buildDeviceVersionQuery(
                destinationModuleId = DumlProtocol.MODULE_REMOTE_CONTROLLER,
                sequenceNumber = sequenceCounter.getAndIncrement() and 0xFFFF,
            )
            val flightControllerQuery = DumlProtocol.buildDeviceVersionQuery(
                destinationModuleId = DumlProtocol.MODULE_FLIGHT_CONTROLLER,
                sequenceNumber = sequenceCounter.getAndIncrement() and 0xFFFF,
            )
            writeBytesToAccessory(remoteQuery)
            writeBytesToAccessory(flightControllerQuery)
        }
    }

    /**
     * Releases all resources when the owning activity is destroyed.
     */
    fun shutdown() {
        BridgeForegroundService.stop(applicationContext)
        udpBridgeSender.shutdown()
        videoDecoder.shutdown()
        closeActiveStream(statusMessage = "Connection closed.")
        if (isReceiverRegistered.compareAndSet(true, false)) {
            try {
                applicationContext.unregisterReceiver(usbBroadcastReceiver)
            } catch (_: IllegalArgumentException) {
                // Ignore if the receiver was already unregistered.
            }
        }
        managerScope.cancel()
    }

    private fun openAccessoryStream(usbAccessory: UsbAccessory) {
        val descriptor = extractDescriptor(usbAccessory)
        if (activeFileDescriptor != null && _connectionState.value.connectionPhase == ConnectionPhase.CONNECTED) {
            return
        }

        val parcelFileDescriptor = try {
            usbManager.openAccessory(usbAccessory)
        } catch (exception: Exception) {
            _connectionState.update { currentState ->
                currentState.copy(
                    connectionPhase = ConnectionPhase.ERROR,
                    statusMessage = "Failed to open USB accessory: ${exception.message}",
                    accessoryDescriptor = descriptor,
                )
            }
            return
        }

        if (parcelFileDescriptor == null) {
            _connectionState.update { currentState ->
                currentState.copy(
                    connectionPhase = ConnectionPhase.ERROR,
                    statusMessage = "UsbManager.openAccessory() returned null. Try unplugging and reconnecting the USB cable.",
                    accessoryDescriptor = descriptor,
                )
            }
            return
        }

        activeFileDescriptor = parcelFileDescriptor
        val fileDescriptor = parcelFileDescriptor.fileDescriptor
        val inputStream = FileInputStream(fileDescriptor)
        val outputStream = FileOutputStream(fileDescriptor)
        activeOutputStream = outputStream

        _connectionState.update { currentState ->
            ControllerConnectionState(
                connectionPhase = ConnectionPhase.CONNECTED,
                statusMessage = "Connected to ${descriptor.manufacturer} ${descriptor.model} over Android Open Accessory (AOA).",
                accessoryDescriptor = descriptor,
                videoDecoderMetrics = currentState.videoDecoderMetrics,
                udpBridgeMetrics = currentState.udpBridgeMetrics,
            )
        }

        BridgeForegroundService.start(applicationContext)

        readerJob?.cancel()
        readerJob = managerScope.launch {
            runReadLoop(inputStream)
        }

        batteryPollingJob?.cancel()
        batteryPollingJob = managerScope.launch {
            runBatteryPollingLoop()
        }

        sendVersionQuery()
    }

    private fun runReadLoop(inputStream: FileInputStream) {
        val streamParser = AoaStreamParser(
            onDumlFrame = { frame -> handleParsedDumlFrame(frame) },
            onVideoChunk = { videoChunk -> handleVideoChunk(videoChunk) },
        )
        val readBuffer = ByteArray(16_384)

        try {
            while (managerScope.isActive) {
                val bytesRead = inputStream.read(readBuffer)
                if (bytesRead < 0) {
                    break
                }
                if (bytesRead == 0) {
                    continue
                }
                val previewCopy = readBuffer.copyOfRange(0, minOf(bytesRead, 24))
                val hexPreview = DumlProtocol.formatHexPreview(previewCopy, maxBytes = 24)
                streamParser.feedBytes(readBuffer, bytesRead)

                _connectionState.update { currentState ->
                    currentState.copy(
                        totalBytesReceived = currentState.totalBytesReceived + bytesRead,
                        aoaDumlEnvelopesReceived = streamParser.aoaDumlEnvelopesReceived,
                        aoaVideoEnvelopesReceived = streamParser.aoaVideoEnvelopesReceived,
                        lastRawHexPreview = hexPreview,
                    )
                }
            }
        } catch (exception: IOException) {
            if (activeFileDescriptor != null) {
                closeActiveStream(
                    statusMessage = "USB stream disconnected: ${exception.message ?: "I/O closed"}",
                )
            }
        }
    }

    private fun handleParsedDumlFrame(frame: DumlFrame) {
        udpBridgeSender.enqueueDumlFrameBytes(DumlProtocol.encodeDumlFrame(frame))

        val sourceLabel = DumlProtocol.formatModuleName(frame.sourceModuleId)
        val destinationLabel = DumlProtocol.formatModuleName(frame.destinationModuleId)
        val packetInfo = LastDumlPacketInfo(
            sourceModule = sourceLabel,
            destinationModule = destinationLabel,
            commandSetAndId = "Set 0x%02X / ID 0x%02X".format(frame.commandSet, frame.commandId),
            sequenceAndLength = "#%05d (%3d B)".format(frame.sequenceNumber, frame.payload.size),
            payloadHex = if (frame.payload.isEmpty()) {
                "(empty payload)"
            } else {
                DumlProtocol.formatHexPreview(frame.payload, maxBytes = 16)
            },
        )

        val decodedOsd = DumlProtocol.decodeFlightControllerOsdTelemetry(frame)
        val decodedBattery = DumlProtocol.decodeBatteryTelemetry(frame)
        val decodedAxes = DumlProtocol.decodeRemoteControllerAxes(frame)
        val decodedVersion = DumlProtocol.decodeDeviceVersionString(frame)

        _connectionState.update { currentState ->
            val updatedModules = currentState.observedModules + sourceLabel
            val updatedVersions = if (decodedVersion != null) {
                currentState.moduleVersions + (sourceLabel to decodedVersion)
            } else {
                currentState.moduleVersions
            }
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
            currentState.copy(
                dumlFramesReceived = currentState.dumlFramesReceived + 1,
                observedModules = updatedModules,
                moduleVersions = updatedVersions,
                remoteControllerAxes = decodedAxes ?: currentState.remoteControllerAxes,
                flightControllerTelemetry = updatedTelemetry,
                batteryTelemetry = updatedBattery,
                lastDumlPacket = packetInfo,
            )
        }
    }

    private fun handleVideoChunk(videoChunk: ByteArray) {
        udpBridgeSender.enqueueVideoChunk(videoChunk)
        videoDecoder.feedRawVideoChunk(videoChunk)
        _connectionState.update { currentState ->
            currentState.copy(
                videoBytesReceived = currentState.videoBytesReceived + videoChunk.size,
            )
        }
    }

    @Synchronized
    private fun writeBytesToAccessory(bytes: ByteArray) {
        val outputStream = activeOutputStream ?: return
        try {
            outputStream.write(bytes)
            outputStream.flush()
            _connectionState.update { currentState ->
                currentState.copy(
                    totalBytesSent = currentState.totalBytesSent + bytes.size,
                )
            }
        } catch (_: IOException) {
            // Stream errors are handled by the reader loop.
        }
    }

    private suspend fun runBatteryPollingLoop() {
        while (managerScope.isActive) {
            val batteryQuery = DumlProtocol.buildBatteryDynamicDataQuery(
                sequenceNumber = sequenceCounter.getAndIncrement() and 0xFFFF,
            )
            writeBytesToAccessory(batteryQuery)
            delay(1000L)
        }
    }

    private fun closeActiveStream(statusMessage: String) {
        readerJob?.cancel()
        readerJob = null
        batteryPollingJob?.cancel()
        batteryPollingJob = null
        activeOutputStream = null
        val descriptorToClose = activeFileDescriptor
        activeFileDescriptor = null
        if (descriptorToClose != null) {
            try {
                descriptorToClose.close()
            } catch (_: IOException) {
                // Ignore close failures.
            }
        }
        _connectionState.update { currentState ->
            currentState.copy(
                connectionPhase = ConnectionPhase.DISCONNECTED,
                statusMessage = statusMessage,
            )
        }
        BridgeForegroundService.stop(applicationContext)
    }

    private fun requestAccessoryPermission(usbAccessory: UsbAccessory) {
        val explicitIntent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(applicationContext.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionPendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            0,
            explicitIntent,
            flags,
        )
        usbManager.requestPermission(usbAccessory, permissionPendingIntent)
    }

    private fun registerUsbReceiver() {
        if (isReceiverRegistered.compareAndSet(false, true)) {
            val intentFilter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            }
            ContextCompat.registerReceiver(
                applicationContext,
                usbBroadcastReceiver,
                intentFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    private fun extractDescriptor(usbAccessory: UsbAccessory): UsbAccessoryDescriptor {
        return UsbAccessoryDescriptor(
            manufacturer = usbAccessory.manufacturer ?: "Unknown",
            model = usbAccessory.model ?: "Unknown",
            description = usbAccessory.description ?: "None",
            version = usbAccessory.version ?: "None",
            uri = usbAccessory.uri ?: "None",
            serial = try {
                usbAccessory.serial ?: "Unavailable"
            } catch (_: SecurityException) {
                "Requires USB Permission"
            },
        )
    }

    private fun extractAccessoryFromIntent(intent: Intent): UsbAccessory? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "one.woodbury.drone.bridge.USB_PERMISSION"
    }
}
