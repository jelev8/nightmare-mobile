package com.abrah.nightmare.npu

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.util.Arrays
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * Runs the two built-in 4x QNN image upscalers against one decoded video frame.
 *
 * The QNN graph is fixed at 192x192 -> 768x768. Tiles and their 12-pixel
 * minimum overlap follow the same placement and linear fade used by the
 * upstream QNN image-upscale path. Only a 768-row output stripe is accumulated;
 * a full-frame Float32 RGB accumulator would be several hundred MiB at 1080p.
 *
 * Call from Dispatchers.IO. This class borrows [runner] and never releases it.
 */
class QnnVideoFrameUpscaler(
    private val context: Context,
    private val runner: QnnRunner,
    private val modelFile: File,
) {
    private data class Tensor(
        val direction: String,
        val name: String,
        val shape: List<Int>,
        val dataType: Int,
    )

    private val input: Tensor
    private val output: Tensor

    init {
        require(modelFile.isFile && modelFile.length() > 0L) {
            "the installed upscaler context is missing or empty"
        }
        val description = runner.describe(modelFile)
        val tensors = TENSOR_PATTERN.findAll(description).map { match ->
            Tensor(
                direction = match.groupValues[1],
                name = match.groupValues[2].trim(),
                shape = match.groupValues[3].split(',').map(String::toInt),
                dataType = match.groupValues[4].toInt(),
            )
        }.toList()
        val inputs = tensors.filter { it.direction == "in" }
        val outputs = tensors.filter { it.direction == "out" }
        require(inputs.size == 1 && outputs.size == 1) {
            "expected one QNN input and output; found ${inputs.size}/${outputs.size}"
        }
        input = inputs.single()
        output = outputs.single()
        require(input.shape == INPUT_SHAPE && output.shape == OUTPUT_SHAPE) {
            "unsupported QNN tensor shapes: ${input.shape} -> ${output.shape}"
        }
        require(input.dataType == FLOAT32_QNN_TYPE && output.dataType == FLOAT32_QNN_TYPE) {
            "expected float32 QNN tensors; got ${input.dataType}/${output.dataType}"
        }
    }

    /**
     * @param scale Final scale presented to the user (2x, 3x or 4x). The model
     * still executes at its native 4x scale; 2x/3x are downsampled afterward.
     * @param isCancelled Polled between tiles so cancellation never interrupts a
     * native QNN call or leaves a half-produced frame to be saved.
     */
    fun upscale(
        source: Bitmap,
        scale: Int = 4,
        isCancelled: () -> Boolean = { false },
        onTile: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): Bitmap {
        require(scale in 2..4) { "video upscale scale must be 2x, 3x or 4x" }
        require(source.width > 0 && source.height > 0) { "invalid source frame dimensions" }

        val sourceWidth = source.width
        val sourceHeight = source.height
        val targetWidth = sourceWidth.toLong() * scale
        val targetHeight = sourceHeight.toLong() * scale
        val nativeWidthLong = sourceWidth.toLong() * NATIVE_SCALE
        val nativeHeightLong = sourceHeight.toLong() * NATIVE_SCALE
        require(targetWidth <= Int.MAX_VALUE && targetHeight <= Int.MAX_VALUE &&
            nativeWidthLong <= Int.MAX_VALUE && nativeHeightLong <= Int.MAX_VALUE) {
            "the requested output dimensions are too large"
        }

        var working = source
        var ownsWorking = false
        var nativeOutput: Bitmap? = null
        try {
            if (source.config == Bitmap.Config.HARDWARE) {
                working = source.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("could not make a software copy of the decoded frame")
                ownsWorking = true
            }

            // The reference path raises the shorter input edge to 192 before
            // tiling, then resizes the 4x result back to exactly source*4.
            val shortEdge = minOf(working.width, working.height)
            if (shortEdge < TILE_SIZE) {
                val factor = TILE_SIZE.toFloat() / shortEdge.toFloat()
                val w = maxOf(TILE_SIZE, (working.width * factor).toInt())
                val h = maxOf(TILE_SIZE, (working.height * factor).toInt())
                val enlarged = Bitmap.createScaledBitmap(working, w, h, true)
                if (ownsWorking) working.recycle()
                working = enlarged
                ownsWorking = enlarged !== source
            }

            val inputWidth = working.width
            val inputHeight = working.height
            val outputWidth = inputWidth.toLong() * NATIVE_SCALE
            val outputHeight = inputHeight.toLong() * NATIVE_SCALE
            require(outputWidth <= Int.MAX_VALUE && outputHeight <= Int.MAX_VALUE &&
                outputWidth * outputHeight <= Int.MAX_VALUE) {
                "the native 4x frame is too large for Android Bitmap"
            }
            val outW = outputWidth.toInt()
            val outH = outputHeight.toInt()
            checkMemoryBudget(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                inputWidth = inputWidth,
                inputHeight = inputHeight,
                outputWidth = outW,
                outputHeight = outH,
                targetWidth = targetWidth.toInt(),
                targetHeight = targetHeight.toInt(),
            )

            val xs = QnnUpscaleTiling.positions(inputWidth)
            val ys = QnnUpscaleTiling.positions(inputHeight)
            val totalTiles = xs.size * ys.size
            require(totalTiles > 0) { "no upscale tiles were produced" }

            // The weight map is separable because each tile's 2D weight is
            // fadeX[x] * fadeY[y]. Precomputing these sums avoids a full-frame
            // weight allocation while preserving the upstream normalization.
            val weightX = FloatArray(outW)
            val weightY = FloatArray(outH)
            for (x in xs) {
                val start = x * NATIVE_SCALE
                for (i in 0 until OUTPUT_TILE_SIZE) {
                    weightX[start + i] += QnnUpscaleTiling.fade(i)
                }
            }
            for (y in ys) {
                val start = y * NATIVE_SCALE
                for (i in 0 until OUTPUT_TILE_SIZE) {
                    weightY[start + i] += QnnUpscaleTiling.fade(i)
                }
            }
            require(weightX.all { it > 0f } && weightY.all { it > 0f }) {
                "tile layout left uncovered output pixels"
            }

            nativeOutput = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

            // 3 channel planes, each 768 rows tall. This is bounded by output
            // width, not output height: roughly 71 MiB at 1920 input pixels wide.
            val stripePlane = OUTPUT_TILE_SIZE * outW
            val accum = FloatArray(3 * stripePlane)
            val tilePixels = IntArray(TILE_SIZE * TILE_SIZE)
            val tileInput = FloatArray(3 * TILE_SIZE * TILE_SIZE)
            val rowPixels = IntArray(outW)
            val tilePlane = OUTPUT_TILE_SIZE * OUTPUT_TILE_SIZE
            val tileFade = FloatArray(OUTPUT_TILE_SIZE) { QnnUpscaleTiling.fade(it) }
            var completed = 0

            for (yi in ys.indices) {
                if (isCancelled()) throw CancellationException("video upscale cancelled")
                val tileY = ys[yi]
                val globalY = tileY * NATIVE_SCALE

                for (tileX in xs) {
                    if (isCancelled()) throw CancellationException("video upscale cancelled")
                    val sourceX = tileX
                    val sourceY = tileY
                    working.getPixels(
                        tilePixels, 0, TILE_SIZE,
                        sourceX, sourceY, TILE_SIZE, TILE_SIZE,
                    )
                    pixelsToChw(tilePixels, tileInput)

                    val tensors = runner.run(modelFile, mapOf(input.name to tileInput))
                    val tileOutput = tensors[output.name]
                        ?: throw IllegalStateException("QNN output tensor ${output.name} is missing")
                    check(tileOutput.size == 3 * tilePlane) {
                        "QNN returned ${tileOutput.size} values; expected ${3 * tilePlane}"
                    }
                    val globalX = tileX * NATIVE_SCALE
                    for (ty in 0 until OUTPUT_TILE_SIZE) {
                        val wy = tileFade[ty]
                        val stripeRow = ty * outW + globalX
                        val tileRow = ty * OUTPUT_TILE_SIZE
                        for (tx in 0 until OUTPUT_TILE_SIZE) {
                            val weight = wy * tileFade[tx]
                            val dst = stripeRow + tx
                            val src = tileRow + tx
                            val red = tileOutput[src]
                            val green = tileOutput[tilePlane + src]
                            val blue = tileOutput[2 * tilePlane + src]
                            check(red.isFinite() && green.isFinite() && blue.isFinite()) {
                                "QNN output contains NaN or infinity"
                            }
                            accum[dst] += red * weight
                            accum[stripePlane + dst] += green * weight
                            accum[2 * stripePlane + dst] += blue * weight
                        }
                    }
                    completed++
                    onTile(completed, totalTiles)
                }

                // No later tile row can touch rows before the next tile's y.
                // Keep only the overlapping tail for the next row of tiles.
                val finalizeRows = if (yi + 1 < ys.size) {
                    (ys[yi + 1] - tileY) * NATIVE_SCALE
                } else OUTPUT_TILE_SIZE
                check(finalizeRows in 1..OUTPUT_TILE_SIZE) { "invalid vertical tile overlap" }

                for (row in 0 until finalizeRows) {
                    val absoluteY = globalY + row
                    val wy = weightY[absoluteY]
                    val base = row * outW
                    for (x in 0 until outW) {
                        val denominator = weightX[x] * wy
                        check(denominator > 0f) { "zero tile weight at ${x},${absoluteY}" }
                        val inv = 1f / denominator
                        val r = (accum[base + x] * inv).coerceIn(0f, 1f)
                        val g = (accum[stripePlane + base + x] * inv).coerceIn(0f, 1f)
                        val b = (accum[2 * stripePlane + base + x] * inv).coerceIn(0f, 1f)
                        rowPixels[x] = (0xFF shl 24) or
                            ((r * 255f).toInt() shl 16) or
                            ((g * 255f).toInt() shl 8) or
                            (b * 255f).toInt()
                    }
                    nativeOutput.setPixels(rowPixels, 0, outW, 0, absoluteY, outW, 1)
                }

                if (finalizeRows < OUTPUT_TILE_SIZE) {
                    val overlapRows = OUTPUT_TILE_SIZE - finalizeRows
                    val copyCount = overlapRows * outW
                    for (channel in 0 until 3) {
                        val channelBase = channel * stripePlane
                        System.arraycopy(
                            accum, channelBase + finalizeRows * outW,
                            accum, channelBase, copyCount,
                        )
                        Arrays.fill(
                            accum,
                            channelBase + copyCount,
                            channelBase + stripePlane,
                            0f,
                        )
                    }
                }
            }

            val native = nativeOutput
                ?: throw IllegalStateException("the native output bitmap was not created")
            val wantedW = targetWidth.toInt()
            val wantedH = targetHeight.toInt()
            val result = if (native.width == wantedW && native.height == wantedH) {
                native
            } else {
                Bitmap.createScaledBitmap(native, wantedW, wantedH, true).also {
                    if (it !== native) native.recycle()
                }
            }
            // Transfer ownership to the caller; finally must not recycle it.
            nativeOutput = null
            return result
        } finally {
            if (ownsWorking && !working.isRecycled) working.recycle()
            nativeOutput?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun pixelsToChw(pixels: IntArray, output: FloatArray) {
        val plane = TILE_SIZE * TILE_SIZE
        for (i in 0 until plane) {
            val argb = pixels[i]
            output[i] = ((argb ushr 16) and 0xFF) / 255f
            output[plane + i] = ((argb ushr 8) and 0xFF) / 255f
            output[2 * plane + i] = (argb and 0xFF) / 255f
        }
    }

    private fun checkMemoryBudget(
        sourceWidth: Int,
        sourceHeight: Int,
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ) = QnnUpscaleMemoryBudget.checkBudget(
        context, sourceWidth, sourceHeight, inputWidth, inputHeight,
        outputWidth, outputHeight, targetWidth, targetHeight,
    )

    private companion object {
        const val TILE_SIZE = 192
        const val OUTPUT_TILE_SIZE = 768
        const val NATIVE_SCALE = 4
        const val FLOAT32_QNN_TYPE = 0x0232
        val INPUT_SHAPE = listOf(1, 3, 192, 192)
        val OUTPUT_SHAPE = listOf(1, 3, 768, 768)
        val TENSOR_PATTERN = Regex("""(in|out) ([^:;]+):([0-9,]+):([0-9]+);""")
    }
}

/** Memory guard shared by the pre-decode preflight and the per-frame check. */
internal object QnnUpscaleMemoryBudget {
    private const val TILE_SIZE = 192
    private const val OUTPUT_TILE_SIZE = 768
    private const val NATIVE_SCALE = 4
    private const val RESERVE_BYTES = 64L * 1024L * 1024L

    /** Run before creating a decoder, encoder, QNN context or output bitmap. */
    fun preflight(context: Context, width: Int, height: Int, scale: Int) {
        require(width > 0 && height > 0) { "invalid source frame dimensions" }
        require(scale in 2..4) { "video upscale scale must be 2x, 3x or 4x" }
        val shortEdge = minOf(width, height)
        val factor = if (shortEdge < TILE_SIZE) TILE_SIZE.toFloat() / shortEdge else 1f
        val inputWidth = if (factor == 1f) width else maxOf(TILE_SIZE, (width * factor).toInt())
        val inputHeight = if (factor == 1f) height else maxOf(TILE_SIZE, (height * factor).toInt())
        val outputWidth = inputWidth.toLong() * NATIVE_SCALE
        val outputHeight = inputHeight.toLong() * NATIVE_SCALE
        val targetWidth = width.toLong() * scale
        val targetHeight = height.toLong() * scale
        require(outputWidth <= Int.MAX_VALUE && outputHeight <= Int.MAX_VALUE &&
            outputWidth * outputHeight <= Int.MAX_VALUE &&
            targetWidth <= Int.MAX_VALUE && targetHeight <= Int.MAX_VALUE
        ) { "the requested video frame dimensions are too large" }
        require(outputWidth % 2L == 0L && outputHeight % 2L == 0L) {
            "the native upscaled frame dimensions must be even"
        }
        require(targetWidth % 2L == 0L && targetHeight % 2L == 0L) {
            "the requested video output dimensions must be even"
        }
        checkBudget(
            context,
            width, height, inputWidth, inputHeight,
            outputWidth.toInt(), outputHeight.toInt(),
            targetWidth.toInt(), targetHeight.toInt(),
        )
    }

    fun checkBudget(
        context: Context,
        sourceWidth: Int,
        sourceHeight: Int,
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ) {
        val nativeOutputBytes = outputWidth.toLong() * outputHeight * 4L
        val targetBytes = if (targetWidth == outputWidth && targetHeight == outputHeight) 0L
            else targetWidth.toLong() * targetHeight * 4L
        val stripeBytes = 3L * OUTPUT_TILE_SIZE * outputWidth * 4L
        val tileBytes = 3L * OUTPUT_TILE_SIZE * OUTPUT_TILE_SIZE * 4L +
            3L * TILE_SIZE * TILE_SIZE * 4L
        val sourceBytes = sourceWidth.toLong() * sourceHeight * 4L +
            inputWidth.toLong() * inputHeight * 4L
        // Reserve room for decoder/encoder surfaces, QNN staging and normal app work.
        val estimate = nativeOutputBytes + targetBytes + stripeBytes + tileBytes + sourceBytes + RESERVE_BYTES

        val runtime = Runtime.getRuntime()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        val heapAvailable = (runtime.maxMemory() - heapUsed).coerceAtLeast(0L)
        val heapBudget = heapAvailable * 4L / 5L
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(memory)
        val systemBudget = if (manager != null) memory.availMem / 4L else Long.MAX_VALUE
        val safeBudget = minOf(heapBudget, systemBudget)

        if (estimate > safeBudget) {
            fun mib(bytes: Long) = String.format(Locale.US, "%.0f", bytes / 1_048_576.0)
            throw IllegalArgumentException(
                "this frame needs about ${mib(estimate)} MiB of working memory; " +
                    "the current safe budget is ${mib(safeBudget)} MiB. " +
                    "Use a smaller source video or close other apps and retry"
            )
        }
    }
}

/** Exact tile geometry and fade curve used by the QNN image-upscale reference. */
internal object QnnUpscaleTiling {
    fun positions(dimension: Int): List<Int> {
        require(dimension >= 192) { "tile dimension must be at least 192" }
        if (dimension == 192) return listOf(0)
        val tileSize = 192
        val minOverlap = 12
        val effectiveTileSize = tileSize - minOverlap
        val numTiles = 1 + (dimension - tileSize + effectiveTileSize - 1) / effectiveTileSize
        val totalDistance = dimension - tileSize
        val numStrides = numTiles - 1
        val baseStride = totalDistance / numStrides
        val remainder = totalDistance % numStrides
        val positions = ArrayList<Int>(numTiles)
        positions += 0
        var current = 0
        for (i in 0 until numStrides) {
            current += baseStride + if (i < remainder) 1 else 0
            positions += current
        }
        positions[positions.lastIndex] = dimension - tileSize
        return positions
    }

    fun fade(index: Int): Float {
        require(index in 0 until 768)
        return when {
            index < 24 -> (index + 1) / 24f
            index >= 744 -> (768 - index) / 24f
            else -> 1f
        }
    }
}
