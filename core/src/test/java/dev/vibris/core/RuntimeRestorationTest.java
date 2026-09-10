package dev.vibris.core;

import dev.vibris.api.EffectiveShaderSettings;
import dev.vibris.api.ReloadResult;
import dev.vibris.protocol.v2.Action;
import dev.vibris.protocol.v2.ActionSequence;
import dev.vibris.protocol.v2.ActivateSource;
import dev.vibris.protocol.v2.ErrorCode;
import dev.vibris.protocol.v2.JobSpec;
import dev.vibris.protocol.v2.LoadShader;
import dev.vibris.protocol.v2.PreparedSourceRef;
import dev.vibris.protocol.v2.ReceiptStatus;
import dev.vibris.protocol.v2.RecoverRuntimeRequest;
import dev.vibris.protocol.v2.RestorePolicy;
import dev.vibris.protocol.v2.ResetTemporalState;
import dev.vibris.protocol.v2.SceneContext;
import dev.vibris.protocol.v2.ShaderConfig;
import dev.vibris.protocol.v2.WaitFrames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeRestorationTest {
    private static final String WORKSPACE_ID = "11111111-1111-4111-8111-111111111111";

    @TempDir
    Path temp;

    @Test
    void successRestoresAndVerifiesTheSafeSnapshot() throws Exception {
        Fixture fixture = new Fixture();
        EffectiveShaderSettings baselineSettings = settings("runtime-baseline", EffectiveShaderSettings.Origin.PRESET);
        fixture.runtime.reloads.add(ReloadResult.success(baselineSettings, List.of()));
        Source baseline = fixture.bootstrap();
        Source candidate = fixture.source("candidate");
        fixture.runtime.reloads.add(ReloadResult.success(
            settings("runtime-candidate", EffectiveShaderSettings.Origin.REQUEST_OVERRIDE),
            List.of()
        ));
        fixture.runtime.reloads.add(ReloadResult.success(
            settings("runtime-baseline", EffectiveShaderSettings.Origin.REQUEST_OVERRIDE),
            List.of()
        ));

        TerminalResult result = fixture.executor.execute(fixture.mutatingJob(candidate), ignored -> {});

        var receipt = result.completed().getResult().getRestoration();
        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK, receipt.getStatus());
        assertEquals(baseline.uuid, receipt.getExpectedSourceUuid());
        assertEquals(baseline.uuid, receipt.getActualSourceUuid());
        assertEquals(baselineSettings.settingsSha256(), receipt.getExpectedSettingsSha256());
        assertEquals(baselineSettings.settingsSha256(), receipt.getActualSettingsSha256());
        assertEquals("runtime-baseline", fixture.runtime.shaderConfigs.getLast().get("QUALITY"));
        assertTrue(receipt.getTemporalStateReset());
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
    }

    private static EffectiveShaderSettings settings(String value, EffectiveShaderSettings.Origin origin) {
        return EffectiveShaderSettings.of(List.of(
            new EffectiveShaderSettings.Setting("QUALITY", value, "default", origin)
        ));
    }

    @Test
    void temporalOnlyTransactionDoesNotRequireASourceSceneSnapshot() throws Exception {
        Fixture fixture = new Fixture();

        TerminalResult result = fixture.executor.execute(fixture.resetJob(), ignored -> {});

        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK,
            result.completed().getResult().getRestoration().getStatus());
        assertTrue(result.completed().getResult().getRestoration().getTemporalStateReset());
    }

    @Test
    void failureStillRestoresAndReturnsTheOriginalError() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.bootstrap();
        Source candidate = fixture.source("candidate");
        CoreJob job = fixture.mutatingJob(candidate, Action.newBuilder().setActivateSource(
            ActivateSource.newBuilder().setSourceUuid(UUID.randomUUID().toString())).build());

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(job, ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_INVALID_SOURCE, failure.code);
        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK, failure.restoration.getStatus());
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
    }

    @Test
    void cancellationRestoresWithANonCancelledRuntimeToken() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.bootstrap();
        Source candidate = fixture.source("candidate");
        CoreJob job = fixture.mutatingJob(candidate,
            Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(2)).build());
        fixture.runtime.beforeCompileCatalogResult = job.cancellation::cancel;

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(job, ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_CANCELLED, failure.code);
        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK, failure.restoration.getStatus());
        assertTrue(failure.restoration.getTemporalStateReset());
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
    }

    @Test
    void executionTimeoutWaitsForTheRuntimeSafePointThenRestores() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.bootstrap();
        Source candidate = fixture.source("candidate");
        fixture.runtime.waitOperations.add(cancellation -> {
            var stage = new CompletableFuture<Long>();
            Thread.ofVirtual().start(() -> {
                while (!cancellation.isCancellationRequested()) Thread.onSpinWait();
                stage.completeExceptionally(new CancellationException());
            });
            return stage;
        });
        CoreJob job = fixture.mutatingJob(candidate,
            Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(2)).build(), 20);

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(job, ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_EXECUTION_TIMEOUT, failure.code);
        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK, failure.restoration.getStatus());
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
    }

    @Test
    void restoreFailureRetainsOwnershipAndBlocksNormalActivation() throws Exception {
        Fixture fixture = new Fixture();
        fixture.bootstrap();
        Source candidate = fixture.source("candidate");
        fixture.runtime.reloads.add(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));
        fixture.runtime.reloads.add(ReloadResult.failure(List.of()));

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.mutatingJob(candidate), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_RESTORE_FAILED, failure.code);
        assertEquals(ReceiptStatus.RECEIPT_STATUS_FAILED, failure.restoration.getStatus());
        assertTrue(failure.holdOwnership);
        assertTrue(fixture.executor.hasPendingRecovery());
        assertFalse(fixture.activator.ready());
        assertThrows(SourceActivator.Failure.class, () -> fixture.activator.begin(candidate.lease));
    }

    @Test
    void recoverRuntimeRetriesUntilItReturnsAVerifiedReceipt() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.bootstrap();
        Source candidate = fixture.source("candidate");
        fixture.runtime.reloads.add(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));
        fixture.runtime.reloads.add(ReloadResult.failure(List.of()));
        assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.mutatingJob(candidate), ignored -> {}));

        fixture.runtime.reloads.add(ReloadResult.failure(List.of()));
        RuntimeJobExecutor.Failure failedRecovery = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.recoveryJob(), ignored -> {}));
        assertEquals(ErrorCode.ERROR_CODE_RECOVERY_FAILED, failedRecovery.code);
        assertTrue(failedRecovery.getMessage().contains("in-flight recovery operation"));
        assertTrue(fixture.executor.hasPendingRecovery());

        fixture.runtime.reloads.add(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));
        TerminalResult recovered = fixture.executor.execute(fixture.recoveryJob(), ignored -> {});
        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK,
            recovered.completed().getResult().getRestoration().getStatus());
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
        assertFalse(fixture.executor.hasPendingRecovery());
        assertTrue(fixture.activator.ready());
    }

    @Test
    void timedOutRestorationCancelsTheOperationAndRecoveryWaitsForItsBarrier() throws Exception {
        Fixture fixture = new Fixture(Duration.ofMillis(25));
        Source baseline = fixture.bootstrap();
        Source candidate = fixture.source("candidate");
        CountDownLatch cancellationObserved = new CountDownLatch(1);
        fixture.runtime.reloadOperations.add(cancellation -> CompletableFuture.completedFuture(
            ReloadResult.success(EffectiveShaderSettings.empty(), List.of())));
        fixture.runtime.reloadOperations.add(cancellation -> {
            CompletableFuture<ReloadResult> stage = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                while (!cancellation.isCancellationRequested()) Thread.onSpinWait();
                cancellationObserved.countDown();
                stage.completeExceptionally(new CancellationException());
            });
            return stage;
        });

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.mutatingJob(candidate), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_RESTORE_FAILED, failure.code);
        assertTrue(cancellationObserved.await(1, TimeUnit.SECONDS));
        assertTrue(fixture.executor.hasPendingRecovery());

        fixture.runtime.reloads.add(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));
        TerminalResult recovered = fixture.executor.execute(fixture.recoveryJob(), ignored -> {});
        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK,
            recovered.completed().getResult().getRestoration().getStatus());
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
        assertFalse(fixture.executor.hasPendingRecovery());
    }

    @Test
    void recoverRuntimeIsIdempotentWhenTheCurrentSafeStateVerifies() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.bootstrap();

        TerminalResult recovered = fixture.executor.execute(fixture.recoveryJob(), ignored -> {});

        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK,
            recovered.completed().getResult().getRestoration().getStatus());
        assertEquals(baseline.uuid,
            recovered.completed().getResult().getRestoration().getActualSourceUuid());
        assertFalse(fixture.executor.hasPendingRecovery());
    }

    private final class Fixture {
        final Path pending = Files.createDirectory(temp.resolve("pending"));
        final RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        final SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        final SourceActivator activator = new SourceActivator(registry, new RecordingLink(runtime.events));
        final RuntimeJobExecutor executor;

        Fixture() throws Exception {
            this(Duration.ofSeconds(45));
        }

        Fixture(Duration restorationTimeout) throws Exception {
            executor = new RuntimeJobExecutor(
                runtime,
                new CoreProbe(),
                activator,
                new ArtifactManager(temp.resolve("artifacts")),
                ServerConfiguration.DEFAULT_MAX_ACTIONS_PER_JOB,
                restorationTimeout);
        }

        Source bootstrap() throws Exception {
            Source source = source("baseline");
            executor.execute(job(source, false, 0, List.of(load(source, "baseline"))), ignored -> {});
            runtime.events.clear();
            return source;
        }

        Source source(String marker) throws Exception {
            String uuid = UUID.randomUUID().toString();
            PreparedSourceRef reference = SourceTestArchive.source(pending, uuid, marker,
                dev.vibris.protocol.v2.VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED, "main");
            List<SourceRegistry.Lease> leases = registry.reserve(registry.validate(List.of(reference)));
            registry.accept(leases);
            return new Source(uuid, leases.getFirst());
        }

        CoreJob mutatingJob(Source source, Action... trailing) {
            return mutatingJob(source, List.of(trailing), 0);
        }

        CoreJob mutatingJob(Source source, Action trailing, long timeoutMs) {
            return mutatingJob(source, List.of(trailing), timeoutMs);
        }

        CoreJob mutatingJob(Source source, List<Action> trailing, long timeoutMs) {
            var actions = new java.util.ArrayList<Action>();
            actions.add(load(source, "candidate"));
            actions.addAll(trailing);
            return job(source, true, timeoutMs, actions);
        }

        CoreJob recoveryJob() {
            JobSpec spec = JobSpec.newBuilder()
                .setJobId("recover-" + UUID.randomUUID())
                .setRecoverRuntime(RecoverRuntimeRequest.getDefaultInstance())
                .build();
            CoreJob job = new CoreJob(spec, spec.getJobId(), WORKSPACE_ID, "message", null);
            job.initialize(List.of());
            return job;
        }

        CoreJob resetJob() {
            JobSpec spec = JobSpec.newBuilder()
                .setJobId("reset-" + UUID.randomUUID())
                .setRestoreState(RestorePolicy.newBuilder().setOnSuccess(true).setOnError(true))
                .setActionSequence(ActionSequence.newBuilder().addActions(
                    Action.newBuilder().setResetTemporalState(ResetTemporalState.getDefaultInstance())))
                .build();
            CoreJob job = new CoreJob(spec, spec.getJobId(), WORKSPACE_ID, "message", null);
            job.initialize(List.of());
            return job;
        }

        CoreJob job(Source source, boolean restore, long timeoutMs, List<Action> actions) {
            JobSpec.Builder spec = JobSpec.newBuilder()
                .setJobId("job-" + UUID.randomUUID())
                .setContext(SceneContext.newBuilder()
                    .setSaveId("save")
                    .setDimensionId("minecraft:overworld")
                    .setFov(70.0))
                .addSources(source.lease.reference())
                .setRestoreState(RestorePolicy.newBuilder().setOnSuccess(restore).setOnError(restore))
                .setActionSequence(ActionSequence.newBuilder().addAllActions(actions));
            if (timeoutMs > 0) spec.getTimeoutsBuilder().setExecutionTimeoutMs(timeoutMs);
            CoreJob job = new CoreJob(spec.build(), spec.getJobId(), WORKSPACE_ID, "message", null);
            job.initialize(List.of(source.lease));
            return job;
        }

        Action load(Source source, String value) {
            return Action.newBuilder().setLoadShader(LoadShader.newBuilder()
                .setSourceUuid(source.uuid)
                .setSourceId(value)
                .setConfigId(value)
                .setConfig(ShaderConfig.newBuilder().putValues("QUALITY", value))).build();
        }
    }

    private static final class RecordingLink implements ShaderLink {
        private final List<String> events;

        RecordingLink(List<String> events) {
            this.events = events;
        }

        @Override
        public void switchTo(SourceRegistry.Lease source, OwnershipCheck ownership) throws Failure {
            ownership.verify();
            events.add("link:" + source.uuid());
        }

        @Override
        public void detach() {
            events.add("detach");
        }

        @Override
        public boolean retainsActiveSource() {
            return true;
        }
    }

    private record Source(String uuid, SourceRegistry.Lease lease) {}
}
