package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.ReloadResult
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v2.ArtifactFormat
import dev.vibris.protocol.v2.ArtifactKind
import dev.vibris.protocol.v2.ArtifactMetadata
import dev.vibris.protocol.v2.ArtifactRole
import dev.vibris.protocol.v2.CaptureReceipt
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobResult
import dev.vibris.protocol.v2.PatchedShadersReceipt
import dev.vibris.protocol.v2.WaitFramesReceipt
import java.io.IOException
import java.nio.file.Path

internal class CaptureProtocolArtifacts {
    @Throws(Exception::class)
    fun commit(
        job: CoreJob,
        plans: List<CapturePlan>,
        captured: List<CaptureResult>,
        transaction: ArtifactManager.JobTransaction,
        diagnostics: List<ReloadResult.Diagnostic>,
        additionalArtifacts: List<GeneratedArtifact>,
        physicalNames: Map<String, String>,
    ): JobResult {
        if (plans.size != captured.size) {
            throw RuntimeJobExecutor.Failure(
                ErrorCode.ERROR_CODE_CAPTURE_FAILED,
                "Runtime capture count did not match its plan.",
            )
        }
        plans.indices.forEach { index -> validateResult(plans[index], captured[index]) }
        val committed = transaction.commit(specifications(plans, additionalArtifacts))
        val result = JobResult.newBuilder().setResultManifestId(committed.manifestId())
        for (index in plans.indices) {
            val plan = plans[index]
            for (group in captured[index].groups) {
                val target = plan.targets.first { it.artifactName == group.name }
                for (artifact in group.artifacts) {
                    result.addArtifacts(
                        captureArtifact(
                            target,
                            group.resource,
                            artifact,
                            requireArtifact(committed, artifact.fileName),
                            physicalNames[target.artifactName],
                        ),
                    )
                }
            }
        }
        additionalArtifacts.forEach { artifact ->
            result.addArtifacts(
                requireArtifact(committed, artifact.fileName),
            )
        }
        result.addArtifacts(
            requireArtifact(committed, "shader.log"),
        )
        result.addArtifacts(
            requireManifest(committed),
        )
        return result.build()
    }

    fun captureReceipt(
        plan: CapturePlan,
        captured: CaptureResult,
        targetIndex: Int,
        committed: JobResult,
        internalWait: WaitFramesReceipt?,
        physicalName: String? = null,
    ): CaptureReceipt {
        val target = plan.targets[targetIndex]
        val group = captured.groups.first { it.name == target.artifactName }
        val receipt = CaptureReceipt.newBuilder()
            .setFrameId(captured.frameId)
            .setResource(toProtocol(group.resource, target, physicalName))
            .addAllArtifacts(receiptArtifacts(target, committed))
        internalWait?.let(receipt::setInternalWait)
        return receipt.build()
    }

    fun patchedShadersReceipt(
        plan: CapturePlan,
        captured: CaptureResult,
        committed: JobResult,
    ): PatchedShadersReceipt {
        val target = plan.targets.single()
        return PatchedShadersReceipt.newBuilder()
            .setShaderGeneration(captured.frameId)
            .addAllArtifacts(receiptArtifacts(target, committed))
            .build()
    }

    fun afterPassReceipt(
        receipt: CapturePlan.AfterPassReceipt,
        committed: JobResult,
    ): CaptureReceipt {
        val plan = CapturePlan(listOf(receipt.request.target))
        return captureReceipt(
            plan,
            receipt.capture,
            0,
            committed,
            null,
            receipt.physicalName,
        ).toBuilder()
            .setPassId(receipt.request.pass.passId)
            .setPassOccurrence(receipt.passOccurrence)
            .build()
    }

    private fun validateResult(plan: CapturePlan, result: CaptureResult) {
        if (result.groups.size != plan.targets.size) {
            throw RuntimeJobExecutor.Failure(
                ErrorCode.ERROR_CODE_CAPTURE_FAILED,
                "Runtime capture groups did not match its plan.",
            )
        }
        for (target in plan.targets) {
            val group = result.groups.firstOrNull { it.name == target.artifactName }
            val resource = group?.resource
            if (
                resource == null || resource.kind != target.resource.kind ||
                resource.logicalName != target.resource.logicalName ||
                resource.frameId != result.frameId ||
                group.artifacts.map { artifact ->
                    listOf(artifact.fileName, artifact.format, artifact.role, artifact.subresourceIndex)
                }.toSet() != target.outputs.map { output ->
                    listOf(output.fileName, output.format, output.role, output.subresourceIndex)
                }.toSet()
            ) {
                throw RuntimeJobExecutor.Failure(
                    ErrorCode.ERROR_CODE_CAPTURE_FAILED,
                    "Runtime capture result did not match its plan.",
                )
            }
        }
    }

    private fun captureArtifact(
        target: CapturePlan.Target,
        resource: ResourceCatalog.ResourceDescriptor,
        artifact: CaptureResult.CapturedArtifact,
        file: ArtifactMetadata,
        physicalName: String?,
    ): ArtifactMetadata = file.toBuilder()
        .setResource(toProtocol(resource, target, physicalName))
        .build()

    private fun receiptArtifacts(target: CapturePlan.Target, committed: JobResult): List<ArtifactMetadata> {
        val names = target.outputs.mapTo(HashSet()) { it.fileName }
        return committed.artifactsList.filter { artifact ->
            Path.of(artifact.relativePath).fileName.toString() in names
        }
    }

    private fun toProtocol(
        resource: ResourceCatalog.ResourceDescriptor,
        target: CapturePlan.Target,
        physicalName: String? = null,
    ): dev.vibris.protocol.v2.ResourceDescriptor {
        val result = dev.vibris.protocol.v2.ResourceDescriptor.newBuilder()
            .setLogicalName(resource.logicalName)
            .setKind(
                when (resource.kind) {
                    ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER ->
                        dev.vibris.protocol.v2.ResourceKind.RESOURCE_KIND_FINAL_FRAMEBUFFER
                    ResourceCatalog.ResourceKind.TEXTURE -> dev.vibris.protocol.v2.ResourceKind.RESOURCE_KIND_TEXTURE
                    ResourceCatalog.ResourceKind.BUFFER -> dev.vibris.protocol.v2.ResourceKind.RESOURCE_KIND_BUFFER
                    ResourceCatalog.ResourceKind.PATCHED_SHADERS ->
                        dev.vibris.protocol.v2.ResourceKind.RESOURCE_KIND_PATCHED_SHADERS
                },
            )
            .setWidth(resource.width)
            .setHeight(resource.height)
            .setDepth(resource.depth)
            .setMipLevels(resource.mipLevels)
            .setLayers(resource.layers)
            .setInternalFormat(resource.internalFormat)
            .setChannelCount(resource.channelCount)
            .setScalarType(dev.vibris.protocol.v2.ScalarType.valueOf("SCALAR_TYPE_" + resource.scalarType.name))
            .setByteSize(resource.byteSize)
            .setFrameId(resource.frameId)
            .setSourceWidth(resource.sourceWidth)
            .setSourceHeight(resource.sourceHeight)
            .setPhysicalName(physicalName ?: resource.semanticLabel.ifBlank { target.resource.logicalName })
        resource.region?.let {
            result.setRegion(dev.vibris.protocol.v2.TextureRegion.newBuilder()
                .setX(it.x).setY(it.y).setWidth(it.width).setHeight(it.height))
        }
        target.resource.textureView?.let { view ->
            result.addAvailableViews(
                dev.vibris.protocol.v2.TextureView.valueOf("TEXTURE_VIEW_" + view.name),
            )
        }
        return result.build()
    }

    private fun specifications(
        plans: List<CapturePlan>,
        additionalArtifacts: List<GeneratedArtifact>,
    ): Map<String, ArtifactManifest.FileSpec> = LinkedHashMap<String, ArtifactManifest.FileSpec>().apply {
        put(
            "shader.log",
            ArtifactManifest.FileSpec(
                ArtifactKind.ARTIFACT_KIND_SHADER_COMPILE_LOG,
                ArtifactFormat.ARTIFACT_FORMAT_TEXT,
                ArtifactRole.ARTIFACT_ROLE_DIAGNOSTIC,
                "text/plain; charset=utf-8",
            ),
        )
        plans.flatMap { it.targets }.forEach { target ->
            target.outputs.forEach { output ->
                put(
                    output.fileName,
                    ArtifactManifest.FileSpec(
                        kind(target.resource.kind),
                        protocolFormat(output.format),
                        protocolRole(output.role),
                        mediaType(output.format),
                    ),
                )
            }
        }
        additionalArtifacts.forEach { artifact ->
            put(
                artifact.fileName,
                ArtifactManifest.FileSpec(
                    artifact.kind,
                    artifact.format,
                    ArtifactRole.ARTIFACT_ROLE_PRIMARY,
                    artifact.mediaType,
                ),
            )
        }
    }

    private fun requireArtifact(committed: ArtifactManager.CommittedJob, name: String): ArtifactMetadata =
        committed.metadata()[name] ?: throw IOException("Runtime did not write $name.")

    private fun requireManifest(committed: ArtifactManager.CommittedJob): ArtifactMetadata {
        val byteSize = committed.fileByteSizes()["manifest.json"]
            ?: throw IOException("Artifact manifest size is unavailable.")
        val createdAt = committed.metadata().values.firstOrNull()?.createdAtUnixMs ?: System.currentTimeMillis()
        return ArtifactMetadata.newBuilder()
            .setArtifactId(committed.manifestId())
            .setJobId(committed.metadata().values.firstOrNull()?.jobId ?: "manifest")
            .setRequestId(committed.metadata().values.firstOrNull()?.requestId ?: "manifest")
            .setRelativePath(committed.manifest().toString())
            .setKind(ArtifactKind.ARTIFACT_KIND_MANIFEST)
            .setFormat(ArtifactFormat.ARTIFACT_FORMAT_JSON)
            .setRole(ArtifactRole.ARTIFACT_ROLE_METADATA)
            .setMediaType("application/json")
            .setByteSize(byteSize)
            .setSha256(committed.manifestSha256())
            .setCreatedAtUnixMs(createdAt)
            .setExpiresAtUnixMs(committed.expiresAtUnixMs())
            .build()
    }

    private fun protocolFormat(format: CapturePlan.ArtifactFormat): ArtifactFormat =
        ArtifactFormat.valueOf("ARTIFACT_FORMAT_" + format.name)

    private fun kind(kind: ResourceCatalog.ResourceKind): ArtifactKind = when (kind) {
        ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER -> ArtifactKind.ARTIFACT_KIND_SCREENSHOT
        ResourceCatalog.ResourceKind.TEXTURE -> ArtifactKind.ARTIFACT_KIND_TEXTURE
        ResourceCatalog.ResourceKind.BUFFER -> ArtifactKind.ARTIFACT_KIND_BUFFER
        ResourceCatalog.ResourceKind.PATCHED_SHADERS -> ArtifactKind.ARTIFACT_KIND_PATCHED_SHADER
    }

    private fun mediaType(format: CapturePlan.ArtifactFormat): String = when (format) {
        CapturePlan.ArtifactFormat.PNG -> "image/png"
        CapturePlan.ArtifactFormat.EXR -> "image/x-exr"
        CapturePlan.ArtifactFormat.BIN -> "application/octet-stream"
        CapturePlan.ArtifactFormat.TEXT -> "text/plain; charset=utf-8"
        CapturePlan.ArtifactFormat.JSON -> "application/json"
    }

    private fun protocolRole(role: CapturePlan.ArtifactRole): ArtifactRole = when (role) {
        CapturePlan.ArtifactRole.PRIMARY -> ArtifactRole.ARTIFACT_ROLE_PRIMARY
        CapturePlan.ArtifactRole.SUBRESOURCE -> ArtifactRole.ARTIFACT_ROLE_SUBRESOURCE
        CapturePlan.ArtifactRole.METADATA -> ArtifactRole.ARTIFACT_ROLE_METADATA
    }

}
