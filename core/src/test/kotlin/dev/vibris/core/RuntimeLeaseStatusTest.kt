package dev.vibris.core

import dev.vibris.api.EffectiveShaderSettings
import dev.vibris.api.ReloadResult
import dev.vibris.api.RuntimeStatus
import dev.vibris.protocol.v2.Action
import dev.vibris.protocol.v2.ActionSequence
import dev.vibris.protocol.v2.ActivateSource
import dev.vibris.protocol.v2.ClientMessage
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobSpec
import dev.vibris.protocol.v2.JobStage
import dev.vibris.protocol.v2.LoadShader
import dev.vibris.protocol.v2.PreparedSourceRef
import dev.vibris.protocol.v2.ReceiptStatus
import dev.vibris.protocol.v2.RecoverRuntimeRequest
import dev.vibris.protocol.v2.RestorePolicy
import dev.vibris.protocol.v2.RuntimePhase
import dev.vibris.protocol.v2.SceneContext
import dev.vibris.protocol.v2.ServerMessage
import dev.vibris.protocol.v2.ServerState
import dev.vibris.protocol.v2.ShaderConfig
import dev.vibris.protocol.v2.SourceOrigin
import dev.vibris.protocol.v2.StatusDetail
import dev.vibris.protocol.v2.StatusWaitCondition
import dev.vibris.protocol.v2.SubmitJob
import dev.vibris.protocol.v2.WorkspaceOrigin
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class RuntimeLeaseStatusTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun jobActivityObserverCoversOnlyActiveLeasesAndPairsConsecutiveJobs() {
        val runtime = RuntimeTestAdapter()
        val pending = temp.resolve("observer-pending").toAbsolutePath()
        Files.createDirectories(pending)
        val events = CopyOnWriteArrayList<Boolean>()
        val engine = VibrisCoreEngine(
            pending,
            runtime,
            ShaderLink.transientLink(),
            ShaderLogSink.none(),
            jobActivityObserver = JobActivityObserver(events::add),
        )
        val firstReload = CompletableFuture<ReloadResult>()
        runtime.reloadStages.add(firstReload)
        val first = recordingSession()
        val second = recordingSession()

        engine.submit(first.session, loadJob("observer-first", source(pending), false))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (events.isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait()
        engine.submit(second.session, loadJob("observer-second", source(pending), false))

        assertEquals(listOf(true), events)
        engine.statusSnapshot()
        assertEquals(listOf(true), events)
        firstReload.complete(ReloadResult.success(EffectiveShaderSettings.empty(), emptyList()))
        assertTrue(first.terminal.await(2, TimeUnit.SECONDS))
        assertTrue(second.terminal.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(true, false, true, false), events)
        engine.close()
    }

    @Test
    fun cancellationRetainsLeaseUntilSafePointAndWakesStatusWaiter() {
        val runtime = RuntimeTestAdapter()
        val pending = temp.resolve("pending").toAbsolutePath()
        Files.createDirectories(pending)
        val engine = VibrisCoreEngine(pending, runtime)
        val descriptor = ServerDescriptor(pending, ArtifactManager(temp.resolve("artifacts")), runtime)
        descriptor.status(engine)
        val reload = CompletableFuture<ReloadResult>()
        runtime.reloadStages.add(reload)
        val compiling = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val session = session(compiling, terminal)

        engine.submit(session, job("cancel-safe", source(pending)))
        assertTrue(compiling.await(2, TimeUnit.SECONDS))
        val admission = engine.awaitStatus(
            StatusWaitCondition.STATUS_WAIT_CONDITION_CAN_ACCEPT_JOB,
            "",
            0,
        )
        assertTrue(admission.satisfied)
        assertFalse(admission.timedOut)

        engine.cancel(session, "cancel-safe")
        val cancelling = descriptor.status(engine, StatusDetail.STATUS_DETAIL_FULL)
        assertTrue(cancelling.hasActiveLease())
        assertTrue(cancelling.activeLease.cancellationRequested)
        assertFalse(cancelling.canStartJob)

        val terminalWaiter = CompletableFuture.supplyAsync {
            engine.awaitStatus(StatusWaitCondition.STATUS_WAIT_CONDITION_JOB_TERMINAL, "cancel-safe", 2_000)
        }
        reload.complete(ReloadResult.success(EffectiveShaderSettings.empty(), emptyList()))
        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        val terminalResult = terminalWaiter.get(2, TimeUnit.SECONDS)
        assertTrue(terminalResult.satisfied)
        assertFalse(terminalResult.timedOut)
        engine.close()
        assertFalse(descriptor.status(engine).hasActiveLease())
    }

    @Test
    fun transitionHistoryIsBoundedToNewestThirtyTwoRecords() {
        val engine = VibrisCoreEngine(temp.resolve("transition-pending"), RuntimeTestAdapter())
        repeat(40) { index ->
            engine.observeRuntimeStatus(
                RuntimeStatus(index % 2 == 0, "save", "minecraft:overworld", ""),
                if (index % 2 == 0) "" else "fixture unavailable",
            )
        }

        val transitions = engine.statusSnapshot().transitions
        assertTrue(transitions.size == 32)
        assertTrue(transitions.first().sequence > 1)
        assertTrue(transitions.last().sequence > transitions.first().sequence)
        engine.close()
    }

    @Test
    fun restoreFailureReleasesTerminalLeaseAndRecoveryCanMakeCoreReady() {
        val runtime = RuntimeTestAdapter()
        val pending = temp.resolve("recovery-pending").toAbsolutePath()
        Files.createDirectories(pending)
        val engine = VibrisCoreEngine(
            pending,
            runtime,
            RetainingLink,
            ShaderLogSink.none(),
        )
        val descriptor = ServerDescriptor(pending, ArtifactManager(temp.resolve("recovery-artifacts")), runtime)
        descriptor.status(engine)

        val baseline = source(pending)
        val baselineSession = recordingSession()
        engine.submit(baselineSession.session, loadJob("baseline", baseline, false))
        assertTrue(baselineSession.terminal.await(2, TimeUnit.SECONDS))

        val candidate = source(pending)
        runtime.reloads.add(ReloadResult.success(EffectiveShaderSettings.empty(), emptyList()))
        runtime.reloads.add(ReloadResult.failure(emptyList()))
        val candidateSession = recordingSession()
        engine.submit(candidateSession.session, loadJob("candidate", candidate, true))
        assertTrue(candidateSession.terminal.await(2, TimeUnit.SECONDS))
        val failed = candidateSession.messages.last { it.hasJobFailed() }.jobFailed
        assertTrue(failed.error.code == ErrorCode.ERROR_CODE_RESTORE_FAILED)
        assertTrue(failed.restoration.status == ReceiptStatus.RECEIPT_STATUS_FAILED)

        val recovering = descriptor.status(engine, StatusDetail.STATUS_DETAIL_FULL)
        assertTrue(recovering.state == ServerState.SERVER_STATE_RECOVERING)
        assertFalse(recovering.hasActiveLease())
        assertFalse(recovering.canAcceptJob)
        assertTrue(recovering.hasRecovery())
        assertEquals("candidate", recovering.recovery.jobId)
        assertEquals(WORKSPACE_ID, recovering.recovery.workspaceId)
        assertEquals(0, recovering.recovery.attemptCount)
        assertEquals(ErrorCode.ERROR_CODE_RESTORE_FAILED, recovering.recovery.lastError.code)

        val rejectedSession = recordingSession()
        engine.submit(rejectedSession.session, loadJob("must-be-rejected", source(pending), false))
        val rejected = rejectedSession.messages.last { it.hasJobFailed() }.jobFailed
        assertTrue(rejected.error.code == ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE)

        runtime.reloads.add(ReloadResult.failure(emptyList()))
        val failedRecoverySession = recordingSession()
        engine.submit(failedRecoverySession.session, recoveryJob("recover-failed"))
        assertTrue(failedRecoverySession.terminal.await(2, TimeUnit.SECONDS))
        val failedRecovery = failedRecoverySession.messages.last { it.hasJobFailed() }.jobFailed
        assertEquals(ErrorCode.ERROR_CODE_RECOVERY_FAILED, failedRecovery.error.code)
        val failedRuntime = descriptor.status(engine, StatusDetail.STATUS_DETAIL_FULL)
        assertEquals(ServerState.SERVER_STATE_FAILED, failedRuntime.state)
        assertEquals(RuntimePhase.RUNTIME_PHASE_FAILED, failedRuntime.readiness.phase)
        assertFalse(failedRuntime.hasActiveLease())
        assertFalse(failedRuntime.canAcceptJob)
        assertTrue(failedRuntime.hasRecovery())
        assertEquals(1, failedRuntime.recovery.attemptCount)
        assertEquals(ErrorCode.ERROR_CODE_RECOVERY_FAILED, failedRuntime.recovery.lastError.code)

        runtime.reloads.add(ReloadResult.success(EffectiveShaderSettings.empty(), emptyList()))
        val recoverySession = recordingSession()
        engine.submit(recoverySession.session, recoveryJob("recover"))
        assertTrue(recoverySession.terminal.await(2, TimeUnit.SECONDS))
        val completed = recoverySession.messages.last { it.hasJobCompleted() }.jobCompleted
        assertTrue(completed.result.restoration.status == ReceiptStatus.RECEIPT_STATUS_OK)
        val ready = engine.awaitStatus(StatusWaitCondition.STATUS_WAIT_CONDITION_CAN_ACCEPT_JOB, "", 2_000)
        assertTrue(ready.satisfied)
        assertFalse(descriptor.status(engine).hasActiveLease())
        assertFalse(descriptor.status(engine).hasRecovery())
        assertTrue(engine.ready())
        engine.close()
    }

    @Test
    fun gracefulRestartClosesAdmissionAndWaitsForAcceptedJobToFinish() {
        val runtime = RuntimeTestAdapter()
        val pending = temp.resolve("restart-pending").toAbsolutePath()
        Files.createDirectories(pending)
        val engine = VibrisCoreEngine(pending, runtime)
        val reload = CompletableFuture<ReloadResult>()
        runtime.reloadStages.add(reload)
        val compiling = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        engine.submit(session(compiling, terminal), job("active-before-restart", source(pending)))
        assertTrue(compiling.await(2, TimeUnit.SECONDS))

        val scheduled = engine.requestRestart(WORKSPACE_ID, "deploy new build")
        assertFalse(scheduled.alreadyScheduled)
        assertEquals(1, scheduled.status.remainingJobs)
        assertFalse(engine.canAcceptJob())
        val status = engine.statusSnapshot()
        assertEquals("deploy new build", status.restart?.reason)
        assertEquals(1, status.restart?.remainingJobs)

        val rejected = recordingSession()
        engine.submit(rejected.session, job("after-restart-request", source(pending)))
        assertTrue(rejected.terminal.await(2, TimeUnit.SECONDS))
        assertEquals(
            ErrorCode.ERROR_CODE_SERVER_RESTARTED,
            rejected.messages.last { it.hasJobFailed() }.jobFailed.error.code,
        )

        val drained = CompletableFuture.supplyAsync { engine.awaitRestartDrain() }
        assertFalse(drained.isDone)
        reload.complete(ReloadResult.success(EffectiveShaderSettings.empty(), emptyList()))
        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        assertTrue(drained.get(2, TimeUnit.SECONDS))
        assertEquals(0, engine.markRestartLaunching()?.remainingJobs)
        engine.close()
    }

    @Test
    fun queuedOrdinaryJobIsRejectedIfEarlierJobLeavesRuntimeInRecovery() {
        val runtime = RuntimeTestAdapter()
        val pending = temp.resolve("queued-recovery-pending").toAbsolutePath()
        Files.createDirectories(pending)
        val engine = VibrisCoreEngine(
            pending,
            runtime,
            RetainingLink,
            ShaderLogSink.none(),
        )

        val baselineSession = recordingSession()
        engine.submit(baselineSession.session, loadJob("baseline-queued", source(pending), false))
        assertTrue(baselineSession.terminal.await(2, TimeUnit.SECONDS))

        val candidateReload = CompletableFuture<ReloadResult>()
        runtime.reloadStages.add(candidateReload)
        runtime.reloads.add(ReloadResult.failure(emptyList()))
        val candidateStarted = CountDownLatch(1)
        runtime.beforeReloadResult = Runnable { candidateStarted.countDown() }
        val candidateSession = recordingSession()
        engine.submit(candidateSession.session, loadJob("candidate-queued", source(pending), true))
        assertTrue(candidateStarted.await(2, TimeUnit.SECONDS))

        val queuedSession = recordingSession()
        engine.submit(queuedSession.session, loadJob("queued-after-failure", source(pending), false))
        assertTrue(engine.queueLength() >= 1)

        candidateReload.complete(ReloadResult.success(EffectiveShaderSettings.empty(), emptyList()))
        assertTrue(candidateSession.terminal.await(2, TimeUnit.SECONDS))
        assertTrue(queuedSession.terminal.await(2, TimeUnit.SECONDS))

        val candidateFailure = candidateSession.messages.last { it.hasJobFailed() }.jobFailed
        assertEquals(ErrorCode.ERROR_CODE_RESTORE_FAILED, candidateFailure.error.code)
        val queuedFailure = queuedSession.messages.last { it.hasJobFailed() }.jobFailed
        assertEquals(ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE, queuedFailure.error.code)
        assertEquals(0, engine.probe().executionCount("queued-after-failure"))
        engine.close()
    }

    private fun session(compiling: CountDownLatch, terminal: CountDownLatch): ControlSession {
        val session = ControlSession(object : StreamObserver<ServerMessage> {
            override fun onNext(message: ServerMessage) {
                if (message.hasJobProgress() && message.jobProgress.stage == JobStage.JOB_STAGE_COMPILING) {
                    compiling.countDown()
                }
                if (message.hasJobCompleted() || message.hasJobFailed()) terminal.countDown()
            }

            override fun onError(throwable: Throwable) = throw AssertionError(throwable)

            override fun onCompleted() = Unit
        })
        session.identify(WORKSPACE_ID, "process")
        return session
    }

    private fun source(pending: Path): PreparedSourceRef {
        val uuid = UUID.randomUUID().toString()
        val source = Files.createDirectory(pending.resolve(uuid))
        val file = Files.writeString(source.resolve("main.glsl"), uuid)
        return PreparedSourceRef.newBuilder()
            .setSourceUuid(uuid)
            .setVcsCheckoutState(dev.vibris.protocol.v2.VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED)
            .setBranch("main")
            .setRequestedRevision("workspace")
            .setResolvedRevision("a".repeat(40))
            .setOrigin(SourceOrigin.newBuilder().setWorkspace(
                WorkspaceOrigin.newBuilder().setDisplayName("fixture").setWorktreeRoot(pending.toString()),
            ))
            .setFileCount(1)
            .setTotalBytes(Files.size(file))
            .build()
    }

    private fun loadJob(id: String, source: PreparedSourceRef, restore: Boolean): ClientMessage {
        val spec = JobSpec.newBuilder()
            .setJobId(id)
            .setContext(SceneContext.newBuilder().setSaveId("save")
                .setDimensionId("minecraft:overworld").setFov(70.0))
            .addSources(source)
            .setRestoreState(RestorePolicy.newBuilder().setOnSuccess(restore).setOnError(restore))
            .setActionSequence(ActionSequence.newBuilder().addActions(Action.newBuilder().setLoadShader(
                LoadShader.newBuilder()
                    .setSourceUuid(source.sourceUuid)
                    .setSourceId(id)
                    .setConfigId(id)
                    .setConfig(ShaderConfig.newBuilder().putValues("QUALITY", id)),
            )))
            .build()
        return submit(id, spec)
    }

    private fun recoveryJob(id: String): ClientMessage = submit(
        id,
        JobSpec.newBuilder()
            .setJobId(id)
            .setRecoverRuntime(RecoverRuntimeRequest.getDefaultInstance())
            .build(),
    )

    private fun submit(id: String, spec: JobSpec): ClientMessage = ClientMessage.newBuilder()
        .setProtocolVersion(ProtocolMessages.V2)
        .setMessageId("message-$id")
        .setRequestId(id)
        .setWorkspaceId(WORKSPACE_ID)
        .setSubmitJob(SubmitJob.newBuilder().setJob(spec))
        .build()

    private fun recordingSession(): SessionFixture {
        val messages = CopyOnWriteArrayList<ServerMessage>()
        val terminal = CountDownLatch(1)
        val session = ControlSession(object : StreamObserver<ServerMessage> {
            override fun onNext(message: ServerMessage) {
                messages.add(message)
                if (message.hasJobCompleted() || message.hasJobFailed()) terminal.countDown()
            }

            override fun onError(throwable: Throwable) = throw AssertionError(throwable)

            override fun onCompleted() = Unit
        })
        session.identify(WORKSPACE_ID, "process")
        return SessionFixture(session, messages, terminal)
    }

    private fun job(id: String, source: PreparedSourceRef): ClientMessage {
        val spec = JobSpec.newBuilder()
            .setJobId(id)
            .setContext(SceneContext.newBuilder().setSaveId("save")
                .setDimensionId("minecraft:overworld").setFov(70.0))
            .addSources(source)
            .setActionSequence(ActionSequence.newBuilder().addActions(Action.newBuilder().setActivateSource(
                ActivateSource.newBuilder().setSourceUuid(source.sourceUuid),
            )))
            .build()
        return ClientMessage.newBuilder()
            .setProtocolVersion(ProtocolMessages.V2)
            .setMessageId("message-$id")
            .setRequestId(id)
            .setWorkspaceId(WORKSPACE_ID)
            .setSubmitJob(SubmitJob.newBuilder().setJob(spec))
            .build()
    }

    private companion object {
        const val WORKSPACE_ID = "11111111-1111-4111-8111-111111111111"

        object RetainingLink : ShaderLink {
            override fun switchTo(source: SourceRegistry.Lease, ownership: ShaderLink.OwnershipCheck) =
                ownership.verify()

            override fun detach() = Unit

            override fun retainsActiveSource(): Boolean = true
        }
    }

    private data class SessionFixture(
        val session: ControlSession,
        val messages: List<ServerMessage>,
        val terminal: CountDownLatch,
    )
}
