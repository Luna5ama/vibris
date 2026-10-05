package dev.vibris.core

import dev.vibris.api.RuntimeAction
import dev.vibris.protocol.v2.Action
import dev.vibris.protocol.v2.ActionKind
import dev.vibris.protocol.v2.GpuMetricsReceipt
import dev.vibris.protocol.v2.GpuTimingMetric
import dev.vibris.protocol.v2.MissingGpuMetric
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object RuntimeActionProtocol {
    fun isRuntime(action: Action): Boolean = when (action.actionCase) {
        Action.ActionCase.GET_CAPTURE_STATUS,
        Action.ActionCase.GET_GPU_METRICS,
        Action.ActionCase.TEXTURE_STATS,
        Action.ActionCase.ACCUMULATE_TEXTURE,
        -> true
        else -> false
    }

    fun toApi(action: Action): RuntimeAction = when (action.actionCase) {
        Action.ActionCase.GET_CAPTURE_STATUS -> RuntimeAction.CaptureStatus
        Action.ActionCase.GET_GPU_METRICS ->
            action.getGpuMetrics.let { command ->
                command.frames.requireRange("frames", 1, 10_000)
                require(command.metricIdsList.all(String::isNotBlank)) { "metric_ids must not contain blanks" }
                require(command.metricIdsList.distinct().size == command.metricIdsCount) {
                    "metric_ids must be unique"
                }
                RuntimeAction.GpuMetrics(command.frames)
            }
        else -> throw IllegalArgumentException("Action is not a runtime action")
    }

    fun gpuMetricsReceipt(action: Action, response: String): GpuMetricsReceipt {
        require(action.hasGetGpuMetrics()) { "Action is not a GPU metrics action" }
        val command = action.getGpuMetrics
        val root = Json.parseToJsonElement(response).requireObject("GPU timing response")
        val timingUnit = root.requireText("timingUnit")
        require(timingUnit == "ns") { "GPU timing unit must be ns" }
        val sampledFrames = root.requireLong("sampledFrames")
        require(sampledFrames == command.frames.toLong()) {
            "GPU timing sampledFrames did not match the requested frame count"
        }

        val scopes = root.requireArray("gpuTimingScopes").map { value ->
            val scope = value.requireObject("GPU timing scope")
            val metric = scope.requireText("metric")
            val kind = scope.requireText("kind")
            val frameworkPass = scope.optionalText("framework_pass")
            val stage = scope.optionalText("stage")
            when (kind) {
                "framework_total" -> require(stage == null) {
                    "framework_total GPU timing scope must not declare a stage"
                }
                "compatibility_aggregate" -> require(stage != null) {
                    "compatibility_aggregate GPU timing scope must declare a stage"
                }
                else -> throw IllegalArgumentException("GPU timing scope kind is unsupported: $kind")
            }
            require(frameworkPass != null) { "GPU timing scope must declare framework_pass" }
            Scope(metric, frameworkPass)
        }
        require(scopes.map(Scope::metric).distinct().size == scopes.size) {
            "GPU timing scopes contain duplicate metric identities"
        }
        val scopesByMetric = scopes.associateBy(Scope::metric)

        val metrics = ArrayList<GpuTimingMetric>()
        val aggregates = root.requireObject("gpuTimings")
        require(aggregates.keys == scopesByMetric.keys) {
            "GPU timing aggregate identities do not match their scopes"
        }
        aggregates.forEach { (metricId, value) ->
            metricId.requireText("GPU timing metric identity")
            val scope = scopesByMetric.getValue(metricId)
            metrics.add(metric(metricId, "", scope.frameworkPass, value.requireStatistics(metricId)))
        }

        root.requireArray("gpuProgramTimings").forEach { value ->
            val program = value.requireObject("GPU program timing")
            val metricId = program.requireText("metric")
            require(program.requireText("kind") == "program") { "GPU program timing kind must be program" }
            val programId = program.requireText("program")
            program.requireText("stage")
            val mainSource = program.requireText("source")
            program.optionalText("dispatch")
            val passId = program.optionalText("framework_pass").orEmpty()
            program.requireObject("defines").forEach { (name, define) ->
                name.requireText("GPU program define name")
                define.requireString("GPU program define value")
            }
            metrics.add(
                metric(
                    metricId,
                    programId,
                    passId,
                    program.requireObject("statistics").statistics(metricId),
                    mainSource,
                ),
            )
        }

        require(metrics.isNotEmpty()) { "GPU timing response contains no metrics" }
        require(metrics.map { Triple(it.metricId, it.programId, it.passId) }.distinct().size == metrics.size) {
            "GPU timing response contains duplicate typed metric identities"
        }
        val requested = command.metricIdsList.toSet()
        val missing = requested - metrics.map(GpuTimingMetric::getMetricId).toSet()
        val selected = metrics.filter { requested.isEmpty() || it.metricId in requested }

        return GpuMetricsReceipt.newBuilder()
            .setTimingUnit(timingUnit)
            .setSampledFrames(sampledFrames.toInt())
            .addAllMetrics(selected)
            .addAllMissing(missing.sorted().map { id ->
                MissingGpuMetric.newBuilder().setMetricId(id)
                    .addAllSuggestions(NameSuggestions.suggest(id, metrics.map(GpuTimingMetric::getMetricId))).build()
            })
            .build()
    }

    fun kind(action: Action): ActionKind = when (action.actionCase) {
        Action.ActionCase.RESET_TEMPORAL_STATE -> ActionKind.ACTION_KIND_RESET_TEMPORAL_STATE
        Action.ActionCase.WAIT_FRAMES -> ActionKind.ACTION_KIND_WAIT_FRAMES
        Action.ActionCase.TAKE_SCREENSHOT -> ActionKind.ACTION_KIND_TAKE_SCREENSHOT
        Action.ActionCase.ACTIVATE_SOURCE -> ActionKind.ACTION_KIND_ACTIVATE_SOURCE
        Action.ActionCase.GET_CAPTURE_STATUS -> ActionKind.ACTION_KIND_GET_CAPTURE_STATUS
        Action.ActionCase.INSPECT_SHADER -> ActionKind.ACTION_KIND_INSPECT_SHADER
        Action.ActionCase.GET_GPU_METRICS -> ActionKind.ACTION_KIND_GET_GPU_METRICS
        Action.ActionCase.LOAD_SHADER -> ActionKind.ACTION_KIND_LOAD_SHADER
        Action.ActionCase.LIST_RESOURCES -> ActionKind.ACTION_KIND_LIST_RESOURCES
        Action.ActionCase.DUMP_TEXTURE -> ActionKind.ACTION_KIND_DUMP_TEXTURE
        Action.ActionCase.DUMP_BUFFER -> ActionKind.ACTION_KIND_DUMP_BUFFER
        Action.ActionCase.GET_PATCHED_SHADERS -> ActionKind.ACTION_KIND_GET_PATCHED_SHADERS
        Action.ActionCase.DUMP_TEXTURE_AFTER_PASS -> ActionKind.ACTION_KIND_DUMP_TEXTURE_AFTER_PASS
        Action.ActionCase.DUMP_BUFFER_AFTER_PASS -> ActionKind.ACTION_KIND_DUMP_BUFFER_AFTER_PASS
        Action.ActionCase.NSIGHT_GPU_TRACE -> ActionKind.ACTION_KIND_NSIGHT_GPU_TRACE
        Action.ActionCase.TEXTURE_STATS -> ActionKind.ACTION_KIND_TEXTURE_STATS
        Action.ActionCase.ACCUMULATE_TEXTURE -> ActionKind.ACTION_KIND_ACCUMULATE_TEXTURE
        else -> throw IllegalArgumentException("Action kind is unsupported")
    }

    private fun String.requireText(field: String): String = apply {
        require(isNotBlank()) { "$field must not be blank" }
    }

    private fun Int.requireRange(field: String, minimum: Int, maximum: Int): Int = apply {
        require(this in minimum..maximum) { "$field must be between $minimum and $maximum" }
    }

    private fun metric(
        metricId: String,
        programId: String,
        passId: String,
        statistics: Statistics,
        mainSource: String = "",
    ): GpuTimingMetric = GpuTimingMetric.newBuilder()
        .setMetricId(metricId)
        .setProgramId(programId)
        .setPassId(passId)
        .setMainSource(mainSource)
        .setAverageNs(statistics.average)
        .setP50Ns(statistics.p50)
        .setP95Ns(statistics.p95)
        .addAllSamplesNs(statistics.samples)
        .build()

    private fun JsonElement.requireObject(label: String): JsonObject = this as? JsonObject
        ?: throw IllegalArgumentException("$label must be an object")

    private fun JsonElement.requireString(label: String): String {
        val value = this as? JsonPrimitive
            ?: throw IllegalArgumentException("$label must be a string")
        require(value.isString) { "$label must be a string" }
        return value.content.requireText(label)
    }

    private fun JsonObject.requireObject(field: String): JsonObject = get(field)?.requireObject(field)
        ?: throw IllegalArgumentException("GPU timing response is missing $field")

    private fun JsonObject.requireArray(field: String): JsonArray = get(field) as? JsonArray
        ?: throw IllegalArgumentException("GPU timing response field $field must be an array")

    private fun JsonObject.requireText(field: String): String = get(field)?.requireString(field)
        ?: throw IllegalArgumentException("GPU timing response is missing $field")

    private fun JsonObject.optionalText(field: String): String? {
        val value = get(field) ?: throw IllegalArgumentException("GPU timing response is missing $field")
        return if (value === JsonNull) null else value.requireString(field)
    }

    private fun JsonObject.requireLong(field: String): Long {
        val value = (get(field) as? JsonPrimitive)?.longOrNull
            ?: throw IllegalArgumentException("GPU timing response field $field must be an integer")
        require(value >= 0) { "GPU timing response field $field must not be negative" }
        return value
    }

    private fun JsonElement.requireStatistics(metricId: String): Statistics =
        requireObject("GPU timing statistics for $metricId").statistics(metricId)

    private fun JsonObject.statistics(metricId: String): Statistics {
        val samples = requireArray("samples").map { value ->
            val sample = (value as? JsonPrimitive)?.longOrNull
                ?: throw IllegalArgumentException("GPU timing sample for $metricId must be an integer")
            require(sample >= 0) { "GPU timing sample for $metricId must not be negative" }
            sample
        }
        require(samples.isNotEmpty()) { "GPU timing metric $metricId contains no samples" }
        return Statistics(
            requireLong("avg"),
            requireLong("p50"),
            requireLong("p95"),
            samples,
        )
    }

    private data class Scope(val metric: String, val frameworkPass: String)

    private data class Statistics(
        val average: Long,
        val p50: Long,
        val p95: Long,
        val samples: List<Long>,
    )
}
