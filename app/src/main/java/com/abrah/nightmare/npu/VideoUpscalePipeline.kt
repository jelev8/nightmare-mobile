package com.abrah.nightmare.npu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException

/**
 * Complete offline pipeline for one saved MP4. It owns temporary files and
 * returned poster; it borrows the QNN runner and leaves its lifetime to caller.
 */
class VideoUpscalePipeline(
    private val context: Context,
    private val runner: QnnRunner,
    private val modelFile: File,
) {
    data class Result(
        val file: File,
        val poster: Bitmap,
        /** Display-oriented dimensions, including the source rotation metadata. */
        val width: Int,
        val height: Int,
    )

    fun run(
        source: VideoUpscaleSource,
        scale: Int,
        outputFile: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (frame: Int, frameCount: Int, tile: Int, tileCount: Int, heartbeat: () -> Unit) -> Unit =
            { _, _, _, _, _ -> },
        log: (String) -> Unit = {},
    ): Result {
        require(scale in 2..4) { "video upscale scale must be 2x, 3x or 4x" }
        require(!outputFile.exists()) { "the output file already exists" }
        outputFile.parentFile?.mkdirs()
        val videoOnly = File(outputFile.parentFile, outputFile.name + ".video-only.part")
        val muxedPart = File(outputFile.parentFile, outputFile.name + ".mux.part")
        videoOnly.delete()
        muxedPart.delete()

        // Refuse oversized jobs before loading the QNN context or starting either codec.
        QnnUpscaleMemoryBudget.preflight(context, source.width, source.height, scale)
        val upscaler = QnnVideoFrameUpscaler(context, runner, modelFile)
        var encoderHeartbeat: () -> Unit = {}
        val actualPts = LongArray(source.frameCount) { Long.MIN_VALUE }
        var decoder: VideoFrameDecoder? = null
        var nextFrame = 0
        var lastOutput: Bitmap? = null
        var poster: Bitmap? = null
        var completed = false

        try {
            val outWidth = Math.multiplyExact(source.width, scale)
            val outHeight = Math.multiplyExact(source.height, scale)
            require(outWidth % 2 == 0 && outHeight % 2 == 0) {
                "the upscaled video dimensions must be even for H.264 encoding"
            }
            val encodeBitRate = maxOf(
                8_000_000L,
                outWidth.toLong() * outHeight * source.fps * 15L / 100L,
            ).coerceAtMost(150_000_000L).toInt()
            requireStorageBudget(source, encodeBitRate)
            log("video input: ${source.width}x${source.height}, ${source.frameCount} frames, ${source.fps} fps nominal")
            log("video output: ${outWidth}x${outHeight}, H.264 target bitrate ${encodeBitRate / 1_000_000} Mbps")
            log(if (source.audioTrack == null) "source audio: none" else "source audio: present; strict preservation enabled")

            VideoWriter.encode(
                count = source.frameCount,
                w = outWidth,
                h = outHeight,
                fps = source.fps,
                out = videoOnly,
                log = log,
                isCancelled = isCancelled,
                presentationTimeUsAt = { index ->
                    actualPts[index].also {
                        check(it != Long.MIN_VALUE) { "frame $index has no decoded presentation timestamp" }
                    }
                },
                orientationDegrees = source.rotationDegrees,
                colorStandard = source.colorStandard,
                colorTransfer = source.colorTransfer,
                colorRange = source.colorRange,
                bitRateOverride = encodeBitRate,
                onHeartbeatReady = { heartbeat -> encoderHeartbeat = heartbeat },
            ) { index ->
                encoderHeartbeat()
                if (isCancelled()) throw CancellationException("video upscale cancelled")
                if (index == 0) {
                    decoder?.close()
                    decoder = VideoFrameDecoder(source)
                    nextFrame = 0
                    lastOutput?.takeIf { !it.isRecycled }?.recycle()
                    lastOutput = null
                    poster?.takeIf { !it.isRecycled }?.recycle()
                    poster = null
                } else {
                    // VideoWriter has drawn and drained the previous frame before
                    // requesting the next one, so its bitmap can now be released.
                    lastOutput?.takeIf { !it.isRecycled }?.recycle()
                    lastOutput = null
                }
                check(index == nextFrame) { "video encoder requested frame $index after $nextFrame" }
                val decoded = decoder?.nextFrame(isCancelled, encoderHeartbeat)
                    ?: throw IllegalStateException("video decoder ended at frame $index of ${source.frameCount}")
                try {
                    val expectedPts = source.framePtsUs[index]
                    check(decoded.presentationTimeUs == expectedPts) {
                        "decoded frame $index timestamp changed: expected $expectedPts, got ${decoded.presentationTimeUs}"
                    }
                    actualPts[index] = decoded.presentationTimeUs - source.timelineOriginUs
                    check(actualPts[index] >= 0L) { "decoded frame timestamp precedes the common audio/video timeline" }

                    val upscaled = upscaler.upscale(
                        decoded.bitmap,
                        scale = scale,
                        isCancelled = isCancelled,
                    ) { tile, total ->
                        encoderHeartbeat()
                        onProgress(index + 1, source.frameCount, tile, total, encoderHeartbeat)
                        encoderHeartbeat()
                    }
                    lastOutput = upscaled
                    if (index == 0) poster = makePoster(upscaled, source.rotationDegrees)
                    nextFrame++
                    upscaled
                } finally {
                    decoded.bitmap.recycle()
                }
            }

            check(nextFrame == source.frameCount) {
                "only $nextFrame of ${source.frameCount} source frames were processed"
            }
            // Flush the decoder once more: a mismatch between MP4 samples and
            // decoded frames must fail rather than silently truncate the clip.
            val extra = decoder?.nextFrame(isCancelled)
            if (extra != null) {
                extra.bitmap.recycle()
                throw IllegalStateException("the decoder produced more frames than the source sample scan")
            }
            decoder?.close()
            decoder = null
            lastOutput?.takeIf { !it.isRecycled }?.recycle()
            lastOutput = null
            // The model context is no longer needed; free NPU memory before audio remux.
            runner.release(modelFile)
            check(videoOnly.isFile && videoOnly.length() > 0L) { "the video encoder produced an empty file" }

            if (source.audioTrack == null) {
                check(videoOnly.renameTo(outputFile)) { "could not commit the encoded video file" }
            } else {
                StrictVideoAudioMuxer.mux(
                    source = source,
                    videoOnly = videoOnly,
                    output = outputFile,
                    orientationDegrees = source.rotationDegrees,
                    isCancelled = isCancelled,
                    log = log,
                )
            }
            verifyOutput(outputFile, source.audioTrack != null, outWidth, outHeight)
            val posterBitmap = poster ?: throw IllegalStateException("the first output frame was not captured")
            poster = null // transfer ownership to caller
            val displayWidth = if (source.rotationDegrees == 90 || source.rotationDegrees == 270) outHeight else outWidth
            val displayHeight = if (source.rotationDegrees == 90 || source.rotationDegrees == 270) outWidth else outHeight
            completed = true
            return Result(outputFile, posterBitmap, displayWidth, displayHeight)
        } finally {
            decoder?.close()
            lastOutput?.takeIf { !it.isRecycled }?.recycle()
            poster?.takeIf { !it.isRecycled }?.recycle()
            videoOnly.delete()
            muxedPart.delete()
            if (!completed) outputFile.delete()
        }
    }

    private fun requireStorageBudget(source: VideoUpscaleSource, bitRate: Int) {
        val durationUs = source.videoEndRelativeUs.coerceAtLeast(1L)
        val baseVideoBytes = bitRate.toLong() * durationUs / 8_000_000L
        val estimatedVideoBytes = baseVideoBytes + baseVideoBytes / 5L
        val estimatedFinalBytes = estimatedVideoBytes + source.audioBytes
        // At peak, the intermediate and final MP4s can coexist; Results then
        // makes one private copy. Reserve an additional 128 MiB for filesystem
        // overhead, the poster, and app work.
        val peakBytes = estimatedFinalBytes * 2L + 128L * 1024L * 1024L
        val availableBytes = android.os.StatFs(context.cacheDir.absolutePath).availableBytes
        if (availableBytes < peakBytes) {
            fun mib(bytes: Long) = String.format(java.util.Locale.US, "%.1f", bytes / 1_048_576.0)
            throw IllegalArgumentException(
                "this upscale may need about ${mib(peakBytes)} MiB of free internal storage; " +
                    "only ${mib(availableBytes)} MiB is currently available"
            )
        }
    }

    private fun makePoster(frame: Bitmap, rotation: Int): Bitmap {
        val maxEdge = 512
        val factor = minOf(1f, maxEdge.toFloat() / maxOf(frame.width, frame.height))
        val width = maxOf(1, (frame.width * factor).toInt())
        val height = maxOf(1, (frame.height * factor).toInt())
        val small = if (width == frame.width && height == frame.height) frame.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw IllegalStateException("could not copy the video poster")
        else Bitmap.createScaledBitmap(frame, width, height, true)
        if (rotation == 0) return small
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(small, 0, 0, small.width, small.height, matrix, true)
        if (rotated !== small) small.recycle()
        return rotated
    }

    private fun verifyOutput(file: File, expectAudio: Boolean, width: Int, height: Int) {
        check(file.isFile && file.length() > 0L) { "the final MP4 is missing or empty" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val videoFormats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val audioCount = (0 until extractor.trackCount).count {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            check(videoFormats.size == 1) { "the final MP4 must contain exactly one readable video track" }
            val videoFormat = videoFormats.single()
            check(
                videoFormat.getInteger(MediaFormat.KEY_WIDTH) == width &&
                    videoFormat.getInteger(MediaFormat.KEY_HEIGHT) == height
            ) { "the final MP4 dimensions do not match the requested upscale output" }
            check(audioCount == if (expectAudio) 1 else 0) {
                if (expectAudio) "the source had audio, but the final MP4 has no audio track"
                else "an unexpected audio track appeared in the final MP4"
            }
        } finally {
            extractor.release()
        }
    }
}

/**
 * Copies the source's compressed audio packets without re-encoding. The source
 * audio and re-encoded video share one origin, so a non-zero track start offset
 * remains a real offset instead of being silently flattened to zero.
 */
private object StrictVideoAudioMuxer {
    private const val FALLBACK_SAMPLE_BUFFER = 4 * 1024 * 1024
    private const val MAX_SAMPLE_BUFFER = 32 * 1024 * 1024

    fun mux(
        source: VideoUpscaleSource,
        videoOnly: File,
        output: File,
        orientationDegrees: Int,
        isCancelled: () -> Boolean,
        log: (String) -> Unit,
    ) {
        val audioTrack = source.audioTrack
            ?: throw IllegalArgumentException("audio mux requested for a source without audio")
        val video = MediaExtractor()
        val audio = MediaExtractor()
        val part = File(output.parentFile, output.name + ".mux.part")
        part.delete()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            video.setDataSource(videoOnly.absolutePath)
            audio.setDataSource(source.file.absolutePath)
            val videoTrack = (0 until video.trackCount).firstOrNull {
                video.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalStateException("encoded intermediate has no video track")
            video.selectTrack(videoTrack)
            audio.selectTrack(audioTrack)
            val videoFormat = video.getTrackFormat(videoTrack)
            val audioFormat = audio.getTrackFormat(audioTrack)

            val writer = MediaMuxer(part.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            if (orientationDegrees != 0) writer.setOrientationHint(orientationDegrees)
            val videoOutTrack = writer.addTrack(videoFormat)
            val audioOutTrack = writer.addTrack(audioFormat)
            writer.start()
            started = true

            var videoBuffer = sampleBuffer(videoFormat)
            var audioBuffer = sampleBuffer(audioFormat)
            val info = MediaCodec.BufferInfo()
            var videoSample = read(video, videoBuffer).also { if (it != null) videoBuffer = it.buffer }
            var audioSample = read(audio, audioBuffer).also { if (it != null) audioBuffer = it.buffer }
            var audioSamples = 0L
            var videoSamples = 0L
            var lastVideoPts = -1L
            var lastAudioPts = -1L

            while (videoSample != null || audioSample != null) {
                if (isCancelled()) throw CancellationException("video upscale cancelled")
                val videoPts = if (videoSample != null) video.sampleTime else Long.MAX_VALUE
                val audioSourcePts = if (audioSample != null) audio.sampleTime else Long.MAX_VALUE
                val audioPts = if (audioSample != null) audioSourcePts - source.timelineOriginUs else Long.MAX_VALUE

                if (videoSample != null && (audioSample == null || videoPts <= audioPts)) {
                    val sample = checkNotNull(videoSample)
                    check(videoPts >= 0L && videoPts > lastVideoPts) {
                        "encoded video samples have missing, duplicate or non-monotonic timestamps"
                    }
                    lastVideoPts = videoPts
                    val flags = if (video.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, sample.size, videoPts, flags)
                    sample.buffer.position(0)
                    sample.buffer.limit(sample.size)
                    writer.writeSampleData(videoOutTrack, sample.buffer, info)
                    videoSamples++
                    video.advance()
                    videoSample = read(video, videoBuffer).also { if (it != null) videoBuffer = it.buffer }
                } else {
                    // Preserve the complete compressed audio track, including a
                    // legitimate start offset or a short tail beyond the final
                    // video sample. Never silently trim source audio.
                    check(audio.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
                        "DRM-encrypted audio is not supported"
                    }
                    val sample = audioSample!!
                    check(audioPts >= 0L && audioPts > lastAudioPts) {
                        "source audio samples have missing, duplicate or non-monotonic timestamps"
                    }
                    lastAudioPts = audioPts
                    info.set(0, sample.size, audioPts, 0)
                    sample.buffer.position(0)
                    sample.buffer.limit(sample.size)
                    writer.writeSampleData(audioOutTrack, sample.buffer, info)
                    audioSamples++
                    audio.advance()
                    audioSample = read(audio, audioBuffer).also { if (it != null) audioBuffer = it.buffer }
                }
            }

            check(videoSamples > 0L) { "no video samples were copied into the final MP4" }
            check(audioSamples > 0L) { "the source had an audio track, but no audio samples could be preserved" }
            writer.stop()
            started = false
            writer.release()
            muxer = null
            check(part.isFile && part.length() > 0L) { "the audio/video mux produced an empty file" }
            check(part.renameTo(output)) { "could not commit the audio-preserved MP4" }
            log("audio preserved: $audioSamples compressed samples; video samples: $videoSamples")
        } catch (t: Throwable) {
            part.delete()
            throw t
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { video.release() }
            runCatching { audio.release() }
        }
    }

    private fun sampleBuffer(format: MediaFormat): ByteBuffer {
        val declared = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(0) else 0
        val capacity = maxOf(FALLBACK_SAMPLE_BUFFER, declared).coerceAtMost(MAX_SAMPLE_BUFFER)
        return ByteBuffer.allocateDirect(capacity)
    }

    private data class EncodedSample(val buffer: ByteBuffer, val size: Int)

    private fun read(extractor: MediaExtractor, reusable: ByteBuffer): EncodedSample? {
        val expectedSize = extractor.sampleSize
        if (expectedSize < 0L) return null
        check(expectedSize > 0L) { "compressed media track contains an empty sample" }
        check(expectedSize <= MAX_SAMPLE_BUFFER.toLong()) {
            "compressed media sample ($expectedSize bytes) exceeds the $MAX_SAMPLE_BUFFER-byte safe mux limit"
        }
        val buffer = if (expectedSize > reusable.capacity().toLong())
            ByteBuffer.allocateDirect(expectedSize.toInt()) else reusable
        buffer.clear()
        val size = extractor.readSampleData(buffer, 0)
        check(size > 0 && size.toLong() == expectedSize) {
            "could not read the complete compressed media sample"
        }
        return EncodedSample(buffer, size)
    }
}
