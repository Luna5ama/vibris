package dev.luna5ama.vibris.capture

import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL33C
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.math.ceil
import kotlin.math.roundToLong

internal class GpuTimingMetrics {
    private val active = ArrayDeque<ActiveTiming>()
    private val pending = ArrayDeque<PendingTiming>()
    private val liveFrames = ArrayDeque<LiveFrame>()
    private val completedFrames = ArrayDeque<GpuFrameTiming>()
    private val queryPool = ArrayDeque<Int>()
    private var capture: Capture? = null
    private var liveFrame: LiveFrame? = null
    private var realtimeEnabled = false
    private var generation = 0L

    fun isCapturing(): Boolean = capture != null
    fun isMeasuringFramework(): Boolean = capture != null || liveFrame != null
    fun capture(frames: Int): CompletionStage<GpuTimingSnapshot> {
        require(frames in 1..MAX_CAPTURE_FRAMES) { "frames must be between 1 and $MAX_CAPTURE_FRAMES" }
        check(capture == null) { "GPU metric capture is already active" }
        val result = CompletableFuture<GpuTimingSnapshot>()
        capture = Capture(frames, result)
        return result
    }

    fun setRealtimeEnabled(enabled: Boolean) {
        if (realtimeEnabled == enabled) return
        realtimeEnabled = enabled
        generation++
        discardRealtime()
    }

    fun beginFrame() {
        harvest(false)
        harvestLiveFrames()
        if (!realtimeEnabled || liveFrame != null) return
        liveFrame = LiveFrame(generation, timestamp())
    }

    fun drainFrames(): List<GpuFrameTiming> {
        harvest(false)
        harvestLiveFrames()
        if (completedFrames.isEmpty()) return emptyList()
        return buildList(completedFrames.size) {
            while (completedFrames.isNotEmpty()) add(completedFrames.removeFirst())
        }
    }

    fun begin(name: String): Boolean = begin(
        GpuTimingTarget.Aggregate(GpuTimingScope(name, GpuTimingScopeKind.COMPATIBILITY_AGGREGATE, null, null)),
        null,
    )

    fun beginAggregate(scope: GpuTimingScope): Boolean {
        val frame = liveFrame
        val node = if (frame != null && scope.kind == GpuTimingScopeKind.FRAMEWORK_TOTAL) {
            LiveNode(scope.frameworkPass ?: scope.metric.removeSuffix("_total")).also { child ->
                val parent = active.lastOrNull { it.liveNode != null }?.liveNode
                if (parent == null) frame.roots.add(child) else parent.children.add(child)
            }
        } else null
        return begin(GpuTimingTarget.Aggregate(scope), node)
    }

    fun beginProgram(program: GpuTimingProgram, frameworkPass: String?, stage: String): Boolean {
        if (capture == null) return false
        return begin(GpuTimingTarget.Program(program.copy(stage = stage).snapshot(), frameworkPass), null)
    }

    private fun begin(target: GpuTimingTarget, node: LiveNode?): Boolean {
        val formal = capture
        if (formal == null && node == null) return false
        harvest(false)
        active.addLast(ActiveTiming(if (formal != null) target else null, formal, node, timestamp()))
        return true
    }

    fun end() {
        if (active.isEmpty()) return
        val timing = active.removeLast()
        if (timing.startQuery == 0) return
        val sample = PendingTiming(timing.target, timing.capture, timing.liveNode, timing.startQuery, timestamp())
        timing.liveNode?.timing = sample
        pending.addLast(sample)
        harvest(false)
    }

    fun finishFrame() {
        liveFrame?.let { frame ->
            frame.endQuery = timestamp()
            liveFrames.addLast(frame)
            liveFrame = null
            while (liveFrames.size > MAX_LIVE_FRAMES) discardLiveFrame(liveFrames.removeFirst())
        }
        capture?.let { current ->
            current.frames--
            if (current.frames == 0) {
                harvest(true)
                capture = null
                current.result.complete(current.histories.snapshot(current.sampledFrames))
            }
        }
        harvest(false)
        harvestLiveFrames()
    }

    fun close() {
        generation++
        realtimeEnabled = false
        discardRealtime()
        while (active.isNotEmpty()) releaseQuery(active.removeLast().startQuery)
        while (pending.isNotEmpty()) delete(pending.removeFirst())
        while (queryPool.isNotEmpty()) GL15C.glDeleteQueries(queryPool.removeFirst())
        capture?.result?.completeExceptionally(IllegalStateException("GPU timing metrics closed"))
        capture = null
    }

    private fun harvest(wait: Boolean) {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val timing = iterator.next()
            if (!timing.resolved) {
                if (!wait && GL15C.glGetQueryObjecti(timing.endQuery, GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) break
                resolve(timing)
            }
            if (timing.formalConsumed && timing.liveConsumed) {
                iterator.remove()
                releaseQuery(timing.startQuery)
                releaseQuery(timing.endQuery)
            }
        }
    }

    private fun resolve(timing: PendingTiming) {
        timing.start = GL33C.glGetQueryObjectui64(timing.startQuery, GL15C.GL_QUERY_RESULT)
        timing.end = GL33C.glGetQueryObjectui64(timing.endQuery, GL15C.GL_QUERY_RESULT)
        timing.resolved = true
        timing.capture?.histories?.add(timing.target!!, (timing.end - timing.start).coerceAtLeast(0L))
        timing.formalConsumed = true
    }

    private fun harvestLiveFrames() {
        while (liveFrames.isNotEmpty()) {
            val frame = liveFrames.first()
            if (GL15C.glGetQueryObjecti(frame.endQuery, GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) break
            val start = GL33C.glGetQueryObjectui64(frame.startQuery, GL15C.GL_QUERY_RESULT)
            val end = GL33C.glGetQueryObjectui64(frame.endQuery, GL15C.GL_QUERY_RESULT)
            liveFrames.removeFirst()
            releaseQuery(frame.startQuery)
            releaseQuery(frame.endQuery)
            frame.walk { node ->
                node.timing?.let { sample ->
                    if (!sample.resolved) resolve(sample)
                    sample.liveConsumed = true
                }
            }
            if (frame.generation == generation && realtimeEnabled) {
                completedFrames.addLast(GpuFrameTiming((end - start).coerceAtLeast(0L), frame.roots.map { it.snapshot(start) }))
            }
            harvest(false)
        }
    }

    private fun discardRealtime() {
        completedFrames.clear()
        liveFrame?.let { frame ->
            GL15C.glDeleteQueries(frame.startQuery)
            frame.walk { node ->
                node.timing?.let { timing ->
                    timing.liveConsumed = true
                    if (timing.capture == null && !timing.resolved && pending.remove(timing)) delete(timing)
                }
            }
        }
        liveFrame = null
        active.forEach { timing ->
            if (timing.liveNode != null) {
                timing.liveNode = null
                if (timing.target == null) {
                    GL15C.glDeleteQueries(timing.startQuery)
                    timing.startQuery = 0
                }
            }
        }
        while (liveFrames.isNotEmpty()) discardLiveFrame(liveFrames.removeFirst())
        harvest(false)
    }

    private fun discardLiveFrame(frame: LiveFrame) {
        GL15C.glDeleteQueries(frame.startQuery)
        if (frame.endQuery != 0) GL15C.glDeleteQueries(frame.endQuery)
        frame.walk { node ->
            node.timing?.let { timing ->
                timing.liveConsumed = true
                if (timing.capture == null && !timing.resolved && pending.remove(timing)) delete(timing)
            }
        }
    }

    private fun timestamp(): Int = acquireQuery().also { GL33C.glQueryCounter(it, GL33C.GL_TIMESTAMP) }
    private fun acquireQuery(): Int = if (queryPool.isEmpty()) GL15C.glGenQueries() else queryPool.removeFirst()
    private fun releaseQuery(query: Int) {
        if (query != 0 && queryPool.size < MAX_POOLED_QUERIES) queryPool.addLast(query)
        else if (query != 0) GL15C.glDeleteQueries(query)
    }
    private fun delete(timing: PendingTiming) {
        GL15C.glDeleteQueries(timing.startQuery)
        GL15C.glDeleteQueries(timing.endQuery)
    }

    private class ActiveTiming(
        val target: GpuTimingTarget?,
        val capture: Capture?,
        var liveNode: LiveNode?,
        var startQuery: Int,
    )
    private class PendingTiming(
        val target: GpuTimingTarget?, val capture: Capture?, val liveNode: LiveNode?, val startQuery: Int, val endQuery: Int,
    ) {
        var start = 0L
        var end = 0L
        var resolved = false
        var formalConsumed = capture == null
        var liveConsumed = liveNode == null
    }
    private class LiveNode(val name: String) {
        val children = ArrayList<LiveNode>()
        var timing: PendingTiming? = null
        fun snapshot(frameStart: Long): GpuScopeTiming {
            val sample = timing
            val start = sample?.start ?: frameStart
            return GpuScopeTiming(name, (start - frameStart).coerceAtLeast(0L), ((sample?.end ?: start) - start).coerceAtLeast(0L), children.map { it.snapshot(frameStart) })
        }
    }
    private class LiveFrame(val generation: Long, val startQuery: Int) {
        val roots = ArrayList<LiveNode>()
        var endQuery = 0
        fun walk(action: (LiveNode) -> Unit) {
            fun visit(nodes: List<LiveNode>) { nodes.forEach { node -> action(node); visit(node.children) } }
            visit(roots)
        }
    }
    private data class Capture(
        var frames: Int,
        val result: CompletableFuture<GpuTimingSnapshot>,
        val histories: GpuTimingHistories = GpuTimingHistories(),
    ) { val sampledFrames = frames }
}

internal sealed interface GpuTimingTarget {
    data class Aggregate(val scope: GpuTimingScope) : GpuTimingTarget
    data class Program(val program: GpuTimingProgram, val frameworkPass: String?) : GpuTimingTarget
}

internal class GpuTimingHistories {
    private val aggregateHistories = linkedMapOf<String, TimingHistory>()
    private val aggregateScopes = linkedMapOf<String, GpuTimingScope>()
    private val programHistories = linkedMapOf<ProgramKey, TimingHistory>()
    fun add(target: GpuTimingTarget, value: Long) = when (target) {
        is GpuTimingTarget.Aggregate -> addAggregate(target.scope, value)
        is GpuTimingTarget.Program -> addProgram(target.program.snapshot(), target.frameworkPass, value)
    }
    fun snapshot(sampledFrames: Int = 1) = GpuTimingSnapshot(
        sampledFrames,
        aggregateHistories.mapValues { it.value.stats() },
        aggregateScopes.values.toList(),
        programHistories.map { (key, history) ->
            GpuProgramTimingStats("${key.program.program}_${key.program.stage}", key.program, key.frameworkPass, key.frameworkPass?.let { "${it}_${key.program.stage}" }, history.stats())
        },
    )
    private fun addAggregate(scope: GpuTimingScope, value: Long) {
        aggregateScopes.putIfAbsent(scope.metric, scope)
        aggregateHistories.getOrPut(scope.metric, ::TimingHistory).add(value)
    }
    private fun addProgram(program: GpuTimingProgram, frameworkPass: String?, value: Long) {
        if (frameworkPass != null) addAggregate(GpuTimingScope("${frameworkPass}_${program.stage}", GpuTimingScopeKind.COMPATIBILITY_AGGREGATE, frameworkPass, program.stage), value)
        programHistories.getOrPut(ProgramKey(program, frameworkPass), ::TimingHistory).add(value)
    }
    private data class ProgramKey(val program: GpuTimingProgram, val frameworkPass: String?)
}

private fun GpuTimingProgram.snapshot() = copy(defines = java.util.Map.copyOf(defines))
internal class TimingHistory {
    private val samples = ArrayList<Long>()
    fun add(value: Long) { samples.add(value) }
    fun stats(): GpuTimingStats {
        val sorted = samples.sorted()
        return GpuTimingStats(samples.sum() / samples.size, percentile(sorted, .05), percentile(sorted, .95), percentile(sorted, .50), samples.toList())
    }
    private fun percentile(sorted: List<Long>, percentile: Double): Long {
        val index = sorted.lastIndex * percentile
        val lower = index.toInt()
        val upper = ceil(index).toInt()
        return (sorted[lower] + (sorted[upper] - sorted[lower]) * (index - lower)).roundToLong()
    }
}

private const val MAX_CAPTURE_FRAMES = 10_000
private const val MAX_LIVE_FRAMES = 8
private const val MAX_POOLED_QUERIES = 512
