package com.abrah.nightmare.npu

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.graphics.PixelFormat
import android.os.SystemClock
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Immutable facts collected from the original MP4 before any output is created.
 * Frame timestamps are presentation timestamps, not an assumed CFR frame index.
 */
data class VideoUpscaleSource(
    val file: File,
    val videoTrack: Int,
    val audioTrack: Int?,
    val videoFormat: MediaFormat,
    val codedWidth: Int,
    val codedHeight: Int,
    val crop: Rect,
    val rotationDegrees: Int,
    val fps: Int,
    val framePtsUs: LongArray,
    val timelineOriginUs: Long,
    val videoEndRelativeUs: Long,
) {
    val width: Int get() = crop.width()
    val height: Int get() = crop.height()
    val frameCount: Int get() = framePtsUs.size
    val outputPtsUs: LongArray
        get() = LongArray(framePtsUs.size) { framePtsUs[it] - timelineOriginUs }
}

/**
 * Inspects the source without decoding it. Unsupported HDR/BT.2020 inputs are
 * refused before allocating a decoder, an output file or a large bitmap.
 */
object VideoUpscaleInspector {
    private const val MAX_FRAMES = 300_000

    fun inspect(file: File): VideoUpscaleSource {
        require(file.isFile && file.length() > 0L) { "source video is missing or empty" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: throw IllegalArgumentException("the selected result has no video track")
            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
            val format = extractor.getTrackFormat(videoTrack)
            val codedWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            val codedHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            require(codedWidth > 0 && codedHeight > 0) { "invalid source video dimensions" }

            val transfer = format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
            if (transfer != null && transfer != 0 &&
                transfer != MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            ) {
                throw IllegalArgumentException("HDR or non-SDR video is not supported yet")
            }
            if (format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) {
                throw IllegalArgumentException("HDR metadata is present; only SDR video is supported")
            }
            val standard = format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)
            if (standard == MediaFormat.COLOR_STANDARD_BT2020) {
                throw IllegalArgumentException("BT.2020 video is not supported yet; use SDR BT.601/BT.709")
            }

            val crop = Rect(
                format.intOrNull(MediaFormat.KEY_CROP_LEFT) ?: 0,
                format.intOrNull(MediaFormat.KEY_CROP_TOP) ?: 0,
                (format.intOrNull(MediaFormat.KEY_CROP_RIGHT) ?: (codedWidth - 1)) + 1,
                (format.intOrNull(MediaFormat.KEY_CROP_BOTTOM) ?: (codedHeight - 1)) + 1,
            )
            require(crop.left >= 0 && crop.top >= 0 &&
                crop.right <= codedWidth && crop.bottom <= codedHeight &&
                crop.width() > 0 && crop.height() > 0
            ) { "the video has an invalid crop rectangle" }

            val rotation = format.intOrNull(MediaFormat.KEY_ROTATION) ?: 0
            require(rotation in setOf(0, 90, 180, 270)) {
                "unsupported video rotation metadata: $rotation degrees"
            }

            extractor.selectTrack(videoTrack)
            val pts = ArrayList<Long>()
            while (extractor.sampleTrackIndex >= 0) {
                val time = extractor.sampleTime
                require(time >= 0L) { "the video contains a sample without a presentation timestamp" }
                pts += time
                require(pts.size <= MAX_FRAMES) { "the source video contains too many frames for one job" }
                if (!extractor.advance()) break
            }
            require(pts.isNotEmpty()) { "the source video contains no decodable frames" }
            // MP4 samples can be stored in decode order when B-frames are used.
            // The decoder presents them in display order, so sort the source
            // presentation timestamps before using them as the frame-count/
            // duration reference.
            pts.sort()
            require(pts.zipWithNext().all { (a, b) -> b > a }) {
                "the source video has duplicate presentation timestamps"
            }

            val audioStart = audioTrack?.let { firstTrackSampleTime(file, it) }
            if (audioTrack != null && audioStart == null) {
                throw IllegalArgumentException("the source has an audio track but no readable audio samples")
            }
            val origin = minOf(pts.first(), audioStart ?: pts.first())
            val declaredDuration = format.longOrNull(MediaFormat.KEY_DURATION) ?: 0L
            val frameDelta = if (pts.size > 1) pts.last() - pts[pts.lastIndex - 1]
                else 1_000_000L / (format.intOrNull(MediaFormat.KEY_FRAME_RATE)?.takeIf { it > 0 } ?: 30)
            val inferredEnd = pts.last() + frameDelta.coerceAtLeast(1L)
            val declaredEnd = if (declaredDuration > 0L) pts.first() + declaredDuration else 0L
            val end = max(inferredEnd, declaredEnd)
            val durationFromFirst = (pts.last() - pts.first()).coerceAtLeast(1L)
            val measuredFps = if (pts.size > 1) {
                ((pts.size - 1) * 1_000_000.0 / durationFromFirst).roundToInt()
            } else 30
            val fps = format.intOrNull(MediaFormat.KEY_FRAME_RATE)
                ?.takeIf { it in 1..240 } ?: measuredFps.coerceIn(1, 240)

            return VideoUpscaleSource(
                file = file,
                videoTrack = videoTrack,
                audioTrack = audioTrack,
                videoFormat = format,
                codedWidth = codedWidth,
                codedHeight = codedHeight,
                crop = crop,
                rotationDegrees = rotation,
                fps = fps,
                framePtsUs = pts.toLongArray(),
                timelineOriginUs = origin,
                videoEndRelativeUs = end - origin,
            )
        } finally {
            extractor.release()
        }
    }

    private fun firstTrackSampleTime(file: File, track: Int): Long? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack(track)
            extractor.sampleTime.takeIf { it >= 0L }
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null
}

data class DecodedVideoFrame(val bitmap: Bitmap, val presentationTimeUs: Long)

/**
 * Sequential MediaCodec decoder targeting an RGBA ImageReader Surface.
 * One frame is materialised at a time; each Image is closed before returning.
 */
class VideoFrameDecoder(
    private val source: VideoUpscaleSource,
) : Closeable {
    private val extractor = MediaExtractor()
    private val reader: ImageReader
    private val decoder: MediaCodec
    private var inputEnded = false
    private var outputEnded = false
    private var closed = false

    init {
        var createdReader: ImageReader? = null
        var createdDecoder: MediaCodec? = null
        try {
            extractor.setDataSource(source.file.absolutePath)
            extractor.selectTrack(source.videoTrack)
            val mime = source.videoFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("source video MIME type is missing")
            val newReader = ImageReader.newInstance(
                source.codedWidth,
                source.codedHeight,
                PixelFormat.RGBA_8888,
                2,
            )
            createdReader = newReader
            val newDecoder = MediaCodec.createDecoderByType(mime)
            createdDecoder = newDecoder
            newDecoder.configure(source.videoFormat, newReader.surface, null, 0)
            newDecoder.start()
            reader = newReader
            decoder = newDecoder
        } catch (t: Throwable) {
            runCatching { createdDecoder?.stop() }
            runCatching { createdDecoder?.release() }
            runCatching { createdReader?.close() }
            runCatching { extractor.release() }
            throw IllegalStateException(
                "this phone could not initialise an RGBA video decoder for the source format: ${t.message}",
                t,
            )
        }
    }

    fun nextFrame(isCancelled: () -> Boolean = { false }): DecodedVideoFrame? {
        check(!closed) { "video decoder is closed" }
        if (outputEnded) return null
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (isCancelled()) throw CancellationException("video upscale cancelled")

            if (!inputEnded) {
                val inputIndex = decoder.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)
                        ?: throw IllegalStateException("video decoder returned no input buffer")
                    inputBuffer.clear()
                    val size = extractor.readSampleData(inputBuffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(
                            inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputEnded = true
                    } else {
                        val pts = extractor.sampleTime
                        check(pts >= 0L) { "decoder input sample has no presentation timestamp" }
                        decoder.queueInputBuffer(inputIndex, 0, size, pts, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = decoder.outputFormat
                    val width = format.intOrNull(MediaFormat.KEY_WIDTH)
                    val height = format.intOrNull(MediaFormat.KEY_HEIGHT)
                    check((width == null || width == source.codedWidth) &&
                        (height == null || height == source.codedHeight)
                    ) { "decoder output dimensions changed unexpectedly" }
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    val flags = info.flags
                    val eos = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val codecConfig = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val hasFrame = !codecConfig && info.presentationTimeUs >= 0L &&
                        (info.size > 0 || !eos)
                    if (hasFrame) {
                        val pts = info.presentationTimeUs
                        decoder.releaseOutputBuffer(outputIndex, true)
                        if (eos) outputEnded = true
                        val image = awaitImage(isCancelled)
                        val bitmap = try {
                            imageToBitmap(image, source.crop)
                        } finally {
                            image.close()
                        }
                        return DecodedVideoFrame(bitmap, pts)
                    } else {
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (eos) outputEnded = true
                    }
                }
            }
            if (outputEnded) return null
        }
    }

    private fun awaitImage(isCancelled: () -> Boolean): Image {
        val deadline = SystemClock.elapsedRealtime() + IMAGE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isCancelled()) throw CancellationException("video upscale cancelled")
            val image = reader.acquireNextImage()
            if (image != null) return image
            SystemClock.sleep(2L)
        }
        throw IllegalStateException("video decoder did not deliver an RGBA frame before timeout")
    }

    private fun imageToBitmap(image: Image, requestedCrop: Rect): Bitmap {
        check(image.planes.size == 1) { "decoder did not produce a packed RGBA image" }
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        check(pixelStride >= 4) { "decoder RGBA pixel stride is too small: $pixelStride" }
        val crop = Rect(requestedCrop).apply {
            intersect(image.cropRect)
        }
        check(!crop.isEmpty) { "decoded frame has an empty crop rectangle" }
        val pixels = IntArray(crop.width() * crop.height())
        val buffer: ByteBuffer = plane.buffer
        for (y in 0 until crop.height()) {
            val row = (crop.top + y) * rowStride
            for (x in 0 until crop.width()) {
                val offset = buffer.position() + row + (crop.left + x) * pixelStride
                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF
                val a = buffer.get(offset + 3).toInt() and 0xFF
                pixels[y * crop.width() + x] =
                    (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        runCatching { extractor.release() }
        runCatching { reader.close() }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private companion object {
        const val IMAGE_TIMEOUT_MS = 30_000L
    }
}
