package dev.vibris.core;

import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.ContextApplyResult;
import dev.vibris.api.CompileCatalog;
import dev.vibris.api.DeterministicTemporalCaptureOutcome;
import dev.vibris.api.DeterministicTemporalCaptureReloaded;
import dev.vibris.api.EffectiveShaderSettings;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.protocol.v2.Action;
import dev.vibris.protocol.v2.ActionKind;
import dev.vibris.protocol.v2.ActionSequence;
import dev.vibris.protocol.v2.ArtifactFormat;
import dev.vibris.protocol.v2.ErrorCode;
import dev.vibris.protocol.v2.JobSpec;
import dev.vibris.protocol.v2.LoadShader;
import dev.vibris.protocol.v2.PreparedSourceRef;
import dev.vibris.protocol.v2.ReceiptStatus;
import dev.vibris.protocol.v2.RecoverRuntimeRequest;
import dev.vibris.protocol.v2.ResetTemporalState;
import dev.vibris.protocol.v2.Resolution;
import dev.vibris.protocol.v2.SceneContext;
import dev.vibris.protocol.v2.ShaderConfig;
import dev.vibris.protocol.v2.TakeScreenshot;
import dev.vibris.protocol.v2.WaitFrames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeJobExecutorDeterministicCaptureTest {
    private static final String WORKSPACE_ID = "11111111-1111-4111-8111-111111111111";

    @TempDir
    Path temp;

    @Test
    void failedBeginStillClosesAttemptedSequence() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        fixture.runtime.deterministicSequenceBeginStages.add(
            CompletableFuture.failedFuture(new IllegalStateException("begin marker")));

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(
                fixture.job(List.of(source), exactBlock(source, "shot", 0)), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_INTERNAL, failure.code);
        assertEquals(1, fixture.runtime.events.stream().filter("deterministic-sequence-begin"::equals).count());
        assertEquals(1, fixture.runtime.events.stream().filter("deterministic-sequence-end"::equals).count());
        assertFalse(fixture.runtime.events.contains("deterministic_capture"));
        assertFalse(fixture.executor.hasPendingRecovery());
    }

    @Test
    void cleanupFailureDominatesBodyFailureAndRecoveryWaitsForBarrier() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        ResourceCatalog.ResourceDescriptor framebuffer = framebuffer();
        fixture.runtime.catalog = ResourceCatalog.of(List.of(framebuffer), List.of());
        fixture.runtime.deterministicCaptureStages.add(
            CompletableFuture.failedFuture(new CancellationException("body marker")));
        DeferredCleanupFuture cleanup = new DeferredCleanupFuture();
        fixture.runtime.deterministicSequenceEndStages.add(cleanup);

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(
                fixture.job(List.of(source), exactBlock(source, "shot", 0)), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_RESTORE_FAILED, failure.code);
        assertTrue(failure.holdOwnership);
        assertTrue(fixture.executor.hasPendingRecovery());
        assertFalse(fixture.activator.ready());
        assertTrue(containsFailureCode(failure, ErrorCode.ERROR_CODE_CANCELLED));

        var recovery = CompletableFuture.supplyAsync(() -> {
            try {
                return fixture.executor.execute(fixture.recoveryJob(), ignored -> {});
            } catch (RuntimeJobExecutor.Failure exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        });
        Thread.sleep(50);
        assertFalse(recovery.isDone());
        assertTrue(fixture.executor.hasPendingRecovery());
        assertFalse(fixture.activator.ready());

        cleanup.complete(null);
        TerminalResult recovered = recovery.get(2, TimeUnit.SECONDS);
        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK,
            recovered.completed().getResult().getRestoration().getStatus());
        assertFalse(fixture.executor.hasPendingRecovery());
        assertTrue(fixture.activator.ready());
    }

    private static boolean containsFailureCode(Throwable throwable, ErrorCode code) {
        if (throwable instanceof RuntimeJobExecutor.Failure failure && failure.code == code) return true;
        for (Throwable suppressed : throwable.getSuppressed()) {
            if (containsFailureCode(suppressed, code)) return true;
        }
        return throwable.getCause() != null && containsFailureCode(throwable.getCause(), code);
    }

    private static final class DeferredCleanupFuture extends CompletableFuture<Void> {
        @Override
        public Void get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
            if (!isDone()) throw new TimeoutException("cleanup remains pending");
            return super.get(timeout, unit);
        }
    }

    @Test
    void exactPreludeResetWaitCaptureUsesOneCompoundCallAndOriginalReceipts() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        ResourceCatalog.ResourceDescriptor framebuffer = framebuffer();
        fixture.runtime.catalog = ResourceCatalog.of(List.of(framebuffer), List.of());
        EffectiveShaderSettings finalSettings = settings("final");
        fixture.runtime.reloads.add(ReloadResult.success(finalSettings, List.of()));
        fixture.runtime.deterministicCaptureResult = capture("shot", framebuffer, 4);
        fixture.runtime.deterministicCaptureFiles.put("shot.png", new byte[]{1, 2, 3});

        TerminalResult terminal = fixture.executor.execute(
            fixture.job(List.of(source), exactBlock(source, "shot", 3)), ignored -> {});
        var result = terminal.completed().getResult();

        assertEquals(1, fixture.runtime.deterministicCaptureCalls);
        assertFalse(fixture.runtime.events.contains("reload"));
        assertFalse(fixture.runtime.events.contains("context"));
        assertFalse(fixture.runtime.events.contains("reset"));
        assertFalse(fixture.runtime.events.contains("frames"));
        assertFalse(fixture.runtime.events.contains("capture"));
        assertSequenceWrapsCapture(fixture.runtime.events);
        assertEquals(1, result.getPreludeReceiptsCount());
        assertEquals(ActionKind.ACTION_KIND_LOAD_SHADER, result.getPreludeReceipts(0).getKind());
        assertEquals(0, result.getPreludeReceipts(0).getActionIndex());
        assertEquals(finalSettings.settingsSha256(),
            result.getPreludeReceipts(0).getRuntimeMutation().getEffectiveSettings().getSettingsSha256());
        assertEquals(List.of(
                ActionKind.ACTION_KIND_RESET_TEMPORAL_STATE,
                ActionKind.ACTION_KIND_WAIT_FRAMES,
                ActionKind.ACTION_KIND_TAKE_SCREENSHOT),
            result.getActionReceiptsList().stream().map(receipt -> receipt.getKind()).toList());
        assertEquals(List.of(0, 1, 2),
            result.getActionReceiptsList().stream().map(receipt -> receipt.getActionIndex()).toList());
        assertTrue(result.getActionReceiptsList().stream()
            .allMatch(receipt -> receipt.getStatus() == ReceiptStatus.RECEIPT_STATUS_OK));
        var wait = result.getActionReceipts(1).getWaitFrames();
        assertEquals(0, wait.getStartFrame());
        assertEquals(3, wait.getEndFrame());
        assertEquals(3, wait.getCompletedFrames());
        assertEquals(4, result.getActionReceipts(2).getCapture().getFrameId());
        assertEquals(1, result.getActionReceipts(2).getCapture().getArtifactsCount());
        assertFalse(fixture.runtime.deterministicPhaseActive);
    }

    @Test
    void zeroWarmupUsesCompoundWithoutWaitReceipt() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        ResourceCatalog.ResourceDescriptor framebuffer = framebuffer();
        fixture.runtime.catalog = ResourceCatalog.of(List.of(framebuffer), List.of());
        fixture.runtime.deterministicCaptureResult = capture("shot", framebuffer, 1);
        fixture.runtime.deterministicCaptureFiles.put("shot.png", new byte[]{1});

        TerminalResult terminal = fixture.executor.execute(
            fixture.job(List.of(source), exactBlock(source, "shot", 0)), ignored -> {});
        var result = terminal.completed().getResult();

        assertEquals(1, fixture.runtime.deterministicCaptureCalls);
        assertEquals(0, fixture.runtime.lastDeterministicCaptureRequest.warmupFrames());
        assertEquals(List.of(
                ActionKind.ACTION_KIND_RESET_TEMPORAL_STATE,
                ActionKind.ACTION_KIND_TAKE_SCREENSHOT),
            result.getActionReceiptsList().stream().map(receipt -> receipt.getKind()).toList());
        assertEquals(1, result.getActionReceipts(1).getCapture().getFrameId());
        assertFalse(result.getActionReceipts(1).getCapture().hasInternalWait());
        assertFalse(fixture.runtime.events.contains("reload"));
        assertFalse(fixture.runtime.events.contains("context"));
        assertFalse(fixture.runtime.events.contains("reset"));
        assertFalse(fixture.runtime.events.contains("frames"));
        assertFalse(fixture.runtime.events.contains("capture"));
    }

    @Test
    void nonExactCaptureDelayRetainsGranularOperations() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        ResourceCatalog.ResourceDescriptor framebuffer = framebuffer();
        fixture.runtime.catalog = ResourceCatalog.of(List.of(framebuffer), List.of());
        fixture.runtime.captureResult = capture("shot", framebuffer, 9);
        fixture.runtime.captureFiles.put("shot.png", new byte[]{4, 5, 6});

        ActionSequence sequence = ActionSequence.newBuilder()
            .addActions(load(source))
            .addActions(Action.newBuilder().setResetTemporalState(ResetTemporalState.getDefaultInstance()))
            .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(2)))
            .addActions(Action.newBuilder().setTakeScreenshot(TakeScreenshot.newBuilder()
                .setArtifactName("shot").setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG).setAfterFrames(1)))
            .build();

        TerminalResult terminal = fixture.executor.execute(
            fixture.job(List.of(source), sequence), ignored -> {});

        assertEquals(0, fixture.runtime.deterministicCaptureCalls);
        assertEquals(2, fixture.runtime.events.stream().filter("frames"::equals).count());
        assertEquals(1, fixture.runtime.events.stream().filter("capture"::equals).count());
        assertFalse(fixture.runtime.events.contains("deterministic-sequence-begin"));
        assertFalse(fixture.runtime.events.contains("deterministic-sequence-end"));
        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK,
            terminal.completed().getResult().getPreludeReceipts(0).getStatus());
    }

    @Test
    void emptyCachedCatalogUsesOnlyFinalCompoundAuthoritativeCatalog() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        ResourceCatalog.ResourceDescriptor framebuffer = framebuffer();
        ResourceCatalog finalCatalog = ResourceCatalog.of(List.of(framebuffer), List.of());
        fixture.runtime.deterministicResourceCatalogs.add(finalCatalog);
        EffectiveShaderSettings finalSettings = settings("final");
        fixture.runtime.reloads.add(ReloadResult.success(finalSettings, List.of()));
        fixture.runtime.deterministicCaptureResult = capture("shot", framebuffer, 4);
        fixture.runtime.deterministicCaptureFiles.put("shot.png", new byte[]{1});

        TerminalResult terminal = fixture.executor.execute(
            fixture.job(List.of(source), exactBlock(source, "shot", 3)), ignored -> {});

        assertEquals(1, fixture.runtime.deterministicCaptureCalls);
        assertEquals(1, fixture.runtime.deterministicPlannerCalls);
        assertEquals(finalCatalog, fixture.runtime.deterministicPlannerCatalogs.getFirst());
        assertEquals(0, fixture.runtime.events.stream().filter("reload"::equals).count());
        assertFalse(fixture.runtime.events.contains("context"));
        assertFalse(fixture.runtime.events.contains("reset"));
        assertFalse(fixture.runtime.events.contains("frames"));
        assertFalse(fixture.runtime.events.contains("capture"));
        assertEquals(finalSettings.settingsSha256(), terminal.completed().getResult()
            .getPreludeReceipts(0).getRuntimeMutation().getEffectiveSettings().getSettingsSha256());
    }

    @Test
    void resetRejectionCompletesLoadAndFailsOnlyResetPhase() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        ResourceCatalog.ResourceDescriptor framebuffer = framebuffer();
        fixture.runtime.catalog = ResourceCatalog.of(List.of(framebuffer), List.of());
        fixture.runtime.reset = new TemporalResetResult(false);

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(
                fixture.job(List.of(source), exactBlock(source, "shot", 3)), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_CAPTURE_FAILED, failure.code);
        assertEquals(1, failure.preludeReceipts.size());
        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK, failure.preludeReceipts.getFirst().getStatus());
        assertEquals(List.of(
                ReceiptStatus.RECEIPT_STATUS_FAILED,
                ReceiptStatus.RECEIPT_STATUS_CANCELLED,
                ReceiptStatus.RECEIPT_STATUS_CANCELLED),
            failure.actionReceipts.stream().map(receipt -> receipt.getStatus()).toList());
        assertFalse(failure.actionReceipts.stream().anyMatch(receipt ->
            receipt.getKind() == ActionKind.ACTION_KIND_TAKE_SCREENSHOT &&
                receipt.getStatus() == ReceiptStatus.RECEIPT_STATUS_OK));
        fixture.assertNoPublishedCapture();
        assertFalse(fixture.runtime.deterministicPhaseActive);
        assertSequenceWrapsCapture(fixture.runtime.events);
    }

    @Test
    void missedCapturePreservesTerminalFrameInFailedReceipt() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        ResourceCatalog.ResourceDescriptor framebuffer = framebuffer();
        ResourceCatalog catalog = ResourceCatalog.of(List.of(framebuffer), List.of());
        fixture.runtime.catalog = catalog;
        CapturePlan plan = screenshotPlan("shot", catalog);
        fixture.runtime.deterministicCaptureOutcomes.add(
            new DeterministicTemporalCaptureOutcome.CaptureRejected(
                reloaded(catalog, reload("final"), 1),
                plan,
                new TemporalResetResult(true),
                2,
                3,
                10,
                13,
                14,
                16,
                failure(DeterministicTemporalCaptureOutcome.FailureKind.MISSED_TARGET, "missed target")
            )
        );

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(
                fixture.job(List.of(source), exactBlock(source, "shot", 3)), ignored -> {}));

        var capture = failure.actionReceipts.get(2).getCapture();
        assertEquals(ErrorCode.ERROR_CODE_CAPTURE_FAILED, failure.code);
        assertEquals(ReceiptStatus.RECEIPT_STATUS_FAILED, failure.actionReceipts.get(2).getStatus());
        assertEquals(16, capture.getFrameId());
        assertEquals(16, capture.getResource().getFrameId());
        assertEquals(0, capture.getArtifactsCount());
        fixture.assertNoPublishedCapture();
    }

    @Test
    void invalidSourceFailsOnlyLoadPrelude() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        ActionSequence sequence = exactBlock(source, "shot", 3).toBuilder()
            .setActions(0, load(source).toBuilder().setLoadShader(
                load(source).getLoadShader().toBuilder().setSourceUuid(UUID.randomUUID().toString())))
            .build();

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.job(List.of(source), sequence), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_INVALID_SOURCE, failure.code);
        assertEquals(ReceiptStatus.RECEIPT_STATUS_FAILED, failure.preludeReceipts.getFirst().getStatus());
        assertTrue(failure.actionReceipts.stream()
            .allMatch(receipt -> receipt.getStatus() == ReceiptStatus.RECEIPT_STATUS_CANCELLED));
    }

    @Test
    void prephaseAvailabilityFailureFailsOnlyLoadPrelude() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        fixture.activator.markNotReady();

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(
                fixture.job(List.of(source), exactBlock(source, "shot", 3)), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE, failure.code);
        assertEquals(ReceiptStatus.RECEIPT_STATUS_FAILED, failure.preludeReceipts.getFirst().getStatus());
        assertTrue(failure.actionReceipts.stream()
            .allMatch(receipt -> receipt.getStatus() == ReceiptStatus.RECEIPT_STATUS_CANCELLED));
    }

    @Test
    void activationCommitFailureFromEveryPostReloadOutcomeFailsOnlyLoadPrelude() throws Exception {
        for (PostReloadOutcome branch : PostReloadOutcome.values()) {
            Fixture fixture = new Fixture();
            Source previous = fixture.source("A");
            Source next = fixture.source("B");
            fixture.runtime.reloads.add(ReloadResult.success(settings("old"), List.of()));
            fixture.executor.loadShader(
                fixture.job(List.of(previous), exactBlock(previous, "unused", 3)),
                previous.lease,
                ShaderConfig.newBuilder().putValues("MODE", "old").build(),
                ignored -> {},
                Long.MAX_VALUE
            );
            ResourceCatalog.ResourceDescriptor framebuffer = framebuffer();
            fixture.runtime.deterministicCaptureOutcomes.add(postReloadOutcome(branch, framebuffer));
            fixture.runtime.deterministicCaptureFiles.put("shot.png", new byte[]{1});
            fixture.runtime.reloads.add(ReloadResult.success(settings("old"), List.of()));
            fixture.link.invalidateAfterSwitch(next.lease.directory());

            RuntimeJobExecutor.Failure failure = assertThrows(
                RuntimeJobExecutor.Failure.class,
                () -> fixture.executor.execute(
                    fixture.job(List.of(next), exactBlock(next, "shot", 3)), ignored -> {}),
                branch.name()
            );

            assertEquals(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, failure.code, branch.name());
            assertEquals(1, failure.preludeReceipts.size(), branch.name());
            assertEquals(ReceiptStatus.RECEIPT_STATUS_FAILED,
                failure.preludeReceipts.getFirst().getStatus(), branch.name());
            assertEquals(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED,
                failure.preludeReceipts.getFirst().getError().getCode(), branch.name());
            assertEquals(List.of(
                    ActionKind.ACTION_KIND_RESET_TEMPORAL_STATE,
                    ActionKind.ACTION_KIND_WAIT_FRAMES,
                    ActionKind.ACTION_KIND_TAKE_SCREENSHOT),
                failure.actionReceipts.stream().map(receipt -> receipt.getKind()).toList(), branch.name());
            assertEquals(List.of(
                    ReceiptStatus.RECEIPT_STATUS_CANCELLED,
                    ReceiptStatus.RECEIPT_STATUS_CANCELLED,
                    ReceiptStatus.RECEIPT_STATUS_CANCELLED),
                failure.actionReceipts.stream().map(receipt -> receipt.getStatus()).toList(), branch.name());
            assertEquals(List.of(
                    ErrorCode.ERROR_CODE_CANCELLED,
                    ErrorCode.ERROR_CODE_CANCELLED,
                    ErrorCode.ERROR_CODE_CANCELLED),
                failure.actionReceipts.stream().map(receipt -> receipt.getError().getCode()).toList(), branch.name());
            assertEquals(previous.lease.uuid(), fixture.registry.activeUuid(), branch.name());
            assertEquals(Map.of("MODE", "old"), fixture.runtime.lastShaderConfig, branch.name());
            assertEquals(context(), fixture.runtime.lastContext, branch.name());
            assertTrue(fixture.activator.ready(), branch.name());
            fixture.assertNoPublishedCapture();
        }
    }

    @Test
    void exceptionalCaptureRollsBackArtifactsAndPublishesNoSuccessfulPriorPhase() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        ResourceCatalog.ResourceDescriptor framebuffer = framebuffer();
        fixture.runtime.catalog = ResourceCatalog.of(List.of(framebuffer), List.of());
        fixture.runtime.deterministicCaptureResult = capture("shot", framebuffer, 4);
        fixture.runtime.deterministicCaptureFiles.put("shot.png", new byte[]{7, 8, 9});
        fixture.runtime.deterministicCaptureFailuresAfterWrite.add(new IllegalStateException("capture failed"));

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(
                fixture.job(List.of(source), exactBlock(source, "shot", 3)), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_RESTORE_FAILED, failure.code);
        assertEquals(ReceiptStatus.RECEIPT_STATUS_CANCELLED, failure.preludeReceipts.getFirst().getStatus());
        assertEquals(List.of(
                ReceiptStatus.RECEIPT_STATUS_CANCELLED,
                ReceiptStatus.RECEIPT_STATUS_CANCELLED,
                ReceiptStatus.RECEIPT_STATUS_FAILED),
            failure.actionReceipts.stream().map(receipt -> receipt.getStatus()).toList());
        assertFalse(failure.actionReceipts.stream().anyMatch(receipt ->
            receipt.getKind() == ActionKind.ACTION_KIND_TAKE_SCREENSHOT &&
                receipt.getStatus() == ReceiptStatus.RECEIPT_STATUS_OK));
        fixture.assertNoPublishedCapture();
        assertFalse(fixture.runtime.deterministicPhaseActive);
    }

    @Test
    void cancelledCaptureAfterWritingRollsBackAndPublishesNoSuccessfulCapture() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        ResourceCatalog.ResourceDescriptor framebuffer = framebuffer();
        fixture.runtime.catalog = ResourceCatalog.of(List.of(framebuffer), List.of());
        fixture.runtime.deterministicCaptureResult = capture("shot", framebuffer, 4);
        fixture.runtime.deterministicCaptureFiles.put("shot.png", new byte[]{7, 8, 9});
        fixture.runtime.deterministicCaptureFailuresAfterWrite.add(
            new CancellationException("capture cancelled"));

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(
                fixture.job(List.of(source), exactBlock(source, "shot", 3)), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_RESTORE_FAILED, failure.code);
        assertEquals(ReceiptStatus.RECEIPT_STATUS_CANCELLED, failure.preludeReceipts.getFirst().getStatus());
        assertEquals(List.of(
                ReceiptStatus.RECEIPT_STATUS_CANCELLED,
                ReceiptStatus.RECEIPT_STATUS_CANCELLED,
                ReceiptStatus.RECEIPT_STATUS_FAILED),
            failure.actionReceipts.stream().map(receipt -> receipt.getStatus()).toList());
        assertFalse(failure.actionReceipts.stream().anyMatch(receipt ->
            receipt.getKind() == ActionKind.ACTION_KIND_TAKE_SCREENSHOT &&
                receipt.getStatus() == ReceiptStatus.RECEIPT_STATUS_OK));
        fixture.assertNoPublishedCapture();
        assertFalse(fixture.runtime.deterministicPhaseActive);
    }

    @Test
    void secondBlockContextFailureRollsActivationBackToFirstSource() throws Exception {
        Fixture fixture = new Fixture();
        Source first = fixture.source("A");
        Source second = fixture.source("B");
        ResourceCatalog.ResourceDescriptor framebuffer = framebuffer();
        fixture.runtime.catalog = ResourceCatalog.of(List.of(framebuffer), List.of());
        fixture.runtime.deterministicCaptureFiles.put("a.png", new byte[]{1});
        ResourceCatalog.ResourceDescriptor capturedFramebuffer = framebuffer(2, 1234);
        fixture.runtime.deterministicCaptureOutcomes.add(
            capturedOutcome("a", framebuffer, capturedFramebuffer, 0, 2));
        fixture.runtime.deterministicCaptureOutcomes.add(
            new DeterministicTemporalCaptureOutcome.ContextRejected(
                ContextApplyResult.failure(context(), "context rejected"),
                failure(DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED, "context rejected")));
        ActionSequence sequence = ActionSequence.newBuilder()
            .addAllActions(exactBlock(first, "a", 1).getActionsList())
            .addAllActions(exactBlock(second, "b", 1).getActionsList())
            .build();

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.job(List.of(first, second), sequence), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_WORLD_LOAD_FAILED, failure.code);
        assertEquals(first.lease.uuid(), fixture.registry.activeUuid());
        assertEquals(2, failure.preludeReceipts.size());
        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK, failure.preludeReceipts.get(0).getStatus());
        assertEquals(ReceiptStatus.RECEIPT_STATUS_FAILED, failure.preludeReceipts.get(1).getStatus());
        assertEquals(List.of(0, 1),
            failure.preludeReceipts.stream().map(receipt -> receipt.getActionIndex()).toList());
        assertEquals(List.of(0, 1, 2, 3, 4, 5),
            failure.actionReceipts.stream().map(receipt -> receipt.getActionIndex()).toList());
        assertEquals(ReceiptStatus.RECEIPT_STATUS_FAILED, failure.actionReceipts.get(2).getStatus());
        assertEquals(1234, failure.actionReceipts.get(2).getCapture().getResource().getByteSize());
        assertTrue(failure.actionReceipts.subList(3, 6).stream()
            .allMatch(receipt -> receipt.getStatus() == ReceiptStatus.RECEIPT_STATUS_CANCELLED));
        fixture.assertNoPublishedCapture();
    }

    @Test
    void twoExactBlocksPreserveBothPreludeAndAllUserReceiptIndices() throws Exception {
        Fixture fixture = new Fixture();
        Source first = fixture.source("A");
        Source second = fixture.source("B");
        ResourceCatalog.ResourceDescriptor framebuffer = framebuffer();
        fixture.runtime.catalog = ResourceCatalog.of(List.of(framebuffer), List.of());
        fixture.runtime.deterministicCaptureFileBatches.add(Map.of("a.png", new byte[]{1}));
        fixture.runtime.deterministicCaptureFileBatches.add(Map.of("b.png", new byte[]{2}));
        fixture.runtime.deterministicCaptureOutcomes.add(capturedOutcome("a", framebuffer, 10, 14));
        fixture.runtime.deterministicCaptureOutcomes.add(capturedOutcome("b", framebuffer, 20, 24));
        ActionSequence sequence = ActionSequence.newBuilder()
            .addAllActions(exactBlock(first, "a", 3).getActionsList())
            .addAllActions(exactBlock(second, "b", 3).getActionsList())
            .build();

        TerminalResult terminal = fixture.executor.execute(
            fixture.job(List.of(first, second), sequence), ignored -> {});
        var result = terminal.completed().getResult();

        assertEquals(2, fixture.runtime.deterministicCaptureCalls);
        assertEquals(List.of(0, 1),
            result.getPreludeReceiptsList().stream().map(receipt -> receipt.getActionIndex()).toList());
        assertTrue(result.getPreludeReceiptsList().stream()
            .allMatch(receipt -> receipt.getKind() == ActionKind.ACTION_KIND_LOAD_SHADER &&
                receipt.getStatus() == ReceiptStatus.RECEIPT_STATUS_OK));
        assertEquals(List.of(0, 1, 2, 3, 4, 5),
            result.getActionReceiptsList().stream().map(receipt -> receipt.getActionIndex()).toList());
        assertEquals(List.of(
                ActionKind.ACTION_KIND_RESET_TEMPORAL_STATE,
                ActionKind.ACTION_KIND_WAIT_FRAMES,
                ActionKind.ACTION_KIND_TAKE_SCREENSHOT,
                ActionKind.ACTION_KIND_RESET_TEMPORAL_STATE,
                ActionKind.ACTION_KIND_WAIT_FRAMES,
                ActionKind.ACTION_KIND_TAKE_SCREENSHOT),
            result.getActionReceiptsList().stream().map(receipt -> receipt.getKind()).toList());
        var firstWait = result.getActionReceipts(1).getWaitFrames();
        var secondWait = result.getActionReceipts(4).getWaitFrames();
        assertEquals(10, firstWait.getStartFrame());
        assertEquals(13, firstWait.getEndFrame());
        assertEquals(14, result.getActionReceipts(2).getCapture().getFrameId());
        assertEquals(20, secondWait.getStartFrame());
        assertEquals(23, secondWait.getEndFrame());
        assertEquals(24, result.getActionReceipts(5).getCapture().getFrameId());
        assertTrue(result.getActionReceipts(2).getCapture().getFrameId() !=
            result.getActionReceipts(5).getCapture().getFrameId());
    }

    private static ActionSequence exactBlock(Source source, String artifact, int warmup) {
        ActionSequence.Builder sequence = ActionSequence.newBuilder()
            .addActions(load(source))
            .addActions(Action.newBuilder().setResetTemporalState(ResetTemporalState.getDefaultInstance()));
        if (warmup > 0) {
            sequence.addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(warmup)));
        }
        return sequence.addActions(Action.newBuilder().setTakeScreenshot(TakeScreenshot.newBuilder()
            .setArtifactName(artifact).setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG))).build();
    }

    private static void assertSequenceWrapsCapture(List<String> events) {
        int begin = events.indexOf("deterministic-sequence-begin");
        int capture = events.indexOf("deterministic_capture");
        int end = events.indexOf("deterministic-sequence-end");
        assertTrue(begin >= 0);
        assertTrue(capture > begin);
        assertTrue(end > capture);
        assertEquals(1, events.stream().filter("deterministic-sequence-begin"::equals).count());
        assertEquals(1, events.stream().filter("deterministic-sequence-end"::equals).count());
    }

    private static Action load(Source source) {
        return Action.newBuilder().setPrelude(true).setLoadShader(LoadShader.newBuilder()
            .setSourceUuid(source.lease.uuid())
            .setSourceId("source")
            .setConfigId("config")
            .setConfig(ShaderConfig.newBuilder().setPreserveCurrent(true)))
            .build();
    }

    private static dev.vibris.api.SceneContext context() {
        return new dev.vibris.api.SceneContext(
            "save", "minecraft:overworld", "noon", "clear", "origin", 70.0,
            new dev.vibris.api.SceneContext.Resolution(1280, 720), "night-gi-1-720p");
    }

    private static EffectiveShaderSettings settings(String value) {
        return EffectiveShaderSettings.of(List.of(new EffectiveShaderSettings.Setting(
            "MODE", value, "default", EffectiveShaderSettings.Origin.PRESERVED_CURRENT)));
    }

    private static ReloadResult reload(String value) {
        return ReloadResult.success(settings(value), List.of());
    }

    private static DeterministicTemporalCaptureOutcome.Captured capturedOutcome(
        String artifact,
        ResourceCatalog.ResourceDescriptor framebuffer,
        long anchor,
        long captureFrame
    ) throws RuntimeJobExecutor.Failure {
        return capturedOutcome(artifact, framebuffer, framebuffer(captureFrame), anchor, captureFrame);
    }

    private static DeterministicTemporalCaptureOutcome.Captured capturedOutcome(
        String artifact,
        ResourceCatalog.ResourceDescriptor framebuffer,
        ResourceCatalog.ResourceDescriptor capturedFramebuffer,
        long anchor,
        long captureFrame
    ) throws RuntimeJobExecutor.Failure {
        ResourceCatalog catalog = ResourceCatalog.of(List.of(framebuffer), List.of());
        CapturePlan plan = screenshotPlan(artifact, catalog);
        return new DeterministicTemporalCaptureOutcome.Captured(
            reloaded(catalog, reload("final"), 1), plan, new TemporalResetResult(true), 1,
            (int) (captureFrame - anchor - 1), anchor, captureFrame - 1,
            capture(artifact, capturedFramebuffer, captureFrame));
    }

    private static DeterministicTemporalCaptureReloaded reloaded(
        ResourceCatalog catalog,
        ReloadResult reload,
        long completedAtUnixMs
    ) {
        return new DeterministicTemporalCaptureReloaded(
            ContextApplyResult.success(context()),
            reload,
            completedAtUnixMs,
            catalog,
            CompileCatalog.empty(1)
        );
    }

    private static CapturePlan screenshotPlan(
        String artifact,
        ResourceCatalog catalog
    ) throws RuntimeJobExecutor.Failure {
        CapturePlan.Target target = CapturePlanBuilder.INSTANCE.screenshot(
            catalog,
            CapturePlan.ArtifactFormat.PNG,
            artifact
        );
        return CapturePlanBuilder.INSTANCE.plan(List.of(target), catalog).capture();
    }

    private static DeterministicTemporalCaptureOutcome.Failure failure(
        DeterministicTemporalCaptureOutcome.FailureKind kind,
        String message
    ) {
        return new DeterministicTemporalCaptureOutcome.Failure(kind, message);
    }

    private static DeterministicTemporalCaptureOutcome postReloadOutcome(
        PostReloadOutcome branch,
        ResourceCatalog.ResourceDescriptor framebuffer
    ) throws RuntimeJobExecutor.Failure {
        ResourceCatalog catalog = ResourceCatalog.of(List.of(framebuffer), List.of());
        DeterministicTemporalCaptureReloaded reloaded = reloaded(catalog, reload("old"), 1);
        CapturePlan plan = screenshotPlan("shot", catalog);
        var rejected = failure(
            DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED,
            branch.name() + " rejected"
        );
        return switch (branch) {
            case PLANNING -> new DeterministicTemporalCaptureOutcome.PlanningRejected(
                reloaded(ResourceCatalog.empty(), reload("old"), 1),
                rejected
            );
            case RESET -> new DeterministicTemporalCaptureOutcome.ResetRejected(
                reloaded, plan, new TemporalResetResult(false), rejected
            );
            case WARMUP -> new DeterministicTemporalCaptureOutcome.WarmupRejected(
                reloaded, plan, new TemporalResetResult(true), 1, 3, 0, 1, 1, rejected
            );
            case CAPTURE -> new DeterministicTemporalCaptureOutcome.CaptureRejected(
                reloaded, plan, new TemporalResetResult(true), 1, 3, 0, 3, 4, 4, rejected
            );
            case CAPTURED -> capturedOutcome("shot", framebuffer, 0, 4);
        };
    }

    private static ResourceCatalog.ResourceDescriptor framebuffer() {
        return framebuffer(0);
    }

    private static ResourceCatalog.ResourceDescriptor framebuffer(long frame) {
        return framebuffer(frame, 1280L * 720L * 4L);
    }

    private static ResourceCatalog.ResourceDescriptor framebuffer(long frame, long byteSize) {
        return ResourceCatalog.ResourceDescriptor.of(
            "final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, List.of(),
            1280, 720, 1, 1, 1, "RGBA8", 4, ResourceCatalog.ScalarType.UINT8,
            byteSize, frame, "final", "", "", "RGBA", "unorm", 8,
            "RGBA", "UNSIGNED_BYTE");
    }

    private static CaptureResult capture(
        String artifact,
        ResourceCatalog.ResourceDescriptor framebuffer,
        long frame
    ) {
        return new CaptureResult(frame, List.of(new CaptureResult.ArtifactGroup(
            artifact,
            atFrame(framebuffer, frame),
            List.of(new CaptureResult.CapturedArtifact(
                artifact + ".png", CapturePlan.ArtifactFormat.PNG,
                CapturePlan.ArtifactRole.PRIMARY, null)))));
    }

    private static ResourceCatalog.ResourceDescriptor atFrame(
        ResourceCatalog.ResourceDescriptor resource,
        long frame
    ) {
        return ResourceCatalog.ResourceDescriptor.of(
            resource.logicalName(), resource.kind(), resource.availableViews(),
            resource.width(), resource.height(), resource.depth(), resource.mipLevels(), resource.layers(),
            resource.internalFormat(), resource.channelCount(), resource.scalarType(), resource.byteSize(), frame,
            resource.semanticLabel(), resource.category(), resource.textureTarget(), resource.channelLayout(),
            resource.numericClass(), resource.componentBits(), resource.readbackFormat(), resource.readbackType());
    }

    private enum PostReloadOutcome {
        PLANNING,
        RESET,
        WARMUP,
        CAPTURE,
        CAPTURED,
    }

    private final class Fixture {
        final Path root = Files.createTempDirectory(temp, "fixture-");
        final Path pending = Files.createDirectory(root.resolve("pending"));
        final Path artifactRoot = root.resolve("artifacts");
        final RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        final SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        final RecordingLink link = new RecordingLink(runtime.events);
        final SourceActivator activator = new SourceActivator(registry, link);
        final ArtifactManager artifacts = new ArtifactManager(artifactRoot);
        final RuntimeJobExecutor executor = new RuntimeJobExecutor(runtime, new CoreProbe(), activator, artifacts);

        Fixture() throws Exception {
        }

        Source source(String marker) throws Exception {
            String uuid = UUID.randomUUID().toString();
            PreparedSourceRef reference = SourceTestArchive.source(pending, uuid, marker,
                dev.vibris.protocol.v2.VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED, "main");
            List<SourceRegistry.Lease> leases = registry.reserve(registry.validate(List.of(reference)));
            registry.accept(leases);
            return new Source(marker, leases.getFirst());
        }

        CoreJob job(List<Source> sources, ActionSequence actions) {
            JobSpec.Builder spec = JobSpec.newBuilder()
                .setJobId("job-" + UUID.randomUUID())
                .setContext(SceneContext.newBuilder()
                    .setSaveId("save")
                    .setDimensionId("minecraft:overworld")
                    .setTimePresetId("noon")
                    .setWeatherPresetId("clear")
                    .setCameraPresetId("origin")
                    .setFov(70.0)
                    .setResolution(Resolution.newBuilder().setWidth(1280).setHeight(720))
                    .setSettingsPresetId("night-gi-1-720p"))
                .setActionSequence(actions);
            sources.forEach(source -> spec.addSources(source.lease.reference()));
            CoreJob job = new CoreJob(spec.build(), spec.getJobId(), WORKSPACE_ID, "message", null);
            job.initialize(sources.stream().map(Source::lease).toList());
            return job;
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

        void assertNoPublishedCapture() throws Exception {
            if (!Files.exists(artifactRoot)) return;
            try (var paths = Files.walk(artifactRoot)) {
                assertFalse(paths.anyMatch(path ->
                    path.getFileName().toString().endsWith(".png") ||
                        path.getFileName().toString().equals("manifest.json")));
            }
        }
    }

    private static final class RecordingLink implements ShaderLink {
        private final List<String> events;
        private Path invalidateAfterSwitch;

        RecordingLink(List<String> events) {
            this.events = events;
        }

        @Override
        public void switchTo(SourceRegistry.Lease source, OwnershipCheck ownership) throws Failure {
            ownership.verify();
            try {
                events.add("link:" + Files.readString(source.directory().resolve("main.glsl")));
                if (source.directory().equals(invalidateAfterSwitch)) {
                    Files.move(source.directory(), source.directory().resolveSibling(source.uuid() + "-invalidated"));
                    invalidateAfterSwitch = null;
                }
            } catch (java.io.IOException exception) {
                throw new Failure("test source could not be read", true, exception);
            }
        }

        void invalidateAfterSwitch(Path source) {
            invalidateAfterSwitch = source;
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

    private record Source(String marker, SourceRegistry.Lease lease) {
    }
}
