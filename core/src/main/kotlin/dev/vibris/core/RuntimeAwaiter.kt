package dev.vibris.core

import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobResult
import dev.vibris.protocol.v2.JobTimings
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class RuntimeAwaiter(private val probe: CoreProbe) {
    @Throws(RuntimeJobExecutor.Failure::class)
    fun <T> capture(stage: CompletionStage<T>, job: CoreJob, deadline: Long): T =
        await(stage, job, deadline, ErrorCode.ERROR_CODE_CAPTURE_FAILED)

    @Throws(RuntimeJobExecutor.Failure::class)
    fun <T> await(stage: CompletionStage<T>, job: CoreJob, deadline: Long): T =
        await(stage, job, deadline, ErrorCode.ERROR_CODE_INTERNAL)

    fun withTimings(job: CoreJob, result: JobResult, startedAtUnixMs: Long, startedNanos: Long): JobResult {
        val completedAtUnixMs = System.currentTimeMillis()
        val executionMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        val queueMs = TimeUnit.NANOSECONDS.toMillis(startedNanos - job.acceptedNanos)
        return result.toBuilder()
            .setTimings(
                JobTimings.newBuilder()
                    .setAcceptedAtUnixMs(job.acceptedAtUnixMs)
                    .setStartedAtUnixMs(startedAtUnixMs)
                    .setCompletedAtUnixMs(completedAtUnixMs)
                    .addAllExecutionSpans(job.executionSpans())
                    .setQueueMs(queueMs)
                    .setExecutionMs(executionMs)
                    .setTotalMs(Math.addExact(queueMs, executionMs)),
            )
            .build()
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    private fun <T> await(
        stage: CompletionStage<T>,
        job: CoreJob,
        deadline: Long,
        operationFailureCode: ErrorCode,
    ): T {
        val future = stage.toCompletableFuture()
        try {
            if (job.cancellation.token().isCancellationRequested()) {
                throw CancellationException()
            }
            val remaining = if (deadline == Long.MAX_VALUE) Long.MAX_VALUE else deadline - System.nanoTime()
            if (remaining <= 0) {
                throw TimeoutException()
            }
            return if (remaining == Long.MAX_VALUE) future.get() else future.get(remaining, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            job.cancellation.cancel()
            awaitCancellation(future)
            probe.event(job.requestId, "SAFE_POINT_TIMEOUT")
            throw RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_EXECUTION_TIMEOUT, "Job execution timed out.")
        } catch (_: InterruptedException) {
            job.cancellation.cancel()
            awaitCancellation(future)
            Thread.currentThread().interrupt()
            throw RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_CANCELLED, "Job execution was interrupted.")
        } catch (_: CancellationException) {
            awaitCancellation(future)
            throw RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_CANCELLED, "Job execution was cancelled.")
        } catch (exception: ExecutionException) {
            throw operationFailure(exception.cause, job, operationFailureCode)
        } catch (exception: CompletionException) {
            throw operationFailure(exception.cause, job, operationFailureCode)
        }
    }

    private fun operationFailure(
        cause: Throwable?,
        job: CoreJob,
        code: ErrorCode,
    ): RuntimeJobExecutor.Failure {
        if (cause is CancellationException || job.cancellation.token().isCancellationRequested()) {
            return RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_CANCELLED, "Job execution was cancelled.")
        }
        return if (code == ErrorCode.ERROR_CODE_CAPTURE_FAILED) {
            CaptureJobExecutor.failure(cause)
        } else {
            RuntimeJobExecutor.Failure(code, "Runtime adapter operation failed.")
        }
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    private fun awaitCancellation(future: CompletableFuture<*>) {
        try {
            future.join()
        } catch (_: CancellationException) {
        } catch (_: CompletionException) {
        }
    }
}