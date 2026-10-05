package dev.luna5ama.vibris.capture

import dev.vibris.api.ArtifactSink
import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResourceNotFoundException
import dev.vibris.api.CaptureResult
import dev.vibris.api.ResourceCatalog
import java.io.IOException
import java.io.OutputStream
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.util.function.Function

object GlCapturePlanExecutor {
    @JvmStatic
    fun capture(
        plan: CapturePlan,
        sink: ArtifactSink,
        frameId: Long,
        cancellation: CancellationToken,
        resolveResource: Function<CapturePlan.Target, Int?>,
    ): CaptureResult {
        val targets = plan.targets.map { target ->
            ResolvedTarget(target, resolveResource.apply(target)
                ?: throw CaptureResourceNotFoundException(target.resource.logicalName))
        }
        val groups = ArrayList<CaptureResult.ArtifactGroup>()
        for ((target, glId) in targets) {
            cancellation.throwIfCancellationRequested()
            val outputs = ArrayList<CaptureResult.CapturedArtifact>()
            val payloads = target.outputs.filter { it.role != CapturePlan.ArtifactRole.METADATA }
            val metadata = when (target.resource.kind) {
                ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER,
                ResourceCatalog.ResourceKind.TEXTURE,
                -> GlArtifactCapture.readTexture(glId, target.resource.mipLevel, target.resource.region).use { readback ->
                    for (spec in payloads) {
                        cancellation.throwIfCancellationRequested()
                        write(sink, spec.fileName) { output ->
                            when (spec.format) {
                                CapturePlan.ArtifactFormat.BIN -> readback.writeBin(output)
                                CapturePlan.ArtifactFormat.PNG ->
                                    readback.writePng(spec.subresourceIndex ?: target.resource.layer, output)
                                else -> throw IllegalArgumentException("Unsupported texture format: ${spec.format}")
                            }
                        }
                        outputs.add(captured(spec))
                    }
                    readback.metadata
                }

                ResourceCatalog.ResourceKind.BUFFER -> {
                    require(payloads.size == 1) { "Buffer capture must have exactly one payload" }
                    val spec = payloads.single()
                    val result = write(sink, spec.fileName) { output -> GlArtifactCapture.captureBuffer(glId, output) }
                    outputs.add(captured(spec))
                    result
                }
                ResourceCatalog.ResourceKind.PATCHED_SHADERS ->
                    throw IllegalArgumentException("Patched shaders require directory artifact capture")
            }
            val descriptor = descriptor(target, metadata, frameId)
            target.outputs.firstOrNull { it.role == CapturePlan.ArtifactRole.METADATA }?.let { spec ->
                writeMetadata(sink, spec.fileName, descriptor, payloads.any { it.format == CapturePlan.ArtifactFormat.PNG })
                outputs.add(captured(spec))
            }
            groups.add(CaptureResult.ArtifactGroup(target.artifactName, descriptor, outputs))
        }
        return CaptureResult(frameId, groups)
    }

    internal fun capture(
        plan: CapturePlan,
        sink: ArtifactSink,
        frameId: Long,
        cancellation: CancellationToken,
        resolveResource: Function<CapturePlan.Target, Int?>,
        captureArtifact: (CapturePlan.Target, CapturePlan.ArtifactOutputSpec, Int, OutputStream) -> GlCaptureMetadata,
    ): CaptureResult {
        val targets = plan.targets.map { target ->
            ResolvedTarget(
                target,
                resolveResource.apply(target) ?: throw CaptureResourceNotFoundException(target.resource.logicalName),
            )
        }
        val captured = ArrayList<CaptureResult.ArtifactGroup>()
        for ((target, glId) in targets) {
            cancellation.throwIfCancellationRequested()
            var metadata: GlCaptureMetadata? = null
            val outputs = ArrayList<CaptureResult.CapturedArtifact>()
            for (outputSpec in target.outputs.filter { it.role != CapturePlan.ArtifactRole.METADATA }) {
                cancellation.throwIfCancellationRequested()
                val current = write(sink, outputSpec.fileName) { output ->
                    captureArtifact(target, outputSpec, glId, output)
                }
                if (metadata == null) metadata = current
                outputs.add(captured(outputSpec))
            }
            val descriptor = descriptor(target, requireNotNull(metadata), frameId)
            target.outputs.firstOrNull { it.role == CapturePlan.ArtifactRole.METADATA }?.let { metadataSpec ->
                writeMetadata(
                    sink,
                    metadataSpec.fileName,
                    descriptor,
                    target.outputs.any { it.role != CapturePlan.ArtifactRole.METADATA &&
                        it.format == CapturePlan.ArtifactFormat.PNG },
                )
                outputs.add(captured(metadataSpec))
            }
            captured.add(CaptureResult.ArtifactGroup(target.artifactName, descriptor, outputs))
        }
        return CaptureResult(frameId, captured)
    }

    private fun descriptor(
        target: CapturePlan.Target,
        metadata: GlCaptureMetadata,
        frameId: Long,
    ) = ResourceCatalog.ResourceDescriptor.of(
        target.resource.logicalName,
        target.resource.kind,
        listOfNotNull(target.resource.textureView),
        metadata.width,
        metadata.height,
        metadata.depth,
        metadata.mipLevels,
        1,
        metadata.internalFormat,
        metadata.channelCount,
        metadata.scalarType,
        metadata.byteSize,
        frameId,
        target.resource.logicalName,
        category(target.resource.logicalName, target.resource.kind),
        metadata.textureTarget,
        metadata.channelLayout,
        metadata.numericClass,
        metadata.componentBits,
        metadata.readbackFormat,
        metadata.readbackType,
        metadata.region,
        metadata.sourceWidth,
        metadata.sourceHeight,
    )

    private fun writeMetadata(
        sink: ArtifactSink,
        fileName: String,
        resource: ResourceCatalog.ResourceDescriptor,
        yFlipped: Boolean,
    ) {
        val json = "{\"logical_name\":\"${escape(resource.logicalName)}\"" +
            (resource.region?.let { ",\"region\":{\"x\":${it.x},\"y\":${it.y},\"width\":${it.width},\"height\":${it.height}}" } ?: "") +
            ",\"source_width\":${resource.sourceWidth},\"source_height\":${resource.sourceHeight}" +
            ",\"kind\":\"${resource.kind}\"" +
            ",\"width\":${resource.width}" +
            ",\"height\":${resource.height}" +
            ",\"depth\":${resource.depth}" +
            ",\"internal_format\":\"${escape(resource.internalFormat)}\"" +
            ",\"channel_count\":${resource.channelCount}" +
            ",\"scalar_type\":\"${resource.scalarType}\"" +
            ",\"category\":\"${escape(resource.category)}\"" +
            ",\"target\":\"${escape(resource.textureTarget)}\"" +
            ",\"mip_levels\":${resource.mipLevels}" +
            ",\"channel_layout\":\"${escape(resource.channelLayout)}\"" +
            ",\"numeric_class\":\"${escape(resource.numericClass)}\"" +
            ",\"component_bits\":${resource.componentBits}" +
            ",\"readback_format\":\"${escape(resource.readbackFormat)}\"" +
            ",\"readback_type\":\"${escape(resource.readbackType)}\"" +
            ",\"endianness\":\"native\"" +
            ",\"packing\":{\"alignment\":1,\"row_length\":0,\"image_height\":0,\"skip_pixels\":0,\"skip_rows\":0,\"skip_images\":0,\"swap_bytes\":false}" +
            ",\"axis_order\":\"X,Y,Z\"" +
            ",\"y_flipped\":$yFlipped" +
            ",\"byte_size\":${resource.byteSize}" +
            ",\"frame_id\":${resource.frameId}}"
        write(sink, fileName) { output -> output.write(json.toByteArray(StandardCharsets.UTF_8)) }
    }

    private fun <T> write(sink: ArtifactSink, fileName: String, action: (OutputStream) -> T): T = try {
        sink.open(fileName).use(action)
    } catch (exception: IOException) {
        throw UncheckedIOException(exception)
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun captured(spec: CapturePlan.ArtifactOutputSpec) = CaptureResult.CapturedArtifact(
        spec.fileName, spec.format, spec.role, spec.subresourceIndex,
    )

    private fun category(name: String, kind: ResourceCatalog.ResourceKind): String = when {
        kind == ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER -> "screenshot"
        kind == ResourceCatalog.ResourceKind.BUFFER -> "iris_ssbo"
        name.startsWith("colortex") -> "colortex"
        name.startsWith("depthtex") -> "depthtex"
        name.startsWith("shadowtex") -> "shadowtex"
        name.startsWith("shadowcolor") -> "shadowcolor"
        name == "noisetex" -> "noise_texture"
        name.startsWith("custom_texture.") -> "custom_texture"
        name.startsWith("iris_custom_texture.") -> "iris_custom_texture"
        name.startsWith("iris_custom_image.") -> "iris_custom_image"
        name.startsWith("gbuffers_terrain.") -> "terrain_atlas"
        else -> "texture"
    }

    private data class ResolvedTarget(val target: CapturePlan.Target, val glId: Int)
}
