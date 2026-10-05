package dev.vibris.api

/** Sampling is owned by the active pipeline; an absent pass selects the frame-end boundary. */
@JvmRecord
data class TextureAnalysisRequest @JvmOverloads constructor(
    val resource: CapturePlan.ResourceSelector,
    val passId: String? = null,
    val accumulation: TextureAccumulation? = null,
) {
    fun outputPlan(): CapturePlan {
        val options = requireNotNull(accumulation)
        val outputs = ArrayList<CapturePlan.ArtifactOutputSpec>()
        for (luma in listOf(false, true)) {
            if (luma && options.lumaWeights.isEmpty()) continue
            for (output in options.outputs + listOf("valid_count", "valid_batches", "incomplete_batches")) {
                val prefix = options.artifactName + if (luma) ".luma.$output" else ".$output"
                outputs.add(CapturePlan.ArtifactOutputSpec("$prefix.bin", CapturePlan.ArtifactFormat.BIN,
                    CapturePlan.ArtifactRole.PRIMARY, null))
                outputs.add(CapturePlan.ArtifactOutputSpec("$prefix.json", CapturePlan.ArtifactFormat.JSON,
                    CapturePlan.ArtifactRole.METADATA, null))
            }
        }
        return CapturePlan(listOf(CapturePlan.Target(resource, CapturePlan.ArtifactFormat.BIN,
            options.artifactName, outputs)))
    }
}

@JvmRecord
data class TextureAccumulation(
    val frames: Int,
    val batches: Int,
    @field:DefensiveSnapshot val lumaWeights: List<Double>,
    @field:DefensiveSnapshot val outputs: List<String>,
    val artifactName: String,
) {
    init {
        require(frames in 2..65535 && batches in 2..64 && frames >= 2 * batches)
        require(lumaWeights.isEmpty() || lumaWeights.size == 3 && lumaWeights.all(Double::isFinite))
        require(outputs.isNotEmpty() && outputs.distinct().size == outputs.size &&
            outputs.all { it in setOf("mean", "variance", "stderr", "flicker", "nonfinite") })
        require(artifactName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
    }
}

@JvmRecord
data class TextureChannelStats(
    val channel: Int,
    val finiteCount: Long,
    val min: Double?, val max: Double?, val mean: Double?, val std: Double?,
    val p1: Double?, val p50: Double?, val p99: Double?,
    val nan: Long, val positiveInf: Long, val negativeInf: Long, val zero: Long,
)

@JvmRecord
data class TextureAnalysisResult(
    val frameId: Long,
    @field:DefensiveSnapshot val channels: List<TextureChannelStats>,
    val summaryJson: String,
    val capture: CaptureResult?,
)
