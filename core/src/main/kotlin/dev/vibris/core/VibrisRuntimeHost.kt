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
import java.util.concurrent.CompletionStage

interface VibrisRuntimeHost : AutoCloseable {
    fun supportsCameraMotion(): Boolean = false

    fun cameraMotion(
        request: dev.vibris.api.CameraMotionPlan,
        plans: List<CapturePlan>,
        sink: ArtifactSink,
        frames: RenderedFrameClock,
        cancellation: CancellationToken,
    ): CompletionStage<dev.vibris.api.CameraMotionResult> = java.util.concurrent.CompletableFuture.failedFuture(
        UnsupportedOperationException("Camera motion is unavailable"),
    )

    fun isClientThread(): Boolean

    fun executeOnClient(task: Runnable)

    fun runtimeEnvironment(): RuntimeEnvironment

    fun status(): RuntimeStatus

    fun executeAction(action: RuntimeAction): CompletionStage<String> =
        java.util.concurrent.CompletableFuture.failedFuture(
            UnsupportedOperationException("Runtime action is unavailable"),
        )

    fun presets(): List<ScenePreset> = java.util.List.of()

    fun validateContext(context: SceneContext): ContextValidationResult = ContextValidationResult.accepted()

    fun applyContext(context: SceneContext, cancellation: CancellationToken): CompletionStage<ContextApplyResult>

    fun reload(config: Map<String, String>?, cancellation: CancellationToken): ReloadResult

    fun compileCatalog(cancellation: CancellationToken): CompileCatalog

    fun resetTemporal(cancellation: CancellationToken): TemporalResetResult

    fun captureDeterministicTemporalPhase(
        request: DeterministicTemporalCaptureRequest,
        planner: DeterministicTemporalCapturePlanner,
        sink: ArtifactSink,
        scheduler: DeterministicTemporalCaptureScheduler,
        cancellation: CancellationToken,
    ): CompletionStage<DeterministicTemporalCaptureOutcome>

    fun beginDeterministicSequence(cancellation: CancellationToken)

    fun endDeterministicSequence(cancellation: CancellationToken)

    fun resourceCatalog(frameId: Long): ResourceCatalog

    fun capture(
        plan: CapturePlan,
        sink: ArtifactSink,
        frameId: Long,
        cancellation: CancellationToken,
    ): CaptureResult

    fun captureAfterPass(
        request: CapturePlan.AfterPassRequest,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<CapturePlan.AfterPassReceipt>

    fun capturePatchedShaders(
        artifactName: String,
        sink: ArtifactSink,
        frameId: Long,
        cancellation: CancellationToken,
    ): CompletionStage<CaptureResult> = java.util.concurrent.CompletableFuture.failedFuture(
        UnsupportedOperationException("Patched shader capture is unavailable"),
    )

    override fun close()

    fun analyzeTexture(request: dev.vibris.api.TextureAnalysisRequest, sink: ArtifactSink?, cancellation: CancellationToken)
        : CompletionStage<dev.vibris.api.TextureAnalysisResult> = java.util.concurrent.CompletableFuture.failedFuture(
            UnsupportedOperationException("Texture analysis is unavailable"),
        )
}