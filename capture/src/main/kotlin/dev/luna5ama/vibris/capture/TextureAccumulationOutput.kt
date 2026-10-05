package dev.luna5ama.vibris.capture

import dev.vibris.api.*
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** The same planned filenames are used for reservation, writes, manifest and receipts. */
object TextureAccumulationOutput {
    private val diagnostics = listOf("valid_count", "valid_batches", "incomplete_batches")

    class Snapshot internal constructor(
        internal val accumulator: GpuTextureAccumulator,
        internal val normal: Array<FloatArray>,
        internal val luma: Array<FloatArray>?,
    )

    @JvmStatic fun snapshot(request: TextureAnalysisRequest, accumulator: GpuTextureAccumulator): Snapshot =
        Snapshot(accumulator, accumulator.readStates(false),
            if (requireNotNull(request.accumulation).lumaWeights.isEmpty()) null else accumulator.readStates(true))

    @JvmStatic fun outputPlan(request: TextureAnalysisRequest): CapturePlan {
        return request.outputPlan()
    }

    @JvmStatic fun write(request: TextureAnalysisRequest, accumulator: GpuTextureAccumulator,
        sink: ArtifactSink, cancellation: CancellationToken): TextureAnalysisResult =
        write(request, snapshot(request, accumulator), sink, cancellation)

    @JvmStatic fun write(request: TextureAnalysisRequest, snapshot: Snapshot,
        sink: ArtifactSink, cancellation: CancellationToken): TextureAnalysisResult {
        val accumulator = snapshot.accumulator
        val accumulation = requireNotNull(request.accumulation)
        val summaries = ArrayList<JsonObject>()
        val plan = outputPlan(request)
        var allStandard = accumulator.batchLengths.distinct().size == 1
        val countFiles = ArrayList<JsonObject>()
        for (luma in listOf(false, true)) {
            if (luma && accumulation.lumaWeights.isEmpty()) continue
            cancellation.throwIfCancellationRequested()
            val state = if (luma) requireNotNull(snapshot.luma) else snapshot.normal
            val channels = if (luma) 1 else accumulator.channels
            val stride = if (luma) 1 else 4
            val pixels = accumulator.width * accumulator.height
            val batchMeans = if (luma) accumulator.lumaScreenBatchMeans.map { listOf(it) }
                else accumulator.screenBatchMeans
            val batchPixelCounts = if (luma) accumulator.lumaScreenBatchPixelCounts.map { listOf(it) }
                else accumulator.screenBatchPixelCounts
            val batchMissing = if (luma) accumulator.lumaScreenBatchNonfiniteSamples.map { listOf(it) }
                else accumulator.screenBatchNonfiniteSamples
            val qualification = (0 until channels).map { channel ->
                accumulator.batchLengths.distinct().size == 1 && (0 until pixels).all { pixel ->
                    val index = pixel * stride + channel
                    state[2][index] == accumulation.frames.toFloat() && state[7][index] == accumulation.batches.toFloat() &&
                        state[11][index] == 0f
                }
            }
            allStandard = allStandard && qualification.all { it }
            fun outputValue(output: String, index: Int): Float = when (output) {
                "mean" -> if (state[2][index] > 0) state[0][index] else Float.NaN
                "variance" -> if (state[2][index] > 1) state[1][index] / (state[2][index] - 1) else Float.NaN
                "stderr" -> if (state[7][index] > 1) sqrt(maxOf(0f,
                    state[6][index] / (state[7][index] - 1) / state[7][index])) else Float.NaN
                "flicker" -> if (state[12][index] > 0) state[9][index] / state[12][index] else Float.NaN
                "nonfinite" -> state[10][index]
                "valid_count" -> state[2][index]
                "valid_batches" -> state[7][index]
                "incomplete_batches" -> state[11][index]
                else -> error("Unknown accumulation output")
            }
            for (channel in 0 until channels) {
                fun finiteMean(value: (Int) -> Double): Double? {
                    var sum = 0.0; var count = 0
                    for (pixel in 0 until pixels) {
                        val x = value(pixel * stride + channel)
                        if (x.isFinite()) { sum += x; count++ }
                    }
                    return (sum / count).takeIf(Double::isFinite)
                }
                val finiteBatches = batchMeans.mapNotNull { it[channel] }
                val mean = finiteBatches.takeIf { it.isNotEmpty() }?.average()
                val se = if (finiteBatches.size < 2) null else sqrt(finiteBatches.sumOf {
                    (it - mean!!) * (it - mean) } / (finiteBatches.size - 1) / finiteBatches.size)
                summaries.add(buildJsonObject {
                    put("channel", channel); put("luma", luma)
                    put("screen_mean", finiteMean { outputValue("mean", it).toDouble() }?.let(::JsonPrimitive) ?: JsonNull)
                    put("screen_mean_stderr", se?.let(::JsonPrimitive) ?: JsonNull)
                    put("mean_pixel_std", finiteMean { sqrt(outputValue("variance", it).toDouble()) }?.let(::JsonPrimitive) ?: JsonNull)
                    put("mean_relative_std", finiteMean {
                        val m = outputValue("mean", it).toDouble()
                        if (m == 0.0) Double.NaN else sqrt(outputValue("variance", it).toDouble()) / abs(m)
                    }?.let(::JsonPrimitive) ?: JsonNull)
                    put("mean_flicker", finiteMean { outputValue("flicker", it).toDouble() }?.let(::JsonPrimitive) ?: JsonNull)
                    put("nonfinite_samples", (0 until pixels).sumOf { state[10][it * stride + channel].toLong() })
                    put("standard_batch_means_se", qualification[channel])
                })
            }
            val prefix = accumulation.artifactName + if (luma) ".luma" else ""
            countFiles.add(buildJsonObject {
                put("luma", luma); put("valid_count_path", "$prefix.valid_count.bin")
                put("valid_batches_path", "$prefix.valid_batches.bin")
                put("incomplete_batches_path", "$prefix.incomplete_batches.bin")
            })
            val common = buildJsonObject {
                put("frames", accumulation.frames); put("batches", accumulation.batches)
                put("pass_id", request.passId?.let(::JsonPrimitive) ?: JsonNull)
                put("start_frame", accumulator.startFrame); put("end_frame", accumulator.endFrame)
                put("batch_lengths", JsonArray(accumulator.batchLengths.map(::JsonPrimitive)))
                put("incomplete_batch_indices", JsonArray(accumulator.batchLengths.indices.filter {
                    accumulator.batchLengths[it] != accumulator.batchLengths.max() || batchMissing[it].any { count -> count > 0 }
                }.map(::JsonPrimitive)))
                put("screen_batch_means", JsonArray(batchMeans.map { row ->
                    JsonArray(row.map { it?.let(::JsonPrimitive) ?: JsonNull }) }))
                put("screen_batch_pixel_counts", JsonArray(batchPixelCounts.map { row ->
                    JsonArray(row.map(::JsonPrimitive)) }))
                put("screen_batch_nonfinite_samples", JsonArray(batchMissing.map { row ->
                    JsonArray(row.map(::JsonPrimitive)) }))
                put("standard_batch_means_se", qualification.all { it })
                put("stderr_estimator", "unweighted_valid_batch_means")
                put("valid_count_path", "$prefix.valid_count.bin")
                put("valid_batches_path", "$prefix.valid_batches.bin")
                put("incomplete_batches_path", "$prefix.incomplete_batches.bin")
            }
            for (output in accumulation.outputs + diagnostics) {
                cancellation.throwIfCancellationRequested()
                val file = "$prefix.$output"
                sink.open("$file.bin").use { stream ->
                    val row = ByteBuffer.allocate(accumulator.width * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                    for (y in 0 until accumulator.height) {
                        row.clear()
                        for (x in 0 until accumulator.width) for (channel in 0 until channels) {
                            row.putFloat(outputValue(output, (y * accumulator.width + x) * stride + channel))
                        }
                        stream.write(row.array())
                    }
                }
                val json = buildJsonObject {
                    put("width", accumulator.width); put("height", accumulator.height); put("depth", 1)
                    put("channel_count", channels); put("scalar_type", "FLOAT32"); put("endianness", "little")
                    put("internal_format", if (channels == 1) "r32f" else "${listOf("r", "rg", "rgb", "rgba")[channels - 1]}32f")
                    put("numeric_class", "float"); put("output", output); put("accumulation", common)
                    put("source_numeric_class", accumulator.metadata.numericClass)
                    put("units", if (accumulator.metadata.numericClass in listOf("unorm", "snorm")) "gpu_normalized" else "native")
                    put("source_width", accumulator.metadata.width); put("source_height", accumulator.metadata.height)
                    request.resource.region?.let { put("region", buildJsonObject {
                        put("x", it.x); put("y", it.y); put("width", it.width); put("height", it.height) }) }
                }
                sink.open("$file.json").use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val descriptor = ResourceCatalog.ResourceDescriptor.of(request.resource.logicalName,
            request.resource.kind, if (request.resource.kind == ResourceCatalog.ResourceKind.TEXTURE)
                listOf(ResourceCatalog.TextureView.CURRENT) else emptyList(),
            accumulator.width, accumulator.height, 1, 1, 1, "rgba32f", accumulator.channels,
            ResourceCatalog.ScalarType.FLOAT32, accumulator.width.toLong() * accumulator.height * accumulator.channels * 4,
            accumulator.endFrame, "accumulation", "analysis", "texture_2d", "RGBA", "float", 32,
            "RGBA", "FLOAT", request.resource.region, accumulator.metadata.width, accumulator.metadata.height)
        val summary = buildJsonObject {
            put("channels", JsonArray(summaries)); put("standard_batch_means_se", allStandard)
            put("sample_counts", JsonArray(countFiles)); put("stderr_estimator", "unweighted_valid_batch_means")
            put("unequal_batch_lengths", accumulator.batchLengths.distinct().size > 1)
        }
        val capture = CaptureResult(accumulator.endFrame, listOf(CaptureResult.ArtifactGroup(accumulation.artifactName,
            descriptor, plan.targets.single().outputs.map { CaptureResult.CapturedArtifact(it.fileName, it.format, it.role, null) })))
        return TextureAnalysisResult(accumulator.endFrame, emptyList(), summary.toString(), capture)
    }
}
