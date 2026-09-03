package dev.luna5ama.vibris.capture

import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

data class StorageBufferInfo(val name: String, val glId: Int, val sizeBytes: Long, val category: String = "iris_ssbo")

data class TextureInfo(
    val name: String,
    val textureId: Int,
    val category: String,
    val target: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    val mipLevels: Int,
    val internalFormat: String,
    val channelLayout: String,
    val numericClass: String,
    val componentBits: Int,
)

data class TextureCatalog(val textures: List<TextureInfo>)

data class ShaderDebugError(
    val type: String,
    val filename: String,
    val message: String,
    val stackTrace: String,
    val timestamp: Long
)

data class GpuTimingStats(
    val average: Long,
    val p5: Long,
    val p95: Long,
    val p50: Long,
    val samples: List<Long>,
)

/** A resolved, non-blocking GPU timing frame. All values are nanoseconds. */
data class GpuFrameTiming(
    val totalNanoseconds: Long,
    val scopes: List<GpuScopeTiming>,
)

/** A framework timing scope whose start is relative to the enclosing frame root. */
data class GpuScopeTiming(
    val name: String,
    val startNanoseconds: Long,
    val durationNanoseconds: Long,
    val children: List<GpuScopeTiming>,
)

data class GpuTimingProgram @JvmOverloads constructor(
    val program: String,
    val stage: String,
    val sourceFile: String,
    val defines: Map<String, String> = emptyMap(),
    val dispatch: String? = null,
) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun compute(
            program: String,
            sourceFile: String,
            defines: Map<String, String> = emptyMap(),
            dispatch: String? = null,
        ) = GpuTimingProgram(program, "compute", sourceFile, defines, dispatch)
    }
}

internal enum class GpuTimingScopeKind(val jsonName: String) {
    FRAMEWORK_TOTAL("framework_total"),
    COMPATIBILITY_AGGREGATE("compatibility_aggregate"),
}

internal data class GpuTimingScope(
    val metric: String,
    val kind: GpuTimingScopeKind,
    val frameworkPass: String?,
    val stage: String?,
)

internal data class GpuProgramTimingStats(
    val metric: String,
    val program: GpuTimingProgram,
    val frameworkPass: String?,
    val compatibilityMetric: String?,
    val statistics: GpuTimingStats,
)

internal data class GpuTimingSnapshot(
    val sampledFrames: Int,
    val aggregateTimings: Map<String, GpuTimingStats>,
    val aggregateScopes: List<GpuTimingScope>,
    val programTimings: List<GpuProgramTimingStats>,
)

interface ShaderDebugHost {
    fun shaderPackName(): String?

    @Throws(Exception::class)
    fun reloadShaders()

    fun gameDirectory(): Path

    fun debugShadersEnabled(): Boolean

    fun storageBuffers(): List<StorageBufferInfo>

    fun textureCatalog(): TextureCatalog

    fun resolveTexture(name: String): Int?

    @Throws(Exception::class)
    fun awaitPatchedShaderWrites() = Unit
}
