package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v2.Action
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.LoadShader
import java.util.Locale

internal class CaptureProgramBuilder(private val maxActions: Int = DEFAULT_MAX_ACTIONS) {
    private val expandedActionLimit = maxActions.toLong() * 2 + 8

    init {
        require(maxActions > 0) { "maxActions must be positive" }
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    fun actions(job: CoreJob, catalog: ResourceCatalog): ActionProgram {
        if (!job.submission.hasActionSequence()) throw invalid("Only action-sequence jobs are executable.")
        if (job.submission.actionSequence.actionsCount.toLong() > expandedActionLimit) {
            throw invalid("Action limit exceeded.")
        }
        if (job.submission.hasResultArtifacts()) {
            val options = job.submission.resultArtifacts
            if ((!options.writeJson && !options.writeCsv) ||
                options.convertedUnitsList.any { it != "us" && it != "ms" } ||
                options.convertedUnitsList.distinct().size != options.convertedUnitsCount
            ) {
                throw invalid("Result artifact options are invalid.")
            }
        }
        val steps = ArrayList<ActionStep>()
        val captureGroup = ArrayList<PendingCapture>()
        val afterPassGroup = ArrayList<PendingAfterPass>()
        val deferredCaptureGroup = ArrayList<DirectCapture>()
        val deferredAfterPassGroup = ArrayList<DirectCapture>()
        val artifactNames = HashSet<String>()
        val capturePlansByActionIndex = HashMap<Int, CapturePlan>()
        val directCaptureActionIndices = LinkedHashSet<Int>()
        val deferredActionIndices = LinkedHashSet<Int>()
        val deferredBlocks = LinkedHashMap<Int, DeterministicBlock>()
        val deferredCaptureSteps = LinkedHashMap<Int, List<DirectCapture>>()
        val deferredAfterPassSteps = LinkedHashMap<Int, List<DirectCapture>>()
        var estimatedBytes = 0L
        var deferCatalogPlanning = false
        fun flushCaptures() {
            estimatedBytes = flush(
                captureGroup,
                steps,
                catalog,
                artifactNames,
                estimatedBytes,
                capturePlansByActionIndex,
            )
        }
        fun flushAfterPasses() {
            estimatedBytes = flushAfterPassGroup(
                afterPassGroup,
                steps,
                catalog,
                artifactNames,
                estimatedBytes,
            )
        }
        fun flushDeferredCaptures() {
            if (deferredCaptureGroup.isEmpty()) return
            val pending = java.util.List.copyOf(deferredCaptureGroup)
            deferredCaptureGroup.clear()
            deferredCaptureSteps[pending.first().actionIndex] = pending
            steps.add(ActionStep.deferredCapture(pending))
        }
        fun flushDeferredAfterPasses() {
            if (deferredAfterPassGroup.isEmpty()) return
            val pending = java.util.List.copyOf(deferredAfterPassGroup)
            deferredAfterPassGroup.clear()
            deferredAfterPassSteps[pending.first().actionIndex] = pending
            steps.add(ActionStep.deferredAfterPass(pending))
        }
        fun flushAll() {
            flushCaptures()
            flushAfterPasses()
            flushDeferredCaptures()
            flushDeferredAfterPasses()
        }
        val actions = job.submission.actionSequence.actionsList
        var actionIndex = 0
        while (actionIndex < actions.size) {
            val deterministic = deterministicBlock(actions, actionIndex)
            if (deterministic != null) {
                flushAll()
                validateLoad(deterministic.loadShader)
                steps.add(ActionStep.deterministic(deterministic))
                deferredBlocks[deterministic.loadActionIndex] = deterministic
                deterministic.captures.forEach { capture ->
                    directCaptureActionIndices.add(capture.actionIndex)
                    deferredActionIndices.add(capture.actionIndex)
                }
                deferCatalogPlanning = true
                actionIndex = deterministic.captures.last().actionIndex + 1
                continue
            }
            val action = actions[actionIndex]
            when {
                action.hasLoadShader() -> {
                    flushAll()
                    val load = action.loadShader
                    validateLoad(load)
                    steps.add(ActionStep.load(actionIndex, load))
                    deferCatalogPlanning = true
                }
                action.hasActivateSource() -> {
                    flushAll()
                    val uuid = action.activateSource.sourceUuid
                    if (uuid.isBlank()) throw invalid("Source UUID is missing.")
                    steps.add(ActionStep.activate(actionIndex, uuid))
                    deferCatalogPlanning = true
                }
                action.hasCameraMotion() -> {
                    flushAll()
                    if (!job.submission.restoreState.onSuccess || !job.submission.restoreState.onError) {
                        throw invalid("Camera motion requires restoration on success and error.")
                    }
                    val motion = try { CameraMotionProtocol.toApi(action.cameraMotion) }
                    catch (failure: IllegalArgumentException) { throw invalid(failure.message ?: "Invalid camera motion.") }
                    (0 until motion.frameCount()).forEach { requireUnique(artifactNames, motion.artifactName(it)) }
                    steps.add(ActionStep.cameraMotion(actionIndex, action))
                }
                action.hasResetTemporalState() -> {
                    flushAll()
                    steps.add(ActionStep.reset(actionIndex))
                }
                action.hasWaitFrames() -> {
                    flushAll()
                    if (action.waitFrames.frameCount <= 0) throw invalid("Frame count must be positive.")
                    steps.add(ActionStep.waitFrames(actionIndex, action.waitFrames.frameCount))
                }
                action.hasTakeScreenshot() || action.hasDumpTexture() || action.hasDumpBuffer() -> {
                    val afterFrames = if (action.hasTakeScreenshot()) action.takeScreenshot.afterFrames else 0
                    if (afterFrames < 0) throw invalid("Screenshot frame delay is too large.")
                    directCaptureActionIndices.add(actionIndex)
                    if (deferCatalogPlanning) {
                        flushDeferredAfterPasses()
                        if (afterFrames > 0) flushDeferredCaptures()
                        deferredActionIndices.add(actionIndex)
                        deferredCaptureGroup.add(DirectCapture(actionIndex, action))
                        if (afterFrames > 0) flushDeferredCaptures()
                    } else {
                        flushAfterPasses()
                        if (afterFrames > 0) flushCaptures()
                        val targets = ArrayList<CapturePlan.Target>(1)
                        CapturePlanBuilder.addAction(targets, action, catalog)
                        if (targets.size != 1) throw invalid("Capture action did not resolve to exactly one target.")
                        captureGroup.add(PendingCapture(actionIndex, targets.single(), afterFrames))
                        if (afterFrames > 0) flushCaptures()
                    }
                }
                action.hasDumpTextureAfterPass() || action.hasDumpBufferAfterPass() -> {
                    if (deferCatalogPlanning) {
                        flushDeferredCaptures()
                        deferredActionIndices.add(actionIndex)
                        deferredAfterPassGroup.add(DirectCapture(actionIndex, action))
                    } else {
                        flushCaptures()
                        afterPassGroup.add(PendingAfterPass(actionIndex, action))
                    }
                }
                action.hasGetPatchedShaders() -> {
                    flushAll()
                    val capture = CapturePlanBuilder.patchedShaders(action.getPatchedShaders.artifactName)
                    requireUnique(artifactNames, capture.targets.single().artifactName)
                    steps.add(ActionStep.patchedShaders(actionIndex, capture))
                }
                action.hasInspectShader() -> {
                    flushAll()
                    steps.add(ActionStep.inspect(actionIndex))
                }
                action.hasNsightGpuTrace() -> {
                    flushAll()
                    try {
                        NsightGpuTraceRunner.validate(action.nsightGpuTrace)
                    } catch (exception: IllegalArgumentException) {
                        throw invalid(exception.message ?: "Nsight GPU Trace action is invalid.")
                    }
                    NsightGpuTraceRunner.artifactFileNames(action.nsightGpuTrace).forEach {
                        requireUnique(artifactNames, it)
                    }
                    steps.add(ActionStep.nsight(actionIndex, action))
                }
                RuntimeActionProtocol.isRuntime(action) -> {
                    flushAll()
                    steps.add(ActionStep.runtime(actionIndex, action))
                }
                else -> throw invalid("Action is not supported.")
            }
            actionIndex++
        }
        flushAll()
        val firstActivation = steps.indexOfFirst {
            it.type == ActionType.ACTIVATE || it.type == ActionType.LOAD || it.type == ActionType.DETERMINISTIC
        }
        if (firstActivation > 0) {
            throw invalid("Source activation must be the first action.")
        }
        val planningSession = PlanningSession(
            artifactNames,
            estimatedBytes,
            capturePlansByActionIndex,
            directCaptureActionIndices,
            deferredActionIndices,
            deferredBlocks,
            deferredCaptureSteps,
            deferredAfterPassSteps,
        )
        return ActionProgram(java.util.List.copyOf(steps), estimatedBytes, planningSession)
    }

    enum class ActionType {
        LOAD,
        DETERMINISTIC,
        CAMERA_MOTION,
        ACTIVATE,
        RESET,
        WAIT,
        CAPTURE,
        DEFERRED_CAPTURE,
        AFTER_PASS,
        DEFERRED_AFTER_PASS,
        PATCHED_SHADERS,
        INSPECT,
        NSIGHT,
        RUNTIME,
    }

    @JvmRecord
    data class ActionStep(
        val type: ActionType,
        val sourceUuid: String?,
        val frames: Int,
        val capture: CapturePlan?,
        val actionIndex: Int,
        val runtimeAction: Action?,
        val loadShader: LoadShader?,
        val captureActions: List<CaptureAction>,
        val afterPassActions: List<AfterPassAction>,
        val deterministic: DeterministicBlock?,
        val deferredActions: List<DirectCapture>,
    ) {
        companion object {
            fun load(actionIndex: Int, load: LoadShader) = ActionStep(
                ActionType.LOAD, null, 0, null, actionIndex, null, load,
                emptyList(), emptyList(), null, emptyList(),
            )
            fun deterministic(block: DeterministicBlock) = ActionStep(
                ActionType.DETERMINISTIC, null, 0, null, block.loadActionIndex, null, block.loadShader,
                emptyList(), emptyList(), block, emptyList(),
            )
            fun activate(actionIndex: Int, uuid: String) =
                ActionStep(
                    ActionType.ACTIVATE, uuid, 0, null, actionIndex, null, null,
                    emptyList(), emptyList(), null, emptyList(),
                )
            fun reset(actionIndex: Int) =
                ActionStep(
                    ActionType.RESET, null, 0, null, actionIndex, null, null,
                    emptyList(), emptyList(), null, emptyList(),
                )
            fun waitFrames(actionIndex: Int, frames: Int) =
                ActionStep(
                    ActionType.WAIT, null, frames, null, actionIndex, null, null,
                    emptyList(), emptyList(), null, emptyList(),
                )
            fun capture(capture: CapturePlan, actions: List<CaptureAction>) =
                ActionStep(
                    ActionType.CAPTURE, null, 0, capture, actions.first().actionIndex,
                    null, null, actions, emptyList(), null, emptyList(),
                )
            fun deferredCapture(actions: List<DirectCapture>) =
                ActionStep(
                    ActionType.DEFERRED_CAPTURE, null, 0, null, actions.first().actionIndex,
                    null, null, emptyList(), emptyList(), null, actions,
                )
            fun afterPass(actions: List<AfterPassAction>) =
                ActionStep(
                    ActionType.AFTER_PASS, null, 0, null, actions.first().actionIndex,
                    null, null, emptyList(), actions, null, emptyList(),
                )
            fun deferredAfterPass(actions: List<DirectCapture>) =
                ActionStep(
                    ActionType.DEFERRED_AFTER_PASS, null, 0, null, actions.first().actionIndex,
                    null, null, emptyList(), emptyList(), null, actions,
                )
            fun patchedShaders(actionIndex: Int, capture: CapturePlan) =
                ActionStep(
                    ActionType.PATCHED_SHADERS, null, 0, capture, actionIndex,
                    null, null, emptyList(), emptyList(), null, emptyList(),
                )
            fun inspect(actionIndex: Int) =
                ActionStep(
                    ActionType.INSPECT, null, 0, null, actionIndex,
                    null, null, emptyList(), emptyList(), null, emptyList(),
                )
            fun nsight(actionIndex: Int, action: Action) =
                ActionStep(
                    ActionType.NSIGHT, null, 0, null, actionIndex,
                    action, null, emptyList(), emptyList(), null, emptyList(),
                )
            fun cameraMotion(actionIndex: Int, action: Action) = ActionStep(
                ActionType.CAMERA_MOTION, null, 0, null, actionIndex,
                action, null, emptyList(), emptyList(), null, emptyList(),
            )
            fun runtime(actionIndex: Int, action: Action) =
                ActionStep(
                    ActionType.RUNTIME, null, 0, null, actionIndex,
                    action, null, emptyList(), emptyList(), null, emptyList(),
                )
        }
    }

    @JvmRecord
    data class DeterministicBlock(
        val loadActionIndex: Int,
        val loadShader: LoadShader,
        val resetActionIndex: Int,
        val waitActionIndex: Int?,
        val warmupFrames: Int,
        val captures: List<DirectCapture>,
    )

    @JvmRecord
    data class DirectCapture(val actionIndex: Int, val action: Action)

    @JvmRecord
    data class CaptureAction(
        val actionIndex: Int,
        val targetIndex: Int,
        val beforeFrames: Int,
    )

    @JvmRecord
    data class AfterPassAction(
        val actionIndex: Int,
        val request: CapturePlan.AfterPassRequest,
    )

    @JvmRecord
    data class ResolvedCaptureGroup(
        val capture: CapturePlan,
        val captureActions: List<CaptureAction>,
        val estimatedBytes: Long,
    )

    @JvmRecord
    data class ResolvedAfterPassGroup(
        val reservationPlan: CapturePlan,
        val afterPassActions: List<AfterPassAction>,
        val estimatedBytes: Long,
    )

    @JvmRecord
    data class ActionProgram(
        val steps: List<ActionStep>,
        val estimatedBytes: Long,
        val planningSession: PlanningSession,
    )

    class PlanningSession internal constructor(
        artifactNames: Set<String>,
        initialEstimatedBytes: Long,
        capturePlansByActionIndex: Map<Int, CapturePlan>,
        directCaptureActionIndices: Set<Int>,
        deferredActionIndices: Set<Int>,
        deferredBlocks: Map<Int, DeterministicBlock>,
        deferredCaptureSteps: Map<Int, List<DirectCapture>>,
        deferredAfterPassSteps: Map<Int, List<DirectCapture>>,
    ) {
        private val artifactNames = HashSet(artifactNames)
        private val capturePlansByActionIndex = HashMap(capturePlansByActionIndex)
        private val deferredActionIndices = HashSet(deferredActionIndices)
        private val deferredBlocks = HashMap(deferredBlocks)
        private val deferredCaptureSteps = HashMap(deferredCaptureSteps)
        private val deferredAfterPassSteps = HashMap(deferredAfterPassSteps)
        private val resolvedGroups = HashMap<Int, ResolvedCaptureGroup>()
        private val resolvedCaptureSteps = HashMap<Int, ResolvedCaptureGroup>()
        private val resolvedAfterPassSteps = HashMap<Int, ResolvedAfterPassGroup>()
        private var estimatedBytes = initialEstimatedBytes

        val hasDeferredCaptures: Boolean = deferredActionIndices.isNotEmpty()
        val directCaptureActionIndices: Set<Int> = java.util.Collections.unmodifiableSet(
            LinkedHashSet(directCaptureActionIndices),
        )

        @Synchronized
        @Throws(RuntimeJobExecutor.Failure::class)
        fun resolveDeferred(
            step: ActionStep,
            catalog: ResourceCatalog,
            registrar: (CapturePlan, Long) -> Unit,
        ): ResolvedCaptureGroup {
            val block = step.deterministic
                ?: throw invalid("Action step is not a deferred deterministic capture.")
            if (step.type != ActionType.DETERMINISTIC || deferredBlocks[block.loadActionIndex] != block ||
                block.captures.any { it.actionIndex !in deferredActionIndices }
            ) {
                throw invalid("Action step is not part of this deferred capture session.")
            }
            resolvedGroups[block.loadActionIndex]?.let { return it }

            val targets = ArrayList<CapturePlan.Target>(block.captures.size)
            block.captures.forEach { capture -> CapturePlanBuilder.addAction(targets, capture.action, catalog) }
            if (targets.size != block.captures.size) {
                throw invalid("Deferred capture actions did not resolve one-to-one.")
            }
            val planned = CapturePlanBuilder.plan(targets, catalog)
            val names = availableNames(planned.capture)
            val total = totalEstimate(planned.estimatedBytes)
            val captureActions = block.captures.mapIndexed { targetIndex, capture ->
                CaptureAction(capture.actionIndex, targetIndex, 0)
            }
            val resolved = ResolvedCaptureGroup(
                planned.capture,
                java.util.List.copyOf(captureActions),
                planned.estimatedBytes,
            )

            registrar(planned.capture, planned.estimatedBytes)

            artifactNames.addAll(names)
            block.captures.forEachIndexed { targetIndex, capture ->
                capturePlansByActionIndex[capture.actionIndex] = CapturePlan(
                    listOf(planned.capture.targets[targetIndex]),
                )
            }
            estimatedBytes = total
            resolvedGroups[block.loadActionIndex] = resolved
            return resolved
        }

        @Synchronized
        @Throws(RuntimeJobExecutor.Failure::class)
        fun resolveDeferredCapture(
            step: ActionStep,
            catalog: ResourceCatalog,
            registrar: (CapturePlan, Long) -> Unit,
        ): ResolvedCaptureGroup {
            val captures = step.deferredActions
            if (step.type != ActionType.DEFERRED_CAPTURE || captures.isEmpty() ||
                deferredCaptureSteps[step.actionIndex] != captures ||
                captures.any { it.actionIndex !in deferredActionIndices }
            ) {
                throw invalid("Action step is not part of this deferred capture session.")
            }
            resolvedCaptureSteps[step.actionIndex]?.let { return it }

            val targets = ArrayList<CapturePlan.Target>(captures.size)
            captures.forEach { capture -> CapturePlanBuilder.addAction(targets, capture.action, catalog) }
            if (targets.size != captures.size) {
                throw invalid("Deferred capture actions did not resolve one-to-one.")
            }
            val planned = CapturePlanBuilder.plan(targets, catalog)
            val names = availableNames(planned.capture)
            val total = totalEstimate(planned.estimatedBytes)
            val captureActions = captures.mapIndexed { targetIndex, capture ->
                val beforeFrames = if (capture.action.hasTakeScreenshot()) {
                    capture.action.takeScreenshot.afterFrames
                } else {
                    0
                }
                CaptureAction(capture.actionIndex, targetIndex, beforeFrames)
            }
            val resolved = ResolvedCaptureGroup(
                planned.capture,
                java.util.List.copyOf(captureActions),
                planned.estimatedBytes,
            )

            registrar(planned.capture, planned.estimatedBytes)

            artifactNames.addAll(names)
            captures.forEachIndexed { targetIndex, capture ->
                capturePlansByActionIndex[capture.actionIndex] = CapturePlan(
                    listOf(planned.capture.targets[targetIndex]),
                )
            }
            estimatedBytes = total
            resolvedCaptureSteps[step.actionIndex] = resolved
            return resolved
        }

        @Synchronized
        @Throws(RuntimeJobExecutor.Failure::class)
        fun resolveDeferredAfterPass(
            step: ActionStep,
            catalog: ResourceCatalog,
            registrar: (CapturePlan, Long) -> Unit,
        ): ResolvedAfterPassGroup {
            val captures = step.deferredActions
            if (step.type != ActionType.DEFERRED_AFTER_PASS || captures.isEmpty() ||
                deferredAfterPassSteps[step.actionIndex] != captures ||
                captures.any { it.actionIndex !in deferredActionIndices }
            ) {
                throw invalid("Action step is not part of this deferred after-pass session.")
            }
            resolvedAfterPassSteps[step.actionIndex]?.let { return it }

            val plans = captures.map { capture ->
                capture to CapturePlanBuilder.afterPassPlan(capture.action, catalog)
            }
            val reservationPlan = CapturePlan(plans.map { (_, plan) -> plan.request.target })
            val names = availableNames(reservationPlan)
            val groupEstimate = try {
                plans.fold(0L) { bytes, (_, plan) -> Math.addExact(bytes, plan.estimatedBytes) }
            } catch (_: ArithmeticException) {
                throw tooLarge()
            }
            val total = totalEstimate(groupEstimate)
            val afterPassActions = plans.map { (capture, plan) ->
                AfterPassAction(capture.actionIndex, plan.request)
            }
            val resolved = ResolvedAfterPassGroup(
                reservationPlan,
                java.util.List.copyOf(afterPassActions),
                groupEstimate,
            )

            registrar(reservationPlan, groupEstimate)

            artifactNames.addAll(names)
            estimatedBytes = total
            resolvedAfterPassSteps[step.actionIndex] = resolved
            return resolved
        }


        private fun availableNames(plan: CapturePlan): List<String> {
            val names = plan.targets
                .flatMap(CapturePlan.Target::outputs)
                .map { it.fileName.lowercase(Locale.ROOT) }
            if (names.size != names.toSet().size || names.any { it in artifactNames }) {
                throw invalid("Capture artifact names are repeated.")
            }
            return names
        }

        private fun totalEstimate(additional: Long): Long = try {
            Math.addExact(estimatedBytes, additional)
        } catch (_: ArithmeticException) {
            throw tooLarge()
        }

        private fun tooLarge() = RuntimeJobExecutor.Failure(
            ErrorCode.ERROR_CODE_ARTIFACT_TOO_LARGE,
            "Artifact estimate is too large.",
        )
    }

    private data class PendingCapture(
        val actionIndex: Int,
        val target: CapturePlan.Target,
        val beforeFrames: Int,
    )

    private data class PendingAfterPass(
        val actionIndex: Int,
        val action: dev.vibris.protocol.v2.Action,
    )

    companion object {
        private const val DEFAULT_MAX_ACTIONS = 64

        @JvmOverloads
        fun startsWithDeterministicBlock(actions: List<Action>, offset: Int = 0): Boolean =
            deterministicBlock(actions, offset) != null

        private fun deterministicBlock(actions: List<Action>, offset: Int): DeterministicBlock? {
            if (offset < 0 || offset + 3 > actions.size) return null
            val load = actions[offset]
            val resetActionIndex = offset + 1
            val reset = actions[resetActionIndex]
            if (!load.prelude || !load.hasLoadShader() || reset.prelude || !reset.hasResetTemporalState()) {
                return null
            }

            var captureOffset = offset + 2
            var waitActionIndex: Int? = null
            var warmupFrames = 0
            if (captureOffset < actions.size && actions[captureOffset].hasWaitFrames()) {
                val wait = actions[captureOffset]
                val frames = wait.waitFrames.frameCount
                if (wait.prelude || frames <= 0) return null
                waitActionIndex = captureOffset
                warmupFrames = frames
                captureOffset++
            }
            val captures = ArrayList<DirectCapture>()
            while (captureOffset < actions.size &&
                !actions[captureOffset].prelude &&
                isZeroDelayDirectCapture(actions[captureOffset])
            ) {
                captures.add(DirectCapture(captureOffset, actions[captureOffset]))
                captureOffset++
            }
            if (captures.isEmpty()) return null
            return DeterministicBlock(
                offset,
                load.loadShader,
                resetActionIndex,
                waitActionIndex,
                warmupFrames,
                java.util.List.copyOf(captures),
            )
        }

        private fun isZeroDelayDirectCapture(action: Action): Boolean = when {
            action.hasTakeScreenshot() -> action.takeScreenshot.afterFrames == 0
            action.hasDumpTexture() -> true
            action.hasDumpBuffer() -> true
            else -> false
        }

        private fun validateLoad(load: LoadShader) {
            if (load.sourceUuid.isBlank() || load.sourceId.isBlank() || load.configId.isBlank()) {
                throw invalid("Shader load references are incomplete.")
            }
        }

        @Throws(RuntimeJobExecutor.Failure::class)
        private fun flush(
            group: MutableList<PendingCapture>,
            steps: MutableList<ActionStep>,
            catalog: ResourceCatalog,
            artifactNames: MutableSet<String>,
            estimatedBytes: Long,
            capturePlansByActionIndex: MutableMap<Int, CapturePlan>,
        ): Long {
            if (group.isEmpty()) return estimatedBytes
            val pending = java.util.List.copyOf(group)
            val planned = CapturePlanBuilder.plan(pending.map(PendingCapture::target), catalog)
            for (target in planned.capture.targets) {
                target.outputs.forEach { requireUnique(artifactNames, it.fileName) }
            }
            group.clear()
            pending.forEachIndexed { index, capture ->
                capturePlansByActionIndex[capture.actionIndex] = CapturePlan(
                    listOf(planned.capture.targets[index]),
                )
            }
            steps.add(
                ActionStep.capture(
                    planned.capture,
                    pending.mapIndexed { index, capture ->
                        CaptureAction(capture.actionIndex, index, capture.beforeFrames)
                    },
                ),
            )
            return try {
                Math.addExact(estimatedBytes, planned.estimatedBytes)
            } catch (_: ArithmeticException) {
                throw RuntimeJobExecutor.Failure(
                    ErrorCode.ERROR_CODE_ARTIFACT_TOO_LARGE,
                    "Artifact estimate is too large.",
                )
            }
        }

        @Throws(RuntimeJobExecutor.Failure::class)
        private fun flushAfterPassGroup(
            group: MutableList<PendingAfterPass>,
            steps: MutableList<ActionStep>,
            catalog: ResourceCatalog,
            artifactNames: MutableSet<String>,
            estimatedBytes: Long,
        ): Long {
            if (group.isEmpty()) return estimatedBytes
            val pending = java.util.List.copyOf(group)
            val plans = pending.map { capture ->
                capture to CapturePlanBuilder.afterPassPlan(capture.action, catalog)
            }
            plans.forEach { (_, plan) ->
                plan.request.target.outputs.forEach { requireUnique(artifactNames, it.fileName) }
            }
            val total = try {
                plans.fold(estimatedBytes) { bytes, (_, plan) -> Math.addExact(bytes, plan.estimatedBytes) }
            } catch (_: ArithmeticException) {
                throw RuntimeJobExecutor.Failure(
                    ErrorCode.ERROR_CODE_ARTIFACT_TOO_LARGE,
                    "Artifact estimate is too large.",
                )
            }
            group.clear()
            steps.add(
                ActionStep.afterPass(plans.map { (capture, plan) ->
                    AfterPassAction(capture.actionIndex, plan.request)
                }),
            )
            return total
        }

        private fun requireUnique(names: MutableSet<String>, name: String) {
            if (!names.add(name.lowercase(Locale.ROOT))) throw invalid("Capture artifact names are repeated.")
        }

        private fun invalid(message: String) = RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_CAPTURE_FAILED, message)
    }
}