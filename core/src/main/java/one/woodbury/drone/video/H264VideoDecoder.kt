package one.woodbury.drone.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Extracts clean Annex-B H.264 NAL units from DJI's `0x574A` (`4A 57`) video stream,
 * filters out proprietary DJI SEI packets (`nal_unit_type = 6`), and groups slices into
 * complete H.264 Access Units (frames) ready for [MediaCodec].
 */
class H264AnnexBStreamExtractor(
    private val onAccessUnitReady: (accessUnitBytes: ByteArray, isKeyframe: Boolean) -> Unit,
) {
    private val streamBuffer = ByteArrayOutputStream()
    private val accessUnitBuffer = ByteArrayOutputStream()

    private var hasSeenSequenceParameterSet = false
    private var currentAccessUnitHasSlice = false
    private var currentAccessUnitIsKeyframe = false

    @Volatile
    var latestSpsNalUnit: ByteArray? = null
        private set

    @Volatile
    var latestPpsNalUnit: ByteArray? = null
        private set

    var spsCount: Long = 0L
        private set

    var accessUnitsEmitted: Long = 0L
        private set

    /**
     * Feeds raw bytes from an AOA `0x574A` video chunk into the Annex-B NAL scanner.
     */
    @Synchronized
    fun feedChunk(chunk: ByteArray, length: Int = chunk.size) {
        if (length <= 0) return
        streamBuffer.write(chunk, 0, length)
        val data = streamBuffer.toByteArray()
        val total = data.size

        var firstStartCodeOffset = findAnnexBStartCode(data, 0, total)
        if (firstStartCodeOffset < 0) {
            // Keep the last 3 bytes in case a start code straddles the next chunk boundary.
            streamBuffer.reset()
            if (total > 3) {
                streamBuffer.write(data, total - 3, 3)
            } else {
                streamBuffer.write(data, 0, total)
            }
            return
        }

        var currentNalStart = firstStartCodeOffset
        while (currentNalStart < total) {
            val startCodeLength = if (
                currentNalStart + 3 < total &&
                data[currentNalStart].toInt() == 0 &&
                data[currentNalStart + 1].toInt() == 0 &&
                data[currentNalStart + 2].toInt() == 0 &&
                data[currentNalStart + 3].toInt() == 1
            ) {
                4
            } else {
                3
            }

            val nalBodyOffset = currentNalStart + startCodeLength
            if (nalBodyOffset >= total) {
                break
            }

            val searchFromOffset = computeNextStartCodeSearchOffset(
                buffer = data,
                nalBodyOffset = nalBodyOffset,
                limit = total,
            )
            if (searchFromOffset < 0) {
                // Incomplete SEI header or payload; wait for more bytes.
                break
            }

            val nextStartCodeOffset = findAnnexBStartCode(
                buffer = data,
                fromIndex = searchFromOffset,
                limit = total,
            )
            if (nextStartCodeOffset < 0) {
                break
            }

            val nalBodyLength = nextStartCodeOffset - nalBodyOffset
            if (nalBodyLength > 0) {
                processSingleNalUnit(
                    buffer = data,
                    nalBodyOffset = nalBodyOffset,
                    nalBodyLength = nalBodyLength,
                )
            }
            if (currentAccessUnitHasSlice && doesStartCodeBeginNewAccessUnit(data, nextStartCodeOffset, total)) {
                flushPendingAccessUnit()
            }
            currentNalStart = nextStartCodeOffset
        }

        streamBuffer.reset()
        if (currentNalStart < total) {
            streamBuffer.write(data, currentNalStart, total - currentNalStart)
        }
    }

    /**
     * Flushes any pending slice data in the current Access Unit buffer (used in tests or stream end).
     */
    @Synchronized
    fun flushPendingAccessUnit() {
        if (currentAccessUnitHasSlice && accessUnitBuffer.size() > 0) {
            val accessUnitBytes = accessUnitBuffer.toByteArray()
            val isKeyframe = currentAccessUnitIsKeyframe
            accessUnitBuffer.reset()
            currentAccessUnitHasSlice = false
            currentAccessUnitIsKeyframe = false
            accessUnitsEmitted++
            onAccessUnitReady(accessUnitBytes, isKeyframe)
        }
    }

    /**
     * Resets stream synchronization state so the next SPS/PPS initializes a fresh decoder session.
     */
    @Synchronized
    fun resetForNewDecoder() {
        accessUnitBuffer.reset()
        currentAccessUnitHasSlice = false
        currentAccessUnitIsKeyframe = false
    }

    private fun processSingleNalUnit(
        buffer: ByteArray,
        nalBodyOffset: Int,
        nalBodyLength: Int,
    ) {
        val nalHeader = buffer[nalBodyOffset].toInt() and 0xFF
        // In H.264, forbidden_zero_bit (bit 7) must be 0.
        if ((nalHeader and 0x80) != 0) {
            return
        }

        val nalUnitType = nalHeader and 0x1F
        // Only retain standard decoder NAL types: Non-IDR (1), IDR (5), SPS (7), PPS (8), AUD (9).
        // This strips DJI proprietary SEI (6) and any non-video data.
        if (nalUnitType != NAL_TYPE_NON_IDR_SLICE &&
            nalUnitType != NAL_TYPE_IDR_SLICE &&
            nalUnitType != NAL_TYPE_SPS &&
            nalUnitType != NAL_TYPE_PPS &&
            nalUnitType != NAL_TYPE_AUD
        ) {
            return
        }

        if (nalUnitType == NAL_TYPE_SPS) {
            hasSeenSequenceParameterSet = true
            spsCount++
            latestSpsNalUnit = buildAnnexBNalWithStartCode(buffer, nalBodyOffset, nalBodyLength)
        } else if (nalUnitType == NAL_TYPE_PPS) {
            latestPpsNalUnit = buildAnnexBNalWithStartCode(buffer, nalBodyOffset, nalBodyLength)
        }

        if (!hasSeenSequenceParameterSet) {
            return
        }

        val isSliceNal = (nalUnitType == NAL_TYPE_NON_IDR_SLICE || nalUnitType == NAL_TYPE_IDR_SLICE)
        val isFirstSliceOfFrame = if (isSliceNal && nalBodyLength >= 2) {
            // In H.264 slice_header(), first_mb_in_slice is an Exp-Golomb ue(v) field right after
            // the 1-byte NAL header. Value 0 (first macroblock of frame) starts with a leading 1 bit.
            (buffer[nalBodyOffset + 1].toInt() and 0x80) != 0
        } else {
            false
        }

        val startsNewAccessUnit = currentAccessUnitHasSlice && (
            nalUnitType == NAL_TYPE_AUD ||
                nalUnitType == NAL_TYPE_SPS ||
                nalUnitType == NAL_TYPE_PPS ||
                isFirstSliceOfFrame
            )

        if (startsNewAccessUnit) {
            flushPendingAccessUnit()
        }

        // Write canonical 4-byte Annex-B start code followed by the NAL body.
        accessUnitBuffer.write(ANNEX_B_START_CODE_4, 0, ANNEX_B_START_CODE_4.size)
        accessUnitBuffer.write(buffer, nalBodyOffset, nalBodyLength)

        if (isSliceNal) {
            currentAccessUnitHasSlice = true
            if (nalUnitType == NAL_TYPE_IDR_SLICE) {
                currentAccessUnitIsKeyframe = true
            }
        }
    }

    private fun buildAnnexBNalWithStartCode(
        buffer: ByteArray,
        nalBodyOffset: Int,
        nalBodyLength: Int,
    ): ByteArray {
        val out = ByteArray(4 + nalBodyLength)
        ANNEX_B_START_CODE_4.copyInto(out, 0)
        buffer.copyInto(
            destination = out,
            destinationOffset = 4,
            startIndex = nalBodyOffset,
            endIndex = nalBodyOffset + nalBodyLength,
        )
        return out
    }

    /**
     * Computes the byte index from which to search for the next Annex-B start code.
     * For SEI NAL units (`nal_unit_type = 6`), reads the H.264 SEI `payloadSize` header
     * so unescaped `00 00 00 01` bytes inside DJI proprietary SEI payloads are skipped.
     * Returns `-1` if more bytes are needed to complete the SEI payload.
     */
    private fun computeNextStartCodeSearchOffset(
        buffer: ByteArray,
        nalBodyOffset: Int,
        limit: Int,
    ): Int {
        val nalHeader = buffer[nalBodyOffset].toInt() and 0xFF
        val nalUnitType = nalHeader and 0x1F
        if (nalUnitType != NAL_TYPE_SEI) {
            return nalBodyOffset + 1
        }

        var cursor = nalBodyOffset + 1
        // Skip payloadType (0xFF bytes followed by last_payload_type_byte).
        while (cursor < limit && (buffer[cursor].toInt() and 0xFF) == 0xFF) {
            cursor++
        }
        if (cursor >= limit) return -1
        cursor++

        // Read payloadSize (0xFF bytes followed by last_payload_size_byte).
        var seiPayloadSize = 0
        while (cursor < limit && (buffer[cursor].toInt() and 0xFF) == 0xFF) {
            seiPayloadSize += 255
            cursor++
            if (seiPayloadSize > 65_536) {
                return nalBodyOffset + 1
            }
        }
        if (cursor >= limit) return -1
        seiPayloadSize += (buffer[cursor].toInt() and 0xFF)
        cursor++

        if (seiPayloadSize < 0 || seiPayloadSize > 65_536) {
            return nalBodyOffset + 1
        }
        val endOfSeiPayload = cursor + seiPayloadSize
        return if (endOfSeiPayload > limit) -1 else endOfSeiPayload
    }

    /**
     * Checks whether the Annex-B start code at [startCodeOffset] begins a new Access Unit
     * (AUD, SPS, PPS, or a slice with `first_mb_in_slice == 0`).
     */
    private fun doesStartCodeBeginNewAccessUnit(
        buffer: ByteArray,
        startCodeOffset: Int,
        limit: Int,
    ): Boolean {
        val startCodeLength = if (
            startCodeOffset + 3 < limit &&
            buffer[startCodeOffset].toInt() == 0 &&
            buffer[startCodeOffset + 1].toInt() == 0 &&
            buffer[startCodeOffset + 2].toInt() == 0 &&
            buffer[startCodeOffset + 3].toInt() == 1
        ) {
            4
        } else {
            3
        }
        val nextNalOffset = startCodeOffset + startCodeLength
        if (nextNalOffset >= limit) return false
        val nextNalHeader = buffer[nextNalOffset].toInt() and 0xFF
        if ((nextNalHeader and 0x80) != 0) return false
        return when (nextNalHeader and 0x1F) {
            NAL_TYPE_AUD, NAL_TYPE_SPS, NAL_TYPE_PPS -> true
            NAL_TYPE_NON_IDR_SLICE, NAL_TYPE_IDR_SLICE -> {
                nextNalOffset + 1 < limit && (buffer[nextNalOffset + 1].toInt() and 0x80) != 0
            }
            else -> false
        }
    }

    private fun findAnnexBStartCode(
        buffer: ByteArray,
        fromIndex: Int,
        limit: Int,
    ): Int {
        var i = fromIndex
        while (i + 2 < limit) {
            if (buffer[i].toInt() == 0 && buffer[i + 1].toInt() == 0) {
                if (buffer[i + 2].toInt() == 1) {
                    return if (i > fromIndex && buffer[i - 1].toInt() == 0) i - 1 else i
                }
                if (i + 3 < limit && buffer[i + 2].toInt() == 0 && buffer[i + 3].toInt() == 1) {
                    return i
                }
            }
            i++
        }
        return -1
    }

    companion object {
        const val NAL_TYPE_NON_IDR_SLICE: Int = 1
        const val NAL_TYPE_IDR_SLICE: Int = 5
        const val NAL_TYPE_SEI: Int = 6
        const val NAL_TYPE_SPS: Int = 7
        const val NAL_TYPE_PPS: Int = 8
        const val NAL_TYPE_AUD: Int = 9

        private val ANNEX_B_START_CODE_4 = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

/**
 * Snapshot of hardware H.264 decoder statistics for the UI card.
 */
data class VideoDecoderMetrics(
    val isSurfaceAttached: Boolean = false,
    val framesDecoded: Long = 0L,
    val keyframesReceived: Long = 0L,
    val videoWidth: Int = 1280,
    val videoHeight: Int = 720,
    val framesPerSecond: Float = 0f,
    val bitrateKbps: Long = 0L,
)

/**
 * Hardware-accelerated H.264 (`video/avc`) decoder backed by Android's [MediaCodec].
 */
class H264VideoDecoder(
    private val onMetricsChanged: (VideoDecoderMetrics) -> Unit,
) {
    private val chunkQueue = LinkedBlockingQueue<ByteArray>(512)
    private val isDecoderRunning = AtomicBoolean(false)
    private val needsCodecConfigPrepend = AtomicBoolean(true)

    private val framesDecodedCounter = AtomicLong(0L)
    private val keyframesReceivedCounter = AtomicLong(0L)
    private val bytesReceivedCounter = AtomicLong(0L)

    private val lastRateCalculationNanos = AtomicLong(System.nanoTime())
    private val lastFramesDecodedForRate = AtomicLong(0L)
    private val lastBytesReceivedForRate = AtomicLong(0L)

    @Volatile
    private var rollingFps: Float = 0f

    @Volatile
    private var rollingBitrateKbps: Long = 0L

    @Volatile
    private var negotiatedWidth: Int = 1280

    @Volatile
    private var negotiatedHeight: Int = 720

    @Volatile
    private var activeCodec: MediaCodec? = null

    @Volatile
    private var inputThread: Thread? = null

    @Volatile
    private var outputThread: Thread? = null

    private val extractor = H264AnnexBStreamExtractor { accessUnitBytes, isKeyframe ->
        if (isKeyframe) {
            keyframesReceivedCounter.incrementAndGet()
        }
        queueAccessUnitToCodec(accessUnitBytes, isKeyframe)
    }

    /**
     * Attaches a [Surface] from a `TextureView` and starts the hardware H.264 decoder threads.
     */
    @Synchronized
    fun attachSurface(surface: Surface) {
        detachSurface()
        try {
            val mediaFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                1280,
                720,
            ).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
            }
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(mediaFormat, surface, null, 0)
            codec.start()

            activeCodec = codec
            needsCodecConfigPrepend.set(true)
            extractor.resetForNewDecoder()
            isDecoderRunning.set(true)

            inputThread = Thread({ runInputLoop() }, "dji-h264-input").apply {
                isDaemon = true
                start()
            }
            outputThread = Thread({ runOutputLoop(codec) }, "dji-h264-output").apply {
                isDaemon = true
                start()
            }
            publishMetrics()
        } catch (_: Exception) {
            isDecoderRunning.set(false)
            publishMetrics()
        }
    }

    /**
     * Stops the active [MediaCodec] instance when the UI surface is destroyed.
     */
    @Synchronized
    fun detachSurface() {
        isDecoderRunning.set(false)
        inputThread?.interrupt()
        outputThread?.interrupt()
        inputThread = null
        outputThread = null

        val codecToRelease = activeCodec
        activeCodec = null
        if (codecToRelease != null) {
            try {
                codecToRelease.stop()
            } catch (_: Exception) {
                // Ignore stop errors during teardown.
            }
            try {
                codecToRelease.release()
            } catch (_: Exception) {
                // Ignore release errors during teardown.
            }
        }
        chunkQueue.clear()
        publishMetrics()
    }

    /**
     * Enqueues a raw `0x574A` video chunk from the USB or UDP reader loop.
     */
    fun feedRawVideoChunk(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        bytesReceivedCounter.addAndGet(chunk.size.toLong())
        if (!chunkQueue.offer(chunk)) {
            chunkQueue.poll()
            chunkQueue.offer(chunk)
        }
    }

    /**
     * Releases all decoder threads and resources.
     */
    fun shutdown() {
        detachSurface()
    }

    private fun runInputLoop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_VIDEO)
        } catch (_: Exception) {
            // Fallback to default thread priority.
        }
        while (isDecoderRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                val chunk = chunkQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                extractor.feedChunk(chunk)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (_: Exception) {
                // Continue processing subsequent video chunks.
            }
        }
    }

    private fun queueAccessUnitToCodec(accessUnitBytes: ByteArray, isKeyframe: Boolean) {
        val codec = activeCodec ?: return
        if (!isDecoderRunning.get()) return

        val bytesToFeed = if (needsCodecConfigPrepend.get()) {
            val sps = extractor.latestSpsNalUnit
            val pps = extractor.latestPpsNalUnit
            if (sps != null && pps != null) {
                needsCodecConfigPrepend.set(false)
                sps + pps + accessUnitBytes
            } else {
                accessUnitBytes
            }
        } else {
            accessUnitBytes
        }

        try {
            var inputBufferIndex = -1
            var attempts = 0
            while (isDecoderRunning.get() && inputBufferIndex < 0 && attempts < 5) {
                inputBufferIndex = codec.dequeueInputBuffer(10_000L)
                attempts++
            }
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return
                inputBuffer.clear()
                val byteCount = minOf(bytesToFeed.size, inputBuffer.remaining())
                inputBuffer.put(bytesToFeed, 0, byteCount)
                val presentationTimeUs = System.nanoTime() / 1_000L
                val flags = if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    byteCount,
                    presentationTimeUs,
                    flags,
                )
            }
        } catch (_: Exception) {
            // Ignore transient codec buffer state errors.
        }
    }

    private fun runOutputLoop(codec: MediaCodec) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_VIDEO)
        } catch (_: Exception) {
            // Fallback to default thread priority.
        }
        val bufferInfo = MediaCodec.BufferInfo()
        var lastRenderTimestampNs = 0L

        while (isDecoderRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 16_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        val width = extractDisplayWidth(format)
                        val height = extractDisplayHeight(format)
                        if (width > 0 && height > 0) {
                            negotiatedWidth = width
                            negotiatedHeight = height
                            publishMetrics()
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        if (outputIndex >= 0) {
                            val nowNs = System.nanoTime()
                            val fps = rollingFps
                            val targetIntervalNs = if (fps >= 15f) {
                                (1_000_000_000L / fps.toLong()).coerceIn(16_666_667L, 40_000_000L)
                            } else {
                                33_333_333L
                            }

                            val scheduledTimeNs = if (lastRenderTimestampNs == 0L || (nowNs - lastRenderTimestampNs) > 100_000_000L) {
                                nowNs
                            } else {
                                minOf(
                                    maxOf(nowNs, lastRenderTimestampNs + targetIntervalNs),
                                    nowNs + MAX_PACING_LEAD_NANOS,
                                )
                            }

                            lastRenderTimestampNs = scheduledTimeNs
                            codec.releaseOutputBuffer(outputIndex, scheduledTimeNs)

                            val count = framesDecodedCounter.incrementAndGet()
                            if (count == 1L || count % 5L == 0L) {
                                publishMetrics()
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (_: IllegalStateException) {
                break
            } catch (_: Exception) {
                // Ignore transient output errors.
            }
        }
    }

    private fun extractDisplayWidth(format: MediaFormat): Int {
        if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
            return format.getInteger("crop-right") - format.getInteger("crop-left") + 1
        }
        return if (format.containsKey(MediaFormat.KEY_WIDTH)) {
            format.getInteger(MediaFormat.KEY_WIDTH)
        } else {
            1280
        }
    }

    private fun extractDisplayHeight(format: MediaFormat): Int {
        if (format.containsKey("crop-top") && format.containsKey("crop-bottom")) {
            return format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        }
        return if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
            format.getInteger(MediaFormat.KEY_HEIGHT)
        } else {
            720
        }
    }

    private fun publishMetrics() {
        val nowNanos = System.nanoTime()
        val elapsedSeconds = (nowNanos - lastRateCalculationNanos.get()) / 1_000_000_000.0
        if (elapsedSeconds >= 1.0) {
            val currentFrames = framesDecodedCounter.get()
            val currentBytes = bytesReceivedCounter.get()
            val deltaFrames = currentFrames - lastFramesDecodedForRate.getAndSet(currentFrames)
            val deltaBytes = currentBytes - lastBytesReceivedForRate.getAndSet(currentBytes)
            lastRateCalculationNanos.set(nowNanos)
            rollingFps = (deltaFrames / elapsedSeconds).toFloat()
            rollingBitrateKbps = ((deltaBytes * 8L) / (elapsedSeconds * 1000.0)).toLong()
        }

        onMetricsChanged(
            VideoDecoderMetrics(
                isSurfaceAttached = isDecoderRunning.get(),
                framesDecoded = framesDecodedCounter.get(),
                keyframesReceived = keyframesReceivedCounter.get(),
                videoWidth = negotiatedWidth,
                videoHeight = negotiatedHeight,
                framesPerSecond = rollingFps,
                bitrateKbps = rollingBitrateKbps,
            ),
        )
    }

    companion object {
        /** Maximum future scheduling lead time in nanoseconds (50 ms) to cap presentation pacing latency. */
        const val MAX_PACING_LEAD_NANOS: Long = 50_000_000L
    }
}
