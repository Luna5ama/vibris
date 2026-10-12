package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResourceNotFoundException
import dev.vibris.api.CaptureResult
import dev.vibris.api.ReloadResult
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v2.CaptureReceipt
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobResult
import dev.vibris.protocol.v2.PatchedShadersReceipt
import dev.vibris.protocol.v2.WaitFramesReceipt
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

internal class CaptureJobExecutor(
    private val artifacts: ArtifactManager?,
    maxActions: Int = ServerConfiguration.DEFAULT_MAX_ACTIONS_PER_JOB,
) {
    private val programs = CaptureProgramBuilder(maxActions)
    private val protocol = CaptureProtocolArtifacts()

    @Throws(RuntimeJobExecutor.Failure::class)
    fun prepareActions(
        job: CoreJob,
        catalog: ResourceCatalog,
        diagnostics: List<ReloadResult.Diagnostic>,
    ): ActionPrepared {
        val program = programs.actions(job, catalog)
        val captures = program.steps.flatMap { step ->
            when (step.type) {
                CaptureProgramBuilder.ActionType.CAPTURE,
                CaptureProgramBuilder.ActionType.PATCHED_SHADERS,
                -> listOf(step.capture!!)
                CaptureProgramBuilder.ActionType.AFTER_PASS -> step.afterPassActions.map { action ->
                    CapturePlan(listOf(action.request.target))
                }
                else -> emptyList()
            }
        }
        val prepared = if (
            captures.isEmpty() && !program.planningSession.hasDeferredCaptures &&
            !ProfileResultArtifacts.requested(job.submission) &&
            program.steps.none { it.type == CaptureProgramBuilder.ActionType.NSIGHT ||
                it.type == CaptureProgramBuilder.ActionType.CAMERA_MOTION ||
                it.runtimeAction?.hasAccumulateTexture() == true }
        ) {
            null
        } else {
            prepare(job, captures, program.estimatedBytes, diagnostics)
        }
        return ActionPrepared(program, prepared)
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    fun resolveDeferred(
        prepared: Prepared,
        program: CaptureProgramBuilder.ActionProgram,
        step: CaptureProgramBuilder.ActionStep,
        catalog: ResourceCatalog,
    ): CaptureProgramBuilder.ResolvedCaptureGroup = try {
        program.planningSession.resolveDeferred(step, catalog, prepared::addPlan)
    } catch (exception: Exception) {
        throw failure(exception)
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    fun failureCaptureReceipt(
        plan: CapturePlan,
        targetIndex: Int,
        targetFrame: Long,
        catalog: ResourceCatalog,
        internalWait: WaitFramesReceipt?,
    ): CaptureReceipt = try {
        val target = plan.targets.getOrNull(targetIndex)
            ?: throw IllegalArgumentException("Capture target index is out of bounds.")
        val selector = target.resource
        val resource = catalog.resources.singleOrNull {
            it.kind == selector.kind && it.logicalName == selector.logicalName
        } ?: throw CaptureResourceNotFoundException(selector.logicalName)
        if (
            selector.mipLevel >= maxOf(1, resource.mipLevels) ||
            selector.layer >= maxOf(1, resource.layers) ||
            (selector.kind == ResourceCatalog.ResourceKind.TEXTURE && selector.textureView !in resource.availableViews)
        ) {
            throw CaptureResourceNotFoundException(selector.logicalName)
        }
        val captured = CaptureResult(
            targetFrame,
            listOf(
                CaptureResult.ArtifactGroup(
                    target.artifactName,
                    resource.copy(frameId = targetFrame),
                    emptyList(),
                ),
            ),
        )
        protocol.captureReceipt(plan, captured, targetIndex, JobResult.getDefaultInstance(), internalWait)
    } catch (exception: Exception) {
        throw failure(exception)
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    private fun prepare(
        job: CoreJob,
        capturePlans: List<CapturePlan>,
        estimate: Long,
        diagnostics: List<ReloadResult.Diagnostic>,
    ): Prepared {
        val manager = artifacts
            ?: throw RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_CAPTURE_FAILED, "Artifact storage is unavailable.")
        val shaderLog = shaderLog(diagnostics)
        var transaction: ArtifactManager.JobTransaction? = null
        try {
            val outputFileCount = outputFileCount(capturePlans)
            transaction = manager.beginJob(
                job.workspaceId,
                job.submission.jobId,
                job.requestId,
                job.submission.workloadCase.name.removeSuffix("_WORKLOAD").lowercase(),
                reservationBytes(estimate, shaderLog.size.toLong(), outputFileCount),
            )
            return Prepared(transaction, capturePlans, diagnostics, shaderLog.size.toLong(), estimate)
        } catch (exception: Exception) {
            closeAfterFailure(transaction, exception)
            throw failure(exception)
        }
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    fun commit(job: CoreJob, prepared: Prepared, captured: CaptureResult): JobResult =
        commit(job, prepared, listOf(captured))

    @Throws(RuntimeJobExecutor.Failure::class)
    fun commit(
        job: CoreJob,
        prepared: Prepared,
        captured: List<CaptureResult>,
    ): JobResult = commit(job, prepared, prepared.plans, captured)

    @Throws(RuntimeJobExecutor.Failure::class)
    fun commit(
        job: CoreJob,
        prepared: Prepared,
        plans: List<CapturePlan>,
        captured: List<CaptureResult>,
        additionalArtifacts: List<GeneratedArtifact> = emptyList(),
        physicalNames: Map<String, String> = emptyMap(),
    ): JobResult {
        try {
            prepared.writeShaderLog()
            return protocol.commit(
                job,
                plans,
                captured,
                prepared.transaction,
                prepared.diagnostics,
                additionalArtifacts,
                physicalNames,
            )
        } catch (exception: Exception) {
            throw failure(exception)
        }
    }


    fun captureReceipt(
        plan: CapturePlan,
        captured: CaptureResult,
        capture: CaptureProgramBuilder.CaptureAction,
        committed: JobResult,
        internalWait: WaitFramesReceipt?,
    ): CaptureReceipt = protocol.captureReceipt(
        plan,
        captured,
        capture.targetIndex,
        committed,
        internalWait,
    )

    fun patchedShadersReceipt(
        plan: CapturePlan,
        captured: CaptureResult,
        committed: JobResult,
    ): PatchedShadersReceipt = protocol.patchedShadersReceipt(plan, captured, committed)

    fun afterPassReceipt(
        receipt: CapturePlan.AfterPassReceipt,
        committed: JobResult,
    ): CaptureReceipt = protocol.afterPassReceipt(receipt, committed)

    inner class Prepared internal constructor(
        internal val transaction: ArtifactManager.JobTransaction,
        capturePlans: List<CapturePlan>,
        diagnostics: List<ReloadResult.Diagnostic>,
        private val shaderLogBytes: Long,
        estimatedBytes: Long,
    ) : AutoCloseable {
        private val mutablePlans = ArrayList(capturePlans)
        private val initialArtifactNames = capturePlans
            .flatMap { plan -> plan.targets.flatMap(CapturePlan.Target::outputs) }
            .map { output -> output.fileName.lowercase(Locale.ROOT) }
        private val artifactNames = HashSet(initialArtifactNames)
        private var estimatedCaptureBytes = estimatedBytes
        private var outputFileCount = outputFileCount(capturePlans)
        private var requestedReservationBytes = reservationBytes(
            estimatedCaptureBytes,
            shaderLogBytes,
            outputFileCount,
        )
        internal val plans: List<CapturePlan>
            get() = java.util.List.copyOf(mutablePlans)
        internal val diagnostics = ArrayList(diagnostics)
        private var shaderLogWritten = false

        init {
            require(estimatedBytes >= 0) { "Capture estimate must not be negative." }
            if (initialArtifactNames.size != artifactNames.size) {
                throw IOException("Capture artifact names are repeated.")
            }
        }

        fun plan(): CapturePlan = plans.first()

        fun sink(): ArtifactManager.JobTransaction = transaction

        @Synchronized
        fun checkpoint(): PreparedCheckpoint = PreparedCheckpoint(
            transaction.checkpoint(),
            mutablePlans.size,
            java.util.Set.copyOf(artifactNames),
            estimatedCaptureBytes,
            outputFileCount,
            requestedReservationBytes,
        )

        @Synchronized
        @Throws(IOException::class)
        fun rollback(checkpoint: PreparedCheckpoint) {
            var rollbackFailure: IOException? = null
            try {
                transaction.rollback(checkpoint.transaction)
            } catch (exception: IOException) {
                rollbackFailure = exception
            } finally {
                while (mutablePlans.size > checkpoint.planCount) mutablePlans.removeLast()
                artifactNames.clear()
                artifactNames.addAll(checkpoint.artifactNames)
                estimatedCaptureBytes = checkpoint.estimatedCaptureBytes
                outputFileCount = checkpoint.outputFileCount
                requestedReservationBytes = checkpoint.requestedReservationBytes
            }
            rollbackFailure?.let { throw it }
        }

        fun addDiagnostics(additional: List<ReloadResult.Diagnostic>) {
            check(!shaderLogWritten) { "Shader log has already been finalized." }
            diagnostics.addAll(additional)
        }

        @Throws(IOException::class)
        @Synchronized
        fun addPlan(plan: CapturePlan, estimatedBytes: Long) {
            require(estimatedBytes >= 0) { "Capture estimate must not be negative." }
            val newNames = plan.targets.flatMap(CapturePlan.Target::outputs)
                .map { output -> output.fileName.lowercase(Locale.ROOT) }
            if (newNames.size != newNames.toSet().size || newNames.any(artifactNames::contains)) {
                throw IOException("Capture artifact names are repeated.")
            }
            val newEstimate = Math.addExact(estimatedCaptureBytes, estimatedBytes)
            val newFileCount = Math.addExact(
                outputFileCount,
                outputFileCount(listOf(plan)),
            )
            val reservation = reservationBytes(newEstimate, shaderLogBytes, newFileCount)
            checkNotNull(artifacts).reserve(transaction, reservation)
            mutablePlans.add(plan)
            artifactNames.addAll(newNames)
            estimatedCaptureBytes = newEstimate
            outputFileCount = newFileCount
            requestedReservationBytes = reservation
        }

        @Throws(IOException::class)
        @Synchronized
        fun reserveAdditionalArtifacts(files: Map<String, Long>) {
            require(files.values.all { it >= 0 }) { "Artifact sizes must not be negative." }
            val canonicalNames = files.keys.map { it.lowercase(Locale.ROOT) }
            if (canonicalNames.size != canonicalNames.toSet().size || canonicalNames.any(artifactNames::contains)) {
                throw IOException("Artifact names are repeated.")
            }
            val bytes = files.values.fold(0L, Math::addExact)
            val overhead = Math.multiplyExact(files.size.toLong(), 2L * 1024)
            val reservation = Math.addExact(requestedReservationBytes, Math.addExact(bytes, overhead))
            checkNotNull(artifacts).reserve(transaction, reservation)
            artifactNames.addAll(canonicalNames)
            outputFileCount = Math.addExact(outputFileCount, files.size)
            requestedReservationBytes = reservation
        }

        @Throws(IOException::class)
        internal fun writeShaderLog() {
            if (shaderLogWritten) {
                throw IOException("Shader log has already been written.")
            }
            transaction.open("shader.log").use { output -> output.write(shaderLog(diagnostics)) }
            shaderLogWritten = true
        }

        @Throws(IOException::class)
        override fun close() {
            transaction.close()
        }
    }

    @JvmRecord
    data class ActionPrepared(
        val program: CaptureProgramBuilder.ActionProgram,
        val prepared: Prepared?,
    )

    data class PreparedCheckpoint internal constructor(
        internal val transaction: ArtifactJobTransaction.Checkpoint,
        internal val planCount: Int,
        internal val artifactNames: Set<String>,
        internal val estimatedCaptureBytes: Long,
        internal val outputFileCount: Int,
        internal val requestedReservationBytes: Long,
    )

    companion object {
        @JvmStatic
        fun failure(exception: Throwable?): RuntimeJobExecutor.Failure {
            var cause = exception
            var root = exception
            while (cause != null && cause !== cause.cause) {
                root = cause
                when (cause) {
                    is ArtifactManager.JobTooLargeException,
                    is ArithmeticException,
                    -> return RuntimeJobExecutor.Failure(
                        ErrorCode.ERROR_CODE_ARTIFACT_TOO_LARGE,
                        "Artifact job is too large.",
                    )

                    is ArtifactManager.QuotaExceededException -> return RuntimeJobExecutor.Failure(
                        ErrorCode.ERROR_CODE_ARTIFACT_QUOTA_EXCEEDED,
                        cause.message,
                    )

                    is CaptureResourceNotFoundException -> return RuntimeJobExecutor.Failure(
                        ErrorCode.ERROR_CODE_RESOURCE_NOT_FOUND,
                        cause.message,
                    )

                    is RuntimeJobExecutor.Failure -> return cause
                }
                cause = cause.cause
            }
            return RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_CAPTURE_FAILED, "Capture artifact creation failed.")
                .withDetails(mapOf("cause" to (root?.javaClass?.name ?: "unknown"),
                    "reason" to (root?.message?.take(512) ?: "No error message")))
        }

        private fun closeAfterFailure(transaction: ArtifactManager.JobTransaction?, original: Exception) {
            if (transaction == null) {
                return
            }
            try {
                transaction.close()
            } catch (closeFailure: IOException) {
                original.addSuppressed(closeFailure)
            }
        }

        private fun outputFileCount(plans: List<CapturePlan>): Int = plans.fold(0) { total, plan ->
            plan.targets.fold(total) { count, target -> Math.addExact(count, target.outputs.size) }
        }

        private fun reservationBytes(estimate: Long, shaderLogBytes: Long, outputFileCount: Int): Long =
            Math.addExact(
                Math.addExact(estimate, shaderLogBytes),
                ArtifactManifest.reservationBytes(Math.addExact(outputFileCount, 9)),
            )

        private fun shaderLog(diagnostics: List<ReloadResult.Diagnostic>): ByteArray {
            if (diagnostics.isEmpty()) {
                return "Shader reload succeeded.\n".toByteArray(StandardCharsets.UTF_8)
            }
            val output = StringBuilder()
            for (diagnostic in diagnostics) {
                output.append('[').append(diagnostic.severity).append("] ").append(diagnostic.source)
                if (diagnostic.line > 0) {
                    output.append(':').append(diagnostic.line)
                }
                output.append(": ").append(diagnostic.message).append(System.lineSeparator())
            }
            return output.toString().toByteArray(StandardCharsets.UTF_8)
        }
    }
}