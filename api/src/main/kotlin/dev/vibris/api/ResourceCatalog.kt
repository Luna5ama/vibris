package dev.vibris.api

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Exact resources and named render-pass boundaries exposed by the active pipeline. */
@JvmRecord
data class ResourceCatalog @JvmOverloads constructor(
    @field:DefensiveSnapshot val resources: List<ResourceDescriptor>,
    @field:DefensiveSnapshot val passes: List<PassDescriptor>,
    val mappingSha256: String,
    val shaderGeneration: Long = 0,
) {
    init {
        require(resources.zipWithNext().all { (left, right) -> RESOURCE_ORDER.compare(left, right) < 0 }) {
            "resources must be uniquely ordered by logical name"
        }
        require(resources.map(ResourceDescriptor::logicalName).distinct().size == resources.size) {
            "resource logical names must be unambiguous"
        }
        require(passes.zipWithNext().all { (left, right) -> left.order < right.order }) {
            "passes must have a unique execution order"
        }
        val resourceNames = resources.mapTo(HashSet(), ResourceDescriptor::logicalName)
        require(passes.all { pass -> pass.readableResources.all(resourceNames::contains) }) {
            "pass readable resources must resolve in this catalog"
        }
        require(mappingSha256 == stableMappingHash(resources, passes)) {
            "mappingSha256 must match the canonical pass/resource mapping"
        }
    }

    @JvmRecord
    data class PassDescriptor @JvmOverloads constructor(
        val passId: String,
        val stage: PassStage,
        val programId: String,
        val order: Int,
        @field:DefensiveSnapshot val readableResources: List<String>,
        @field:DefensiveSnapshot val programs: List<PassProgram> = emptyList(),
    ) {
        init {
            require(PROGRAM_ID.matches(programId)) { "programId must be a canonical program identifier" }
            require(passId == "${stage.id}/$programId") { "passId must use canonical stage/program form" }
            require(order >= 0) { "pass order must not be negative" }
            require(readableResources.zipWithNext().all { (left, right) -> left < right }) {
                "readable resources must be uniquely ordered"
            }
            require(readableResources.all(::isLogicalName)) {
                "readable resources must use logical names"
            }
        }

        companion object {
            @JvmStatic
            @JvmOverloads
            fun of(
                stage: PassStage,
                programId: String,
                order: Int,
                readableResources: Collection<String>,
                programs: Collection<PassProgram> = emptyList(),
            ): PassDescriptor = PassDescriptor(
                "${stage.id}/$programId",
                stage,
                programId,
                order,
                readableResources.sorted(),
                programs.toList(),
            )
        }
    }

    @JvmRecord
    data class PassProgram(val programId: String, val stage: String, val entryFile: String, val mainSource: String)

    @JvmRecord
    data class ResourceDescriptor @JvmOverloads constructor(
        val logicalName: String,
        val kind: ResourceKind,
        @field:DefensiveSnapshot val availableViews: List<TextureView>,
        val width: Int,
        val height: Int,
        val depth: Int,
        val mipLevels: Int,
        val layers: Int,
        val internalFormat: String,
        val channelCount: Int,
        val scalarType: ScalarType,
        val byteSize: Long,
        val frameId: Long,
        val semanticLabel: String,
        val category: String,
        val textureTarget: String,
        val channelLayout: String,
        val numericClass: String,
        val componentBits: Int,
        val readbackFormat: String,
        val readbackType: String,
        val region: CapturePlan.TextureRegion? = null,
        val sourceWidth: Int = width,
        val sourceHeight: Int = height,
    ) {
        init {
            require(isLogicalName(logicalName)) { "logicalName must be a canonical logical resource name" }
            require(
                width >= 0 &&
                    height >= 0 &&
                    depth >= 0 &&
                    mipLevels >= 0 &&
                    layers >= 0 &&
                    channelCount >= 0 &&
                    componentBits >= 0 &&
                    byteSize >= 0 &&
                    frameId >= 0,
            ) {
                "Resource metadata must not be negative"
            }
            require(availableViews.zipWithNext().all { (left, right) -> left.ordinal < right.ordinal }) {
                "available texture views must be uniquely ordered"
            }
            if (kind == ResourceKind.TEXTURE) {
                require(availableViews.isNotEmpty()) { "texture resources must expose at least one logical view" }
                require(mipLevels > 0 && layers > 0) { "texture resources must expose mip and layer bounds" }
            } else {
                require(availableViews.isEmpty()) { "only texture resources may expose views" }
            }
        }

        companion object {
            @JvmStatic
            @JvmOverloads
            fun of(
                logicalName: String,
                kind: ResourceKind,
                availableViews: Collection<TextureView>,
                width: Int,
                height: Int,
                depth: Int,
                mipLevels: Int,
                layers: Int,
                internalFormat: String,
                channelCount: Int,
                scalarType: ScalarType,
                byteSize: Long,
                frameId: Long,
                semanticLabel: String,
                category: String,
                textureTarget: String,
                channelLayout: String,
                numericClass: String,
                componentBits: Int,
                readbackFormat: String,
                readbackType: String,
                region: CapturePlan.TextureRegion? = null,
                sourceWidth: Int = width,
                sourceHeight: Int = height,
            ): ResourceDescriptor = ResourceDescriptor(
                logicalName,
                kind,
                availableViews.sortedBy(TextureView::ordinal),
                width,
                height,
                depth,
                mipLevels,
                layers,
                internalFormat,
                channelCount,
                scalarType,
                byteSize,
                frameId,
                semanticLabel,
                category,
                textureTarget,
                channelLayout,
                numericClass,
                componentBits,
                readbackFormat,
                readbackType,
                region,
                sourceWidth,
                sourceHeight,
            )
        }
    }

    enum class PassStage(val id: String) {
        BEGIN("begin"),
        PREPARE("prepare"),
        DEFERRED("deferred"),
        COMPOSITE("composite"),
        FINAL("final"),
        SHADOW_COMPOSITE("shadow_composite"),
    }

    enum class TextureView {
        CURRENT,
        ALTERNATE,
        MAIN,
        ALT,
    }

    enum class ResourceKind {
        FINAL_FRAMEBUFFER,
        TEXTURE,
        BUFFER,
        PATCHED_SHADERS,
    }

    enum class ScalarType {
        UNSPECIFIED,
        UINT8,
        SINT8,
        UINT16,
        SINT16,
        UINT32,
        SINT32,
        FLOAT16,
        FLOAT32,
    }

    companion object {
        private val MAPPING_HASH_DOMAIN = "vibris-pass-resource-mapping-v2".toByteArray(Charsets.UTF_8)
        private val PROGRAM_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val LOGICAL_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val RESOURCE_ORDER = compareBy<ResourceDescriptor>(ResourceDescriptor::logicalName)

        @JvmStatic
        @JvmOverloads
        fun of(resources: Collection<ResourceDescriptor>, passes: Collection<PassDescriptor>, shaderGeneration: Long = 0): ResourceCatalog {
            val orderedResources = resources.sortedWith(RESOURCE_ORDER)
            val orderedPasses = passes.sortedBy(PassDescriptor::order)
            return ResourceCatalog(
                orderedResources,
                orderedPasses,
                stableMappingHash(orderedResources, orderedPasses),
                shaderGeneration,
            )
        }

        @JvmStatic
        fun empty(): ResourceCatalog = of(emptyList(), emptyList())

        internal fun isLogicalName(value: String): Boolean =
            LOGICAL_NAME.matches(value) && !value.endsWith(".main") && !value.endsWith(".alt")

        private fun stableMappingHash(
            resources: List<ResourceDescriptor>,
            passes: List<PassDescriptor>,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(MAPPING_HASH_DOMAIN)
            digest.update(0.toByte())
            digest.updateCount(resources.size)
            resources.forEach { resource ->
                digest.updateField(resource.logicalName)
                digest.updateField(resource.kind.name)
                digest.updateCount(resource.availableViews.size)
                resource.availableViews.forEach { digest.updateField(it.name) }
            }
            digest.updateCount(passes.size)
            passes.forEach { pass ->
                digest.updateField(pass.passId)
                digest.updateField(pass.stage.name)
                digest.updateField(pass.programId)
                digest.updateCount(pass.order)
                digest.updateCount(pass.readableResources.size)
                pass.readableResources.forEach { resource -> digest.updateField(resource) }
                digest.updateCount(pass.programs.size)
                pass.programs.forEach { program ->
                    digest.updateField(program.programId)
                    digest.updateField(program.stage)
                    digest.updateField(program.entryFile)
                    digest.updateField(program.mainSource)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        private fun MessageDigest.updateCount(value: Int) {
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
        }

        private fun MessageDigest.updateField(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            updateCount(bytes.size)
            update(bytes)
        }
    }
}
