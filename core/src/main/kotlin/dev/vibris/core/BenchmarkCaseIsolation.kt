package dev.vibris.core

import dev.vibris.api.EffectiveShaderSettings
import dev.vibris.api.SceneContext
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobSpec
import dev.vibris.protocol.v2.ProtocolError
import dev.vibris.protocol.v2.ReceiptStatus
import dev.vibris.protocol.v2.RestorationReceipt

/** A retained Core-observed snapshot used by every mutating strict-v2 job. */
internal class BenchmarkCaseIsolation private constructor(
    val snapshot: Snapshot,
    private val restoreOnSuccess: Boolean,
    private val restoreOnError: Boolean,
    private val mutatesRuntime: Boolean,
) {
    private var retainedReleased = false

    fun shouldRestore(successful: Boolean): Boolean = mutatesRuntime &&
        if (successful) restoreOnSuccess else restoreOnError

    fun release(activator: SourceActivator) {
        if (retainedReleased) return
        snapshot.source?.let(activator::releaseRetained)
        retainedReleased = true
    }

    fun successReceipt(actual: Snapshot, temporalStateReset: Boolean): RestorationReceipt =
        receipt(snapshot, actual, temporalStateReset, null)

    fun currentReceipt(actual: Snapshot): RestorationReceipt =
        receipt(actual, actual, false, null)

    fun failureReceipt(
        actual: Snapshot,
        code: ErrorCode,
        message: String,
        temporalStateReset: Boolean,
    ): RestorationReceipt = receipt(
        snapshot,
        actual,
        temporalStateReset,
        ProtocolError.newBuilder()
            .setCode(code)
            .setMessage(message)
            .setRetryable(true)
            .putDetails("manual_recovery", MANUAL_RECOVERY)
            .build(),
    )

    data class Snapshot(
        val source: SourceRegistry.Lease?,
        val shaderSettings: EffectiveShaderSettings?,
        val scene: SceneContext?,
    )

    companion object {
        const val MANUAL_RECOVERY =
            "Keep the current runtime open. After any in-flight recovery operation reaches its safe point, repair the Vibris shader link or runtime bridge and submit recover_runtime again."
        const val BOOTSTRAP_RECOVERY =
            "Submit one load_shader action with restore_state.on_success=false and restore_state.on_error=false to establish the first Core-owned safe snapshot, then retry the transactional job."

        @Throws(RuntimeJobExecutor.Failure::class)
        fun begin(
            job: CoreJob,
            activator: SourceActivator,
            shaderSettings: EffectiveShaderSettings?,
            scene: SceneContext?,
        ): BenchmarkCaseIsolation {
            val workloadMutates = when (job.submission.workloadCase) {
                JobSpec.WorkloadCase.BENCHMARK,
                JobSpec.WorkloadCase.MATRIX,
                JobSpec.WorkloadCase.COMPILE_VALIDATION,
                -> true
                else -> false
            }
            val changesSourceSettingsOrScene = workloadMutates || job.submission.actionSequence.actionsList.any { action ->
                action.hasActivateSource() || action.hasLoadShader() || action.hasCameraMotion()
            }
            val mutates = changesSourceSettingsOrScene || job.submission.actionSequence.actionsList.any { action ->
                action.hasResetTemporalState()
            }
            val restoreOnSuccess = job.submission.restoreState.onSuccess
            val restoreOnError = job.submission.restoreState.onError
            val source = try {
                activator.retainActive()
            } catch (failure: SourceActivator.Failure) {
                throw RuntimeJobExecutor.Failure(failure.code, failure.message)
            }
            val snapshot = Snapshot(source, shaderSettings, scene)
            if (changesSourceSettingsOrScene && (restoreOnSuccess || restoreOnError) &&
                (snapshot.scene == null || snapshot.source != null && snapshot.shaderSettings == null)
            ) {
                source?.let(activator::releaseRetained)
                val message = "Transactional runtime state is unavailable: establish a verified source, settings, and scene snapshot before requesting restoration. $BOOTSTRAP_RECOVERY"
                throw RuntimeJobExecutor.Failure(
                    ErrorCode.ERROR_CODE_RESTORE_FAILED,
                    message,
                    restoration = receipt(
                        snapshot,
                        snapshot,
                        false,
                        ProtocolError.newBuilder()
                            .setCode(ErrorCode.ERROR_CODE_RESTORE_FAILED)
                            .setMessage(message)
                            .setRetryable(false)
                            .putDetails("manual_recovery", BOOTSTRAP_RECOVERY)
                            .build(),
                    ),
                )
            }
            return BenchmarkCaseIsolation(snapshot, restoreOnSuccess, restoreOnError, mutates)
        }

        fun noMutationReceipt(actual: Snapshot): RestorationReceipt = receipt(actual, actual, false, null)

        fun recoveryFailureReceipt(actual: Snapshot, message: String): RestorationReceipt = receipt(
            actual,
            actual,
            false,
            ProtocolError.newBuilder()
                .setCode(ErrorCode.ERROR_CODE_RECOVERY_FAILED)
                .setMessage(message)
                .setRetryable(true)
                .putDetails("manual_recovery", MANUAL_RECOVERY)
                .build(),
        )

        private fun receipt(
            expected: Snapshot,
            actual: Snapshot,
            temporalStateReset: Boolean,
            error: ProtocolError?,
        ): RestorationReceipt {
            val builder = RestorationReceipt.newBuilder()
                .setStatus(if (error == null) ReceiptStatus.RECEIPT_STATUS_OK else ReceiptStatus.RECEIPT_STATUS_FAILED)
                .setExpectedSourceUuid(expected.source?.uuid.orEmpty())
                .setActualSourceUuid(actual.source?.uuid.orEmpty())
                .setExpectedSourceSha256(expected.source?.snapshotSha256.orEmpty())
                .setActualSourceSha256(actual.source?.snapshotSha256.orEmpty())
                .setExpectedSettingsSha256(expected.shaderSettings?.settingsSha256.orEmpty())
                .setActualSettingsSha256(actual.shaderSettings?.settingsSha256.orEmpty())
                .setExpectedSceneSha256(expected.scene?.let(BenchmarkProvenance::sceneHash).orEmpty())
                .setActualSceneSha256(actual.scene?.let(BenchmarkProvenance::sceneHash).orEmpty())
                .setTemporalStateReset(temporalStateReset)
                .setVerifiedAtUnixMs(System.currentTimeMillis())
            error?.let(builder::setError)
            return builder.build()
        }
    }
}