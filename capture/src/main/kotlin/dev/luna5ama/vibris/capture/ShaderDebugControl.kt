package dev.luna5ama.vibris.capture

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.ArrayDeque
import dev.vibris.api.ArtifactSink
import dev.vibris.api.CancellationToken
import dev.vibris.api.CaptureResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class ShaderDebugControl constructor(
    private val host: ShaderDebugHost,
) {
    private val errorLock = Any()
    private val errors = ArrayDeque<ShaderDebugError>()
    private val passStack: ThreadLocal<ArrayDeque<String>> = ThreadLocal.withInitial { ArrayDeque() }
    private val passTimingStack: ThreadLocal<ArrayDeque<Boolean>> = ThreadLocal.withInitial { ArrayDeque() }
    private val timingStack: ThreadLocal<ArrayDeque<Boolean>> = ThreadLocal.withInitial { ArrayDeque() }
    private val metrics = GpuTimingMetrics()
    private val identityLock = Any()
    private var patchedShaderSha256 = ""
    private var patchedShaderGeneration = 0L

    fun status(): JsonObject = try {
        val pack = host.shaderPackName()
        buildJsonObject {
            put("status", "ok")
            put("pack_loaded", pack != null)
            put("shaderpack", pack?.let(::JsonPrimitive) ?: JsonNull)
        }
    } catch (_: Exception) {
        buildJsonObject {
            put("status", "ok")
            put("pack_loaded", false)
            put("shaderpack", "unknown")
        }
    }

    fun inspect(): JsonObject = JsonObject(
        status() + ("errors" to errorJson()) + ("patched_shader" to patchedShaderIdentity()),
    )

    fun reload(): JsonObject {
        clearErrors()
        val failure = try {
            host.reloadShaders()
            null
        } catch (exception: Exception) {
            exception
        }
        if (failure != null && errorSnapshot().isEmpty()) {
            recordError(
                failure.javaClass.simpleName,
                "",
                failure.message.orEmpty(),
                failure.stackTraceToString(),
                System.currentTimeMillis()
            )
        }
        return buildJsonObject {
            put("success", failure == null)
            put("errors", errorJson())
        }
    }

    fun recordError(
        type: String,
        filename: String,
        message: String,
        stackTrace: String,
        timestamp: Long = System.currentTimeMillis()
    ) = synchronized(errorLock) {
        while (errors.size >= MAX_ERRORS) errors.removeFirst()
        errors.addLast(ShaderDebugError(type, filename, message, stackTrace, timestamp))
    }

    fun clearErrors() = synchronized(errorLock) { errors.clear() }

    fun errorList(): List<ShaderDebugError> = errorSnapshot()

    fun setRealtimeTimingEnabled(enabled: Boolean) = metrics.setRealtimeEnabled(enabled)

    fun beginFrame() = metrics.beginFrame()

    fun drainRealtimeTimings(): List<GpuFrameTiming> = metrics.drainFrames()

    fun tickFrame() = metrics.finishFrame()

    fun closeTiming() = metrics.close()

    fun captureMetrics(frames: Int): CompletionStage<JsonObject> =
        metrics.capture(frames).thenApply(::metricsJson)

    internal fun metricsJson(values: GpuTimingSnapshot): JsonObject = buildJsonObject {
        put("timingUnit", "ns")
        put("sampledFrames", values.sampledFrames)
        put("gpuTimings", buildJsonObject {
            values.aggregateTimings.forEach { (name, stats) ->
                put(name, timingStatistics(stats))
            }
        })
        put("gpuTimingScopes", buildJsonArray {
            values.aggregateScopes.forEach { scope ->
                add(buildJsonObject {
                    put("metric", scope.metric)
                    put("kind", scope.kind.jsonName)
                    put("framework_pass", scope.frameworkPass?.let(::JsonPrimitive) ?: JsonNull)
                    put("stage", scope.stage?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        })
        put("gpuProgramTimings", buildJsonArray {
            values.programTimings.forEach { timing ->
                add(buildJsonObject {
                    put("metric", timing.metric)
                    put("kind", "program")
                    put("program", timing.program.program)
                    put("stage", timing.program.stage)
                    put("source", timing.program.sourceFile)
                    put("defines", buildJsonObject {
                        timing.program.defines.toSortedMap().forEach { (name, value) -> put(name, value) }
                    })
                    put("dispatch", timing.program.dispatch?.let(::JsonPrimitive) ?: JsonNull)
                    put("framework_pass", timing.frameworkPass?.let(::JsonPrimitive) ?: JsonNull)
                    put(
                        "compatibility_metric",
                        timing.compatibilityMetric?.let(::JsonPrimitive) ?: JsonNull,
                    )
                    put("statistics", timingStatistics(timing.statistics))
                })
            }
        })
    }

    private fun timingStatistics(stats: GpuTimingStats): JsonObject = buildJsonObject {
        put("avg", stats.average)
        put("p5", stats.p5)
        put("p95", stats.p95)
        put("p50", stats.p50)
        put("samples", buildJsonArray {
            stats.samples.forEach { add(JsonPrimitive(it)) }
        })
    }

    fun buffersJson(): JsonObject = buildJsonObject {
        put("buffers", buildJsonArray {
            host.storageBuffers().forEach { buffer ->
                add(buildJsonObject {
                    put("name", buffer.name)
                    put("category", buffer.category)
                    put("size_bytes", buffer.sizeBytes)
                })
            }
        })
    }

    fun texturesJson(): JsonObject {
        val catalog = host.textureCatalog()
        return buildJsonObject {
            put("textures", textureArray(catalog.textures))
        }
    }

    fun capturePatchedShaders(
        artifactName: String,
        sink: ArtifactSink,
        frameId: Long,
        cancellation: CancellationToken,
    ): CompletionStage<CaptureResult> = CompletableFuture.supplyAsync {
        PatchedShaderCapture.capture(host, artifactName, sink, frameId, cancellation)
    }

    fun pushPass(name: String) {
        passStack.get().addLast(name)
        passTimingStack.get().addLast(
            metrics.isMeasuringFramework() && metrics.beginAggregate(
                GpuTimingScope(
                    metric = "${name}_total",
                    kind = GpuTimingScopeKind.FRAMEWORK_TOTAL,
                    frameworkPass = name,
                    stage = null,
                ),
            ),
        )
    }

    fun popPass() {
        val stack = passStack.get()
        if (stack.isNotEmpty()) {
            val timings = passTimingStack.get()
            if (timings.isNotEmpty() && timings.removeLast()) metrics.end()
            stack.removeLast()
        }
    }

    fun beginDraw() = beginTiming("draw")

    fun beginDraw(program: GpuTimingProgram) = beginTiming("draw", program)

    fun endDraw() = endTiming()

    fun beginCompute() = beginTiming("compute")

    fun beginCompute(program: GpuTimingProgram) = beginTiming("compute", program)

    fun endCompute() = endTiming()

    private fun beginTiming(kind: String, program: GpuTimingProgram? = null) {
        if (!metrics.isCapturing()) return
        val pass = currentPass()
        val measured = when {
            program != null -> {
                metrics.beginProgram(program, pass, kind)
                true
            }
            pass != null -> {
                metrics.beginAggregate(
                    GpuTimingScope(
                        metric = "${pass}_$kind",
                        kind = GpuTimingScopeKind.COMPATIBILITY_AGGREGATE,
                        frameworkPass = pass,
                        stage = kind,
                    ),
                )
                true
            }
            else -> false
        }
        timingStack.get().addLast(measured)
    }

    private fun endTiming() {
        if (!metrics.isCapturing()) return
        val stack = timingStack.get()
        if (stack.isNotEmpty() && stack.removeLast()) metrics.end()
    }

    private fun currentPass() = passStack.get().peekLast()

    private fun errorSnapshot() = synchronized(errorLock) { errors.toList() }

    private fun errorJson() = buildJsonArray {
        errorSnapshot().forEach { error ->
            add(buildJsonObject {
                put("type", error.type)
                put("filename", error.filename)
                put("message", error.message)
                put("stackTrace", error.stackTrace)
                put("timestamp", error.timestamp)
            })
        }
    }

    private fun textureArray(textures: List<TextureInfo>) = buildJsonArray {
        textures.forEach { texture ->
            add(buildJsonObject {
                put("name", texture.name)
                put("category", texture.category)
                put("target", texture.target)
                put("width", texture.width)
                put("height", texture.height)
                put("depth", texture.depth)
                put("mip_levels", texture.mipLevels)
                put("internal_format", texture.internalFormat)
                put("channel_layout", texture.channelLayout)
                put("numeric_class", texture.numericClass)
                put("component_bits", texture.componentBits)
            })
        }
    }

    private fun patchedShaderIdentity(): JsonObject {
        if (!host.debugShadersEnabled()) return buildJsonObject {
            put("available", false)
            put("reason", "debug_shaders_disabled")
        }
        return try {
            val identity = PatchedShaderCapture.identity(host)
            val generation = synchronized(identityLock) {
                if (identity.sha256 != patchedShaderSha256) {
                    patchedShaderSha256 = identity.sha256
                    ++patchedShaderGeneration
                }
                patchedShaderGeneration
            }
            buildJsonObject {
                put("available", true)
                put("sha256", identity.sha256)
                put("generation", generation)
                put("file_count", identity.fileCount)
                put("total_bytes", identity.totalBytes)
            }
        } catch (exception: Exception) {
            buildJsonObject {
                put("available", false)
                put("reason", exception.message ?: "patched_shader_identity_failed")
            }
        }
    }

    private companion object {
        const val MAX_ERRORS = 100
    }
}
