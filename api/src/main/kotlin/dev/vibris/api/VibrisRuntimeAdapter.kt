package dev.vibris.api

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

interface VibrisRuntimeAdapter : AutoCloseable {
    fun supportsCameraMotion(): Boolean = false
    fun supportsCameraTranslation(): Boolean = false

    fun cameraMotion(
        request: CameraMotionPlan,
        plans: List<CapturePlan>,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<CameraMotionResult> = CompletableFuture.failedFuture(
        UnsupportedOperationException("Camera motion is unavailable"),
    )

    fun getRuntimeEnvironment(): CompletionStage<RuntimeEnvironment>

    fun listPresets(): CompletionStage<List<ScenePreset>> {
        val context = SceneContext(
            "test-save",
            "minecraft:overworld",
            "noon",
            "clear",
            "origin",
            70.0,
            SceneContext.Resolution(640, 360),
            "default",
        )
        return CompletableFuture.completedFuture(listOf(ScenePreset("default", "Default", context)))
    }

    fun validateContext(context: SceneContext): CompletionStage<ContextValidationResult> =
        CompletableFuture.completedFuture(ContextValidationResult.accepted())

    fun getStatus(): CompletionStage<RuntimeStatus>

    fun executeAction(action: RuntimeAction): CompletionStage<String> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Runtime action is unavailable"))

    fun ensureWorldAndContext(
        context: SceneContext,
        cancellation: CancellationToken,
    ): CompletionStage<ContextApplyResult>

    /**
     * Reloads the active source. A null config preserves the runtime's current setting values.
     * Every successful result must report the complete resolved setting set, including defaults and origins.
     */
    fun reloadVibrisShaderpack(
        config: Map<String, String>?,
        cancellation: CancellationToken,
    ): CompletionStage<ReloadResult>

    fun getCompileCatalog(cancellation: CancellationToken): CompletionStage<CompileCatalog>

    fun resetTemporalState(cancellation: CancellationToken): CompletionStage<TemporalResetResult>

    fun waitRenderedFrames(frameCount: Int, cancellation: CancellationToken): CompletionStage<Long>

    fun getResourceCatalog(): ResourceCatalog

    fun capture(
        plan: CapturePlan,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<CaptureResult>

    fun captureDeterministicTemporalPhase(
        request: DeterministicTemporalCaptureRequest,
        planner: DeterministicTemporalCapturePlanner,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<DeterministicTemporalCaptureOutcome>

    fun beginDeterministicSequence(cancellation: CancellationToken): CompletionStage<Void>

    fun endDeterministicSequence(cancellation: CancellationToken): CompletionStage<Void>

    fun captureAfterPass(
        request: CapturePlan.AfterPassRequest,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<CapturePlan.AfterPassReceipt>

    fun capturePatchedShaders(
        artifactName: String,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<CaptureResult> = CompletableFuture.failedFuture(
        UnsupportedOperationException("Patched shader capture is unavailable"),
    )

    override fun close()

    fun analyzeTexture(request: TextureAnalysisRequest, sink: ArtifactSink?, cancellation: CancellationToken)
        : CompletionStage<TextureAnalysisResult> = CompletableFuture.failedFuture(
            UnsupportedOperationException("Texture analysis is unavailable"),
        )
}