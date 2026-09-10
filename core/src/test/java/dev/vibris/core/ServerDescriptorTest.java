package dev.vibris.core;

import dev.vibris.api.EffectiveShaderSettings;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.RuntimeStatus;
import dev.vibris.protocol.v2.Action;
import dev.vibris.protocol.v2.ActionSequence;
import dev.vibris.protocol.v2.ActivateSource;
import dev.vibris.protocol.v2.Capability;
import dev.vibris.protocol.v2.ClientMessage;
import dev.vibris.protocol.v2.JobSpec;
import dev.vibris.protocol.v2.JobStage;
import dev.vibris.protocol.v2.PreparedSourceRef;
import dev.vibris.protocol.v2.RuntimePhase;
import dev.vibris.protocol.v2.SceneContext;
import dev.vibris.protocol.v2.ServerMessage;
import dev.vibris.protocol.v2.ServerState;
import dev.vibris.protocol.v2.StatusDetail;
import dev.vibris.protocol.v2.SubmitJob;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerDescriptorTest {
    private static final String WORKSPACE_ID = "11111111-1111-4111-8111-111111111111";

    @TempDir
    Path temp;

    @Test
    void reportsTruthfulStrictV2Readiness() {
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        runtime.status = new RuntimeStatus(false, "actual-save", "minecraft:the_nether", "runtime-source");
        Path pending = temp.resolve("pending").toAbsolutePath();
        VibrisCoreEngine engine = new VibrisCoreEngine(pending, runtime);
        ServerDescriptor descriptor = new ServerDescriptor(
            pending, new ArtifactManager(temp.resolve("artifacts")), runtime);

        var status = descriptor.status(engine);

        assertEquals(ServerState.SERVER_STATE_FAILED, status.getState());
        assertTrue(status.getReadiness().getCoreOnline());
        assertFalse(status.getReadiness().getMinecraftConnected());
        assertEquals(RuntimePhase.RUNTIME_PHASE_DISCONNECTED, status.getReadiness().getPhase());
        assertEquals("runtime-source", status.getActiveSourceUuid());
        assertTrue(status.getCanAcceptJob());
        assertFalse(status.getCanStartJob());
        engine.close();
    }

    @Test
    void advertisesOnlyImplementedStrictV2Capability() {
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        Path pending = temp.resolve("pending").toAbsolutePath();
        VibrisCoreEngine engine = new VibrisCoreEngine(pending, runtime);
        ServerDescriptor descriptor = new ServerDescriptor(
            pending, new ArtifactManager(temp.resolve("artifacts")), runtime);

        var hello = descriptor.hello(engine);

        assertEquals(List.of(
            Capability.CAPABILITY_CONTROL_STREAM,
            Capability.CAPABILITY_RUNTIME_LEASE,
            Capability.CAPABILITY_STATUS_WAIT,
            Capability.CAPABILITY_TRANSACTIONAL_RESTORE,
            Capability.CAPABILITY_GRACEFUL_RESTART), hello.getCapabilitiesList());
        assertEquals(VibrisCoreEngine.MAX_STATUS_WAIT_MS, hello.getLimits().getMaxStatusWaitMs());
        assertEquals("vibris-core", hello.getServerVersion());
        assertEquals(ServerState.SERVER_STATE_AVAILABLE, hello.getStatus().getState());
        engine.close();
    }

    @Test
    void longShaderReloadReportsOccupiedWithoutBlockingRuntimeStatusQuery() throws Exception {
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        Path pending = temp.resolve("pending").toAbsolutePath();
        Files.createDirectories(pending);
        VibrisCoreEngine engine = new VibrisCoreEngine(pending, runtime);
        ServerDescriptor descriptor = new ServerDescriptor(
            pending, new ArtifactManager(temp.resolve("artifacts")), runtime);
        descriptor.status(engine);
        assertEquals(1, runtime.statusCalls);
        CompletableFuture<ReloadResult> reload = new CompletableFuture<>();
        runtime.reloadStages.add(reload);
        CountDownLatch compiling = new CountDownLatch(1);
        CountDownLatch terminal = new CountDownLatch(1);
        ControlSession session = new ControlSession(new StreamObserver<>() {
            @Override
            public void onNext(ServerMessage message) {
                if (message.hasJobProgress() && message.getJobProgress().getStage() == JobStage.JOB_STAGE_COMPILING) {
                    compiling.countDown();
                }
                if (message.hasJobCompleted() || message.hasJobFailed()) terminal.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }

            @Override
            public void onCompleted() {
            }
        });
        session.identify(WORKSPACE_ID, "process");
        PreparedSourceRef source = source(pending);
        JobSpec job = JobSpec.newBuilder()
            .setJobId("long-reload")
            .setContext(SceneContext.newBuilder().setSaveId("save")
                .setDimensionId("minecraft:overworld").setFov(70.0))
            .addSources(source)
            .setActionSequence(ActionSequence.newBuilder().addActions(Action.newBuilder().setActivateSource(
                ActivateSource.newBuilder().setSourceUuid(source.getSourceUuid()))))
            .build();
        ClientMessage message = ClientMessage.newBuilder()
            .setProtocolVersion(ProtocolMessages.V2)
            .setMessageId("message")
            .setRequestId("long-reload")
            .setWorkspaceId(WORKSPACE_ID)
            .setSubmitJob(SubmitJob.newBuilder().setJob(job))
            .build();

        submit(engine, session, message);
        assertTrue(compiling.await(2, TimeUnit.SECONDS));
        runtime.status = new RuntimeStatus(false, "", "", "");
        var status = descriptor.status(engine, StatusDetail.STATUS_DETAIL_FULL);

        assertEquals(1, runtime.statusCalls);
        assertEquals(ServerState.SERVER_STATE_OCCUPIED, status.getState());
        assertEquals(RuntimePhase.RUNTIME_PHASE_RELOADING_SHADERS, status.getReadiness().getPhase());
        assertFalse(status.getCanStartJob());
        assertTrue(status.hasActiveLease());
        assertEquals(WORKSPACE_ID, status.getActiveLease().getWorkspaceId());
        assertEquals("long-reload", status.getActiveLease().getJobId());
        assertEquals("long-reload", status.getActiveLease().getRequestId());
        assertEquals("action_sequence", status.getActiveLease().getOperation());
        assertEquals(JobStage.JOB_STAGE_COMPILING, status.getActiveLease().getStage());
        assertEquals(pending.toString(), status.getActiveLease().getWorktreeRoot());
        assertTrue(status.getActiveLease().getStartedAtUnixMs() > 0);
        assertEquals(1, status.getJobsCount());
        assertTrue(status.getTransitionsCount() <= 32);
        reload.complete(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));
        assertTrue(terminal.await(2, TimeUnit.SECONDS));
        engine.close();
    }

    private static PreparedSourceRef source(Path pending) throws Exception {
        String uuid = UUID.randomUUID().toString();
        return SourceTestArchive.source(pending, uuid, "fixture",
            dev.vibris.protocol.v2.VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED, "main").toBuilder()
            .setRequestedRevision("workspace")
            .setResolvedRevision("a".repeat(40))
            .setOrigin(dev.vibris.protocol.v2.SourceOrigin.newBuilder()
                .setWorkspace(dev.vibris.protocol.v2.WorkspaceOrigin.newBuilder()
                    .setDisplayName("fixture")
                    .setWorktreeRoot(pending.toString())))
            .build();
    }

    private static void submit(VibrisCoreEngine engine, ControlSession session, ClientMessage message)
        throws Exception {
        for (var method : VibrisCoreEngine.class.getDeclaredMethods()) {
            if (method.getName().startsWith("submit") && method.getParameterCount() == 2 &&
                method.getParameterTypes()[0] == ControlSession.class &&
                method.getParameterTypes()[1] == ClientMessage.class) {
                method.setAccessible(true);
                method.invoke(engine, session, message);
                return;
            }
        }
        throw new AssertionError("VibrisCoreEngine submit method was not found");
    }
}
