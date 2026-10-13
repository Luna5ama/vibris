package dev.vibris.core

import dev.vibris.api.ArtifactSink
import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.CompileCatalog
import dev.vibris.api.ContextApplyResult
import dev.vibris.api.ContextValidationResult
import dev.vibris.api.DeterministicTemporalCaptureOutcome
import dev.vibris.api.DeterministicTemporalCapturePlanner
import dev.vibris.api.DeterministicTemporalCaptureRequest
import dev.vibris.api.RuntimeAction
import dev.vibris.api.RuntimeEnvironment
import dev.vibris.api.ReloadResult
import dev.vibris.api.ResourceCatalog
import dev.vibris.api.RuntimeStatus
import dev.vibris.api.SceneContext
import dev.vibris.api.ScenePreset
import dev.vibris.api.TemporalResetResult
import dev.vibris.api.VibrisRuntimeAdapter
import java.util.Objects
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Supplier

class ThreadBoundVibrisRuntimeAdapter @JvmOverloads constructor(
    host: VibrisRuntimeHost?,
    frames: RenderedFrameClock?,
    private val frameWaitObserver: BiConsumer<Long, Long>? = null,
    private val activityObserver: Consumer<Boolean>? = null,
) : VibrisRuntimeAdapter {
    private val host = Objects.requireNonNull(host, "host")!!
    private val frames = Objects.requireNonNull(frames, "frames")!!
    private val closed = AtomicBoolean()
    private val pendingOperations = AtomicInteger()

    @Volatile
    private var catalog = ResourceCatalog.empty()

    fun isIdle(): Boolean = pendingOperations.get() == 0

    override fun supportsCameraMotion(): Boolean = host.supportsCameraMotion()
    override fun supportsCameraTranslation(): Boolean = host.supportsCameraTranslation()

    override fun cameraMotion(
        request: dev.vibris.api.CameraMotionPlan,
        plans: List<CapturePlan>,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<dev.vibris.api.CameraMotionResult> = trackActivity {
        onClientStage(Supplier { host.cameraMotion(request, plans, sink, frames, cancellation) }, cancellation)
            .thenCompose { result -> onClient(Supplier {
                catalog = host.resourceCatalog(frames.currentFrame())
                result
            }, cancellation) }
    }

    override fun getRuntimeEnvironment(): CompletionStage<RuntimeEnvironment> =
        trackActivity { onClient(Supplier(host::runtimeEnvironment), CancellationToken.none()) }

    override fun getStatus(): CompletionStage<RuntimeStatus> = trackActivity {
        onClient(
            Supplier {
                val status = host.status()
                catalog = try {
                    host.resourceCatalog(frames.currentFrame())
                } catch (_: IllegalStateException) {
                    ResourceCatalog.empty()
                }
                status
            },
            CancellationToken.none(),
        )
    }

    override fun executeAction(action: RuntimeAction): CompletionStage<String> =
        trackActivity { onClientStage(Supplier { host.executeAction(action) }, CancellationToken.none()) }

    override fun listPresets(): CompletionStage<List<ScenePreset>> =
        trackActivity { onClient(Supplier(host::presets), CancellationToken.none()) }

    override fun validateContext(context: SceneContext): CompletionStage<ContextValidationResult> =
        trackActivity { onClient(Supplier { host.validateContext(context) }, CancellationToken.none()) }

    override fun ensureWorldAndContext(
        context: SceneContext,
        cancellation: CancellationToken,
    ): CompletionStage<ContextApplyResult> =
        trackActivity { onClientStage(Supplier { host.applyContext(context, cancellation) }, cancellation) }

    override fun reloadVibrisShaderpack(
        config: Map<String, String>?,
        cancellation: CancellationToken,
    ): CompletionStage<ReloadResult> =
        trackActivity {
            onClient(
                Supplier {
                    val result = host.reload(config, cancellation)
                    if (result.successful) {
                        catalog = host.resourceCatalog(frames.currentFrame())
                    }
                    result
                },
                cancellation,
            )
        }

    override fun getCompileCatalog(cancellation: CancellationToken): CompletionStage<CompileCatalog> =
        trackActivity {
            onClient(
                Supplier { host.compileCatalog(cancellation) },
                cancellation,
            )
        }

    override fun resetTemporalState(cancellation: CancellationToken): CompletionStage<TemporalResetResult> =
        trackActivity { onClient(Supplier { host.resetTemporal(cancellation) }, cancellation) }

    override fun waitRenderedFrames(
        frameCount: Int,
        cancellation: CancellationToken,
    ): CompletionStage<Long> {
        if (closed.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("Vibris runtime is closed"))
        }
        val start = frames.currentFrame()
        return trackActivity {
            frames.waitRenderedFrames(frameCount, cancellation).thenCompose { end ->
                onClient(Supplier {
                    catalog = try {
                        host.resourceCatalog(end)
                    } catch (_: UnsupportedOperationException) {
                        ResourceCatalog.empty()
                    } catch (_: IllegalStateException) {
                        ResourceCatalog.empty()
                    }
                    frameWaitObserver?.accept(start, end)
                    end
                }, cancellation)
            }
        }
    }

    override fun getResourceCatalog(): ResourceCatalog = catalog

    override fun capture(
        plan: CapturePlan,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<CaptureResult> {
        if (closed.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("Vibris runtime is closed"))
        }
        return trackActivity {
            frames.captureAtNextFrame(cancellation) { frameId ->
                val result = host.capture(plan, sink, frameId, cancellation)
                catalog = host.resourceCatalog(frameId)
                result
            }
        }
    }

    override fun captureDeterministicTemporalPhase(
        request: DeterministicTemporalCaptureRequest,
        planner: DeterministicTemporalCapturePlanner,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<DeterministicTemporalCaptureOutcome> {
        if (closed.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("Vibris runtime is closed"))
        }
        return trackActivity {
            val scheduler = scheduler()
            onClientStage(
                Supplier {
                    host.captureDeterministicTemporalPhase(
                        request,
                        planner,
                        sink,
                        scheduler,
                        cancellation,
                    )
                },
                cancellation,
            ).thenCompose(::postprocessDeterministicOutcome)
        }
    }

    override fun beginDeterministicSequence(cancellation: CancellationToken): CompletionStage<Void> =
        trackActivity { onClientTask(Runnable { host.beginDeterministicSequence(cancellation) }, cancellation) }

    override fun endDeterministicSequence(cancellation: CancellationToken): CompletionStage<Void> =
        trackActivity { onClientTask(Runnable { host.endDeterministicSequence(cancellation) }, cancellation) }

    override fun captureAfterPass(
        request: CapturePlan.AfterPassRequest,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<CapturePlan.AfterPassReceipt> {
        if (closed.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("Vibris runtime is closed"))
        }
        return trackActivity {
            onClientStage(Supplier { host.captureAfterPass(request, sink, cancellation) }, cancellation)
        }
    }

    override fun capturePatchedShaders(
        artifactName: String,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<CaptureResult> {
        if (closed.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("Vibris runtime is closed"))
        }
        return trackActivity {
            onClientStage(
                Supplier { host.capturePatchedShaders(artifactName, sink, frames.currentFrame(), cancellation) },
                cancellation,
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        frames.close()
        if (host.isClientThread()) {
            host.close()
            return
        }
        val complete = CompletableFuture<Void>()
        host.executeOnClient {
            try {
                host.close()
                complete.complete(null)
            } catch (throwable: Throwable) {
                complete.completeExceptionally(throwable)
            }
        }
        complete.join()
    }

    private fun <T> onClient(action: Supplier<T>, cancellation: CancellationToken): CompletionStage<T> {
        if (closed.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("Vibris runtime is closed"))
        }
        val result = CompletableFuture<T>()
        val task = Runnable {
            try {
                if (closed.get()) {
                    throw IllegalStateException("Vibris runtime is closed")
                }
                cancellation.throwIfCancellationRequested()
                result.complete(action.get())
            } catch (throwable: Throwable) {
                result.completeExceptionally(throwable)
            }
        }
        if (host.isClientThread()) {
            task.run()
        } else {
            host.executeOnClient(task)
        }
        return result
    }

    override fun analyzeTexture(
        request: dev.vibris.api.TextureAnalysisRequest, sink: ArtifactSink?, cancellation: CancellationToken,
    ): CompletionStage<dev.vibris.api.TextureAnalysisResult> {
        if (closed.get()) return CompletableFuture.failedFuture(IllegalStateException("Vibris runtime is closed"))
        return trackActivity {
            onClientStage(Supplier { host.analyzeTexture(request, sink, cancellation) }, cancellation)
        }
    }

    private fun onClientTask(action: Runnable, cancellation: CancellationToken): CompletionStage<Void> {
        if (closed.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("Vibris runtime is closed"))
        }
        val result = CompletableFuture<Void>()
        val task = Runnable {
            try {
                if (closed.get()) throw IllegalStateException("Vibris runtime is closed")
                cancellation.throwIfCancellationRequested()
                action.run()
                result.complete(null)
            } catch (throwable: Throwable) {
                result.completeExceptionally(throwable)
            }
        }
        if (host.isClientThread()) task.run() else host.executeOnClient(task)
        return result
    }

    private fun <T> onClientStage(
        action: Supplier<CompletionStage<T>>,
        cancellation: CancellationToken,
    ): CompletionStage<T> = onClient(action, cancellation).thenCompose { stage -> stage }

    private fun postprocessDeterministicOutcome(
        outcome: DeterministicTemporalCaptureOutcome,
    ): CompletionStage<DeterministicTemporalCaptureOutcome> {
        if (!closed.get()) {
            runCatching { observeDeterministicOutcome(outcome) }
        }
        return CompletableFuture.completedFuture(outcome)
    }

    private fun observeDeterministicOutcome(
        outcome: DeterministicTemporalCaptureOutcome,
    ) {
        val observedFrames = when (outcome) {
            is DeterministicTemporalCaptureOutcome.ContextRejected,
            is DeterministicTemporalCaptureOutcome.ReloadRejected,
            is DeterministicTemporalCaptureOutcome.PlanningRejected,
            is DeterministicTemporalCaptureOutcome.ResetRejected,
            -> null
            is DeterministicTemporalCaptureOutcome.WarmupRejected ->
                outcome.anchorFrame to outcome.currentFrame
            is DeterministicTemporalCaptureOutcome.CaptureRejected ->
                outcome.anchorFrame to outcome.warmupEndFrame
            is DeterministicTemporalCaptureOutcome.Captured ->
                outcome.anchorFrame to outcome.warmupEndFrame
        }
        observedFrames?.let { (start, end) ->
            runCatching { frameWaitObserver?.accept(start, end) }
        }
        catalog = when (outcome) {
            is DeterministicTemporalCaptureOutcome.ContextRejected,
            is DeterministicTemporalCaptureOutcome.ReloadRejected,
            -> catalog
            is DeterministicTemporalCaptureOutcome.PlanningRejected -> outcome.reloaded.resourceCatalog
            is DeterministicTemporalCaptureOutcome.ResetRejected -> outcome.reloaded.resourceCatalog
            is DeterministicTemporalCaptureOutcome.WarmupRejected -> outcome.reloaded.resourceCatalog
            is DeterministicTemporalCaptureOutcome.CaptureRejected -> outcome.reloaded.resourceCatalog
            is DeterministicTemporalCaptureOutcome.Captured -> outcome.reloaded.resourceCatalog
        }
    }

    private fun scheduler(): DeterministicTemporalCaptureScheduler =
        object : DeterministicTemporalCaptureScheduler {
            override fun schedule(
                warmupFrames: Int,
                cancellation: CancellationToken,
                capture: java.util.function.LongFunction<CaptureResult>,
            ): DeterministicTemporalCaptureScheduler.ScheduledCapture =
                frames.scheduleDeterministicTemporalCapture(warmupFrames, cancellation, capture)

            override fun currentFrame(): Long = frames.currentFrame()
        }

    private fun <T> trackActivity(action: Supplier<CompletionStage<T>>): CompletionStage<T> {
        val becameActive = pendingOperations.getAndIncrement() == 0
        val stage = try {
            if (becameActive) {
                activityObserver?.accept(true)
            }
            action.get()
        } catch (throwable: Throwable) {
            operationFinished()
            return CompletableFuture.failedFuture(throwable)
        }
        return stage.whenComplete { _, _ -> operationFinished() }
    }

    private fun operationFinished() {
        if (pendingOperations.decrementAndGet() == 0) {
            activityObserver?.accept(false)
        }
    }

}