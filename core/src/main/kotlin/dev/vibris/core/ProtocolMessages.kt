package dev.vibris.core

import dev.vibris.core.request.RequestState
import dev.vibris.protocol.v2.ActionReceipt
import dev.vibris.protocol.v2.ArtifactMetadata
import dev.vibris.protocol.v2.ClientMessage
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobAccepted
import dev.vibris.protocol.v2.JobFailed
import dev.vibris.protocol.v2.JobProgress
import dev.vibris.protocol.v2.JobStage
import dev.vibris.protocol.v2.JobState
import dev.vibris.protocol.v2.ProtocolError
import dev.vibris.protocol.v2.ProtocolVersion
import dev.vibris.protocol.v2.RestorationReceipt
import dev.vibris.protocol.v2.ServerMessage

internal object ProtocolMessages {
    @JvmField
    val V2: ProtocolVersion = ProtocolVersion.newBuilder().setMajor(3).setMinor(0).build()

    @JvmStatic
    fun accepted(job: CoreJob, queuePosition: Int): ServerMessage =
        envelope(job.messageId, job.requestId, job.workspaceId)
            .setJobAccepted(
                JobAccepted.newBuilder()
                    .setJobId(job.submission.jobId)
                    .setRequestId(job.requestId)
                    .setQueuePosition(queuePosition)
                    .setAcceptedAtUnixMs(job.acceptedAtUnixMs),
            )
            .build()

    @JvmStatic
    fun progress(job: CoreJob, stage: JobStage): ServerMessage =
        envelope(job.messageId, job.requestId, job.workspaceId)
            .setJobProgress(
                JobProgress.newBuilder()
                    .setJobId(job.submission.jobId)
                    .setRequestId(job.requestId)
                    .setStage(stage),
            )
            .build()

    @JvmStatic
    fun failure(
        jobId: String,
        requestId: String,
        code: ErrorCode,
        message: String,
    ): TerminalResult = failure(jobId, requestId, code, message, emptyList(), null)

    @JvmStatic
    fun failure(
        jobId: String,
        requestId: String,
        code: ErrorCode,
        message: String,
        artifacts: List<ArtifactMetadata>,
    ): TerminalResult = failure(jobId, requestId, code, message, artifacts, null)

    @JvmStatic
    fun failure(
        jobId: String,
        requestId: String,
        code: ErrorCode,
        message: String,
        artifacts: List<ArtifactMetadata>,
        restoration: RestorationReceipt?,
    ): TerminalResult = failure(
        jobId,
        requestId,
        code,
        message,
        artifacts,
        restoration,
        emptyList(),
        emptyList(),
    )

    @JvmStatic
    fun failure(
        jobId: String,
        requestId: String,
        code: ErrorCode,
        message: String,
        artifacts: List<ArtifactMetadata>,
        restoration: RestorationReceipt?,
        actionReceipts: List<ActionReceipt>,
        preludeReceipts: List<ActionReceipt>,
    ): TerminalResult {
        val error = error(code, message)
        if (artifacts.isNotEmpty()) {
            error.setLogPath(artifacts.first().relativePath)
        }
        if (code == ErrorCode.ERROR_CODE_RESTORE_FAILED || code == ErrorCode.ERROR_CODE_RECOVERY_FAILED) {
            error.putDetails(
                "manual_recovery",
                restoration?.takeIf { it.hasError() }
                    ?.error?.detailsMap?.get("manual_recovery")
                    ?: BenchmarkCaseIsolation.MANUAL_RECOVERY,
            )
        }
        val failed = JobFailed.newBuilder()
            .setJobId(jobId)
            .setRequestId(requestId)
            .setError(error)
            .addAllArtifacts(artifacts)
            .addAllActionReceipts(actionReceipts)
            .addAllPreludeReceipts(preludeReceipts)
        restoration?.let(failed::setRestoration)
        return TerminalResult.failed(failed.build())
    }

    @JvmStatic
    fun immediateFailure(
        message: ClientMessage,
        workspaceId: String,
        code: ErrorCode,
        detail: String,
    ): ServerMessage =
        failure(
            message.takeIf { it.hasSubmitJob() && it.submitJob.hasJob() }
                ?.submitJob?.job?.jobId
                ?.takeIf(String::isNotBlank)
                ?: message.requestId,
            message.requestId,
            code,
            detail,
        )
            .message(message.messageId, message.requestId, workspaceId)

    @JvmStatic
    fun jobState(state: RequestState): JobState =
        when (state) {
            RequestState.ACCEPTED -> JobState.JOB_STATE_QUEUED
            RequestState.RUNNING -> JobState.JOB_STATE_RUNNING
            RequestState.COMPLETED -> JobState.JOB_STATE_COMPLETED
            RequestState.FAILED -> JobState.JOB_STATE_FAILED
            RequestState.CANCELLED -> JobState.JOB_STATE_CANCELLED
        }

    @JvmStatic
    fun envelope(messageId: String, requestId: String, workspaceId: String): ServerMessage.Builder =
        ServerMessage.newBuilder()
            .setProtocolVersion(V2)
            .setMessageId(messageId)
            .setRequestId(requestId)
            .setWorkspaceId(workspaceId)

    @JvmStatic
    fun error(code: ErrorCode, message: String): ProtocolError.Builder = ProtocolError.newBuilder()
        .setCode(code)
        .setMessage(message)
        .setRetryable(
            code == ErrorCode.ERROR_CODE_QUEUE_FULL ||
                code == ErrorCode.ERROR_CODE_QUEUE_TIMEOUT ||
                code == ErrorCode.ERROR_CODE_EXECUTION_TIMEOUT ||
                code == ErrorCode.ERROR_CODE_SERVER_RESTARTED,
        )
}
