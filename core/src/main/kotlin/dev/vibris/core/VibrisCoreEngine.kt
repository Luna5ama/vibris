package dev.vibris.core

import dev.vibris.api.RuntimeStatus
import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.core.request.RequestRegistry
import dev.vibris.core.request.RequestState
import dev.vibris.protocol.v2.ClientMessage
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobStage
import dev.vibris.protocol.v2.JobState
import dev.vibris.protocol.v2.JobSummary
import dev.vibris.protocol.v2.QueueEntry
import dev.vibris.protocol.v2.RuntimeFailure
import dev.vibris.protocol.v2.RuntimeLease
import dev.vibris.protocol.v2.RuntimePhase
import dev.vibris.protocol.v2.RuntimeRecovery
import dev.vibris.protocol.v2.RestartPhase
import dev.vibris.protocol.v2.RestartStatus
import dev.vibris.protocol.v2.ServerMessage
import dev.vibris.protocol.v2.ServerState
import dev.vibris.protocol.v2.StateTransition
import dev.vibris.protocol.v2.StatusWaitCondition
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class VibrisCoreEngine internal constructor(
    pendingRoot: Path,
    private val runtime: VibrisRuntimeAdapter,
    shaderLink: ShaderLink,
    shaderLogs: ShaderLogSink,
    maxSourceBytes: Long = ServerConfiguration.DEFAULT_MAX_SOURCE_BYTES,
    maxSourceFiles: Int = ServerConfiguration.DEFAULT_MAX_SOURCE_FILES,
    maxGlobalQueue: Int = ServerConfiguration.DEFAULT_MAX_GLOBAL_QUEUE,
    maxActionsPerJob: Int = ServerConfiguration.DEFAULT_MAX_ACTIONS_PER_JOB,
    replayCaptureRoot: Path? = null,
    replayerRoot: Path? = null,
    bundledReplayJava: Path? = null,
    private val jobActivityObserver: JobActivityObserver = JobActivityObserver.none(),
) : AutoCloseable {
    private val requests = RequestRegistry<TerminalResult>(
        LIVE_REQUEST_CAPACITY,
        TERMINAL_REQUEST_CAPACITY,
        TERMINAL_TTL,
        Clock.systemUTC(),
    )
    private val liveJobs = HashMap<String, CoreJob>()
    private val terminalJobs = LinkedHashMap<String, JobSummary>()
    private val scheduler = FairJobScheduler(maxGlobalQueue, ::schedulerChanged)
    private val probe = CoreProbe()
    private val sources = SourceRegistry(pendingRoot, probe, maxSourceBytes, maxSourceFiles)
    private val activator = SourceActivator(sources, shaderLink)
    private val executor = RuntimeJobExecutor(
        runtime,
        probe,
        activator,
        shaderLogs,
        maxActionsPerJob,
        replayCaptureRoot = replayCaptureRoot,
        replayerRoot = replayerRoot,
        bundledReplayJava = bundledReplayJava,
    )
    private val delivery = TerminalDelivery()
    private val disconnectTimer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Vibris Disconnect Grace").apply { isDaemon = true }
    }
    private val transitions = ArrayDeque<StateTransition>()
    private var transitionSequence = 0L
    private var statusRevision = 0L
    private var observedServerState = ServerState.SERVER_STATE_STARTING
    private var observedRuntimePhase = RuntimePhase.RUNTIME_PHASE_CONNECTING
    private var lastError: RuntimeFailure? = null
    private var runtimeStatus = RuntimeStatus(false, "", "", "")
    private var runtimeObserved = false
    private var closed = false
    private var activeRequestId = ""
    private var activeStage = JobStage.JOB_STAGE_UNSPECIFIED
    private var restartStatus: RestartStatus? = null
    private var terminalDeliveriesInFlight = 0

    constructor(pendingRoot: Path, runtime: VibrisRuntimeAdapter) :
        this(pendingRoot, runtime, ShaderLink.transientLink(), ShaderLogSink.none())

    init {
        updateMetrics()
        synchronized(this) { stateChangedLocked("core-started") }
    }

    fun probe(): CoreProbe = probe

    @Synchronized
    fun ready(): Boolean = !closed && activator.ready()

    @Synchronized
    fun activeSourceUuid(): String = sources.activeUuid()

    @Synchronized
    fun queueLength(): Int = scheduler.size()

    @Synchronized
    fun canAcceptJob(): Boolean = projectionLocked().canAcceptJob

    @Synchronized
    internal fun requestRestart(workspaceId: String, reason: String): RestartRequestResult {
        val existing = restartStatus
        if (existing != null) return RestartRequestResult(restartSnapshotLocked(existing), true)
        val scheduled = RestartStatus.newBuilder()
            .setPhase(RestartPhase.RESTART_PHASE_DRAINING)
            .setRequestedByWorkspaceId(workspaceId)
            .setRequestedAtUnixMs(System.currentTimeMillis())
            .setReason(reason.take(512))
            .setRetryAfterMs(RESTART_RETRY_AFTER_MS)
            .build()
        restartStatus = scheduled
        stateChangedLocked("restart-scheduled")
        return RestartRequestResult(restartSnapshotLocked(scheduled), false)
    }

    @Throws(InterruptedException::class)
    internal fun awaitRestartDrain(): Boolean = synchronized(this) {
        while (!closed && restartStatus != null &&
            (liveJobs.isNotEmpty() || terminalDeliveriesInFlight != 0)
        ) {
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (this as java.lang.Object).wait()
        }
        !closed && restartStatus != null
    }

    @Synchronized
    internal fun markRestartLaunching(): RestartStatus? {
        val current = restartStatus ?: return null
        val launching = current.toBuilder()
            .setPhase(RestartPhase.RESTART_PHASE_LAUNCHING)
            .setRemainingJobs(0)
            .build()
        restartStatus = launching
        stateChangedLocked("restart-launching")
        return launching
    }

    @Synchronized
    internal fun restartFailed(detail: String) {
        if (restartStatus == null) return
        restartStatus = null
        lastError = runtimeFailure(
            ErrorCode.ERROR_CODE_INTERNAL,
            "Scheduled Minecraft restart could not be launched: ${detail.take(384)}",
            "",
            "",
        )
        stateChangedLocked("restart-launch-failed")
    }

    @Synchronized
    internal fun activeJob(): ActiveJob? = scheduler.snapshot().active?.let { active ->
        ActiveJob(active.metadata.requestId, currentStage(active.metadata.requestId))
    }

    @Synchronized
    internal fun observeRuntimeStatus(status: RuntimeStatus, unavailableDetail: String = "") {
        val changed = !runtimeObserved || runtimeStatus != status
        runtimeObserved = true
        runtimeStatus = status
        if (!status.ready && unavailableDetail.isNotBlank()) {
            lastError = runtimeFailure(
                ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE,
                unavailableDetail,
                "",
                "",
            )
        }
        if (changed) {
            stateChangedLocked(if (status.ready) "runtime-available" else "runtime-unavailable")
        }
    }

    @Synchronized
    internal fun statusSnapshot(): StatusSnapshot {
        val schedulerSnapshot = scheduler.snapshot()
        val projection = projectionLocked(schedulerSnapshot)
        val active = schedulerSnapshot.active
        val activeJob = active?.metadata?.requestId?.let(liveJobs::get)
        val lease = active?.let {
            RuntimeLease.newBuilder()
                .setLeaseId(leaseId(it))
                .setWorkspaceId(it.metadata.workspaceId)
                .setWorktreeRoot(it.metadata.worktreeRoot)
                .setJobId(it.metadata.jobId)
                .setRequestId(it.metadata.requestId)
                .setOperation(it.metadata.operation)
                .setStage(currentStage(it.metadata.requestId))
                .setStartedAtUnixMs(it.startedAtUnixMs)
                .setCancellationRequested(activeJob?.cancellation?.token()?.isCancellationRequested() == true)
                .build()
        }
        val queue = schedulerSnapshot.queued.map { queued ->
            QueueEntry.newBuilder()
                .setJobId(queued.metadata.jobId)
                .setWorkspaceId(queued.metadata.workspaceId)
                .setOperation(queued.metadata.operation)
                .setPosition(queued.position)
                .setQueuedAtUnixMs(queued.metadata.queuedAtUnixMs)
                .build()
        }
        val jobs = ArrayList<JobSummary>()
        if (active != null) {
            jobs.add(liveSummary(active.metadata, activeJob, currentStage(active.metadata.requestId), true))
        }
        schedulerSnapshot.queued.forEach { queued ->
            jobs.add(
                liveSummary(
                    queued.metadata,
                    liveJobs[queued.metadata.requestId],
                    JobStage.JOB_STAGE_QUEUED,
                    false,
                ),
            )
        }
        jobs.addAll(terminalJobs.values)
        return StatusSnapshot(
            projection.state,
            projection.phase,
            projection.coreOnline,
            runtimeStatus,
            projection.canAcceptJob,
            projection.canStartJob,
            lease,
            executor.recoveryStatus(),
            queue,
            jobs,
            lastError,
            transitions.toList(),
            sources.activeUuid().ifBlank { runtimeStatus.activeSourceUuid },
            restartStatus?.let(::restartSnapshotLocked),
            statusRevision,
        )
    }

    @Throws(InterruptedException::class)
    internal fun awaitStatus(
        condition: StatusWaitCondition,
        jobId: String,
        timeoutMs: Long,
    ): WaitResult = synchronized(this) {
        if (conditionSatisfiedLocked(condition, jobId)) return@synchronized WaitResult(true, false)
        if (timeoutMs <= 0) return@synchronized WaitResult(false, true)
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val started = System.nanoTime()
        while (true) {
            val elapsed = System.nanoTime() - started
            val remaining = timeoutNanos - elapsed
            if (remaining <= 0) return@synchronized WaitResult(false, true)
            val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
            val nanos = (remaining - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (this as java.lang.Object).wait(millis, nanos)
            if (conditionSatisfiedLocked(condition, jobId)) return@synchronized WaitResult(true, false)
        }
        @Suppress("UNREACHABLE_CODE")
        WaitResult(false, true)
    }

    internal fun submit(session: ControlSession, message: ClientMessage) {
        if (!message.submitJob.hasJob()) {
            failImmediate(session, message, ErrorCode.ERROR_CODE_INVALID_REQUEST, "Job specification is required.")
            return
        }
        val submission = message.submitJob.job
        val requestId = message.requestId
        if (requestId.isBlank() || submission.jobId.isBlank() || message.workspaceId != session.workspaceId()) {
            failImmediate(session, message, ErrorCode.ERROR_CODE_INVALID_REQUEST, "Job envelope is inconsistent.")
            return
        }
        var candidates: List<SourceRegistry.Candidate> = emptyList()
        var validationFailure: SourceRegistry.Failure? = null
        val recovery = submission.hasRecoverRuntime()
        if (!recovery) {
            try {
                candidates = sources.validate(submission.sourcesList, submission.sourcesCount)
            } catch (failure: SourceRegistry.Failure) {
                validationFailure = failure
            }
        } else if (submission.sourcesCount != 0) {
            validationFailure = SourceRegistry.Failure(
                ErrorCode.ERROR_CODE_INVALID_REQUEST,
                "recover_runtime must not carry prepared shader sources.",
            )
        }
        val job = CoreJob(submission, requestId, session.workspaceId(), message.messageId, session)
        synchronized(this) {
            if (restartStatus != null) {
                failImmediate(
                    session,
                    message,
                    ErrorCode.ERROR_CODE_SERVER_RESTARTED,
                    "A graceful Minecraft restart is scheduled; wait for the replacement runtime and retry.",
                )
                return
            }
            if (closed || !activator.ready() && !recovery) {
                failImmediate(session, message, ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE, "Vibris is not ready.")
                return
            }
            val accepted = requests.accept(requestId, session.workspaceId())
            if (accepted.kind == RequestRegistry.AcceptKind.OWNER_MISMATCH) {
                failImmediate(session, message, ErrorCode.ERROR_CODE_INTERNAL, "Request belongs to another workspace.")
                return
            }
            if (accepted.kind == RequestRegistry.AcceptKind.CACHED_FINAL) {
                delivery.send(
                    session,
                    accepted.snapshot!!.result!!.message(message.messageId, requestId, session.workspaceId()),
                )
                return
            }
            if (accepted.kind == RequestRegistry.AcceptKind.CURRENT) {
                sendCurrent(session, message, accepted.snapshot!!.state)
                return
            }
            if (accepted.kind == RequestRegistry.AcceptKind.FULL) {
                failImmediate(session, message, ErrorCode.ERROR_CODE_QUEUE_FULL, "The request registry is full.")
                return
            }
            if (validationFailure != null) {
                finishRejected(session, message, validationFailure.code, validationFailure.message!!)
                return
            }
            val leases: List<SourceRegistry.Lease>
            try {
                leases = sources.reserve(candidates)
            } catch (failure: SourceRegistry.Failure) {
                finishRejected(session, message, failure.code, failure.message!!)
                return
            }
            liveJobs[requestId] = job
            val scheduled = scheduler.submit(metadata(job), Runnable { execute(job) })
            if (!scheduled.accepted) {
                liveJobs.remove(requestId)
                sources.reject(leases)
                finishRejected(session, message, ErrorCode.ERROR_CODE_QUEUE_FULL, "The global execution queue is full.")
                return
            }
            sources.accept(leases)
            session.send(ProtocolMessages.accepted(job, scheduled.position))
            job.initialize(leases)
            stateChangedLocked("job-queued", submission.jobId)
            updateMetrics()
        }
    }

    internal fun cancel(session: ControlSession, jobId: String) {
        val queued = synchronized(this) {
            val current = liveJobs.values.firstOrNull { it.submission.jobId == jobId }
                ?: return@synchronized null
            if (current.workspaceId != session.workspaceId()) return@synchronized null
            current.bind(session)
            current.cancellation.cancel()
            stateChangedLocked("cancellation-requested", current.submission.jobId)
            if (scheduler.cancel(current.requestId)) current else null
        }
        if (queued != null) {
            finish(
                queued,
                ProtocolMessages.failure(
                    queued.submission.jobId,
                    queued.requestId,
                    ErrorCode.ERROR_CODE_CANCELLED,
                    "Job was cancelled.",
                    emptyList(),
                    executor.restorationReceipt(),
                ),
                RequestState.CANCELLED,
                false,
            )
        }
    }

    internal fun resume(session: ControlSession, message: ClientMessage) {
        val responses: List<ServerMessage>
        synchronized(this) {
            responses = ResumeResponses.create(session, message, requests, liveJobs)
        }
        for (response in responses) {
            delivery.send(session, response)
        }
    }

    internal fun disconnected(session: ControlSession) {
        session.disconnect()
        synchronized(this) {
            for (job in liveJobs.values) {
                if (!job.stillOwnedBy(session)) continue
                job.scheduleDisconnectCancellation(
                    disconnectTimer.schedule(
                        { cancelDisconnected(job, session) },
                        DISCONNECT_GRACE.toMillis(),
                        TimeUnit.MILLISECONDS,
                    ),
                )
            }
        }
    }

    private fun cancelDisconnected(job: CoreJob, session: ControlSession) {
        synchronized(this) {
            if (!liveJobs.containsKey(job.requestId) || !job.stillOwnedBy(session)) return
        }
        cancel(session, job.submission.jobId)
    }

    private fun execute(job: CoreJob) {
        var started = false
        try {
            job.awaitReady()
            synchronized(this) {
                if (!liveJobs.containsKey(job.requestId)) return
                if (queueTimedOut(job)) {
                    finish(
                        job,
                        ProtocolMessages.failure(
                            job.submission.jobId,
                            job.requestId,
                            ErrorCode.ERROR_CODE_QUEUE_TIMEOUT,
                            "Job expired in the execution queue.",
                            emptyList(),
                            executor.restorationReceipt(),
                        ),
                        RequestState.FAILED,
                        false,
                    )
                    return
                }
                if (!job.submission.hasRecoverRuntime() &&
                    (executor.hasPendingRecovery() || !activator.ready())
                ) {
                    finish(
                        job,
                        ProtocolMessages.failure(
                            job.submission.jobId,
                            job.requestId,
                            ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE,
                            "Vibris entered recovery before this queued job could start.",
                            emptyList(),
                            executor.restorationReceipt(),
                        ),
                        RequestState.FAILED,
                        false,
                    )
                    return
                }
                requests.markRunning(job.requestId)
                activeRequestId = job.requestId
                activeStage = JobStage.JOB_STAGE_UNSPECIFIED
                stateChangedLocked("lease-acquired", job.submission.jobId)
            }
            started = true
            notifyJobActivity(true)
            probe.jobStarted(job.requestId)
            probe.event(job.requestId, "ACQUIRED_LEASE")
            val terminal = executor.execute(job) { stage -> sendProgress(job, stage) }
            finish(job, terminal, RequestState.COMPLETED, true)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            finish(
                job,
                ProtocolMessages.failure(
                    job.submission.jobId,
                    job.requestId,
                    ErrorCode.ERROR_CODE_CANCELLED,
                    "Job execution was interrupted.",
                    emptyList(),
                    executor.restorationReceipt(),
                ),
                RequestState.CANCELLED,
                false,
            )
        } catch (_: CancellationException) {
            finish(
                job,
                ProtocolMessages.failure(
                    job.submission.jobId,
                    job.requestId,
                    ErrorCode.ERROR_CODE_CANCELLED,
                    "Job execution was cancelled.",
                    emptyList(),
                    executor.restorationReceipt(),
                ),
                RequestState.CANCELLED,
                false,
            )
        } catch (failure: RuntimeJobExecutor.Failure) {
            val state = if (failure.code == ErrorCode.ERROR_CODE_CANCELLED) {
                RequestState.CANCELLED
            } else {
                RequestState.FAILED
            }
            finish(
                job,
                ProtocolMessages.failure(
                    job.submission.jobId,
                    job.requestId,
                    failure.code,
                    failure.message!!,
                    failure.artifacts,
                    failure.restoration ?: executor.restorationReceipt(),
                    failure.actionReceipts,
                    failure.preludeReceipts,
                ),
                state,
                false,
                !failure.holdOwnership,
            )
        } finally {
            if (started) {
                try {
                    probe.jobStopped()
                } finally {
                    notifyJobActivity(false)
                }
            }
            Thread.interrupted()
            updateMetrics()
        }
    }

    private fun notifyJobActivity(active: Boolean) {
        try {
            jobActivityObserver.activityChanged(active)
        } catch (_: Exception) {
            // Observability must never affect job execution or terminal delivery.
        }
    }

    private fun finish(
        job: CoreJob,
        terminal: TerminalResult,
        state: RequestState,
        successful: Boolean,
        releaseSources: Boolean = true,
    ) {
        val session = synchronized(this) {
            if (liveJobs.remove(job.requestId) == null) return
            if (releaseSources) {
                activator.release(job.sources)
            }
            requests.finish(job.requestId, state, terminal)
            terminalJobs[job.submission.jobId] = terminalSummary(job, state)
            while (terminalJobs.size > TERMINAL_JOB_CAPACITY) {
                terminalJobs.remove(terminalJobs.keys.iterator().next())
            }
            if (activeRequestId == job.requestId) {
                activeRequestId = ""
                activeStage = JobStage.JOB_STAGE_UNSPECIFIED
            }
            val failure = terminal.failed?.error?.let { error ->
                runtimeFailure(error.code, error.message, job.requestId, error.logPath)
            }
            if (failure != null) lastError = failure
            stateChangedLocked(
                if (successful) "job-completed" else if (state == RequestState.CANCELLED) {
                    "job-cancelled"
                } else {
                    "job-failed"
                },
                job.submission.jobId,
            )
            updateMetrics()
            terminalDeliveriesInFlight++
            job.session!!
        }
        probe.event(
            job.requestId,
            if (successful) "SUCCEEDED" else if (state == RequestState.CANCELLED) "CANCELLED" else "FAILED",
        )
        try {
            delivery.send(
                session,
                terminal.message(job.messageId, job.requestId, job.workspaceId),
            )
        } finally {
            synchronized(this) {
                terminalDeliveriesInFlight--
                stateChangedLocked("terminal-delivered", job.submission.jobId)
            }
        }
    }

    private fun finishRejected(
        session: ControlSession,
        message: ClientMessage,
        code: ErrorCode,
        detail: String,
    ) {
        val jobId = message.submitJob.job.jobId.ifBlank { message.requestId }
        val terminal = ProtocolMessages.failure(
            jobId,
            message.requestId,
            code,
            detail,
            emptyList(),
            executor.restorationReceipt(),
        )
        synchronized(this) {
            requests.finish(message.requestId, RequestState.FAILED, terminal)
            terminalJobs[jobId] = rejectedSummary(message, jobId)
            lastError = runtimeFailure(code, detail, message.requestId, terminal.failed?.error?.logPath.orEmpty())
            stateChangedLocked("job-rejected", jobId)
            updateMetrics()
        }
        session.send(terminal.message(message.messageId, message.requestId, session.workspaceId()))
    }

    private fun sendCurrent(session: ControlSession, message: ClientMessage, state: RequestState) {
        val job = liveJobs[message.requestId]
        if (job == null) {
            failImmediate(session, message, ErrorCode.ERROR_CODE_INTERNAL, "Request validation is still in progress.")
            return
        }
        job.bind(session)
        val position = scheduler.snapshot().queued
            .firstOrNull { it.metadata.requestId == job.requestId }
            ?.position
            ?: 0
        session.send(ProtocolMessages.accepted(job, position))
        if (state == RequestState.RUNNING) session.send(ProtocolMessages.progress(job, activeStage))
    }

    private fun sendProgress(job: CoreJob, stage: JobStage) {
        synchronized(this) {
            if (activeRequestId == job.requestId) {
                activeStage = stage
                stateChangedLocked("stage-${stage.name.lowercase()}", job.submission.jobId)
            }
        }
        job.session!!.send(ProtocolMessages.progress(job, stage))
    }

    private fun queueTimedOut(job: CoreJob): Boolean {
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - job.acceptedNanos)
        val queue = job.submission.timeouts.queueTimeoutMs
        val total = job.submission.timeouts.totalTimeoutMs
        return queue > 0 && elapsed >= queue || total > 0 && elapsed >= total
    }

    private fun schedulerChanged() {
        synchronized(this) { stateChangedLocked("scheduler-state") }
    }

    private fun conditionSatisfiedLocked(condition: StatusWaitCondition, jobId: String): Boolean = when (condition) {
        StatusWaitCondition.STATUS_WAIT_CONDITION_CAN_ACCEPT_JOB -> projectionLocked().canAcceptJob
        StatusWaitCondition.STATUS_WAIT_CONDITION_JOB_TERMINAL -> terminalJobs[jobId]?.state?.terminal() == true
        else -> true
    }

    private fun stateChangedLocked(reason: String, jobId: String = "") {
        val projection = projectionLocked()
        if (projection.state != observedServerState || projection.phase != observedRuntimePhase) {
            transitions.addLast(
                StateTransition.newBuilder()
                    .setSequence(++transitionSequence)
                    .setOccurredAtUnixMs(System.currentTimeMillis())
                    .setFromState(observedServerState)
                    .setToState(projection.state)
                    .setRuntimePhase(projection.phase)
                    .setJobId(jobId)
                    .setReason(reason.take(256))
                    .build(),
            )
            while (transitions.size > TRANSITION_CAPACITY) transitions.removeFirst()
            observedServerState = projection.state
            observedRuntimePhase = projection.phase
        }
        statusRevision++
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (this as java.lang.Object).notifyAll()
    }

    private fun projectionLocked(schedulerSnapshot: FairJobScheduler.Snapshot = scheduler.snapshot()): Projection {
        val recoveryPending = executor.hasPendingRecovery()
        val recoveryFailed = executor.hasFailedRecovery()
        val coreOnline = !closed && (activator.ready() || recoveryPending)
        val active = schedulerSnapshot.active
        val hasWork = active != null || schedulerSnapshot.queued.isNotEmpty() || recoveryPending
        val phase = when {
            closed -> RuntimePhase.RUNTIME_PHASE_DISCONNECTED
            recoveryFailed && active == null -> RuntimePhase.RUNTIME_PHASE_FAILED
            !coreOnline -> RuntimePhase.RUNTIME_PHASE_FAILED
            recoveryPending && active == null -> RuntimePhase.RUNTIME_PHASE_RECOVERING
            active != null -> runtimePhase(currentStage(active.metadata.requestId))
            schedulerSnapshot.queued.isNotEmpty() -> RuntimePhase.RUNTIME_PHASE_EXECUTING
            !runtimeObserved -> RuntimePhase.RUNTIME_PHASE_CONNECTING
            runtimeStatus.ready -> RuntimePhase.RUNTIME_PHASE_AVAILABLE
            else -> RuntimePhase.RUNTIME_PHASE_DISCONNECTED
        }
        val state = when {
            closed -> ServerState.SERVER_STATE_STOPPING
            recoveryFailed && active == null -> ServerState.SERVER_STATE_FAILED
            !coreOnline -> ServerState.SERVER_STATE_FAILED
            !runtimeObserved -> ServerState.SERVER_STATE_STARTING
            hasWork && phase == RuntimePhase.RUNTIME_PHASE_RECOVERING -> ServerState.SERVER_STATE_RECOVERING
            hasWork -> ServerState.SERVER_STATE_OCCUPIED
            runtimeStatus.ready -> ServerState.SERVER_STATE_AVAILABLE
            else -> ServerState.SERVER_STATE_FAILED
        }
        val canAccept = coreOnline && !recoveryPending && scheduler.canAccept() &&
            requests.liveSize() < LIVE_REQUEST_CAPACITY && restartStatus == null
        return Projection(
            state,
            phase,
            coreOnline,
            canAccept,
            canAccept && runtimeObserved && runtimeStatus.ready && !hasWork,
        )
    }

    private fun currentStage(requestId: String): JobStage =
        if (activeRequestId == requestId) activeStage else JobStage.JOB_STAGE_UNSPECIFIED

    private fun restartSnapshotLocked(status: RestartStatus): RestartStatus = status.toBuilder()
        .setRemainingJobs(liveJobs.size + terminalDeliveriesInFlight)
        .build()

    private fun liveSummary(
        metadata: FairJobScheduler.JobMetadata,
        job: CoreJob?,
        stage: JobStage,
        active: Boolean,
    ): JobSummary {
        val cancelling = job?.cancellation?.token()?.isCancellationRequested() == true
        return JobSummary.newBuilder()
            .setJobId(metadata.jobId)
            .setRequestId(metadata.requestId)
            .setWorkspaceId(metadata.workspaceId)
            .setOperation(metadata.operation)
            .setState(
                if (cancelling) JobState.JOB_STATE_CANCELLING else if (active) {
                    JobState.JOB_STATE_RUNNING
                } else {
                    JobState.JOB_STATE_QUEUED
                },
            )
            .setStage(stage)
            .setTotalUnits(job?.let(::totalUnits) ?: 0)
            .setResumable(true)
            .setCancelable(!cancelling)
            .build()
    }

    private fun terminalSummary(job: CoreJob, state: RequestState): JobSummary = JobSummary.newBuilder()
        .setJobId(job.submission.jobId)
        .setRequestId(job.requestId)
        .setWorkspaceId(job.workspaceId)
        .setOperation(operation(job))
        .setState(ProtocolMessages.jobState(state))
        .setStage(JobStage.JOB_STAGE_FINALIZING)
        .setCompletedUnits(if (state == RequestState.COMPLETED) totalUnits(job) else 0)
        .setTotalUnits(totalUnits(job))
        .setResumable(false)
        .setCancelable(false)
        .build()

    private fun rejectedSummary(message: ClientMessage, jobId: String): JobSummary = JobSummary.newBuilder()
        .setJobId(jobId)
        .setRequestId(message.requestId)
        .setWorkspaceId(message.workspaceId)
        .setOperation(message.submitJob.job.workloadCase.name.lowercase())
        .setState(JobState.JOB_STATE_FAILED)
        .setStage(JobStage.JOB_STAGE_VALIDATING)
        .setResumable(false)
        .setCancelable(false)
        .build()

    private fun runtimeFailure(
        code: ErrorCode,
        message: String,
        requestId: String,
        logPath: String,
    ): RuntimeFailure = RuntimeFailure.newBuilder()
        .setCode(code)
        .setMessage(message.take(512))
        .setPhase(projectionLocked().phase.name)
        .setFailedAtUnixMs(System.currentTimeMillis())
        .setRequestId(requestId)
        .setLogPath(logPath)
        .setRetryable(
            code == ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE ||
                code == ErrorCode.ERROR_CODE_QUEUE_FULL ||
                code == ErrorCode.ERROR_CODE_QUEUE_TIMEOUT ||
                code == ErrorCode.ERROR_CODE_EXECUTION_TIMEOUT,
        )
        .setRecoveryAction(recoveryAction(code))
        .build()

    @Synchronized
    private fun updateMetrics() {
        probe.registries(requests.size(), sources.size(), scheduler.size())
    }

    override fun close() {
        val jobs = synchronized(this) {
            if (closed) return
            closed = true
            stateChangedLocked("core-stopping")
            ArrayList(liveJobs.values)
        }
        for (job in jobs) cancel(job.session!!, job.submission.jobId)
        EngineShutdown.close(runtime, disconnectTimer, scheduler, activator)
        updateMetrics()
    }

    private data class Projection(
        val state: ServerState,
        val phase: RuntimePhase,
        val coreOnline: Boolean,
        val canAcceptJob: Boolean,
        val canStartJob: Boolean,
    )

    internal data class StatusSnapshot(
        val state: ServerState,
        val phase: RuntimePhase,
        val coreOnline: Boolean,
        val runtimeStatus: RuntimeStatus,
        val canAcceptJob: Boolean,
        val canStartJob: Boolean,
        val activeLease: RuntimeLease?,
        val recovery: RuntimeRecovery?,
        val queue: List<QueueEntry>,
        val jobs: List<JobSummary>,
        val lastError: RuntimeFailure?,
        val transitions: List<StateTransition>,
        val activeSourceUuid: String,
        val restart: RestartStatus?,
        val revision: Long,
    )

    internal data class WaitResult(val satisfied: Boolean, val timedOut: Boolean)

    internal data class ActiveJob(val requestId: String, val stage: JobStage)

    internal data class RestartRequestResult(val status: RestartStatus, val alreadyScheduled: Boolean)

    companion object {
        const val REQUEST_REGISTRY_CAPACITY = 192
        const val MAX_STATUS_WAIT_MS = 300_000L
        private const val LIVE_REQUEST_CAPACITY = 64
        private const val TERMINAL_REQUEST_CAPACITY = 128
        private const val TERMINAL_JOB_CAPACITY = 128
        private const val TRANSITION_CAPACITY = 32
        private val TERMINAL_TTL: Duration = Duration.ofMinutes(10)
        private val DISCONNECT_GRACE: Duration = Duration.ofSeconds(2)
        private const val RESTART_RETRY_AFTER_MS = 10_000L

        private fun failImmediate(
            session: ControlSession,
            message: ClientMessage,
            code: ErrorCode,
            detail: String,
        ) {
            session.send(ProtocolMessages.immediateFailure(message, session.workspaceId(), code, detail))
        }

        private fun metadata(job: CoreJob): FairJobScheduler.JobMetadata = FairJobScheduler.JobMetadata(
            job.requestId,
            job.workspaceId,
            job.submission.jobId,
            worktreeRoot(job),
            operation(job),
            job.submission.schedulingGroupId,
            job.acceptedAtUnixMs,
        )

        private fun worktreeRoot(job: CoreJob): String = job.submission.sourcesList.asSequence()
            .filter { it.origin.hasWorkspace() }
            .map { it.origin.workspace.worktreeRoot }
            .firstOrNull(String::isNotBlank)
            .orEmpty()

        private fun operation(job: CoreJob): String = job.submission.workloadCase.name.lowercase()

        private fun totalUnits(job: CoreJob): Int = when (job.submission.workloadCase) {
            dev.vibris.protocol.v2.JobSpec.WorkloadCase.ACTION_SEQUENCE ->
                job.submission.actionSequence.actionsCount
            dev.vibris.protocol.v2.JobSpec.WorkloadCase.MATRIX -> job.submission.matrix.casesCount
            dev.vibris.protocol.v2.JobSpec.WorkloadCase.COMPILE_VALIDATION ->
                job.submission.compileValidation.casesCount
            dev.vibris.protocol.v2.JobSpec.WorkloadCase.BENCHMARK -> job.submission.benchmark.repetitions
            else -> 1
        }

        private fun leaseId(active: FairJobScheduler.ActiveJob): String = UUID.nameUUIDFromBytes(
            (active.metadata.requestId + '\u0000' + active.startedAtUnixMs)
                .toByteArray(StandardCharsets.UTF_8),
        ).toString()

        private fun runtimePhase(stage: JobStage): RuntimePhase = when (stage) {
            JobStage.JOB_STAGE_ACTIVATING_SOURCE,
            JobStage.JOB_STAGE_COMPILING,
            -> RuntimePhase.RUNTIME_PHASE_RELOADING_SHADERS
            JobStage.JOB_STAGE_LOADING_WORLD -> RuntimePhase.RUNTIME_PHASE_LOADING_WORLD
            JobStage.JOB_STAGE_APPLYING_CONTEXT -> RuntimePhase.RUNTIME_PHASE_APPLYING_SCENE
            JobStage.JOB_STAGE_RESTORING -> RuntimePhase.RUNTIME_PHASE_RESTORING
            JobStage.JOB_STAGE_RECOVERING -> RuntimePhase.RUNTIME_PHASE_RECOVERING
            else -> RuntimePhase.RUNTIME_PHASE_EXECUTING
        }

        private fun recoveryAction(code: ErrorCode): String = when (code) {
            ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE,
            ErrorCode.ERROR_CODE_SERVER_OFFLINE,
            -> "Reconnect the Minecraft runtime bridge and retry status."
            ErrorCode.ERROR_CODE_QUEUE_FULL,
            ErrorCode.ERROR_CODE_QUEUE_TIMEOUT,
            -> "Wait for can_accept_job, then submit; accepted work runs in workspace round-robin order."
            ErrorCode.ERROR_CODE_SHADER_COMPILE_FAILED -> "Inspect the shader compile log and correct the source."
            ErrorCode.ERROR_CODE_CANCELLED -> "Submit a new job when can_accept_job is true."
            else -> "Inspect the failure details and run recover_runtime if runtime state is uncertain."
        }

        private fun JobState.terminal(): Boolean = this == JobState.JOB_STATE_COMPLETED ||
            this == JobState.JOB_STATE_FAILED || this == JobState.JOB_STATE_CANCELLED
    }
}
