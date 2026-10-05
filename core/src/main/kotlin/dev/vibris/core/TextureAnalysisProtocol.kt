package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.ResourceCatalog
import dev.vibris.api.TextureAccumulation
import dev.vibris.api.TextureAnalysisRequest
import dev.vibris.api.TextureAnalysisResult
import dev.vibris.protocol.v2.Action
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.TextureStatsReceipt

internal object TextureAnalysisProtocol {
    fun request(action: Action, catalog: ResourceCatalog): TextureAnalysisRequest {
        val selector = if (action.hasTextureStats()) action.textureStats.resource else action.accumulateTexture.resource
        val passId = if (action.hasTextureStats()) action.textureStats.passId else action.accumulateTexture.passId
        val region = if (action.hasTextureStats()) action.textureStats.takeIf { it.hasRegion() }?.region
            else action.accumulateTexture.takeIf { it.hasRegion() }?.region
        val resource = catalog.resources.singleOrNull { it.logicalName == selector.logicalName }
            ?: throw unknown("texture", selector.logicalName, catalog.resources.map { it.logicalName })
        if (passId.isNotEmpty() && catalog.passes.none { it.passId == passId }) {
            throw unknown("pass", passId, catalog.passes.map { it.passId })
        }
        try {
            require(resource.kind == ResourceCatalog.ResourceKind.TEXTURE ||
                resource.kind == ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER) { "Resource is not a texture" }
            require(selector.mipLevel in 0 until maxOf(1, resource.mipLevels) &&
                selector.layer in 0 until maxOf(1, resource.layers)) { "Texture subresource is out of range" }
            val view = if (resource.kind == ResourceCatalog.ResourceKind.TEXTURE) {
                ResourceCatalog.TextureView.valueOf(selector.view.name.removePrefix("TEXTURE_VIEW_")).also {
                    require(it in resource.availableViews) { "Texture view is unavailable: $it" }
                }
            } else null
            val crop = region?.let { CapturePlan.TextureRegion(it.x, it.y, it.width, it.height) }
            crop?.validate(maxOf(1, resource.width shr selector.mipLevel), maxOf(1, resource.height shr selector.mipLevel))
            val accumulation = if (action.hasAccumulateTexture()) action.accumulateTexture.let {
                TextureAccumulation(it.frames, it.batches.takeIf { count -> count != 0 } ?: 16,
                    it.lumaWeightsList, it.outputsList.ifEmpty { listOf("mean", "variance", "stderr") }, it.artifactName)
            } else null
            return TextureAnalysisRequest(CapturePlan.ResourceSelector(resource.kind, resource.logicalName,
                view, selector.mipLevel, selector.layer, crop), passId.takeIf(String::isNotEmpty), accumulation)
        } catch (failure: IllegalArgumentException) {
            throw RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_INVALID_REQUEST, failure.message).withDetails(
                mapOf("available_views" to resource.availableViews.joinToString(",") { it.name.lowercase() }))
        }
    }

    private fun unknown(kind: String, id: String, candidates: List<String>) = RuntimeJobExecutor.Failure(
        ErrorCode.ERROR_CODE_RESOURCE_NOT_FOUND, "Unknown $kind: $id",
    ).withDetails(mapOf("suggestions" to kotlinx.serialization.json.JsonArray(
        NameSuggestions.suggest(id, candidates).map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()))

    fun stats(result: TextureAnalysisResult): TextureStatsReceipt = TextureStatsReceipt.newBuilder()
        .setFrameId(result.frameId).addAllChannels(result.channels.map { stats ->
            dev.vibris.protocol.v2.TextureChannelStats.newBuilder().setChannel(stats.channel)
                .setFiniteCount(stats.finiteCount).setNan(stats.nan).setPositiveInf(stats.positiveInf)
                .setNegativeInf(stats.negativeInf).setZero(stats.zero).apply {
                    stats.min?.let(::setMin); stats.max?.let(::setMax); stats.mean?.let(::setMean)
                    stats.std?.let(::setStd); stats.p1?.let(::setP1); stats.p50?.let(::setP50); stats.p99?.let(::setP99)
                }.build()
        }).build()
}
