package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.DeterministicTemporalCaptureOutcome
import dev.vibris.api.DeterministicTemporalCapturePlanner
import dev.vibris.api.DeterministicTemporalCapturePlanning
import dev.vibris.api.DeterministicTemporalCaptureReloaded
import dev.vibris.api.ReloadResult
import dev.vibris.api.ResourceCatalog
import dev.vibris.api.RuntimeAction
import dev.vibris.api.SceneContext
import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v2.EmptyReceipt
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobResult
import dev.vibris.protocol.v2.JobStage
import dev.vibris.protocol.v2.PatchedShadersReceipt
import dev.vibris.protocol.v2.ResetTemporalReceipt
import dev.vibris.protocol.v2.RuntimeMutationReceipt
import dev.vibris.protocol.v2.ShaderInspectionReceipt
import dev.vibris.protocol.v2.WaitFramesReceipt
import java.io.IOException
import java.nio.file.Path
import java.util.LinkedHashSet
import java.util.function.Consumer

internal class ActionJobExecutor(
    private val runtime: VibrisRuntimeAdapter,
    private val probe: CoreProbe,
    private val captures: CaptureJobExecutor,
    private val owner: RuntimeJobExecutor,
    private val nsight: NsightGpuTraceRunner,
) {
    @Throws(RuntimeJobExecutor.Failure::class)
    fun execute(job: CoreJob, progress: Consumer<JobStage>, deadline: Long): JobResult {
        val receiptBook = ActionReceiptBook(job.submission.actionSequence.actionsList)
        val pendingCaptureIndices = LinkedHashSet<Int>()
        var activeIndices: List<Int> = emptyList()
        try {
            val diagnostics = ArrayList<ReloadResult.Diagnostic>()
            val motions = ArrayList<Pair<Int, Triple<dev.vibris.api.CameraMotionPlan,
                dev.vibris.api.CameraMotionResult, List<CapturePlan>>>>()
            val action = captures.prepareActions(job, runtime.getResourceCatalog(), diagnostics)
            val prepared = action.prepared
            val captured = ArrayList<CaptureResult>()
            val completedCapturePlans = ArrayList<CapturePlan>()
            val captureExecutions = ArrayList<CaptureExecution>()
            val afterPassExecutions = ArrayList<AfterPassExecution>()
            val patchedExecutions = ArrayList<PatchedExecution>()
            val nsightExecutions = ArrayList<NsightExecution>()
            val accumulationExecutions = ArrayList<Triple<Int, CapturePlan, dev.vibris.api.TextureAnalysisResult>>()

            fun resolveDeferredCapture(
                step: CaptureProgramBuilder.ActionStep,
            ): CaptureProgramBuilder.ResolvedCaptureGroup {
                if (prepared == null) throw captureUnavailable()
                return try {
                    action.program.planningSession.resolveDeferredCapture(
                        step,
                        runtime.getResourceCatalog(),
                        prepared::addPlan,
                    )
                } catch (failure: RuntimeJobExecutor.Failure) {
                    throw failure
                } catch (failure: Exception) {
                    throw CaptureJobExecutor.failure(failure)
                }
            }

            fun resolveDeferredAfterPass(
                step: CaptureProgramBuilder.ActionStep,
            ): CaptureProgramBuilder.ResolvedAfterPassGroup {
                if (prepared == null) throw captureUnavailable()
                return try {
                    action.program.planningSession.resolveDeferredAfterPass(
                        step,
                        runtime.getResourceCatalog(),
                        prepared::addPlan,
                    )
                } catch (failure: RuntimeJobExecutor.Failure) {
                    throw failure
                } catch (failure: Exception) {
                    throw CaptureJobExecutor.failure(failure)
                }
            }

            fun executeCaptureGroup(
                plan: CapturePlan,
                actions: List<CaptureProgramBuilder.CaptureAction>,
            ) {
                if (prepared == null) throw captureUnavailable()
                val waits = HashMap<Int, WaitFramesReceipt>()
                actions.forEach { capture ->
                    if (capture.beforeFrames > 0) {
                        activeIndices = listOf(capture.actionIndex)
                        val endFrame = owner.waitFrames(job, progress, deadline, capture.beforeFrames)
                        waits[capture.actionIndex] = waitReceipt(capture.beforeFrames, endFrame)
                    }
                    val placeholder = dev.vibris.protocol.v2.CaptureReceipt.newBuilder()
                    waits[capture.actionIndex]?.let(placeholder::setInternalWait)
                    receiptBook.put(
                        capture.actionIndex,
                        receiptBook.success(capture.actionIndex).setCapture(placeholder).build(),
                    )
                }
                activeIndices = actions.map(CaptureProgramBuilder.CaptureAction::actionIndex)
                val result = owner.capture(job, progress, deadline, prepared, plan)
                captured.add(result)
                completedCapturePlans.add(plan)
                actions.forEach { capture ->
                    receiptBook.replace(
                        capture.actionIndex,
                        receiptBook.success(capture.actionIndex)
                            .setCapture(
                                captures.captureReceipt(
                                    plan,
                                    result,
                                    capture,
                                    JobResult.getDefaultInstance(),
                                    waits[capture.actionIndex],
                                ),
                            )
                            .build(),
                    )
                    pendingCaptureIndices.add(capture.actionIndex)
                }
                captureExecutions.add(CaptureExecution(plan, result, actions, waits))
            }

            fun executeAfterPassGroup(actions: List<CaptureProgramBuilder.AfterPassAction>) {
                if (prepared == null) throw captureUnavailable()
                val receipts = owner.captureAfterPass(job, progress, deadline, prepared, actions)
                check(receipts.size == actions.size) {
                    "Runtime after-pass receipt count did not match its requests."
                }
                receipts.zip(actions).forEach { (receipt, capture) ->
                    val plan = CapturePlan(listOf(capture.request.target))
                    captured.add(receipt.capture)
                    completedCapturePlans.add(plan)
                    receiptBook.put(
                        capture.actionIndex,
                        receiptBook.success(capture.actionIndex)
                            .setCapture(captures.afterPassReceipt(receipt, JobResult.getDefaultInstance()))
                            .build(),
                    )
                    pendingCaptureIndices.add(capture.actionIndex)
                    afterPassExecutions.add(AfterPassExecution(capture.actionIndex, receipt))
                }
            }

            fun executeDeterministic(step: CaptureProgramBuilder.ActionStep) {
                if (prepared == null) throw captureUnavailable()
                val block = checkNotNull(step.deterministic) { "A deterministic action step must contain its block." }
                activeIndices = listOf(block.loadActionIndex)
                val source = source(
                    job,
                    block.loadShader.sourceUuid,
                    "Load action references an unprepared source.",
                )
                var resolved: CaptureProgramBuilder.ResolvedCaptureGroup? = null
                val planner = DeterministicTemporalCapturePlanner { resourceCatalog, _ ->
                    try {
                        captures.resolveDeferred(prepared, action.program, step, resourceCatalog).let { group ->
                            resolved = group
                            DeterministicTemporalCapturePlanning.Planned(group.capture)
                        }
                    } catch (failure: RuntimeJobExecutor.Failure) {
                        DeterministicTemporalCapturePlanning.Rejected(owner.deterministicPlanningFailure(failure))
                    }
                }
                val outcome = try {
                    owner.captureDeterministicTemporalPhase(
                        job,
                        source,
                        block.loadShader.config,
                        planner,
                        progress,
                        deadline,
                        prepared,
                        block.warmupFrames,
                    )
                } catch (phaseFailure: RuntimeJobExecutor.DeterministicPhaseFailure) {
                    activeIndices = when (phaseFailure.phase) {
                        RuntimeJobExecutor.DeterministicFailurePhase.LOAD -> listOf(block.loadActionIndex)
                        RuntimeJobExecutor.DeterministicFailurePhase.RESET -> listOf(block.resetActionIndex)
                        RuntimeJobExecutor.DeterministicFailurePhase.WAIT ->
                            listOf(checkNotNull(block.waitActionIndex))
                        RuntimeJobExecutor.DeterministicFailurePhase.CAPTURE ->
                            block.captures.map(CaptureProgramBuilder.DirectCapture::actionIndex)
                    }
                    throw phaseFailure.failure
                }
                when (outcome) {
                    is DeterministicTemporalCaptureOutcome.ContextRejected -> {
                        activeIndices = listOf(block.loadActionIndex)
                        throw deterministicContextFailure(outcome)
                    }
                    is DeterministicTemporalCaptureOutcome.ReloadRejected -> {
                        diagnostics.addAll(outcome.reload.diagnostics)
                        prepared.addDiagnostics(outcome.reload.diagnostics)
                        activeIndices = listOf(block.loadActionIndex)
                        throw deterministicReloadFailure(job, outcome)
                    }
                    is DeterministicTemporalCaptureOutcome.PlanningRejected -> {
                        activeIndices = listOf(block.captures.first().actionIndex)
                        publishDeterministicDiagnostics(diagnostics, prepared, outcome.reloaded)
                        publishDeterministicLoad(receiptBook, block, source, outcome.reloaded)
                        throw owner.deterministicPhaseFailure(outcome.failure)
                    }
                    is DeterministicTemporalCaptureOutcome.ResetRejected -> {
                        activeIndices = listOf(block.resetActionIndex)
                        verifyDeterministicPlan(resolved, outcome.plan)
                        publishDeterministicDiagnostics(diagnostics, prepared, outcome.reloaded)
                        publishDeterministicLoad(receiptBook, block, source, outcome.reloaded)
                        throw owner.deterministicPhaseFailure(outcome.failure)
                    }
                    is DeterministicTemporalCaptureOutcome.WarmupRejected -> {
                        activeIndices = listOf(checkNotNull(block.waitActionIndex))
                        verifyDeterministicPlan(resolved, outcome.plan)
                        publishDeterministicDiagnostics(diagnostics, prepared, outcome.reloaded)
                        publishDeterministicLoadAndReset(
                            receiptBook,
                            block,
                            source,
                            outcome.reloaded,
                            outcome.resetCompletedAtUnixMs,
                        )
                        val waitActionIndex = checkNotNull(block.waitActionIndex)
                        receiptBook.put(
                            waitActionIndex,
                            receiptBook.success(waitActionIndex)
                                .setWaitFrames(
                                    partialWaitReceipt(
                                        block.warmupFrames,
                                        outcome.anchorFrame,
                                        outcome.currentFrame,
                                        outcome.completedFrames,
                                    ),
                                )
                                .build(),
                        )
                        activeIndices = listOf(waitActionIndex)
                        throw owner.deterministicPhaseFailure(outcome.failure)
                    }
                    is DeterministicTemporalCaptureOutcome.CaptureRejected -> {
                        activeIndices = block.captures.map(CaptureProgramBuilder.DirectCapture::actionIndex)
                        val captureGroup = verifyDeterministicPlan(resolved, outcome.plan)
                        publishDeterministicDiagnostics(diagnostics, prepared, outcome.reloaded)
                        publishDeterministicLoadAndReset(
                            receiptBook,
                            block,
                            source,
                            outcome.reloaded,
                            outcome.resetCompletedAtUnixMs,
                        )
                        publishDeterministicWait(
                            receiptBook,
                            block,
                            outcome.anchorFrame,
                            outcome.warmupEndFrame,
                        )
                        publishDeterministicCapturePlaceholders(
                            receiptBook,
                            captureGroup,
                            outcome.terminalFrame,
                            outcome.reloaded.resourceCatalog,
                        )
                        activeIndices = captureGroup.captureActions
                            .map(CaptureProgramBuilder.CaptureAction::actionIndex)
                        throw owner.deterministicPhaseFailure(outcome.failure)
                    }
                    is DeterministicTemporalCaptureOutcome.Captured -> {
                        activeIndices = block.captures.map(CaptureProgramBuilder.DirectCapture::actionIndex)
                        val captureGroup = verifyDeterministicPlan(resolved, outcome.plan)
                        publishDeterministicDiagnostics(diagnostics, prepared, outcome.reloaded)
                        publishDeterministicLoadAndReset(
                            receiptBook,
                            block,
                            source,
                            outcome.reloaded,
                            outcome.resetCompletedAtUnixMs,
                        )
                        publishDeterministicWait(
                            receiptBook,
                            block,
                            outcome.anchorFrame,
                            outcome.warmupEndFrame,
                        )
                        publishDeterministicCapturedPlaceholders(
                            receiptBook,
                            captureGroup,
                            outcome,
                        )
                        captureGroup.captureActions.forEach { capture ->
                            pendingCaptureIndices.add(capture.actionIndex)
                        }
                        captured.add(outcome.capture)
                        completedCapturePlans.add(outcome.plan)
                        captureExecutions.add(
                            CaptureExecution(
                                outcome.plan,
                                outcome.capture,
                                captureGroup.captureActions,
                                emptyMap(),
                            ),
                        )
                        activeIndices = emptyList()
                    }
                }
            }

            fun executeSteps() {
                action.program.steps.forEach { step ->
                    activeIndices = step.actionIndices()
                    val stepStartedNanos = System.nanoTime()
                    try {
                        when (step.type) {
                            CaptureProgramBuilder.ActionType.DETERMINISTIC -> executeDeterministic(step)
                            CaptureProgramBuilder.ActionType.LOAD -> {
                                val load = step.loadShader!!
                                val execution = load(job, load, progress, deadline)
                                diagnostics.addAll(execution.reload.diagnostics)
                                prepared?.addDiagnostics(execution.reload.diagnostics)
                                owner.observeCatalog(
                                    owner.await(runtime.getCompileCatalog(job.cancellation.token()), job, deadline),
                                )
                                receiptBook.put(
                                    step.actionIndex,
                                    receiptBook.success(step.actionIndex)
                                        .setRuntimeMutation(mutation(execution))
                                        .build(),
                                )
                            }
                            CaptureProgramBuilder.ActionType.ACTIVATE -> {
                                val execution = activate(job, step.sourceUuid!!, progress, deadline)
                                diagnostics.addAll(execution.reload.diagnostics)
                                prepared?.addDiagnostics(execution.reload.diagnostics)
                                receiptBook.put(
                                    step.actionIndex,
                                    receiptBook.success(step.actionIndex)
                                        .setRuntimeMutation(mutation(execution))
                                        .build(),
                                )
                            }
                            CaptureProgramBuilder.ActionType.CAMERA_MOTION -> {
                                if (prepared == null) throw captureUnavailable()
                                if (!runtime.supportsCameraMotion()) {
                                    throw RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_INVALID_REQUEST, "Runtime does not support camera motion.")
                                }
                                val request = CameraMotionProtocol.toApi(step.runtimeAction!!.cameraMotion)
                                if (request.hasTranslation() && !runtime.supportsCameraTranslation()) {
                                    throw RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_INVALID_REQUEST,
                                        "Runtime does not support camera XYZ translation.")
                                }
                                val planned = CameraMotionProtocol.plans(request, runtime.getResourceCatalog())
                                planned.forEach { prepared.addPlan(it.capture, it.estimatedBytes) }
                                val plans = planned.map { it.capture }
                                val motion = owner.await(runtime.cameraMotion(request, plans, prepared.transaction,
                                    job.cancellation.token()), job, deadline)
                                completedCapturePlans.addAll(plans)
                                captured.addAll(motion.captures)
                                receiptBook.put(step.actionIndex, receiptBook.success(step.actionIndex)
                                    .setCameraMotion(CameraMotionProtocol.receipt(request, motion, plans,
                                        JobResult.getDefaultInstance(), captures)).build())
                                pendingCaptureIndices.add(step.actionIndex)
                                motions.add(step.actionIndex to Triple(request, motion, plans))
                            }
                            CaptureProgramBuilder.ActionType.RESET -> {
                                owner.reset(job, progress, deadline)
                                receiptBook.put(
                                    step.actionIndex,
                                    receiptBook.success(step.actionIndex)
                                        .setResetTemporal(
                                            ResetTemporalReceipt.newBuilder()
                                                .setCompletedAtUnixMs(System.currentTimeMillis()),
                                        )
                                        .build(),
                                )
                            }
                            CaptureProgramBuilder.ActionType.WAIT -> {
                                val endFrame = owner.waitFrames(job, progress, deadline, step.frames)
                                receiptBook.put(
                                    step.actionIndex,
                                    receiptBook.success(step.actionIndex)
                                        .setWaitFrames(waitReceipt(step.frames, endFrame))
                                        .build(),
                                )
                            }
                            CaptureProgramBuilder.ActionType.CAPTURE -> {
                                executeCaptureGroup(step.capture!!, step.captureActions)
                            }
                            CaptureProgramBuilder.ActionType.DEFERRED_CAPTURE -> {
                                val resolved = resolveDeferredCapture(step)
                                executeCaptureGroup(resolved.capture, resolved.captureActions)
                            }
                            CaptureProgramBuilder.ActionType.AFTER_PASS -> {
                                executeAfterPassGroup(step.afterPassActions)
                            }
                            CaptureProgramBuilder.ActionType.DEFERRED_AFTER_PASS -> {
                                executeAfterPassGroup(resolveDeferredAfterPass(step).afterPassActions)
                            }
                            CaptureProgramBuilder.ActionType.PATCHED_SHADERS -> {
                                if (prepared == null) throw captureUnavailable()
                                receiptBook.put(
                                    step.actionIndex,
                                    receiptBook.success(step.actionIndex)
                                        .setPatchedShaders(PatchedShadersReceipt.getDefaultInstance())
                                        .build(),
                                )
                                val placeholder = step.capture!!
                                val result = owner.capturePatchedShaders(
                                    job,
                                    progress,
                                    deadline,
                                    prepared,
                                    placeholder.targets.single().artifactName,
                                )
                                val realized = CapturePlanBuilder.realizePatchedShaders(placeholder, result)
                                captured.add(result)
                                completedCapturePlans.add(realized)
                                pendingCaptureIndices.add(step.actionIndex)
                                patchedExecutions.add(PatchedExecution(step.actionIndex, realized, result))
                            }
                            CaptureProgramBuilder.ActionType.INSPECT -> {
                                val catalog = owner.await(
                                    runtime.getCompileCatalog(job.cancellation.token()),
                                    job,
                                    deadline,
                                )
                                owner.observeCatalog(catalog)
                                receiptBook.put(
                                    step.actionIndex,
                                    receiptBook.success(step.actionIndex)
                                        .setShaderInspection(
                                            ShaderInspectionReceipt.newBuilder()
                                                .setCatalog(CompileCatalogProtocol.toProtocol(catalog)),
                                        )
                                        .build(),
                                )
                            }
                            CaptureProgramBuilder.ActionType.NSIGHT -> {
                                if (prepared == null) throw captureUnavailable()
                                val execution = nsight.execute(
                                    step.runtimeAction!!.nsightGpuTrace,
                                    job,
                                    progress,
                                    deadline,
                                    runtime,
                                    owner,
                                    prepared,
                                )
                                nsightExecutions.add(
                                    NsightExecution(step.actionIndex, execution.receipt, execution.artifacts),
                                )
                                pendingCaptureIndices.add(step.actionIndex)
                                receiptBook.put(
                                    step.actionIndex,
                                    receiptBook.success(step.actionIndex)
                                        .setNsightGpuTrace(execution.receipt)
                                        .build(),
                                )
                            }
                            CaptureProgramBuilder.ActionType.RUNTIME -> {
                                progress.accept(JobStage.JOB_STAGE_SAMPLING)
                                if (step.runtimeAction!!.hasAccumulateTexture()) {
                                    if (prepared == null) throw captureUnavailable()
                                    val request = TextureAnalysisProtocol.request(step.runtimeAction, runtime.getResourceCatalog()).copy(
                                        timing = dev.vibris.api.OperationTiming(job::recordMeasuredSpan))
                                    val plan = request.outputPlan()
                                    val descriptor = runtime.getResourceCatalog().resources.single {
                                        it.logicalName == request.resource.logicalName }
                                    val pixels = (request.resource.region?.width ?: maxOf(1, descriptor.width shr request.resource.mipLevel)).toLong() *
                                        (request.resource.region?.height ?: maxOf(1, descriptor.height shr request.resource.mipLevel))
                                    val accumulation = requireNotNull(request.accumulation)
                                    val fields = accumulation.outputs.size + 3L
                                    val bytes = pixels * 4 * fields * (descriptor.channelCount +
                                        if (accumulation.lumaWeights.isEmpty()) 0 else 1) + plan.targets.single().outputs.size * 65536L
                                    prepared.addPlan(plan, bytes)
                                    val result = owner.await(runtime.analyzeTexture(request, prepared.sink(), job.cancellation.token()), job, deadline)
                                    captured.add(requireNotNull(result.capture)); completedCapturePlans.add(plan)
                                    accumulationExecutions.add(Triple(step.actionIndex, plan, result))
                                    pendingCaptureIndices.add(step.actionIndex)
                                    receiptBook.put(step.actionIndex, receiptBook.success(step.actionIndex)
                                        .setAccumulation(dev.vibris.protocol.v2.AccumulationReceipt.newBuilder()
                                            .setSummaryJson(result.summaryJson)).build())
                                    return@forEach
                                }
                                if (step.runtimeAction!!.hasTextureStats()) {
                                    val request = TextureAnalysisProtocol.request(step.runtimeAction, runtime.getResourceCatalog()).copy(
                                        timing = dev.vibris.api.OperationTiming(job::recordMeasuredSpan))
                                    val result = owner.await(runtime.analyzeTexture(request, null, job.cancellation.token()), job, deadline)
                                    receiptBook.put(step.actionIndex, receiptBook.success(step.actionIndex)
                                        .setTextureStats(TextureAnalysisProtocol.stats(result)).build())
                                    return@forEach
                                }
                                val response = owner.await(
                                    runtime.executeAction(RuntimeActionProtocol.toApi(step.runtimeAction!!)),
                                    job,
                                    deadline,
                                )
                                val receipt = receiptBook.success(step.actionIndex)
                                if (step.runtimeAction.hasGetGpuMetrics()) {
                                    try {
                                        receipt.setGpuMetrics(
                                            RuntimeActionProtocol.gpuMetricsReceipt(step.runtimeAction, response),
                                        )
                                    } catch (exception: IllegalArgumentException) {
                                        throw RuntimeJobExecutor.Failure(
                                            ErrorCode.ERROR_CODE_NO_GPU_SAMPLES,
                                            exception.message ?: "GPU timing response is invalid.",
                                        )
                                    }
                                } else {
                                    receipt.setEmpty(EmptyReceipt.getDefaultInstance())
                                }
                                receiptBook.put(step.actionIndex, receipt.build())
                            }
                        }
                    } finally {
                        job.recordSpan("action.${step.type.name.lowercase()}", stepStartedNanos)
                    }
                    activeIndices = emptyList()
                }
            }

            fun executeSequencedSteps() {
                val deterministic = action.program.steps.any {
                    it.type == CaptureProgramBuilder.ActionType.DETERMINISTIC ||
                        it.type == CaptureProgramBuilder.ActionType.CAMERA_MOTION
                }
                if (!deterministic) {
                    executeSteps()
                    return
                }

                var executionFailure: Throwable? = null
                try {
                    owner.beginDeterministicSequence(job, deadline)
                    executeSteps()
                } catch (failure: Throwable) {
                    executionFailure = failure
                }
                try {
                    owner.endDeterministicSequence()
                } catch (cleanupFailure: Throwable) {
                    executionFailure?.let(cleanupFailure::addSuppressed)
                    throw cleanupFailure
                }
                executionFailure?.let { throw it }
            }

            if (prepared == null) {
                executeSequencedSteps()
                return receiptBook.complete().addTo(JobResult.newBuilder()).build()
            }
            prepared.use {
                executeSequencedSteps()
                progress.accept(JobStage.JOB_STAGE_WRITING_ARTIFACTS)
                probe.event(job.requestId, "WRITING_ARTIFACTS")
                val resultArtifacts = job.measure("artifact.profile_write") {
                    ProfileResultArtifacts.write(job.submission, prepared.transaction, receiptBook.complete().actions)
                }
                val generatedArtifacts = resultArtifacts + nsightExecutions.flatMap(NsightExecution::artifacts)
                progress.accept(JobStage.JOB_STAGE_FINALIZING)
                probe.event(job.requestId, "FINALIZING")
                activeIndices = pendingCaptureIndices.toList()
                val committed = job.measure("artifact.commit") { captures.commit(
                    job,
                    prepared,
                    completedCapturePlans,
                    captured,
                    generatedArtifacts,
                    afterPassExecutions.associate { execution ->
                        execution.receipt.request.target.artifactName to execution.receipt.physicalName
                    },
                ) }
                motions.forEach { (index, motion) ->
                    receiptBook.replace(index, receiptBook.success(index).setCameraMotion(
                        CameraMotionProtocol.receipt(motion.first, motion.second, motion.third, committed, captures)
                    ).build())
                }
                captureExecutions.forEach { execution ->
                    execution.actions.forEach { capture ->
                        receiptBook.replace(
                            capture.actionIndex,
                            receiptBook.success(capture.actionIndex)
                                .setCapture(
                                    captures.captureReceipt(
                                        execution.plan,
                                        execution.result,
                                        capture,
                                        committed,
                                        execution.waits[capture.actionIndex],
                                    ),
                                )
                                .build(),
                        )
                    }
                }
                afterPassExecutions.forEach { execution ->
                    receiptBook.replace(
                        execution.actionIndex,
                        receiptBook.success(execution.actionIndex)
                            .setCapture(captures.afterPassReceipt(execution.receipt, committed))
                            .build(),
                    )
                }
                patchedExecutions.forEach { execution ->
                    receiptBook.replace(
                        execution.actionIndex,
                        receiptBook.success(execution.actionIndex)
                            .setPatchedShaders(
                                captures.patchedShadersReceipt(execution.plan, execution.result, committed),
                            )
                            .build(),
                    )
                }
                nsightExecutions.forEach { execution ->
                    val names = execution.artifacts.mapTo(HashSet()) { it.fileName }
                    val artifacts = committed.artifactsList.filter { artifact ->
                        Path.of(artifact.relativePath).fileName.toString() in names
                    }
                    receiptBook.replace(
                        execution.actionIndex,
                        receiptBook.success(execution.actionIndex)
                            .setNsightGpuTrace(
                                execution.receipt.toBuilder().addAllArtifacts(artifacts),
                            )
                            .build(),
                    )
                }
                pendingCaptureIndices.clear()
                accumulationExecutions.forEach { (index, plan, result) ->
                    receiptBook.replace(index, receiptBook.success(index)
                        .setAccumulation(dev.vibris.protocol.v2.AccumulationReceipt.newBuilder()
                            .setSummaryJson(result.summaryJson).setCapture(captures.captureReceipt(plan,
                                requireNotNull(result.capture), CaptureProgramBuilder.CaptureAction(index, 0, 0), committed, null)))
                        .build())
                }
                activeIndices = emptyList()
                return receiptBook.complete().addTo(committed.toBuilder()).build()
            }
        } catch (failure: RuntimeJobExecutor.Failure) {
            val receipts = receiptBook.fail(failure, pendingCaptureIndices + activeIndices)
            throw failure.withActionReceipts(receipts.actions, receipts.preludes)
        } catch (exception: IOException) {
            val failure = CaptureJobExecutor.failure(exception)
            val receipts = receiptBook.fail(failure, pendingCaptureIndices + activeIndices)
            throw failure.withActionReceipts(receipts.actions, receipts.preludes)
        } catch (exception: RuntimeException) {
            val failure = RuntimeJobExecutor.Failure(
                ErrorCode.ERROR_CODE_INTERNAL,
                exception.message ?: "Action execution failed.",
            )
            val receipts = receiptBook.fail(failure, pendingCaptureIndices + activeIndices)
            throw failure.withActionReceipts(receipts.actions, receipts.preludes)
        }
    }

    private fun activate(
        job: CoreJob,
        uuid: String,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): MutationResult {
        val source = source(job, uuid, "Action references an unprepared source.")
        val reload = owner.activateSource(job, source, progress, deadline)
        val context = owner.applyContext(job, progress, deadline).context
        return MutationResult(source, reload, context)
    }

    private fun load(
        job: CoreJob,
        load: dev.vibris.protocol.v2.LoadShader,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): MutationResult {
        val source = source(job, load.sourceUuid, "Load action references an unprepared source.")
        val loaded = owner.loadShader(job, source, load.config, progress, deadline)
        return MutationResult(source, loaded.reload, loaded.context.context)
    }

    private fun source(job: CoreJob, uuid: String, message: String): SourceRegistry.Lease =
        job.sources.firstOrNull { it.uuid().equals(uuid, ignoreCase = true) }
            ?: throw RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_INVALID_SOURCE, message)

    private fun mutation(
        result: MutationResult,
        completedAtUnixMs: Long = System.currentTimeMillis(),
    ): RuntimeMutationReceipt = RuntimeMutationReceipt.newBuilder()
        .setSourceUuid(result.source.uuid)
        .setSourceSha256(result.source.snapshotSha256)
        .setEffectiveSettings(BenchmarkProvenance.effectiveSettings(result.reload.effectiveSettings))
        .setSceneSha256(BenchmarkProvenance.sceneHash(result.context))
        .setCompletedAtUnixMs(completedAtUnixMs)
        .build()

    private fun waitReceipt(frames: Int, endFrame: Long): WaitFramesReceipt =
        waitReceipt(frames, endFrame - frames, endFrame)

    private fun waitReceipt(frames: Int, startFrame: Long, endFrame: Long): WaitFramesReceipt {
        check(startFrame >= 0) { "Runtime returned a negative frame anchor." }
        check(endFrame == Math.addExact(startFrame, frames.toLong())) {
            "Runtime returned an inexact frame wait."
        }
        check(endFrame >= frames.toLong()) { "Runtime returned an incomplete frame wait." }
        return WaitFramesReceipt.newBuilder()
            .setRequestedFrames(frames)
            .setStartFrame(startFrame)
            .setEndFrame(endFrame)
            .setCompletedFrames(frames)
            .build()
    }

    private fun partialWaitReceipt(
        frames: Int,
        startFrame: Long,
        currentFrame: Long,
        completedFrames: Int,
    ): WaitFramesReceipt {
        check(completedFrames in 0 until frames) { "Runtime returned an invalid partial frame wait." }
        check(currentFrame == Math.addExact(startFrame, completedFrames.toLong())) {
            "Runtime returned an inexact partial frame wait."
        }
        return WaitFramesReceipt.newBuilder()
            .setRequestedFrames(frames)
            .setStartFrame(startFrame)
            .setEndFrame(currentFrame)
            .setCompletedFrames(completedFrames)
            .build()
    }

    private fun publishDeterministicLoadAndReset(
        receiptBook: ActionReceiptBook,
        block: CaptureProgramBuilder.DeterministicBlock,
        source: SourceRegistry.Lease,
        reloaded: DeterministicTemporalCaptureReloaded,
        resetCompletedAtUnixMs: Long,
    ) {
        publishDeterministicLoad(receiptBook, block, source, reloaded)
        receiptBook.put(
            block.resetActionIndex,
            receiptBook.success(block.resetActionIndex)
                .setResetTemporal(
                    ResetTemporalReceipt.newBuilder().setCompletedAtUnixMs(resetCompletedAtUnixMs),
                )
                .build(),
        )
    }

    private fun publishDeterministicLoad(
        receiptBook: ActionReceiptBook,
        block: CaptureProgramBuilder.DeterministicBlock,
        source: SourceRegistry.Lease,
        reloaded: DeterministicTemporalCaptureReloaded,
    ) {
        receiptBook.put(
            block.loadActionIndex,
            receiptBook.success(block.loadActionIndex)
                .setRuntimeMutation(
                    mutation(
                        MutationResult(source, reloaded.reload, reloaded.context.context),
                        reloaded.reloadCompletedAtUnixMs,
                    ),
                )
                .build(),
        )
    }

    private fun publishDeterministicDiagnostics(
        diagnostics: MutableList<ReloadResult.Diagnostic>,
        prepared: CaptureJobExecutor.Prepared,
        reloaded: DeterministicTemporalCaptureReloaded,
    ) {
        diagnostics.addAll(reloaded.reload.diagnostics)
        prepared.addDiagnostics(reloaded.reload.diagnostics)
    }

    private fun publishDeterministicWait(
        receiptBook: ActionReceiptBook,
        block: CaptureProgramBuilder.DeterministicBlock,
        anchorFrame: Long,
        warmupEndFrame: Long,
    ): WaitFramesReceipt? {
        val waitActionIndex = block.waitActionIndex
        if (waitActionIndex == null) {
            check(block.warmupFrames == 0 && warmupEndFrame == anchorFrame) {
                "A deterministic block without a wait must have zero warmup frames."
            }
            return null
        }
        val receipt = waitReceipt(block.warmupFrames, anchorFrame, warmupEndFrame)
        receiptBook.put(
            waitActionIndex,
            receiptBook.success(waitActionIndex).setWaitFrames(receipt).build(),
        )
        return receipt
    }

    private fun publishDeterministicCapturePlaceholders(
        receiptBook: ActionReceiptBook,
        group: CaptureProgramBuilder.ResolvedCaptureGroup,
        targetFrame: Long,
        catalog: ResourceCatalog,
    ) {
        group.captureActions.forEach { capture ->
            receiptBook.put(
                capture.actionIndex,
                receiptBook.success(capture.actionIndex)
                    .setCapture(
                        captures.failureCaptureReceipt(
                            group.capture,
                            capture.targetIndex,
                            targetFrame,
                            catalog,
                            null,
                        ),
                    )
                    .build(),
            )
        }
    }

    private fun publishDeterministicCapturedPlaceholders(
        receiptBook: ActionReceiptBook,
        group: CaptureProgramBuilder.ResolvedCaptureGroup,
        outcome: DeterministicTemporalCaptureOutcome.Captured,
    ) {
        group.captureActions.forEach { capture ->
            receiptBook.put(
                capture.actionIndex,
                receiptBook.success(capture.actionIndex)
                    .setCapture(
                        captures.captureReceipt(
                            outcome.plan,
                            outcome.capture,
                            capture,
                            JobResult.getDefaultInstance(),
                            null,
                        ),
                    )
                    .build(),
            )
        }
    }

    private fun verifyDeterministicPlan(
        resolved: CaptureProgramBuilder.ResolvedCaptureGroup?,
        outcomePlan: CapturePlan,
    ): CaptureProgramBuilder.ResolvedCaptureGroup {
        val group = resolved ?: throw RuntimeJobExecutor.Failure(
            ErrorCode.ERROR_CODE_CAPTURE_FAILED,
            "Runtime completed deterministic planning without returning the resolved capture group.",
        )
        if (group.capture != outcomePlan) {
            throw RuntimeJobExecutor.Failure(
                ErrorCode.ERROR_CODE_CAPTURE_FAILED,
                "Runtime deterministic capture plan did not match the authoritative resolved plan.",
            )
        }
        return group
    }

    private fun deterministicContextFailure(
        outcome: DeterministicTemporalCaptureOutcome.ContextRejected,
    ): RuntimeJobExecutor.Failure = if (
        outcome.failure.kind == DeterministicTemporalCaptureOutcome.FailureKind.CANCELLED
    ) {
        owner.deterministicPhaseFailure(outcome.failure)
    } else {
        RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_WORLD_LOAD_FAILED, outcome.failure.message)
    }

    private fun deterministicReloadFailure(
        job: CoreJob,
        outcome: DeterministicTemporalCaptureOutcome.ReloadRejected,
    ): RuntimeJobExecutor.Failure = if (
        outcome.failure.kind == DeterministicTemporalCaptureOutcome.FailureKind.CANCELLED
    ) {
        owner.deterministicPhaseFailure(outcome.failure)
    } else {
        owner.deterministicReloadFailure(job, outcome.reload)
    }

    private fun CaptureProgramBuilder.ActionStep.actionIndices(): List<Int> =
        when (type) {
            CaptureProgramBuilder.ActionType.DETERMINISTIC -> buildList {
                val block = checkNotNull(deterministic)
                add(block.loadActionIndex)
                add(block.resetActionIndex)
                block.waitActionIndex?.let(::add)
                addAll(block.captures.map(CaptureProgramBuilder.DirectCapture::actionIndex))
            }
            CaptureProgramBuilder.ActionType.CAPTURE ->
                captureActions.map(CaptureProgramBuilder.CaptureAction::actionIndex)
            CaptureProgramBuilder.ActionType.DEFERRED_CAPTURE,
            CaptureProgramBuilder.ActionType.DEFERRED_AFTER_PASS,
            -> deferredActions.map(CaptureProgramBuilder.DirectCapture::actionIndex)
            CaptureProgramBuilder.ActionType.AFTER_PASS ->
                afterPassActions.map(CaptureProgramBuilder.AfterPassAction::actionIndex)
            else -> listOf(actionIndex)
        }

    private fun captureUnavailable() = RuntimeJobExecutor.Failure(
        ErrorCode.ERROR_CODE_CAPTURE_FAILED,
        "Capture storage is unavailable.",
    )

    private data class MutationResult(
        val source: SourceRegistry.Lease,
        val reload: ReloadResult,
        val context: SceneContext,
    )

    private data class CaptureExecution(
        val plan: CapturePlan,
        val result: CaptureResult,
        val actions: List<CaptureProgramBuilder.CaptureAction>,
        val waits: Map<Int, WaitFramesReceipt>,
    )

    private data class PatchedExecution(
        val actionIndex: Int,
        val plan: CapturePlan,
        val result: CaptureResult,
    )

    private data class AfterPassExecution(
        val actionIndex: Int,
        val receipt: CapturePlan.AfterPassReceipt,
    )

    private data class NsightExecution(
        val actionIndex: Int,
        val receipt: dev.vibris.protocol.v2.NsightGpuTraceReceipt,
        val artifacts: List<GeneratedArtifact>,
    )

}