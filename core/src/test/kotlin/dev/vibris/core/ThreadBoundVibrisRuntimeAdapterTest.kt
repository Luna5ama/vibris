package dev.vibris.core

import dev.vibris.api.ArtifactSink
import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.CompileCatalog
import dev.vibris.api.ContextApplyResult
import dev.vibris.api.DeterministicTemporalCaptureOutcome
import dev.vibris.api.DeterministicTemporalCapturePlanner
import dev.vibris.api.DeterministicTemporalCapturePlanning
import dev.vibris.api.DeterministicTemporalCaptureReloaded
import dev.vibris.api.DeterministicTemporalCaptureRequest
import dev.vibris.api.EffectiveShaderSettings
import dev.vibris.api.ReloadResult
import dev.vibris.api.ResourceCatalog
import dev.vibris.api.RuntimeStatus
import dev.vibris.api.RuntimeEnvironment
import dev.vibris.api.SceneContext
import dev.vibris.api.TemporalResetResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedDeque

class ThreadBoundVibrisRuntimeAdapterTest {
    @Test
    fun refreshesTheCatalogAfterWarmupBeforeDeferredPassCapture() {
        val frames = RenderedFrameClock()
        val adapter = ThreadBoundVibrisRuntimeAdapter(StubHost(hasResourceCatalog = true), frames)
        assertEquals(ResourceCatalog.empty(), adapter.getResourceCatalog())
        val waited = adapter.waitRenderedFrames(1, CancellationToken.none())
        frames.renderedFrame()
        assertEquals(1L, waited.toCompletableFuture().join())
        assertEquals(RESOURCE_CATALOG, adapter.getResourceCatalog())
        adapter.close()
    }

    @Test
    fun reportsPendingFrameWorkAndNotifiesOnActivityTransitions() {
        val frames = RenderedFrameClock()
        val activityStates = ArrayList<Boolean>()
        val adapter = ThreadBoundVibrisRuntimeAdapter(
            StubHost(),
            frames,
            null,
            activityStates::add,
        )

        assertTrue(adapter.isIdle())
        val first = adapter.waitRenderedFrames(1, CancellationToken.none())
        val second = adapter.waitRenderedFrames(2, CancellationToken.none())
        assertFalse(adapter.isIdle())
        assertEquals(listOf(true), activityStates)

        frames.renderedFrame()
        assertEquals(1L, first.toCompletableFuture().join())
        assertFalse(adapter.isIdle())
        assertEquals(listOf(true), activityStates)
        frames.renderedFrame()
        assertEquals(2L, second.toCompletableFuture().join())
        assertTrue(adapter.isIdle())
        assertEquals(listOf(true, false), activityStates)

        val third = adapter.waitRenderedFrames(1, CancellationToken.none())
        assertEquals(listOf(true, false, true), activityStates)
        frames.renderedFrame()
        assertEquals(3L, third.toCompletableFuture().join())
        assertTrue(adapter.isIdle())
        assertEquals(listOf(true, false, true, false), activityStates)
        adapter.close()
    }

    @Test
    fun queriesCompileCatalogOnTheClientThread() {
        val frames = RenderedFrameClock()
        val adapter = ThreadBoundVibrisRuntimeAdapter(StubHost(), frames)

        val catalog = adapter.getCompileCatalog(CancellationToken.none()).toCompletableFuture().join()
        assertEquals(7L, catalog.shaderGeneration)
        adapter.close()
    }

    @Test
    fun queriesRuntimeEnvironmentOnTheClientThread() {
        val adapter = ThreadBoundVibrisRuntimeAdapter(StubHost(), RenderedFrameClock())

        val environment = adapter.getRuntimeEnvironment().toCompletableFuture().join()

        assertEquals("test-minecraft", environment.minecraftVersion)
        adapter.close()
    }

    @Test
    fun deterministicSequenceBoundariesRunAsDistinctClientTasks() {
        val host = QueuedHost(RenderedFrameClock())
        val adapter = ThreadBoundVibrisRuntimeAdapter(host, host.frames)

        val begin = adapter.beginDeterministicSequence(CancellationToken.none()).toCompletableFuture()
        assertEquals(1, host.pendingTasks())
        host.runNextTask()
        begin.join()

        val end = adapter.endDeterministicSequence(CancellationToken.none()).toCompletableFuture()
        assertEquals(1, host.pendingTasks())
        host.runNextTask()
        end.join()

        assertEquals(
            listOf("deterministic-sequence-begin", "deterministic-sequence-end"),
            host.events,
        )
    }

    @Test
    fun deterministicCaptureUsesOneClientTaskAndRegistersBeforeAnyInterleavingFrame() {
        val frames = RenderedFrameClock()
        val host = QueuedHost(frames)
        val waits = ArrayList<Pair<Long, Long>>()
        val activity = ArrayList<Boolean>()
        val adapter = ThreadBoundVibrisRuntimeAdapter(
            host,
            frames,
            { start, end -> waits.add(start to end) },
            activity::add,
        )

        val result = adapter.captureDeterministicTemporalPhase(request(2), PLANNER, SINK, CancellationToken.none())
            .toCompletableFuture()
        assertEquals(listOf(true), activity)
        assertFalse(adapter.isIdle())
        assertEquals(1, host.pendingTasks())

        host.runNextTask()
        assertEquals(
            listOf(
                "context",
                "reload",
                "resource-catalog",
                "compile-catalog",
                "planner",
                "reset",
                "begin",
                "schedule",
            ),
            host.events,
        )
        assertEquals(1, host.plannerCalls)
        assertEquals(1, host.catalogQueries)
        assertEquals(1, host.compileCatalogQueries)
        assertEquals(RESOURCE_CATALOG, host.plannedResourceCatalog)
        assertEquals(COMPILE_CATALOG, host.plannedCompileCatalog)
        assertTrue(host.phaseActive)
        assertFalse(result.isDone)
        assertEquals(0, host.pendingTasks())
        repeat(2) { host.renderFrame() }
        assertFalse(result.isDone)
        host.renderFrame()

        assertEquals("capture:3", host.events[8])
        assertEquals("end", host.events[9])
        assertFalse(host.phaseActive)
        val captured = result.join() as DeterministicTemporalCaptureOutcome.Captured
        assertTrue(result.isDone)
        assertEquals(0, host.pendingTasks())
        assertTrue(adapter.isIdle())
        assertEquals(RESOURCE_CATALOG, captured.reloaded.resourceCatalog)
        assertEquals(COMPILE_CATALOG, captured.reloaded.compileCatalog)
        assertEquals(0L, captured.anchorFrame)
        assertEquals(2L, captured.warmupEndFrame)
        assertEquals(3L, captured.capture.frameId)
        assertEquals(listOf(0L to 2L), waits)
        assertEquals(10, host.events.size)
        assertFalse(host.phaseActive)
        assertEquals(1, host.endCount)
        assertTrue(adapter.isIdle())
        assertEquals(listOf(true, false), activity)
        host.closeAdapter(adapter)
    }

    @Test
    fun deterministicPreScheduleFailuresAreTypedAndStopAtTheirExactBoundary() {
        val context = immediateOutcome(HostFailurePoint.CONTEXT, PLANNER)
        assertTrue(context.outcome is DeterministicTemporalCaptureOutcome.ContextRejected)
        assertEquals(listOf("context"), context.host.events)
        assertEquals(0, context.host.plannerCalls)
        assertEquals(0, context.host.resetCalls)
        assertEquals(0, context.host.scheduleCalls)
        context.host.closeAdapter(context.adapter)

        val reload = immediateOutcome(HostFailurePoint.RELOAD, PLANNER)
        assertTrue(reload.outcome is DeterministicTemporalCaptureOutcome.ReloadRejected)
        assertEquals(listOf("context", "reload"), reload.host.events)
        assertEquals(0, reload.host.plannerCalls)
        assertEquals(0, reload.host.resetCalls)
        assertEquals(0, reload.host.scheduleCalls)
        reload.host.closeAdapter(reload.adapter)

        val rejectedPlanner = DeterministicTemporalCapturePlanner { resourceCatalog, compileCatalog ->
            assertEquals(RESOURCE_CATALOG, resourceCatalog)
            assertEquals(COMPILE_CATALOG, compileCatalog)
            DeterministicTemporalCapturePlanning.Rejected(
                DeterministicTemporalCaptureOutcome.Failure(
                    DeterministicTemporalCaptureOutcome.FailureKind.RESOURCE_NOT_FOUND,
                    "resource missing",
                ),
            )
        }
        val planning = immediateOutcome(HostFailurePoint.NONE, rejectedPlanner)
        assertTrue(planning.outcome is DeterministicTemporalCaptureOutcome.PlanningRejected)
        assertEquals(
            listOf("context", "reload", "resource-catalog", "compile-catalog", "planner"),
            planning.host.events,
        )
        assertEquals(1, planning.host.plannerCalls)
        assertEquals(0, planning.host.resetCalls)
        assertEquals(0, planning.host.scheduleCalls)
        assertEquals(RESOURCE_CATALOG, planning.adapter.getResourceCatalog())
        planning.host.closeAdapter(planning.adapter)

        val reset = immediateOutcome(HostFailurePoint.RESET, PLANNER)
        assertTrue(reset.outcome is DeterministicTemporalCaptureOutcome.ResetRejected)
        assertEquals(listOf("planner", "reset"), reset.host.events.takeLast(2))
        assertEquals(1, reset.host.plannerCalls)
        assertEquals(1, reset.host.resetCalls)
        assertEquals(0, reset.host.scheduleCalls)
        assertEquals(RESOURCE_CATALOG, reset.adapter.getResourceCatalog())
        reset.host.closeAdapter(reset.adapter)
    }

    @Test
    fun deterministicCaptureCancellationEndsPhaseBeforeCompleting() {
        val frames = RenderedFrameClock()
        val host = QueuedHost(frames)
        val activity = ArrayList<Boolean>()
        val adapter = ThreadBoundVibrisRuntimeAdapter(host, frames, null, activity::add)
        val cancellation = CancellationToken.source()
        val result = adapter.captureDeterministicTemporalPhase(request(3), PLANNER, SINK, cancellation.token())
            .toCompletableFuture()

        host.runNextTask()
        assertTrue(host.phaseActive)
        host.renderFrame()
        cancellation.cancel()
        host.awaitPendingTask()

        assertFalse(result.isDone)
        assertTrue(host.phaseActive)
        assertEquals(1, host.pendingTasks())
        assertFalse(adapter.isIdle())
        repeat(3) { host.renderFrame() }
        assertEquals(4L, frames.currentFrame())
        host.runNextTask()

        val rejected = result.join() as DeterministicTemporalCaptureOutcome.WarmupRejected
        assertEquals(DeterministicTemporalCaptureOutcome.FailureKind.CANCELLED, rejected.failure.kind)
        assertEquals(1, rejected.completedFrames)
        assertEquals(1L, rejected.currentFrame)
        assertEquals(listOf("schedule", "end"), host.events.takeLast(2))
        assertFalse(host.phaseActive)
        assertEquals(1, host.endCount)
        assertTrue(adapter.isIdle())
        assertEquals(listOf(true, false), activity)
        host.closeAdapter(adapter)
    }

    @Test
    fun deterministicCaptureFailureEndsPhaseBeforeCompleting() {
        val frames = RenderedFrameClock()
        val host = QueuedHost(frames)
        host.failCapture = true
        host.failEnd = true
        val adapter = ThreadBoundVibrisRuntimeAdapter(host, frames)
        val result = adapter.captureDeterministicTemporalPhase(request(1), PLANNER, SINK, CancellationToken.none())
            .toCompletableFuture()

        host.runNextTask()
        host.renderFrame()
        host.renderFrame()

        assertTrue(result.isDone)
        assertFalse(host.phaseActive)
        assertEquals(0, host.pendingTasks())
        val rejected = result.join() as DeterministicTemporalCaptureOutcome.CaptureRejected
        assertEquals(DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED, rejected.failure.kind)
        assertTrue(rejected.failure.message.contains("capture failed"))
        assertTrue(rejected.failure.message.contains("deterministic cleanup failed"))
        assertEquals(listOf("schedule", "capture:2", "end"), host.events.takeLast(3))
        assertFalse(host.phaseActive)
        assertEquals(1, host.endCount)
        assertTrue(adapter.isIdle())
        host.closeAdapter(adapter)
    }

    @Test
    fun deterministicCleanupFailureRejectsSuccessfulCaptureAndRestoresIdleState() {
        val frames = RenderedFrameClock()
        val host = QueuedHost(frames)
        host.failEnd = true
        val adapter = ThreadBoundVibrisRuntimeAdapter(host, frames)
        val result = adapter.captureDeterministicTemporalPhase(request(0), PLANNER, SINK, CancellationToken.none())
            .toCompletableFuture()

        host.runNextTask()
        host.renderFrame()

        val rejected = result.join() as DeterministicTemporalCaptureOutcome.CaptureRejected
        assertEquals(DeterministicTemporalCaptureOutcome.FailureKind.CLEANUP_FAILED, rejected.failure.kind)
        assertTrue(rejected.failure.message.contains("deterministic cleanup failed"))
        assertEquals(listOf("schedule", "capture:1", "end"), host.events.takeLast(3))
        assertFalse(host.phaseActive)
        assertEquals(1, host.endCount)
        assertTrue(adapter.isIdle())
        host.closeAdapter(adapter)
    }

    @Test
    fun queuedDeterministicCaptureDoesNotEnterHostAfterClose() {
        val frames = RenderedFrameClock()
        val host = QueuedHost(frames)
        val adapter = ThreadBoundVibrisRuntimeAdapter(host, frames)
        val result = adapter.captureDeterministicTemporalPhase(request(1), PLANNER, SINK, CancellationToken.none())
            .toCompletableFuture()

        assertEquals(1, host.pendingTasks())
        val closer = Thread(adapter::close)
        closer.start()
        host.awaitPendingTasks(2)
        host.runNextTask()
        host.runNextTask()
        closer.join(2000)

        val failure = assertThrows(CompletionException::class.java, result::join)
        assertEquals("Vibris runtime is closed", failure.cause?.message)
        assertEquals(listOf("close"), host.events)
        assertTrue(adapter.isIdle())
        assertEquals(1, host.closeCount)
    }

    @Test
    fun closeDuringActivePhaseRunsCleanupBeforeClosingHostAndPreservesTypedOutcome() {
        val frames = RenderedFrameClock()
        val host = QueuedHost(frames)
        val adapter = ThreadBoundVibrisRuntimeAdapter(host, frames)
        val result = adapter.captureDeterministicTemporalPhase(
            request(2),
            PLANNER,
            SINK,
            CancellationToken.none(),
        ).toCompletableFuture()

        host.runNextTask()
        assertTrue(host.phaseActive)
        val closer = Thread(adapter::close)
        closer.start()
        host.awaitPendingTasks(2)

        host.runNextTask()
        val rejected = result.join() as DeterministicTemporalCaptureOutcome.WarmupRejected
        assertEquals(DeterministicTemporalCaptureOutcome.FailureKind.CANCELLED, rejected.failure.kind)
        assertFalse(host.phaseActive)
        assertEquals(0, host.closeCount)
        assertEquals("end", host.events.last())

        host.runNextTask()
        closer.join(2000)
        assertFalse(closer.isAlive)
        assertEquals(1, host.endCount)
        assertEquals(1, host.closeCount)
        assertEquals(listOf("end", "close"), host.events.takeLast(2))
        assertTrue(adapter.isIdle())
    }

    @Test
    fun workerCompletionObservesOutcomeWithoutASecondClientTask() {
        val frames = RenderedFrameClock()
        val host = QueuedHost(frames)
        host.completeOutcomeOnWorker = true
        val adapter = ThreadBoundVibrisRuntimeAdapter(host, frames)
        val result = adapter.captureDeterministicTemporalPhase(request(0), PLANNER, SINK, CancellationToken.none())
            .toCompletableFuture()

        host.runNextTask()
        host.renderFrame()

        assertTrue(result.join() is DeterministicTemporalCaptureOutcome.Captured)
        assertEquals(0, host.pendingTasks())
        assertEquals(1, host.catalogQueries)
        assertEquals(RESOURCE_CATALOG, adapter.getResourceCatalog())
        assertTrue(adapter.isIdle())
        host.closeAdapter(adapter)
    }

    @Test
    fun closeAfterWorkerOutcomePreservesTypedOutcome() {
        val frames = RenderedFrameClock()
        val host = QueuedHost(frames)
        host.completeOutcomeOnWorker = true
        val adapter = ThreadBoundVibrisRuntimeAdapter(host, frames)
        val result = adapter.captureDeterministicTemporalPhase(request(0), PLANNER, SINK, CancellationToken.none())
            .toCompletableFuture()

        host.runNextTask()
        host.renderFrame()
        assertTrue(result.join() is DeterministicTemporalCaptureOutcome.Captured)

        val closer = Thread(adapter::close)
        closer.start()
        host.awaitPendingTask()
        host.runNextTask()
        closer.join(2000)

        assertFalse(closer.isAlive)
        assertEquals(1, host.closeCount)
        assertTrue(result.isDone)
        assertEquals(1, host.catalogQueries)
        assertTrue(adapter.isIdle())
    }

    @Test
    fun clientSubmissionFailureAfterTypedOutcomePreservesOutcome() {
        val frames = RenderedFrameClock()
        val host = QueuedHost(frames)
        host.completeOutcomeOnWorker = true
        val adapter = ThreadBoundVibrisRuntimeAdapter(host, frames)
        val result = adapter.captureDeterministicTemporalPhase(request(0), PLANNER, SINK, CancellationToken.none())
            .toCompletableFuture()

        host.runNextTask()
        host.failClientSubmission = true
        host.renderFrame()

        assertTrue(result.join() is DeterministicTemporalCaptureOutcome.Captured)
        assertEquals(1, host.catalogQueries)
        assertTrue(adapter.isIdle())
        host.failClientSubmission = false
        host.closeAdapter(adapter)
    }

    @Test
    fun postprocessUsesReloadedCatalogWithoutASecondHostQuery() {
        val frames = RenderedFrameClock()
        val host = QueuedHost(frames)
        val adapter = ThreadBoundVibrisRuntimeAdapter(host, frames)
        val result = adapter.captureDeterministicTemporalPhase(request(0), PLANNER, SINK, CancellationToken.none())
            .toCompletableFuture()

        host.runNextTask()
        host.renderFrame()

        assertTrue(result.join() is DeterministicTemporalCaptureOutcome.Captured)
        assertEquals(1, host.catalogQueries)
        assertEquals(1, host.compileCatalogQueries)
        assertEquals(RESOURCE_CATALOG, adapter.getResourceCatalog())
        assertTrue(adapter.isIdle())
        host.closeAdapter(adapter)
    }

    private fun immediateOutcome(
        failurePoint: HostFailurePoint,
        planner: DeterministicTemporalCapturePlanner,
    ): ImmediateOutcome {
        val host = QueuedHost(RenderedFrameClock())
        host.failurePoint = failurePoint
        val adapter = ThreadBoundVibrisRuntimeAdapter(host, host.frames)
        val result = adapter.captureDeterministicTemporalPhase(
            request(1),
            planner,
            SINK,
            CancellationToken.none(),
        ).toCompletableFuture()
        assertEquals(1, host.pendingTasks())
        host.runNextTask()
        return ImmediateOutcome(result.join(), host, adapter)
    }

    private data class ImmediateOutcome(
        val outcome: DeterministicTemporalCaptureOutcome,
        val host: QueuedHost,
        val adapter: ThreadBoundVibrisRuntimeAdapter,
    )

    private enum class HostFailurePoint {
        NONE,
        CONTEXT,
        RELOAD,
        RESET,
    }

    private class QueuedHost(val frames: RenderedFrameClock) : VibrisRuntimeHost {
        val events = ArrayList<String>()
        private val tasks = ConcurrentLinkedDeque<Runnable>()
        private var clientThread = false
        var phaseActive = false
            private set
        var endCount = 0
            private set
        var failCapture = false
        var failEnd = false
        var completeOutcomeOnWorker = false
        var failClientSubmission = false
        var failurePoint = HostFailurePoint.NONE
        var catalogQueries = 0
            private set
        var compileCatalogQueries = 0
            private set
        var plannerCalls = 0
            private set
        var resetCalls = 0
            private set
        var scheduleCalls = 0
            private set
        var plannedResourceCatalog: ResourceCatalog? = null
            private set
        var plannedCompileCatalog: CompileCatalog? = null
            private set
        var closeCount = 0
            private set

        override fun isClientThread(): Boolean = clientThread

        override fun executeOnClient(task: Runnable) {
            if (failClientSubmission) throw IllegalStateException("client submission failed")
            tasks.addLast(task)
        }

        fun pendingTasks(): Int = tasks.size

        fun awaitPendingTask() {
            awaitPendingTasks(1)
        }

        fun awaitPendingTasks(count: Int) {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (tasks.size < count && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            check(tasks.size >= count) { "Timed out waiting for $count client tasks" }
        }

        fun runNextTask() {
            check(tasks.isNotEmpty()) { "No client task is queued" }
            onClient(tasks.removeFirst())
        }

        fun runLastTask() {
            check(tasks.isNotEmpty()) { "No client task is queued" }
            onClient(tasks.removeLast())
        }

        fun renderFrame() {
            onClient(frames::renderedFrame)
        }

        fun closeAdapter(adapter: ThreadBoundVibrisRuntimeAdapter) {
            onClient(adapter::close)
        }

        private fun onClient(action: Runnable) {
            check(!clientThread)
            clientThread = true
            try {
                action.run()
            } finally {
                clientThread = false
            }
        }

        override fun runtimeEnvironment(): RuntimeEnvironment = unsupported()

        override fun status(): RuntimeStatus = unsupported()

        override fun applyContext(
            context: SceneContext,
            cancellation: CancellationToken,
        ): CompletionStage<ContextApplyResult> = unsupported()

        override fun reload(config: Map<String, String>?, cancellation: CancellationToken): ReloadResult {
            requireClientThread()
            assertEquals(mapOf("QUALITY" to "2"), config)
            events.add("reload")
            return if (failurePoint == HostFailurePoint.RELOAD) {
                ReloadResult.failure(emptyList())
            } else {
                ReloadResult.success(EffectiveShaderSettings.empty(), emptyList())
            }
        }

        override fun compileCatalog(cancellation: CancellationToken): CompileCatalog {
            requireClientThread()
            cancellation.throwIfCancellationRequested()
            compileCatalogQueries++
            events.add("compile-catalog")
            return COMPILE_CATALOG
        }

        override fun resetTemporal(cancellation: CancellationToken): TemporalResetResult {
            requireClientThread()
            cancellation.throwIfCancellationRequested()
            resetCalls++
            events.add("reset")
            return TemporalResetResult(failurePoint != HostFailurePoint.RESET)
        }

        override fun captureDeterministicTemporalPhase(
            request: DeterministicTemporalCaptureRequest,
            planner: DeterministicTemporalCapturePlanner,
            sink: ArtifactSink,
            scheduler: DeterministicTemporalCaptureScheduler,
            cancellation: CancellationToken,
        ): CompletionStage<DeterministicTemporalCaptureOutcome> {
            requireClientThread()
            events.add("context")
            val context = if (failurePoint == HostFailurePoint.CONTEXT) {
                ContextApplyResult.failure(request.context, "context rejected")
            } else {
                ContextApplyResult.success(request.context)
            }
            if (!context.successful) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                    DeterministicTemporalCaptureOutcome.ContextRejected(
                        context,
                        failure("context rejected"),
                    ),
                )
            }
            val reload = reload(if (request.preserveCurrentSettings) null else request.settings, cancellation)
            if (!reload.successful) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                    DeterministicTemporalCaptureOutcome.ReloadRejected(
                        context,
                        reload,
                        failure("reload rejected"),
                    ),
                )
            }
            val reloadCompletedAtUnixMs = System.currentTimeMillis().coerceAtLeast(1L)
            val resourceCatalog = resourceCatalog(scheduler.currentFrame())
            val compileCatalog = compileCatalog(cancellation)
            val reloaded = DeterministicTemporalCaptureReloaded(
                context,
                reload,
                reloadCompletedAtUnixMs,
                resourceCatalog,
                compileCatalog,
            )
            events.add("planner")
            plannerCalls++
            plannedResourceCatalog = resourceCatalog
            plannedCompileCatalog = compileCatalog
            val planning = try {
                planner.plan(resourceCatalog, compileCatalog)
            } catch (planningFailure: Throwable) {
                DeterministicTemporalCapturePlanning.Rejected(
                    failure(planningFailure.message ?: "planner failed"),
                )
            }
            val plan = when (planning) {
                is DeterministicTemporalCapturePlanning.Planned -> planning.plan
                is DeterministicTemporalCapturePlanning.Rejected -> {
                    return java.util.concurrent.CompletableFuture.completedFuture(
                        DeterministicTemporalCaptureOutcome.PlanningRejected(
                            reloaded,
                            planning.failure,
                        ),
                    )
                }
            }
            val reset = resetTemporal(cancellation)
            if (!reset.successful) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                    DeterministicTemporalCaptureOutcome.ResetRejected(
                        reloaded,
                        plan,
                        reset,
                        failure("reset rejected"),
                    ),
                )
            }
            val resetCompletedAtUnixMs = System.currentTimeMillis().coerceAtLeast(1L)
            check(!phaseActive)
            phaseActive = true
            events.add("begin")
            scheduleCalls++
            events.add("schedule")
            val scheduled = scheduler.schedule(request.warmupFrames, cancellation) { frameId ->
                capture(plan, sink, frameId, cancellation)
            }
            val result = java.util.concurrent.CompletableFuture<DeterministicTemporalCaptureOutcome>()
            scheduled.capture.whenComplete { capture, operationFailure ->
                val finish = Runnable {
                    val terminalFrame = scheduled.terminalFrame.toCompletableFuture().join()
                    val operation = operationFailure?.let(::unwrap)
                    val cleanup = try {
                        requireClientThread()
                        phaseActive = false
                        endCount++
                        events.add("end")
                        if (failEnd) IllegalStateException("deterministic cleanup failed") else null
                    } catch (failure: Throwable) {
                        failure
                    }
                    val outcome = if (operation != null || cleanup != null) {
                        val failure = operation ?: cleanup!!
                        if (operation != null && cleanup != null) operation.addSuppressed(cleanup)
                        val kind = when {
                            failure is CancellationException ->
                                DeterministicTemporalCaptureOutcome.FailureKind.CANCELLED
                            cleanup != null && operation == null ->
                                DeterministicTemporalCaptureOutcome.FailureKind.CLEANUP_FAILED
                            failure is RenderedFrameClock.TargetMissedException ||
                                failure is RenderedFrameClock.AnchorMismatchException ->
                                DeterministicTemporalCaptureOutcome.FailureKind.MISSED_TARGET
                            else -> DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED
                        }
                        val detail = DeterministicTemporalCaptureOutcome.Failure(
                            kind,
                            buildString {
                                append(failure.message ?: failure.javaClass.simpleName)
                                failure.suppressed.forEach { append("; suppressed: ${it.message}") }
                            },
                        )
                        if (terminalFrame < scheduled.warmupEndFrame) {
                            DeterministicTemporalCaptureOutcome.WarmupRejected(
                                reloaded,
                                plan,
                                reset,
                                resetCompletedAtUnixMs,
                                scheduled.warmupFrames,
                                scheduled.anchorFrame,
                                Math.toIntExact(terminalFrame - scheduled.anchorFrame),
                                terminalFrame,
                                detail,
                            )
                        } else {
                            DeterministicTemporalCaptureOutcome.CaptureRejected(
                                reloaded,
                                plan,
                                reset,
                                resetCompletedAtUnixMs,
                                scheduled.warmupFrames,
                                scheduled.anchorFrame,
                                scheduled.warmupEndFrame,
                                scheduled.captureFrame,
                                terminalFrame,
                                detail,
                            )
                        }
                    } else {
                        DeterministicTemporalCaptureOutcome.Captured(
                            reloaded,
                            plan,
                            reset,
                            resetCompletedAtUnixMs,
                            scheduled.warmupFrames,
                            scheduled.anchorFrame,
                            scheduled.warmupEndFrame,
                            capture,
                        )
                    }
                    if (completeOutcomeOnWorker) {
                        Thread { result.complete(outcome) }.start()
                    } else {
                        result.complete(outcome)
                    }
                }
                if (isClientThread()) finish.run() else executeOnClient(finish)
            }
            return result
        }

        override fun beginDeterministicSequence(cancellation: CancellationToken) {
            requireClientThread()
            cancellation.throwIfCancellationRequested()
            events.add("deterministic-sequence-begin")
        }

        override fun endDeterministicSequence(cancellation: CancellationToken) {
            requireClientThread()
            cancellation.throwIfCancellationRequested()
            events.add("deterministic-sequence-end")
        }

        override fun resourceCatalog(frameId: Long): ResourceCatalog {
            requireClientThread()
            check(closeCount == 0) { "Host is closed" }
            catalogQueries++
            events.add("resource-catalog")
            return RESOURCE_CATALOG
        }

        override fun capture(
            plan: CapturePlan,
            sink: ArtifactSink,
            frameId: Long,
            cancellation: CancellationToken,
        ): CaptureResult {
            requireClientThread()
            events.add("capture:$frameId")
            if (failCapture) throw IllegalStateException("capture failed")
            return CaptureResult(
                frameId,
                plan.targets.map { target ->
                    CaptureResult.ArtifactGroup(
                        target.artifactName,
                        RESOURCE_DESCRIPTOR.copy(frameId = frameId),
                        emptyList(),
                    )
                },
            )
        }

        override fun captureAfterPass(
            request: CapturePlan.AfterPassRequest,
            sink: ArtifactSink,
            cancellation: CancellationToken,
        ): CompletionStage<CapturePlan.AfterPassReceipt> = unsupported()

        override fun close() {
            requireClientThread()
            check(!phaseActive)
            closeCount++
            events.add("close")
        }

        private fun requireClientThread() {
            check(clientThread) { "Operation did not run on the client thread" }
        }

        private fun unwrap(failure: Throwable): Throwable =
            if (failure is CompletionException && failure.cause != null) failure.cause!! else failure

        private fun failure(message: String): DeterministicTemporalCaptureOutcome.Failure =
            DeterministicTemporalCaptureOutcome.Failure(
                DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED,
                message,
            )

        private fun <T> unsupported(): T = throw UnsupportedOperationException()
    }

    private class StubHost(private val hasResourceCatalog: Boolean = false) : VibrisRuntimeHost {
        override fun isClientThread(): Boolean = true

        override fun executeOnClient(task: Runnable) = task.run()

        override fun runtimeEnvironment(): RuntimeEnvironment = RuntimeEnvironment(
            "test-minecraft", "test-iris", "test-vibris", "test-java", "test-os",
            "test-gpu-vendor", "test-gpu-renderer", "test-opengl", "test-driver",
        )

        override fun status(): RuntimeStatus = unsupported()

        override fun applyContext(
            context: SceneContext,
            cancellation: CancellationToken,
        ): CompletionStage<ContextApplyResult> = unsupported()

        override fun reload(config: Map<String, String>?, cancellation: CancellationToken): ReloadResult = unsupported()

        override fun compileCatalog(cancellation: CancellationToken): CompileCatalog = CompileCatalog.empty(7)

        override fun resetTemporal(cancellation: CancellationToken): TemporalResetResult = unsupported()

        override fun captureDeterministicTemporalPhase(
            request: DeterministicTemporalCaptureRequest,
            planner: DeterministicTemporalCapturePlanner,
            sink: ArtifactSink,
            scheduler: DeterministicTemporalCaptureScheduler,
            cancellation: CancellationToken,
        ): CompletionStage<DeterministicTemporalCaptureOutcome> = unsupported()

        override fun beginDeterministicSequence(cancellation: CancellationToken) = Unit

        override fun endDeterministicSequence(cancellation: CancellationToken) = Unit

        override fun resourceCatalog(frameId: Long): ResourceCatalog =
            if (hasResourceCatalog) RESOURCE_CATALOG else unsupported()

        override fun capture(
            plan: CapturePlan,
            sink: ArtifactSink,
            frameId: Long,
            cancellation: CancellationToken,
        ): CaptureResult = unsupported()

        override fun captureAfterPass(
            request: CapturePlan.AfterPassRequest,
            sink: ArtifactSink,
            cancellation: CancellationToken,
        ): CompletionStage<CapturePlan.AfterPassReceipt> = unsupported()

        override fun close() = Unit

        private fun <T> unsupported(): T = throw UnsupportedOperationException()
    }

    companion object {
        private val CONTEXT = SceneContext(
            "test-save",
            "minecraft:overworld",
            "noon",
            "clear",
            "origin",
            70.0,
            SceneContext.Resolution(1280, 720),
            "default",
        )
        private val SINK = ArtifactSink { ByteArrayOutputStream() }
        private val RESOURCE_DESCRIPTOR = ResourceCatalog.ResourceDescriptor.of(
            "colortex0",
            ResourceCatalog.ResourceKind.TEXTURE,
            listOf(ResourceCatalog.TextureView.CURRENT),
            1,
            1,
            1,
            1,
            1,
            "RGBA8",
            4,
            ResourceCatalog.ScalarType.UINT8,
            4,
            0,
            "test color",
            "render_target",
            "TEXTURE_2D",
            "RGBA",
            "unorm",
            8,
            "RGBA",
            "UNSIGNED_BYTE",
        )
        private val RESOURCE_CATALOG = ResourceCatalog.of(listOf(RESOURCE_DESCRIPTOR), emptyList())
        private val COMPILE_CATALOG = CompileCatalog.empty(11L)
        private val CAPTURE_PLAN = CapturePlan(
            listOf(
                CapturePlan.Target(
                    CapturePlan.ResourceSelector(
                        ResourceCatalog.ResourceKind.TEXTURE,
                        "colortex0",
                        ResourceCatalog.TextureView.CURRENT,
                        0,
                        0,
                    ),
                    CapturePlan.ArtifactFormat.PNG,
                    "colortex0",
                    emptyList(),
                ),
            ),
        )
        private val PLANNER = DeterministicTemporalCapturePlanner { resourceCatalog, compileCatalog ->
            assertEquals(RESOURCE_CATALOG, resourceCatalog)
            assertEquals(COMPILE_CATALOG, compileCatalog)
            DeterministicTemporalCapturePlanning.Planned(CAPTURE_PLAN)
        }

        private fun request(warmupFrames: Int): DeterministicTemporalCaptureRequest =
            DeterministicTemporalCaptureRequest(
                CONTEXT,
                false,
                mapOf("QUALITY" to "2"),
                warmupFrames,
            )
    }
}
