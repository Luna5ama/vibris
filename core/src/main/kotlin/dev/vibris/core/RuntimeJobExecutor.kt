package dev.vibris.core

import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.ContextApplyResult
import dev.vibris.api.CompileCatalog
import dev.vibris.api.DeterministicTemporalCaptureOutcome
import dev.vibris.api.DeterministicTemporalCapturePlanner
import dev.vibris.api.DeterministicTemporalCaptureReloaded
import dev.vibris.api.DeterministicTemporalCaptureRequest
import dev.vibris.api.EffectiveShaderSettings
import dev.vibris.api.ReloadResult
import dev.vibris.api.SceneContext
import dev.vibris.api.TemporalResetResult
import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v2.ActionReceipt
import dev.vibris.protocol.v2.ArtifactMetadata
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobCompleted
import dev.vibris.protocol.v2.JobResult
import dev.vibris.protocol.v2.JobStage
import dev.vibris.protocol.v2.RestorationReceipt
import dev.vibris.protocol.v2.RuntimeRecovery
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer

internal class RuntimeJobExecutor @JvmOverloads constructor(
    runtime: VibrisRuntimeAdapter?,
    private val probe: CoreProbe,
    private val activator: SourceActivator,
    private val shaderLogs: ShaderLogSink,
    maxActions: Int = ServerConfiguration.DEFAULT_MAX_ACTIONS_PER_JOB,
    private val restorationTimeout: Duration = DEFAULT_RESTORATION_TIMEOUT,
    replayCaptureRoot: Path? = null,
    replayerRoot: Path? = null,
    bundledReplayJava: Path? = null,
) {
    private val runtime: VibrisRuntimeAdapter = requireNotNull(runtime) { "runtime" }
    private val captures = CaptureJobExecutor(shaderLogs as? ArtifactManager, maxActions)
    private val awaiter = RuntimeAwaiter(probe)
    private val actions = ActionJobExecutor(
        this.runtime,
        probe,
        captures,
        this,
        NsightGpuTraceRunner(replayCaptureRoot, replayerRoot, bundledReplayJava),
    )
    private val compileValidation = CompileValidationJobExecutor(this)

    init {
        require(!restorationTimeout.isZero && !restorationTimeout.isNegative) {
            "restorationTimeout must be positive"
        }
    }

    @Volatile
    private var activeContext: SceneContext? = null

    @Volatile
    private var activeShaderSettings: EffectiveShaderSettings? = null

    @Volatile
    private var pendingRecovery: PendingRecovery? = null

    @Volatile
    private var activeShaderLoadedAtUnixMs: Long = 0

    @Volatile
    private var activePassMappingSha256: String = ""

    @Throws(Failure::class)
    fun execute(job: CoreJob, progress: Consumer<JobStage>): TerminalResult {
        val startedAtUnixMs = System.currentTimeMillis()
        val startedNanos = System.nanoTime()
        val deadline = RuntimeJobContext.deadline(job)
        if (job.submission.hasRecoverRuntime()) {
            return executeRecovery(job, progress, startedAtUnixMs, startedNanos)
        }
        BenchmarkProvenance.captureRuntimeEnvironment(
            await(runtime.getRuntimeEnvironment(), job, deadline),
        )
        val isolation = BenchmarkCaseIsolation.begin(
            job,
            activator,
            activeShaderSettings,
            activeContext,
        )
        try {
            var completed = if (job.submission.hasCompileValidation()) {
                compileValidation.execute(job, progress, deadline)
            } else {
                actions.execute(job, progress, deadline)
            }
            completed = attachProvenance(job, completed)
            val restoration = terminalize(job, isolation, true, progress)
            completed = completed.toBuilder().setRestoration(restoration).build()
            isolation.release(activator)
            return completed(job, completed, startedAtUnixMs, startedNanos)
        } catch (failure: Failure) {
            if (failure.holdOwnership) throw failure
            if (failure.cleanupBarrier != null) {
                throw retainUnsafeRuntime(job, isolation, failure)
            }
            val restored = try {
                terminalize(job, isolation, false, progress)
            } catch (restoreFailure: Failure) {
                restoreFailure.addSuppressed(failure)
                throw restoreFailure
            }
            isolation.release(activator)
            throw failure.withRestoration(restored)
        } catch (failure: RuntimeException) {
            val wrapped = Failure(ErrorCode.ERROR_CODE_INTERNAL, failure.message ?: "Runtime job failed.")
            val restored = try {
                terminalize(job, isolation, false, progress)
            } catch (restoreFailure: Failure) {
                restoreFailure.addSuppressed(failure)
                throw restoreFailure
            }
            isolation.release(activator)
            throw wrapped.withRestoration(restored)
        }
    }

    private fun completed(
        job: CoreJob,
        result: JobResult,
        startedAtUnixMs: Long,
        startedNanos: Long,
    ): TerminalResult = TerminalResult.completed(
        JobCompleted.newBuilder()
            .setJobId(job.submission.jobId)
            .setRequestId(job.requestId)
            .setResult(awaiter.withTimings(job, result, startedAtUnixMs, startedNanos))
            .build(),
    )

    private fun attachProvenance(job: CoreJob, result: JobResult): JobResult {
        val source = activator.activeSnapshot() ?: return result
        return result.toBuilder()
            .setProvenance(
                BenchmarkProvenance.result(
                    job,
                    source,
                    activeShaderSettings,
                    activeContext,
                    activeShaderLoadedAtUnixMs,
                    activePassMappingSha256,
                ),
            )
            .build()
    }

    @Throws(Failure::class)
    fun applyContext(job: CoreJob, progress: Consumer<JobStage>, deadline: Long): ContextApplyResult {
        val cancellation = job.cancellation.token()
        progress.accept(JobStage.JOB_STAGE_LOADING_WORLD)
        probe.event(job.requestId, "ENSURING_WORLD")
        progress.accept(JobStage.JOB_STAGE_APPLYING_CONTEXT)
        val context: ContextApplyResult = await(
            runtime.ensureWorldAndContext(RuntimeJobContext.toApi(job.submission.context), cancellation),
            job,
            deadline,
        )
        if (!context.successful) {
            throw Failure(ErrorCode.ERROR_CODE_WORLD_LOAD_FAILED, context.message)
        }
        activeContext = context.context
        probe.contextApplied(job.requestId, job.workspaceId, RuntimeJobContext.toProtocol(context.context))
        return context
    }

    @Throws(Failure::class)
    fun reset(job: CoreJob, progress: Consumer<JobStage>, deadline: Long) {
        progress.accept(JobStage.JOB_STAGE_RESETTING_TEMPORAL_STATE)
        probe.event(job.requestId, "RESETTING_TEMPORAL_STATE")
        val reset: TemporalResetResult = await(runtime.resetTemporalState(job.cancellation.token()), job, deadline)
        if (!reset.successful) {
            throw Failure(ErrorCode.ERROR_CODE_INTERNAL, "Runtime temporal state reset failed.")
        }
    }

    @Throws(Failure::class)
    fun waitFrames(
        job: CoreJob,
        progress: Consumer<JobStage>,
        deadline: Long,
        frames: Int,
    ): Long {
        progress.accept(JobStage.JOB_STAGE_WARMING_UP)
        probe.event(job.requestId, "WARMING_UP")
        return await(runtime.waitRenderedFrames(frames, job.cancellation.token()), job, deadline)
    }

    fun runtime(): VibrisRuntimeAdapter = runtime

    fun observeCatalog(catalog: CompileCatalog, loadedAtUnixMs: Long = System.currentTimeMillis()) {
        activePassMappingSha256 = catalog.mappingSha256
        activeShaderLoadedAtUnixMs = loadedAtUnixMs
    }

    fun probe(): CoreProbe = probe

    @Throws(Failure::class)
    fun activateSource(
        job: CoreJob,
        source: SourceRegistry.Lease,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): ReloadResult = reuseLoadedPipeline(job, source, null, progress, deadline)
        ?: activateSource(job, source, null, progress, deadline)

    @Throws(Failure::class)
    fun loadShader(
        job: CoreJob,
        source: SourceRegistry.Lease,
        config: dev.vibris.protocol.v2.ShaderConfig,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): LoadResult {
        val settings = if (config.preserveCurrent) null else config.valuesMap
        val reload = reuseLoadedPipeline(job, source, settings, progress, deadline) ?: if (activator.isActive(source)) {
            reloadActiveSource(job, source, settings, progress, deadline)
        } else {
            activateSource(job, source, settings, progress, deadline)
        }
        val context = applyContext(job, progress, deadline)
        reset(job, progress, deadline)
        return LoadResult(reload, context)
    }

    @Throws(Failure::class)
    fun compileShader(
        job: CoreJob,
        source: SourceRegistry.Lease,
        config: dev.vibris.protocol.v2.ShaderConfig,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): CompileResult {
        val settings = if (config.preserveCurrent) null else config.valuesMap
        reuseLoadedPipeline(job, source, settings, progress, deadline)?.let { reload ->
            val catalog = await(runtime.getCompileCatalog(job.cancellation.token()), job, deadline)
            val loadedAtUnixMs = activeShaderLoadedAtUnixMs.takeIf { it > 0 } ?: System.currentTimeMillis()
            observeCatalog(catalog, loadedAtUnixMs)
            return CompileResult(reload, catalog, loadedAtUnixMs)
        }
        val activation = if (activator.isActive(source)) null else try {
            progress.accept(JobStage.JOB_STAGE_ACTIVATING_SOURCE)
            probe.event(job.requestId, "ACTIVATING_SOURCE")
            activator.begin(source, job.cancellation.token(), deadline)
        } catch (failure: SourceActivator.Failure) {
            throw Failure(failure.code, failure.message)
        }
        try {
            val reload = reload(job, settings, progress, deadline)
            val catalog = await(runtime.getCompileCatalog(job.cancellation.token()), job, deadline)
            val loadedAtUnixMs = System.currentTimeMillis()
            observeCatalog(catalog, loadedAtUnixMs)
            if (activation != null) {
                try {
                    activator.commit(activation)
                } catch (failure: SourceActivator.Failure) {
                    throw Failure(failure.code, failure.message)
                }
            }
            return CompileResult(reload, catalog, loadedAtUnixMs)
        } catch (failure: Failure) {
            if (activation != null) {
                val restored = activator.rollback(activation)
                if (!restored) activator.fail(activation)
            }
            throw failure
        }
    }

    @Throws(Failure::class)
    private fun activateSource(
        job: CoreJob,
        source: SourceRegistry.Lease,
        config: Map<String, String>?,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): ReloadResult {
        progress.accept(JobStage.JOB_STAGE_ACTIVATING_SOURCE)
        probe.event(job.requestId, "ACTIVATING_SOURCE")
        val activation = try {
            activator.begin(source, job.cancellation.token(), deadline)
        } catch (failure: SourceActivator.Failure) {
            throw Failure(failure.code, failure.message)
        }
        var original: Failure? = null
        var successful: ReloadResult? = null
        var activeStatePreserved = false
        try {
            val reload = reload(job, config, progress, deadline)
            if (!reload.successful) {
                activeStatePreserved = reload.activeStatePreserved
                throw ShaderReloadFailure.create(shaderLogs, job, reload)
            }
            successful = reload
            try {
                activator.commit(activation)
            } catch (failure: SourceActivator.Failure) {
                throw Failure(failure.code, failure.message)
            }
        } catch (failure: Failure) {
            original = failure
        }
        if (original == null) {
            activeShaderLoadedAtUnixMs = System.currentTimeMillis()
            return successful!!
        }
        val restored = activator.rollback(activation)
        if (restored && activation.previous() != null && !activeStatePreserved && !reloadPreviousSource()) {
            activator.markNotReady()
        }
        if (!restored) {
            activator.fail(activation)
        }
        throw original
    }

    @Throws(Failure::class)
    private fun reloadActiveSource(
        job: CoreJob,
        source: SourceRegistry.Lease,
        config: Map<String, String>?,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): ReloadResult {
        val reload = reload(job, config, progress, deadline)
        if (!reload.successful) {
            if (!reload.activeStatePreserved && !reloadPreviousSource()) {
                activator.markNotReady()
            }
            throw ShaderReloadFailure.create(shaderLogs, job, reload)
        }
        activeShaderLoadedAtUnixMs = System.currentTimeMillis()
        return reload
    }

    @Throws(Failure::class)
    private fun reload(
        job: CoreJob,
        config: Map<String, String>?,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): ReloadResult {
        progress.accept(JobStage.JOB_STAGE_COMPILING)
        probe.event(job.requestId, "RELOADING_SHADERS")
        val result = await(runtime.reloadVibrisShaderpack(config, job.cancellation.token()), job, deadline)
        if (result.successful) {
            activeShaderSettings = result.effectiveSettings
            activePassMappingSha256 = ""
        }
        return result
    }

    @Throws(Failure::class)
    private fun reuseLoadedPipeline(
        job: CoreJob,
        source: SourceRegistry.Lease,
        config: Map<String, String>?,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): ReloadResult? {
        if (!activator.ready()) return null
        val current = try {
            activator.activeSnapshot()
        } catch (failure: SourceActivator.Failure) {
            throw Failure(failure.code, failure.message)
        } ?: return null
        val settings = activeShaderSettings ?: return null
        if (current.snapshotSha256 != source.snapshotSha256 || !matchesLoadedSettings(settings, config)) {
            return null
        }
        if (!activator.isActive(source)) {
            progress.accept(JobStage.JOB_STAGE_ACTIVATING_SOURCE)
            probe.event(job.requestId, "ACTIVATING_SOURCE")
            val activation = try {
                activator.begin(source, job.cancellation.token(), deadline)
            } catch (failure: SourceActivator.Failure) {
                throw Failure(failure.code, failure.message)
            }
            try {
                activator.commit(activation)
            } catch (failure: SourceActivator.Failure) {
                val restored = activator.rollback(activation)
                if (!restored) activator.fail(activation)
                throw Failure(failure.code, failure.message)
            }
        }
        probe.event(job.requestId, "REUSING_LOADED_SHADER")
        return ReloadResult.success(settings, emptyList())
    }

    private fun matchesLoadedSettings(
        settings: EffectiveShaderSettings,
        config: Map<String, String>?,
    ): Boolean {
        if (config == null) return true
        val known = settings.settings.associateBy { setting -> setting.name }
        if (config.keys.any { name -> name !in known }) return false
        return settings.settings.all { setting ->
            setting.value == (config[setting.name] ?: setting.defaultValue)
        }
    }

    @Throws(Failure::class)
    fun capture(
        job: CoreJob,
        progress: Consumer<JobStage>,
        deadline: Long,
        prepared: CaptureJobExecutor.Prepared,
        plan: CapturePlan,
    ): CaptureResult {
        progress.accept(JobStage.JOB_STAGE_CAPTURING)
        probe.event(job.requestId, "CAPTURING")
        val checkpoint = prepared.checkpoint()
        try {
            return awaitCapture(runtime.capture(plan, prepared.sink(), job.cancellation.token()), job, deadline)
        } catch (failure: Failure) {
            try {
                prepared.rollback(checkpoint)
            } catch (rollbackFailure: IOException) {
                rollbackFailure.addSuppressed(failure)
                throw CaptureJobExecutor.failure(rollbackFailure)
            }
            throw failure
        }
    }

    @Throws(Failure::class)
    fun captureDeterministicTemporalPhase(
        job: CoreJob,
        source: SourceRegistry.Lease,
        config: dev.vibris.protocol.v2.ShaderConfig,
        planner: DeterministicTemporalCapturePlanner,
        progress: Consumer<JobStage>,
        deadline: Long,
        prepared: CaptureJobExecutor.Prepared,
        warmupFrames: Int,
    ): DeterministicTemporalCaptureOutcome {
        val previous = try {
            deterministicSnapshot()
        } catch (failure: Failure) {
            throw DeterministicPhaseFailure(DeterministicFailurePhase.LOAD, failure)
        }
        val checkpoint = prepared.checkpoint()
        val activation = try {
            if (activator.isActive(source)) {
                null
            } else {
                progress.accept(JobStage.JOB_STAGE_ACTIVATING_SOURCE)
                probe.event(job.requestId, "ACTIVATING_SOURCE")
                try {
                    activator.begin(source, job.cancellation.token(), deadline)
                } catch (failure: SourceActivator.Failure) {
                    throw Failure(failure.code, failure.message)
                }
            }
        } catch (failure: Failure) {
            throw DeterministicPhaseFailure(DeterministicFailurePhase.LOAD, failure)
        }
        var phaseAttempted = false
        var activationFinished = false
        var checkpointFinished = false
        var failurePhase = DeterministicFailurePhase.LOAD
        try {
            progress.accept(JobStage.JOB_STAGE_CAPTURING)
            probe.event(job.requestId, "CAPTURING_DETERMINISTIC_TEMPORAL_PHASE")
            val request = DeterministicTemporalCaptureRequest(
                RuntimeJobContext.toApi(job.submission.context),
                config.preserveCurrent,
                if (config.preserveCurrent) emptyMap() else config.valuesMap,
                warmupFrames,
            )
            failurePhase = DeterministicFailurePhase.CAPTURE
            phaseAttempted = true
            val outcome = awaitCapture(
                runtime.captureDeterministicTemporalPhase(
                    request,
                    planner,
                    prepared.sink(),
                    job.cancellation.token(),
                ),
                job,
                deadline,
            )
            val reloaded = when (outcome) {
                is DeterministicTemporalCaptureOutcome.ContextRejected,
                is DeterministicTemporalCaptureOutcome.ReloadRejected,
                -> null
                is DeterministicTemporalCaptureOutcome.PlanningRejected -> outcome.reloaded
                is DeterministicTemporalCaptureOutcome.ResetRejected -> outcome.reloaded
                is DeterministicTemporalCaptureOutcome.WarmupRejected -> outcome.reloaded
                is DeterministicTemporalCaptureOutcome.CaptureRejected -> outcome.reloaded
                is DeterministicTemporalCaptureOutcome.Captured -> outcome.reloaded
            }
            if (reloaded != null) {
                failurePhase = DeterministicFailurePhase.LOAD
                observeDeterministicLoad(reloaded)
                commitDeterministicActivation(activation)
                activationFinished = true
            }
            val rejection = when (outcome) {
                is DeterministicTemporalCaptureOutcome.ContextRejected -> {
                    failurePhase = DeterministicFailurePhase.LOAD
                    activationFinished = true
                    rollbackRejectedContext(previous, activation)
                    outcome.failure
                }
                is DeterministicTemporalCaptureOutcome.ReloadRejected -> {
                    failurePhase = DeterministicFailurePhase.LOAD
                    activationFinished = true
                    rollbackRejectedReload(previous, activation, outcome)
                    outcome.failure
                }
                is DeterministicTemporalCaptureOutcome.PlanningRejected -> {
                    failurePhase = DeterministicFailurePhase.CAPTURE
                    outcome.failure
                }
                is DeterministicTemporalCaptureOutcome.ResetRejected -> {
                    failurePhase = DeterministicFailurePhase.RESET
                    outcome.failure
                }
                is DeterministicTemporalCaptureOutcome.WarmupRejected -> {
                    failurePhase = DeterministicFailurePhase.WAIT
                    outcome.failure
                }
                is DeterministicTemporalCaptureOutcome.CaptureRejected -> {
                    failurePhase = DeterministicFailurePhase.CAPTURE
                    outcome.failure
                }
                is DeterministicTemporalCaptureOutcome.Captured -> {
                    failurePhase = DeterministicFailurePhase.CAPTURE
                    return outcome
                }
            }
            checkpointFinished = true
            rollbackDeterministicCapture(
                prepared,
                checkpoint,
                rejection,
            )
            return outcome
        } catch (failure: Failure) {
            var terminal = failure
            if (!activationFinished) {
                if (phaseAttempted) {
                    terminal = restoreDeterministicSnapshot(previous, activation, failure) ?: failure
                } else if (activation != null && !rollbackActivation(activation)) {
                    terminal = restorationFailure("The previous shader source link could not be restored.")
                    terminal.addSuppressed(failure)
                }
            }
            val terminalPhase = failurePhase
            if (!checkpointFinished) {
                try {
                    rollbackCapture(prepared, checkpoint, terminal)
                } catch (cleanup: Failure) {
                    throw DeterministicPhaseFailure(terminalPhase, cleanup)
                }
            }
            throw DeterministicPhaseFailure(
                deterministicFailurePhase(phaseAttempted, activationFinished, terminalPhase),
                terminal,
            )
        } catch (failure: RuntimeException) {
            val mapped = CaptureJobExecutor.failure(failure)
            var terminal = mapped
            if (!activationFinished) {
                if (phaseAttempted) {
                    terminal = restoreDeterministicSnapshot(previous, activation, mapped) ?: mapped
                } else if (activation != null && !rollbackActivation(activation)) {
                    terminal = restorationFailure("The previous shader source link could not be restored.")
                    terminal.addSuppressed(mapped)
                }
            }
            val terminalPhase = failurePhase
            if (!checkpointFinished) {
                try {
                    rollbackCapture(prepared, checkpoint, terminal)
                } catch (cleanup: Failure) {
                    throw DeterministicPhaseFailure(terminalPhase, cleanup)
                }
            }
            throw DeterministicPhaseFailure(
                deterministicFailurePhase(phaseAttempted, activationFinished, terminalPhase),
                terminal,
            )
        }
    }

    private fun deterministicFailurePhase(
        phaseAttempted: Boolean,
        activationFinished: Boolean,
        failurePhase: DeterministicFailurePhase,
    ): DeterministicFailurePhase {
        check(phaseAttempted || failurePhase == DeterministicFailurePhase.LOAD)
        if (!activationFinished && failurePhase != DeterministicFailurePhase.LOAD) {
            check(failurePhase == DeterministicFailurePhase.CAPTURE)
        }
        return failurePhase
    }

    fun deterministicReloadFailure(job: CoreJob, reload: ReloadResult): Failure =
        ShaderReloadFailure.create(shaderLogs, job, reload)

    fun deterministicPhaseFailure(
        failure: DeterministicTemporalCaptureOutcome.Failure,
    ): Failure = Failure(
        when (failure.kind) {
            DeterministicTemporalCaptureOutcome.FailureKind.CANCELLED -> ErrorCode.ERROR_CODE_CANCELLED
            DeterministicTemporalCaptureOutcome.FailureKind.RESOURCE_NOT_FOUND ->
                ErrorCode.ERROR_CODE_RESOURCE_NOT_FOUND
            DeterministicTemporalCaptureOutcome.FailureKind.ARTIFACT_TOO_LARGE ->
                ErrorCode.ERROR_CODE_ARTIFACT_TOO_LARGE
            DeterministicTemporalCaptureOutcome.FailureKind.ARTIFACT_QUOTA_EXCEEDED ->
                ErrorCode.ERROR_CODE_ARTIFACT_QUOTA_EXCEEDED
            DeterministicTemporalCaptureOutcome.FailureKind.INVALID_CAPTURE,
            DeterministicTemporalCaptureOutcome.FailureKind.MISSED_TARGET,
            DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED,
            DeterministicTemporalCaptureOutcome.FailureKind.CLEANUP_FAILED,
            -> ErrorCode.ERROR_CODE_CAPTURE_FAILED
        },
        failure.message,
    )

    fun deterministicPlanningFailure(failure: Failure): DeterministicTemporalCaptureOutcome.Failure =
        DeterministicTemporalCaptureOutcome.Failure(
            when (failure.code) {
                ErrorCode.ERROR_CODE_CANCELLED -> DeterministicTemporalCaptureOutcome.FailureKind.CANCELLED
                ErrorCode.ERROR_CODE_RESOURCE_NOT_FOUND ->
                    DeterministicTemporalCaptureOutcome.FailureKind.RESOURCE_NOT_FOUND
                ErrorCode.ERROR_CODE_ARTIFACT_TOO_LARGE ->
                    DeterministicTemporalCaptureOutcome.FailureKind.ARTIFACT_TOO_LARGE
                ErrorCode.ERROR_CODE_ARTIFACT_QUOTA_EXCEEDED ->
                    DeterministicTemporalCaptureOutcome.FailureKind.ARTIFACT_QUOTA_EXCEEDED
                else -> DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED
            },
            failure.message ?: "Deterministic capture planning failed.",
        )

    private fun observeDeterministicLoad(reloaded: DeterministicTemporalCaptureReloaded) {
        activeContext = reloaded.context.context
        activeShaderSettings = reloaded.reload.effectiveSettings
        activeShaderLoadedAtUnixMs = reloaded.reloadCompletedAtUnixMs
        activePassMappingSha256 = reloaded.compileCatalog.mappingSha256
    }

    @Throws(Failure::class)
    private fun commitDeterministicActivation(activation: SourceActivator.Activation?) {
        if (activation == null) return
        try {
            activator.commit(activation)
        } catch (failure: SourceActivator.Failure) {
            throw Failure(failure.code, failure.message)
        }
    }

    @Throws(Failure::class)
    private fun rollbackRejectedContext(
        previous: DeterministicRuntimeSnapshot,
        activation: SourceActivator.Activation?,
    ) {
        if (activation != null && !rollbackActivation(activation)) {
            throw restorationFailure("The previous shader source link could not be restored after context rejection.")
        }
        if (!restorePreviousContext(previous)) {
            throw restorationFailure("The previous scene context could not be restored after context rejection.")
        }
        adoptPreviousShaderState(previous)
    }

    @Throws(Failure::class)
    private fun rollbackRejectedReload(
        previous: DeterministicRuntimeSnapshot,
        activation: SourceActivator.Activation?,
        outcome: DeterministicTemporalCaptureOutcome.ReloadRejected,
    ) {
        if (activation != null && !rollbackActivation(activation)) {
            throw restorationFailure("The previous shader source link could not be restored after reload rejection.")
        }
        if (outcome.reload.activeStatePreserved) {
            val settings = previous.settings
            if (
                previous.source == null || settings == null ||
                !settings.hasSameResolvedState(outcome.reload.effectiveSettings)
            ) {
                throw restorationFailure(
                    "The preserved shader state does not match the previous effective settings.",
                )
            }
            adoptPreviousShaderState(previous)
        } else if (!restorePreviousShader(previous)) {
            throw restorationFailure(
                "The previous shader source and settings could not be restored after reload rejection.",
            )
        }
        if (!restorePreviousContext(previous)) {
            throw restorationFailure("The previous scene context could not be restored after reload rejection.")
        }
    }

    private fun rollbackActivation(activation: SourceActivator.Activation): Boolean {
        val restored = try {
            activator.rollback(activation)
        } catch (_: Exception) {
            false
        }
        if (!restored) {
            runCatching { activator.fail(activation) }
            activator.markNotReady()
        }
        return restored
    }

    private fun restoreDeterministicSnapshot(
        previous: DeterministicRuntimeSnapshot,
        activation: SourceActivator.Activation?,
        original: Failure,
    ): Failure? {
        val linkRestored = activation == null || rollbackActivation(activation)
        val shaderRestored = linkRestored && restorePreviousShader(previous)
        val contextRestored = linkRestored && restorePreviousContext(previous)
        if (linkRestored && shaderRestored && contextRestored) return null
        val failure = restorationFailure(
            "The exact runtime state before deterministic capture could not be restored.",
        )
        failure.addSuppressed(original)
        return failure
    }

    private fun restorePreviousShader(previous: DeterministicRuntimeSnapshot): Boolean {
        if (previous.source == null) {
            return false
        }
        val settings = previous.settings ?: return false
        return try {
            val reload = restoreOperation("shader reload") { cancellation ->
                runtime.reloadVibrisShaderpack(settings.values(), cancellation)
            }
            if (!reload.successful || !settings.hasSameResolvedState(reload.effectiveSettings)) return false
            val reloadedAtUnixMs = System.currentTimeMillis()
            val catalog = restoreOperation("compile catalog refresh") { cancellation ->
                runtime.getCompileCatalog(cancellation)
            }
            activeShaderSettings = reload.effectiveSettings
            activeShaderLoadedAtUnixMs = reloadedAtUnixMs
            activePassMappingSha256 = catalog.mappingSha256
            true
        } catch (failure: Failure) {
            throw failure
        } catch (_: Exception) {
            false
        }
    }

    private fun restorePreviousContext(previous: DeterministicRuntimeSnapshot): Boolean {
        val context = previous.context ?: return false
        return try {
            val applied = restoreOperation("scene restoration") { cancellation ->
                runtime.ensureWorldAndContext(context, cancellation)
            }
            if (!applied.successful || applied.context != context) return false
            activeContext = applied.context
            true
        } catch (failure: Failure) {
            throw failure
        } catch (_: Exception) {
            false
        }
    }

    private fun adoptPreviousShaderState(previous: DeterministicRuntimeSnapshot) {
        activeShaderSettings = previous.settings
        activeShaderLoadedAtUnixMs = previous.loadedAtUnixMs
        activePassMappingSha256 = previous.passMappingSha256
    }

    private fun restorationFailure(message: String): Failure {
        activator.markNotReady()
        return Failure(ErrorCode.ERROR_CODE_RESTORE_FAILED, message)
    }

    @Throws(Failure::class)
    private fun deterministicSnapshot(): DeterministicRuntimeSnapshot {
        val source = try {
            activator.activeSnapshot()
        } catch (failure: SourceActivator.Failure) {
            throw Failure(failure.code, failure.message)
        }
        return DeterministicRuntimeSnapshot(
            source,
            activeShaderSettings,
            activeContext,
            activeShaderLoadedAtUnixMs,
            activePassMappingSha256,
        )
    }

    @Throws(Failure::class)
    private fun rollbackDeterministicCapture(
        prepared: CaptureJobExecutor.Prepared,
        checkpoint: CaptureJobExecutor.PreparedCheckpoint,
        original: DeterministicTemporalCaptureOutcome.Failure,
    ) {
        try {
            prepared.rollback(checkpoint)
        } catch (failure: IOException) {
            val cleanup = CaptureJobExecutor.failure(failure)
            cleanup.addSuppressed(deterministicPhaseFailure(original))
            throw cleanup
        }
    }

    @Throws(Failure::class)
    fun captureAfterPass(
        job: CoreJob,
        progress: Consumer<JobStage>,
        deadline: Long,
        prepared: CaptureJobExecutor.Prepared,
        actions: List<CaptureProgramBuilder.AfterPassAction>,
    ): List<CapturePlan.AfterPassReceipt> {
        progress.accept(JobStage.JOB_STAGE_CAPTURING)
        probe.event(job.requestId, "CAPTURING_AFTER_PASS")
        val checkpoint = prepared.checkpoint()
        val stages = ArrayList<CompletionStage<CapturePlan.AfterPassReceipt>>(actions.size)
        try {
            actions.forEach { action ->
                stages.add(
                    runtime.captureAfterPass(
                        action.request,
                        prepared.sink(),
                        job.cancellation.token(),
                    ),
                )
            }
            return stages.map { stage -> awaitCapture(stage, job, deadline) }
        } catch (failure: Failure) {
            cancelAfterPassGroup(job, stages)
            rollbackCapture(prepared, checkpoint, failure)
            throw failure
        } catch (exception: RuntimeException) {
            cancelAfterPassGroup(job, stages)
            val failure = CaptureJobExecutor.failure(exception)
            rollbackCapture(prepared, checkpoint, failure)
            throw failure
        }
    }

    @Throws(Failure::class)
    fun capturePatchedShaders(
        job: CoreJob,
        progress: Consumer<JobStage>,
        deadline: Long,
        prepared: CaptureJobExecutor.Prepared,
        artifactName: String,
    ): CaptureResult {
        progress.accept(JobStage.JOB_STAGE_CAPTURING)
        probe.event(job.requestId, "CAPTURING_PATCHED_SHADERS")
        val checkpoint = prepared.checkpoint()
        try {
            return awaitCapture(
                runtime.capturePatchedShaders(artifactName, prepared.sink(), job.cancellation.token()),
                job,
                deadline,
            )
        } catch (failure: Failure) {
            try {
                prepared.rollback(checkpoint)
            } catch (rollbackFailure: IOException) {
                rollbackFailure.addSuppressed(failure)
                throw CaptureJobExecutor.failure(rollbackFailure)
            }
            throw failure
        }
    }

    @Throws(Failure::class)
    fun <T> awaitCapture(stage: CompletionStage<T>, job: CoreJob, deadline: Long): T =
        awaiter.capture(stage, job, deadline)

    private fun cancelAfterPassGroup(
        job: CoreJob,
        stages: List<CompletionStage<CapturePlan.AfterPassReceipt>>,
    ) {
        job.cancellation.cancel()
        stages.forEach { stage ->
            runCatching { stage.toCompletableFuture().handle { _, _ -> null }.join() }
        }
    }

    @Throws(Failure::class)
    private fun rollbackCapture(
        prepared: CaptureJobExecutor.Prepared,
        checkpoint: CaptureJobExecutor.PreparedCheckpoint,
        failure: Failure,
    ) {
        try {
            prepared.rollback(checkpoint)
        } catch (rollbackFailure: IOException) {
            val cleanup = CaptureJobExecutor.failure(rollbackFailure)
            cleanup.addSuppressed(failure)
            throw cleanup
        }
    }

    private fun reloadPreviousSource(): Boolean {
        try {
            val result = restoreOperation("shader reload") { cancellation ->
                runtime.reloadVibrisShaderpack(null, cancellation)
            }
            if (result.successful) activeShaderSettings = result.effectiveSettings
            return result.successful
        } catch (failure: Failure) {
            throw failure
        } catch (_: Exception) {
            return false
        }
    }

    @Synchronized
    fun hasPendingRecovery(): Boolean = pendingRecovery != null

    @Synchronized
    fun hasFailedRecovery(): Boolean = pendingRecovery?.attemptCount?.let { it > 0 } == true

    @Synchronized
    fun recoveryStatus(): RuntimeRecovery? = pendingRecovery?.let { recovery ->
        RuntimeRecovery.newBuilder()
            .setJobId(recovery.jobId)
            .setRequestId(recovery.requestId)
            .setWorkspaceId(recovery.workspaceId)
            .setStartedAtUnixMs(recovery.startedAtUnixMs)
            .setAttemptCount(recovery.attemptCount)
            .apply {
                if (recovery.lastReceipt.hasError()) setLastError(recovery.lastReceipt.error)
            }
            .build()
    }

    @Synchronized
    fun restorationReceipt(): RestorationReceipt {
        pendingRecovery?.let { return it.lastReceipt }
        val actual = runCatching(::currentSnapshot).getOrElse {
            BenchmarkCaseIsolation.Snapshot(null, activeShaderSettings, activeContext)
        }
        return BenchmarkCaseIsolation.noMutationReceipt(actual)
    }

    private fun terminalize(
        job: CoreJob,
        isolation: BenchmarkCaseIsolation,
        successful: Boolean,
        progress: Consumer<JobStage>,
    ): RestorationReceipt {
        if (!isolation.shouldRestore(successful)) {
            if (successful) {
                try {
                    val source = activator.verifyActiveSource()
                    return isolation.currentReceipt(currentSnapshot(source))
                } catch (failure: SourceActivator.Failure) {
                    throw Failure(failure.code, failure.message)
                }
            }
            val actual = runCatching(::currentSnapshot).getOrElse {
                BenchmarkCaseIsolation.Snapshot(null, activeShaderSettings, activeContext)
            }
            return isolation.currentReceipt(actual)
        }
        progress.accept(JobStage.JOB_STAGE_RESTORING)
        probe.event(job.requestId, "RESTORING_RUNTIME_STATE")
        return try {
            val actual = restore(isolation.snapshot)
            isolation.successReceipt(actual, true)
        } catch (failure: Exception) {
            activator.markNotReady()
            val message = failure.message ?: "The last safe runtime snapshot could not be restored."
            val actual = runCatching(::currentSnapshot).getOrElse {
                BenchmarkCaseIsolation.Snapshot(null, activeShaderSettings, activeContext)
            }
            val receipt = isolation.failureReceipt(
                actual,
                ErrorCode.ERROR_CODE_RESTORE_FAILED,
                message,
                false,
            )
            pendingRecovery = PendingRecovery(
                isolation = isolation,
                heldSources = job.sources.toList(),
                jobId = job.submission.jobId,
                requestId = job.requestId,
                workspaceId = job.workspaceId,
                startedAtUnixMs = System.currentTimeMillis(),
                lastReceipt = receipt,
                cleanupBarrier = (failure as? Failure)?.cleanupBarrier,
            )
            throw Failure(
                ErrorCode.ERROR_CODE_RESTORE_FAILED,
                "$message ${BenchmarkCaseIsolation.MANUAL_RECOVERY}",
                restoration = receipt,
                holdOwnership = true,
            )
        }
    }

    private fun retainUnsafeRuntime(
        job: CoreJob,
        isolation: BenchmarkCaseIsolation,
        failure: Failure,
    ): Failure {
        activator.markNotReady()
        val message = failure.message ?: "The deterministic runtime sequence could not be closed."
        val actual = runCatching(::currentSnapshot).getOrElse {
            BenchmarkCaseIsolation.Snapshot(null, activeShaderSettings, activeContext)
        }
        val receipt = isolation.failureReceipt(
            actual,
            ErrorCode.ERROR_CODE_RESTORE_FAILED,
            message,
            false,
        )
        pendingRecovery = PendingRecovery(
            isolation = isolation,
            heldSources = job.sources.toList(),
            jobId = job.submission.jobId,
            requestId = job.requestId,
            workspaceId = job.workspaceId,
            startedAtUnixMs = System.currentTimeMillis(),
            lastReceipt = receipt,
            cleanupBarrier = failure.cleanupBarrier,
        )
        return failure.requiringRecovery(
            receipt,
            "$message ${BenchmarkCaseIsolation.MANUAL_RECOVERY}",
        )
    }

    private fun executeRecovery(
        job: CoreJob,
        progress: Consumer<JobStage>,
        startedAtUnixMs: Long,
        startedNanos: Long,
    ): TerminalResult {
        progress.accept(JobStage.JOB_STAGE_RECOVERING)
        probe.event(job.requestId, "RECOVERING_RUNTIME_STATE")
        val recovery = pendingRecovery
        if (recovery == null) {
            try {
                val source = activator.markReadyAfterVerification()
                return completed(
                    job,
                    JobResult.newBuilder()
                        .setRestoration(BenchmarkCaseIsolation.noMutationReceipt(currentSnapshot(source)))
                        .build(),
                    startedAtUnixMs,
                    startedNanos,
                )
            } catch (failure: Exception) {
                val message = failure.message ?: "The runtime link could not be revalidated."
                val actual = runCatching(::currentSnapshot).getOrElse {
                    BenchmarkCaseIsolation.Snapshot(null, activeShaderSettings, activeContext)
                }
                throw Failure(
                    ErrorCode.ERROR_CODE_RECOVERY_FAILED,
                    "$message ${BenchmarkCaseIsolation.MANUAL_RECOVERY}",
                    restoration = BenchmarkCaseIsolation.recoveryFailureReceipt(actual, message),
                )
            }
        }
        recovery.attemptCount++
        try {
            recovery.cleanupBarrier?.let { barrier ->
                restoreAwait(barrier, operation = "the previous restoration operation")
                recovery.cleanupBarrier = null
            }
            val actual = restore(recovery.isolation.snapshot, markReady = true)
            val receipt = recovery.isolation.successReceipt(actual, true)
            recovery.isolation.release(activator)
            activator.release(recovery.heldSources)
            pendingRecovery = null
            return completed(
                job,
                JobResult.newBuilder().setRestoration(receipt).build(),
                startedAtUnixMs,
                startedNanos,
            )
        } catch (failure: Exception) {
            activator.markNotReady()
            if (failure is Failure && failure.cleanupBarrier != null) {
                recovery.cleanupBarrier = failure.cleanupBarrier
            }
            val message = failure.message ?: "Runtime recovery could not verify the last safe snapshot."
            val actual = runCatching(::currentSnapshot).getOrElse {
                BenchmarkCaseIsolation.Snapshot(null, activeShaderSettings, activeContext)
            }
            val receipt = recovery.isolation.failureReceipt(
                actual,
                ErrorCode.ERROR_CODE_RECOVERY_FAILED,
                message,
                false,
            )
            recovery.lastReceipt = receipt
            throw Failure(
                ErrorCode.ERROR_CODE_RECOVERY_FAILED,
                "$message ${BenchmarkCaseIsolation.MANUAL_RECOVERY}",
                restoration = receipt,
                holdOwnership = true,
            )
        }
    }

    @Throws(Exception::class)
    private fun restore(
        expected: BenchmarkCaseIsolation.Snapshot,
        markReady: Boolean = false,
    ): BenchmarkCaseIsolation.Snapshot {
        val currentSource = activator.activeSnapshot()
        val currentSettings = activeShaderSettings
        val canReusePipeline = activator.ready() && expected.source != null && expected.shaderSettings != null &&
            currentSource?.snapshotSha256 == expected.source.snapshotSha256 &&
            currentSettings?.let(expected.shaderSettings::hasSameResolvedState) == true
        activator.restore(expected.source)
        if (expected.source != null) {
            if (canReusePipeline) {
                activeShaderSettings = expected.shaderSettings
            } else {
                val reload = restoreOperation("shader reload") { cancellation ->
                    runtime.reloadVibrisShaderpack(expected.shaderSettings!!.values(), cancellation)
                }
                check(reload.successful) { "The safe shader source or settings could not be reloaded." }
                activeShaderSettings = reload.effectiveSettings
            }
        } else {
            activeShaderSettings = null
        }
        expected.scene?.let { scene ->
            val context = restoreOperation("scene restoration") { cancellation ->
                runtime.ensureWorldAndContext(scene, cancellation)
            }
            check(context.successful && context.context == scene) {
                "The safe scene context could not be restored exactly."
            }
            activeContext = context.context
        }
        val reset = restoreOperation("temporal reset") { cancellation ->
            runtime.resetTemporalState(cancellation)
        }
        check(reset.successful) { "Temporal state could not be reset after restoration." }
        val source = if (markReady) {
            activator.markReadyAfterVerification()
        } else {
            activator.verifyActiveSource()
        }
        val actual = currentSnapshot(source)
        check(expected.source?.uuid == actual.source?.uuid) { "Restored source UUID does not match the safe snapshot." }
        check(expected.source?.snapshotSha256 == actual.source?.snapshotSha256) {
            "Restored source content does not match the safe snapshot."
        }
        val actualSettings = actual.shaderSettings
        check(expected.shaderSettings?.let { actualSettings != null && it.hasSameResolvedState(actualSettings) }
            ?: (actualSettings == null)) {
            "Restored shader settings do not match the safe snapshot."
        }
        check(expected.scene == actual.scene) { "Restored scene does not match the safe snapshot." }
        return actual
    }

    private fun currentSnapshot(
        source: SourceRegistry.Lease? = activator.activeSnapshot(),
    ): BenchmarkCaseIsolation.Snapshot = BenchmarkCaseIsolation.Snapshot(
        source,
        activeShaderSettings,
        activeContext,
    )

    @Throws(Exception::class)
    private fun <T> restoreOperation(
        operation: String,
        start: (CancellationToken) -> CompletionStage<T>,
    ): T {
        val cancellation = CancellationToken.source()
        return restoreAwait(start(cancellation.token()), cancellation, operation)
    }

    @Throws(Failure::class)
    private fun <T> restoreAwait(
        stage: CompletionStage<T>,
        cancellation: CancellationToken.Source? = null,
        operation: String = "runtime restoration",
    ): T {
        val timeoutNanos = restorationTimeout.toNanos()
        val deadline = System.nanoTime() + timeoutNanos
        var interrupted = false
        try {
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    throw restorationTimeout(stage, cancellation, operation, null)
                }
                try {
                    return stage.toCompletableFuture().get(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (failure: TimeoutException) {
                    throw restorationTimeout(stage, cancellation, operation, failure)
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    @Throws(Failure::class)
    fun <T> await(stage: CompletionStage<T>, job: CoreJob, deadline: Long): T = awaiter.await(stage, job, deadline)

    @Throws(Failure::class)
    fun beginDeterministicSequence(job: CoreJob, deadline: Long) {
        await(runtime.beginDeterministicSequence(job.cancellation.token()), job, deadline)
    }

    @Throws(Failure::class)
    fun endDeterministicSequence() {
        try {
            restoreOperation("deterministic sequence cleanup") { cancellation ->
                runtime.endDeterministicSequence(cancellation)
            }
        } catch (failure: Failure) {
            throw failure
        } catch (failure: Exception) {
            val barrier = CompletableFuture.failedFuture<Void>(failure)
            throw Failure(
                ErrorCode.ERROR_CODE_RESTORE_FAILED,
                failure.cause?.message ?: failure.message ?: "The deterministic runtime sequence could not be closed.",
                barrier,
            )
        }
    }

    private fun restorationTimeout(
        stage: CompletionStage<*>,
        cancellation: CancellationToken.Source?,
        operation: String,
        cause: TimeoutException?,
    ): Failure {
        cancellation?.cancel()
        val timeout = if (restorationTimeout.nano == 0) {
            "${restorationTimeout.seconds} seconds"
        } else {
            "${restorationTimeout.toMillis()} milliseconds"
        }
        val detail = if (cancellation == null) {
            "$operation did not reach a safe point within $timeout. " +
                "Recovery remains blocked on that in-flight operation."
        } else {
            "$operation did not reach a safe point within $timeout. " +
                "Cancellation was requested; recovery will wait for that in-flight operation to stop."
        }
        return Failure(
            ErrorCode.ERROR_CODE_RESTORE_FAILED,
            detail,
            stage.handle<Void> { _, _ -> null },
        ).also { failure -> if (cause != null) failure.initCause(cause) }
    }

    data class LoadResult(
        val reload: ReloadResult,
        val context: ContextApplyResult,
    )

    data class CompileResult(
        val reload: ReloadResult,
        val catalog: CompileCatalog,
        val loadedAtUnixMs: Long,
    )

    class Failure internal constructor(
        @JvmField val code: ErrorCode,
        message: String?,
        @JvmField val artifacts: List<ArtifactMetadata>,
        @JvmField val diagnostics: List<ReloadResult.Diagnostic>,
        @JvmField val restoration: RestorationReceipt?,
        @JvmField val holdOwnership: Boolean,
        @JvmField val actionReceipts: List<ActionReceipt>,
        @JvmField val preludeReceipts: List<ActionReceipt>,
        internal val cleanupBarrier: CompletionStage<Void>? = null,
    ) : Exception(message) {
        constructor(code: ErrorCode, message: String?) :
            this(
                code,
                message,
                java.util.List.of(),
                java.util.List.of(),
                null,
                false,
                java.util.List.of(),
                java.util.List.of(),
            )

        constructor(code: ErrorCode, message: String?, artifact: ArtifactMetadata) :
            this(
                code,
                message,
                java.util.List.of(artifact),
                java.util.List.of(),
                null,
                false,
                java.util.List.of(),
                java.util.List.of(),
            )

        internal constructor(
            code: ErrorCode,
            message: String?,
            restoration: RestorationReceipt?,
            holdOwnership: Boolean = false,
        ) : this(
            code,
            message,
            java.util.List.of(),
            java.util.List.of(),
            restoration,
            holdOwnership,
            java.util.List.of(),
            java.util.List.of(),
        )

        internal constructor(
            code: ErrorCode,
            message: String?,
            cleanupBarrier: CompletionStage<Void>,
        ) : this(
            code,
            message,
            java.util.List.of(),
            java.util.List.of(),
            null,
            false,
            java.util.List.of(),
            java.util.List.of(),
            cleanupBarrier,
        )

        fun withRestoration(value: RestorationReceipt): Failure = Failure(
            code,
            message,
            artifacts,
            diagnostics,
            value,
            holdOwnership,
            actionReceipts,
            preludeReceipts,
            cleanupBarrier,
        ).also { replacement -> suppressed.forEach(replacement::addSuppressed) }

        fun withActionReceipts(actions: List<ActionReceipt>, preludes: List<ActionReceipt>): Failure = Failure(
            code,
            message,
            artifacts,
            diagnostics,
            restoration,
            holdOwnership,
            java.util.List.copyOf(actions),
            java.util.List.copyOf(preludes),
            cleanupBarrier,
        ).also { replacement -> suppressed.forEach(replacement::addSuppressed) }

        fun requiringRecovery(value: RestorationReceipt, detail: String): Failure = Failure(
            ErrorCode.ERROR_CODE_RESTORE_FAILED,
            detail,
            artifacts,
            diagnostics,
            value,
            true,
            actionReceipts,
            preludeReceipts,
            cleanupBarrier,
        ).also { replacement -> suppressed.forEach(replacement::addSuppressed) }
    }

    enum class DeterministicFailurePhase {
        LOAD,
        RESET,
        WAIT,
        CAPTURE,
    }

    class DeterministicPhaseFailure(
        val phase: DeterministicFailurePhase,
        val failure: Failure,
    ) : Exception(failure.message, failure)

    private data class PendingRecovery(
        val isolation: BenchmarkCaseIsolation,
        val heldSources: List<SourceRegistry.Lease>,
        val jobId: String,
        val requestId: String,
        val workspaceId: String,
        val startedAtUnixMs: Long,
        @Volatile var attemptCount: Int = 0,
        @Volatile var lastReceipt: RestorationReceipt,
        @Volatile var cleanupBarrier: CompletionStage<Void>? = null,
    )

    private data class DeterministicRuntimeSnapshot(
        val source: SourceRegistry.Lease?,
        val settings: EffectiveShaderSettings?,
        val context: SceneContext?,
        val loadedAtUnixMs: Long,
        val passMappingSha256: String,
    )

    private companion object {
        val DEFAULT_RESTORATION_TIMEOUT: Duration = Duration.ofSeconds(45)
    }

}
