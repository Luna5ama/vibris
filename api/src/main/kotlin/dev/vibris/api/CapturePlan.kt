package dev.vibris.api

import java.util.Locale
import java.util.regex.Pattern

@JvmRecord
data class CapturePlan(@field:DefensiveSnapshot val targets: List<Target>) {

    @JvmRecord
    data class ResourceSelector @JvmOverloads constructor(
        val kind: ResourceCatalog.ResourceKind,
        val logicalName: String,
        val textureView: ResourceCatalog.TextureView?,
        val mipLevel: Int,
        val layer: Int,
        val region: TextureRegion? = null,
    ) {
        init {
            require(ResourceCatalog.isLogicalName(logicalName)) {
                "logicalName must be a canonical logical resource name"
            }
            require(mipLevel >= 0 && layer >= 0) { "Mip level and layer must be non-negative" }
            require(region == null || kind == ResourceCatalog.ResourceKind.TEXTURE || kind == ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER) { "Only texture selectors support a region" }
            when (kind) {
                ResourceCatalog.ResourceKind.TEXTURE -> require(textureView != null) {
                    "Texture selectors require an explicit logical view"
                }
                ResourceCatalog.ResourceKind.BUFFER -> require(
                    textureView == null && mipLevel == 0 && layer == 0,
                ) {
                    "Buffer selectors cannot specify a view, mip level, or layer"
                }
                ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER,
                ResourceCatalog.ResourceKind.PATCHED_SHADERS,
                -> require(textureView == null && mipLevel == 0 && layer == 0) {
                    "Non-texture selectors cannot specify a view, mip level, or layer"
                }
            }
        }
    }

    @JvmRecord
    data class TextureRegion(val x: Int, val y: Int, val width: Int, val height: Int) {
        init { require(x >= 0 && y >= 0 && width > 0 && height > 0) { "Texture region must be nonempty and nonnegative" } }
        fun validate(sourceWidth: Int, sourceHeight: Int) {
            require(x <= sourceWidth - width && y <= sourceHeight - height) {
                "Texture region exceeds mip dimensions ${sourceWidth}x$sourceHeight"
            }
        }
    }

    @JvmRecord
    data class Target(
        val resource: ResourceSelector,
        val format: ArtifactFormat,
        val artifactName: String,
        @field:DefensiveSnapshot val outputs: List<ArtifactOutputSpec>,
    ) {
        init {
            require(SAFE_ARTIFACT_NAME.matcher(artifactName).matches()) {
                "Artifact name must be a safe file name"
            }
            require(resource.kind != ResourceCatalog.ResourceKind.BUFFER || format == ArtifactFormat.BIN) {
                "Buffer captures must use the complete BIN format"
            }
        }

        fun fileName(): String {
            outputs.firstOrNull { it.role != ArtifactRole.METADATA }?.let { return it.fileName }
            val extension = "." + format.name.lowercase(Locale.ROOT)
            return if (artifactName.lowercase(Locale.ROOT).endsWith(extension)) {
                artifactName
            } else {
                artifactName + extension
            }
        }

        fun metadataFileName(): String = "$artifactName.json"

        companion object {
            private val SAFE_ARTIFACT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        }
    }

    /** A one-shot request that remains pending until the exact named pass completes. */
    @JvmRecord
    data class AfterPassRequest(
        val mappingSha256: String,
        val pass: ResourceCatalog.PassDescriptor,
        val target: Target,
    ) {
        init {
            require(SHA256.matches(mappingSha256)) { "mappingSha256 must be a lowercase SHA-256" }
            require(
                target.resource.kind == ResourceCatalog.ResourceKind.TEXTURE ||
                    target.resource.kind == ResourceCatalog.ResourceKind.BUFFER,
            ) {
                "After-pass capture supports only textures and buffers"
            }
            require(target.resource.logicalName in pass.readableResources) {
                "Selected resource is not readable after the named pass"
            }
        }
    }

    /** Exact completion receipt for a previously pending after-pass request. */
    @JvmRecord
    data class AfterPassReceipt(
        val request: AfterPassRequest,
        val passOccurrence: Int,
        val physicalName: String,
        val capture: CaptureResult,
    ) {
        init {
            require(passOccurrence > 0) { "passOccurrence must be positive" }
            require(physicalName.isNotBlank()) { "physicalName must not be blank" }
            require(capture.groups.size == 1) { "After-pass capture must return exactly one artifact group" }
            val group = capture.groups.single()
            require(group.name == request.target.artifactName) { "Capture group does not match the request" }
            require(group.resource.logicalName == request.target.resource.logicalName) {
                "Capture resource does not match the request"
            }
            require(group.resource.kind == request.target.resource.kind) {
                "Capture resource kind does not match the request"
            }
        }
    }

    @JvmRecord
    data class ArtifactOutputSpec(
        val fileName: String,
        val format: ArtifactFormat,
        val role: ArtifactRole,
        val subresourceIndex: Int?,
    ) {
        init {
            require(SAFE_OUTPUT_NAME.matcher(fileName).matches()) { "Artifact output name must be safe" }
            require(subresourceIndex == null || subresourceIndex >= 0) { "Subresource index must not be negative" }
        }

        companion object {
            private val SAFE_OUTPUT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,191}")
        }
    }

    enum class ArtifactRole {
        PRIMARY,
        SUBRESOURCE,
        METADATA,
    }

    enum class ArtifactFormat {
        PNG,
        EXR,
        BIN,
        TEXT,
        JSON,
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")

        @JvmStatic
        fun empty(): CapturePlan = CapturePlan(java.util.List.of())
    }
}
