package dev.vibris.api;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeAdapterContractTest {
    @Test
    void runtimeEnvironmentRejectsBlankIdentityValues() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeEnvironment(
            "", "iris", "vibris", "java", "os", "vendor", "renderer", "opengl", "driver"
        ));
    }

    @Test
    void adapterUsesTypedAsynchronousOperations() {
        SceneContext context = new SceneContext(
            "test-save",
            "minecraft:overworld",
            "sunset",
            "clear",
            "village-rooftop",
            70.0,
            new SceneContext.Resolution(1920, 1080),
            "default"
        );
        CancellationToken.Source cancellation = CancellationToken.source();
        TestAdapter adapter = new TestAdapter();

        assertEquals("test-minecraft", adapter.getRuntimeEnvironment().toCompletableFuture().join().minecraftVersion());
        assertTrue(adapter.getStatus().toCompletableFuture().join().ready());
        assertEquals(context, adapter.ensureWorldAndContext(context, cancellation.token())
            .toCompletableFuture().join().context());
        assertTrue(adapter.reloadVibrisShaderpack(null, cancellation.token())
            .toCompletableFuture().join().successful());
        assertEquals(0, adapter.getCompileCatalog(cancellation.token())
            .toCompletableFuture().join().shaderGeneration());
        assertEquals(7, adapter.waitRenderedFrames(7, cancellation.token()).toCompletableFuture().join());
        assertEquals(9, adapter.capture(
            CapturePlan.empty(), name -> new ByteArrayOutputStream(), cancellation.token()
        ).toCompletableFuture().join().frameId());
        var deterministic = adapter.captureDeterministicTemporalPhase(
            new DeterministicTemporalCaptureRequest(context, true, Map.of(), 7),
            (resources, compile) -> new DeterministicTemporalCapturePlanning.Planned(testCapturePlan()),
            name -> new ByteArrayOutputStream(),
            cancellation.token()
        ).toCompletableFuture().join();
        assertTrue(deterministic instanceof DeterministicTemporalCaptureOutcome.Captured);
        assertEquals(9, ((DeterministicTemporalCaptureOutcome.Captured) deterministic).capture().frameId());
    }

    @Test
    void cancellationIsObservableWithoutRuntimeDependencies() {
        CancellationToken.Source source = CancellationToken.source();
        assertTrue(!source.token().isCancellationRequested());

        source.cancel();

        assertTrue(source.token().isCancellationRequested());
        assertThrows(java.util.concurrent.CancellationException.class, source.token()::throwIfCancellationRequested);
    }

    @Test
    void canonicalConstructorsDefensivelySnapshotCollections() {
        var target = new CapturePlan.Target(
            new CapturePlan.ResourceSelector(
                ResourceCatalog.ResourceKind.TEXTURE,
                "colortex0",
                ResourceCatalog.TextureView.CURRENT,
                0,
                0
            ),
            CapturePlan.ArtifactFormat.PNG,
            "colortex0",
            List.of()
        );
        var targets = new ArrayList<>(List.of(target));
        CapturePlan plan = new CapturePlan(targets);
        targets.clear();
        assertEquals(List.of(target), plan.targets());
        assertThrows(UnsupportedOperationException.class, () -> plan.targets().clear());

        var descriptor = textureDescriptor("colortex0", 1);
        var groups = new ArrayList<>(List.of(
            new CaptureResult.ArtifactGroup("colortex0", descriptor, List.of())
        ));
        CaptureResult result = new CaptureResult(1, groups);
        groups.clear();
        assertEquals(1, result.groups().size());
        assertThrows(UnsupportedOperationException.class, () -> result.groups().clear());

        var errors = new ArrayList<>(List.of("invalid context"));
        ContextValidationResult validation = new ContextValidationResult(false, errors);
        errors.clear();
        assertEquals(List.of("invalid context"), validation.errors());
        assertThrows(UnsupportedOperationException.class, () -> validation.errors().clear());

        var diagnostic = new ReloadResult.Diagnostic(
            ReloadResult.Severity.ERROR, "program.glsl", 7, "compile failed"
        );
        var diagnostics = new ArrayList<>(List.of(diagnostic));
        ReloadResult reload = new ReloadResult(false, true, EffectiveShaderSettings.empty(), diagnostics);
        diagnostics.clear();
        assertEquals(List.of(diagnostic), reload.diagnostics());
        assertThrows(UnsupportedOperationException.class, () -> reload.diagnostics().clear());

        var resources = new ArrayList<>(List.of(descriptor));
        ResourceCatalog catalog = ResourceCatalog.of(resources, List.of());
        resources.clear();
        assertEquals(List.of(descriptor), catalog.resources());
        assertThrows(UnsupportedOperationException.class, () -> catalog.resources().clear());

        var settings = new LinkedHashMap<String, String>();
        settings.put("QUALITY", "high");
        var request = new DeterministicTemporalCaptureRequest(
            testContext(), false, settings, 7
        );
        settings.clear();
        assertEquals(Map.of("QUALITY", "high"), request.settings());
        assertThrows(UnsupportedOperationException.class, () -> request.settings().clear());
    }

    @Test
    void deterministicTemporalCaptureRequestIsStrictAndUnambiguous() {
        SceneContext context = testContext();

        assertEquals(Map.of(), new DeterministicTemporalCaptureRequest(
            context, true, Map.of(), 0
        ).settings());
        assertEquals(Map.of(), new DeterministicTemporalCaptureRequest(
            context, false, Map.of(), 0
        ).settings());
        assertThrows(IllegalArgumentException.class, () -> new DeterministicTemporalCaptureRequest(
            context, true, Map.of("QUALITY", "high"), 0
        ));
        assertThrows(IllegalArgumentException.class, () -> new DeterministicTemporalCaptureRequest(
            context, false, Map.of(), -1
        ));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureRequest(
            null, false, Map.of(), 0
        ));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureRequest(
            context, false, null, 0
        ));
    }

    @Test
    void deterministicTemporalCapturePlanningIsDeferredAndTyped() {
        ResourceCatalog resources = ResourceCatalog.empty();
        CompileCatalog compile = CompileCatalog.empty(17);
        CapturePlan plan = testCapturePlan();
        int[] callCount = {0};
        DeterministicTemporalCapturePlanner planner = (actualResources, actualCompile) -> {
            callCount[0]++;
            assertEquals(resources, actualResources);
            assertEquals(compile, actualCompile);
            return new DeterministicTemporalCapturePlanning.Planned(plan);
        };

        var planned = planner.plan(resources, compile);

        assertEquals(1, callCount[0]);
        assertEquals(plan, ((DeterministicTemporalCapturePlanning.Planned) planned).plan());
        var missing = failure(DeterministicTemporalCaptureOutcome.FailureKind.RESOURCE_NOT_FOUND);
        assertEquals(missing, new DeterministicTemporalCapturePlanning.Rejected(missing).failure());
        assertThrows(IllegalArgumentException.class,
            () -> new DeterministicTemporalCapturePlanning.Planned(CapturePlan.empty()));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCapturePlanning.Planned(null));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCapturePlanning.Rejected(null));
        assertTrue(DeterministicTemporalCapturePlanner.class.isInterface());
        assertTrue(DeterministicTemporalCapturePlanning.class.isSealed());
        assertEquals(Set.of(
            DeterministicTemporalCapturePlanning.Planned.class,
            DeterministicTemporalCapturePlanning.Rejected.class
        ), Set.of(DeterministicTemporalCapturePlanning.class.getPermittedSubclasses()));
    }

    @Test
    void deterministicTemporalCaptureReloadedIsAuthoritative() {
        SceneContext requested = testContext();
        ContextApplyResult applied = ContextApplyResult.success(requested);
        ContextApplyResult rejected = ContextApplyResult.failure(requested, "rejected");
        ReloadResult reload = ReloadResult.success(EffectiveShaderSettings.empty(), List.of());
        ReloadResult reloadRejected = ReloadResult.failure(List.of());
        ResourceCatalog resources = ResourceCatalog.empty();
        CompileCatalog compile = CompileCatalog.empty(17);
        var reloaded = new DeterministicTemporalCaptureReloaded(applied, reload, 23, resources, compile);

        assertEquals(applied, reloaded.context());
        assertEquals(reload, reloaded.reload());
        assertEquals(23, reloaded.reloadCompletedAtUnixMs());
        assertEquals(resources, reloaded.resourceCatalog());
        assertEquals(compile, reloaded.compileCatalog());
        assertThrows(IllegalArgumentException.class, () -> new DeterministicTemporalCaptureReloaded(
            rejected, reload, 23, resources, compile
        ));
        assertThrows(IllegalArgumentException.class, () -> new DeterministicTemporalCaptureReloaded(
            applied, reloadRejected, 23, resources, compile
        ));
        assertThrows(IllegalArgumentException.class, () -> new DeterministicTemporalCaptureReloaded(
            applied, reload, 0, resources, compile
        ));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureReloaded(
            null, reload, 23, resources, compile
        ));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureReloaded(
            applied, null, 23, resources, compile
        ));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureReloaded(
            applied, reload, 23, null, compile
        ));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureReloaded(
            applied, reload, 23, resources, null
        ));
    }

    @Test
    void deterministicTemporalCaptureOutcomesAreMutuallyExclusiveAndFrameExact() {
        SceneContext requested = testContext();
        ContextApplyResult applied = ContextApplyResult.success(requested);
        ContextApplyResult rejected = ContextApplyResult.failure(requested, "rejected");
        ReloadResult reload = ReloadResult.success(EffectiveShaderSettings.empty(), List.of());
        ReloadResult reloadRejected = ReloadResult.failure(List.of());
        DeterministicTemporalCaptureReloaded reloaded = reloaded(applied, reload);
        CapturePlan plan = testCapturePlan();
        TemporalResetResult reset = new TemporalResetResult(true);
        TemporalResetResult resetRejected = new TemporalResetResult(false);
        CaptureResult capturedAtNine = captured(plan, 9);
        var cancelled = failure(DeterministicTemporalCaptureOutcome.FailureKind.CANCELLED);
        var missedTarget = failure(DeterministicTemporalCaptureOutcome.FailureKind.MISSED_TARGET);

        assertEquals(rejected, new DeterministicTemporalCaptureOutcome.ContextRejected(
            rejected, cancelled
        ).context());
        assertEquals(reloadRejected, new DeterministicTemporalCaptureOutcome.ReloadRejected(
            applied, reloadRejected, cancelled
        ).reload());
        assertEquals(reloaded, new DeterministicTemporalCaptureOutcome.PlanningRejected(
            reloaded, missedTarget
        ).reloaded());
        assertEquals(resetRejected, new DeterministicTemporalCaptureOutcome.ResetRejected(
            reloaded, plan, resetRejected, cancelled
        ).reset());
        var warmupRejected = new DeterministicTemporalCaptureOutcome.WarmupRejected(
            reloaded, plan, reset, 1, 7, 1, 3, 4, cancelled
        );
        assertEquals(plan, warmupRejected.plan());
        assertEquals(3, warmupRejected.completedFrames());
        assertEquals(4, warmupRejected.currentFrame());
        var captureRejected = new DeterministicTemporalCaptureOutcome.CaptureRejected(
            reloaded, plan, reset, 1, 7, 1, 8, 9, 10, missedTarget
        );
        assertEquals(8, captureRejected.warmupEndFrame());
        assertEquals(9, captureRejected.targetFrame());
        assertEquals(10, captureRejected.terminalFrame());
        assertEquals(missedTarget, captureRejected.failure());
        assertEquals(1, new DeterministicTemporalCaptureOutcome.CaptureRejected(
            reloaded, plan, reset, 1, 0, 1, 1, 2, 1, cancelled
        ).warmupEndFrame());
        var captured = new DeterministicTemporalCaptureOutcome.Captured(
            reloaded, plan, reset, 1, 7, 1, 8, capturedAtNine
        );
        assertEquals(plan, captured.plan());
        assertEquals(1, captured.anchorFrame());
        assertEquals(8, captured.warmupEndFrame());
        assertEquals(9, captured.capture().frameId());

        assertThrows(NullPointerException.class,
            () -> new DeterministicTemporalCaptureOutcome.ContextRejected(null, cancelled));
        assertThrows(IllegalArgumentException.class,
            () -> new DeterministicTemporalCaptureOutcome.ContextRejected(applied, cancelled));
        assertThrows(NullPointerException.class,
            () -> new DeterministicTemporalCaptureOutcome.ReloadRejected(null, reloadRejected, cancelled));
        assertThrows(NullPointerException.class,
            () -> new DeterministicTemporalCaptureOutcome.ReloadRejected(applied, null, cancelled));
        assertThrows(IllegalArgumentException.class,
            () -> new DeterministicTemporalCaptureOutcome.ReloadRejected(rejected, reloadRejected, cancelled));
        assertThrows(IllegalArgumentException.class,
            () -> new DeterministicTemporalCaptureOutcome.ReloadRejected(applied, reload, cancelled));
        assertThrows(NullPointerException.class,
            () -> new DeterministicTemporalCaptureOutcome.ContextRejected(rejected, null));
        assertThrows(NullPointerException.class,
            () -> new DeterministicTemporalCaptureOutcome.ReloadRejected(applied, reloadRejected, null));
        assertThrows(NullPointerException.class,
            () -> new DeterministicTemporalCaptureOutcome.PlanningRejected(null, cancelled));
        assertThrows(NullPointerException.class,
            () -> new DeterministicTemporalCaptureOutcome.PlanningRejected(reloaded, null));
        assertThrows(IllegalArgumentException.class,
            () -> new DeterministicTemporalCaptureOutcome.ResetRejected(reloaded, plan, reset, cancelled));
        assertThrows(NullPointerException.class,
            () -> new DeterministicTemporalCaptureOutcome.ResetRejected(null, plan, resetRejected, cancelled));
        assertThrows(NullPointerException.class,
            () -> new DeterministicTemporalCaptureOutcome.ResetRejected(reloaded, null, resetRejected, cancelled));
        assertThrows(NullPointerException.class,
            () -> new DeterministicTemporalCaptureOutcome.ResetRejected(reloaded, plan, null, cancelled));
        assertThrows(NullPointerException.class,
            () -> new DeterministicTemporalCaptureOutcome.ResetRejected(reloaded, plan, resetRejected, null));
        assertThrows(IllegalArgumentException.class, () -> new DeterministicTemporalCaptureOutcome.ResetRejected(
            reloaded, CapturePlan.empty(), resetRejected, cancelled
        ));
        assertThrows(IllegalArgumentException.class,
            () -> new DeterministicTemporalCaptureOutcome.Failure(
                DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED, " "
            ));
        assertThrows(NullPointerException.class,
            () -> new DeterministicTemporalCaptureOutcome.Failure(null, "failed"));
        assertThrows(NullPointerException.class,
            () -> new DeterministicTemporalCaptureOutcome.Failure(
                DeterministicTemporalCaptureOutcome.FailureKind.CLEANUP_FAILED, null
            ));
        assertInvalidWarmupRejected(reloaded, plan, resetRejected, 1, 7, 1, 3, 4, cancelled);
        assertInvalidWarmupRejected(reloaded, plan, reset, 0, 7, 1, 3, 4, cancelled);
        assertInvalidWarmupRejected(reloaded, plan, reset, 1, 0, 1, 0, 1, cancelled);
        assertInvalidWarmupRejected(reloaded, plan, reset, 1, 7, -1, 0, -1, cancelled);
        assertInvalidWarmupRejected(reloaded, plan, reset, 1, 7, 1, -1, 0, cancelled);
        assertInvalidWarmupRejected(reloaded, plan, reset, 1, 7, 1, 7, 8, cancelled);
        assertInvalidWarmupRejected(reloaded, plan, reset, 1, 7, 1, 3, 5, cancelled);
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureOutcome.WarmupRejected(
            null, plan, reset, 1, 7, 1, 0, 1, cancelled
        ));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureOutcome.WarmupRejected(
            reloaded, null, reset, 1, 7, 1, 0, 1, cancelled
        ));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureOutcome.WarmupRejected(
            reloaded, plan, null, 1, 7, 1, 0, 1, cancelled
        ));
        assertThrows(IllegalArgumentException.class, () -> new DeterministicTemporalCaptureOutcome.WarmupRejected(
            reloaded, CapturePlan.empty(), reset, 1, 7, 1, 0, 1, cancelled
        ));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureOutcome.WarmupRejected(
            reloaded, plan, reset, 1, 7, 1, 0, 1, null
        ));
        assertThrows(ArithmeticException.class, () -> new DeterministicTemporalCaptureOutcome.WarmupRejected(
            reloaded, plan, reset, 1, 2, Long.MAX_VALUE, 1, Long.MAX_VALUE, cancelled
        ));
        assertInvalidCaptureRejected(reloaded, plan, resetRejected, 1, 7, 1, 8, 9, 9, missedTarget);
        assertInvalidCaptureRejected(reloaded, plan, reset, 0, 7, 1, 8, 9, 9, missedTarget);
        assertInvalidCaptureRejected(reloaded, plan, reset, 1, -1, 1, 1, 2, 2, missedTarget);
        assertInvalidCaptureRejected(reloaded, plan, reset, 1, 7, -1, 6, 7, 7, missedTarget);
        assertInvalidCaptureRejected(reloaded, plan, reset, 1, 7, 1, 7, 8, 8, missedTarget);
        assertInvalidCaptureRejected(reloaded, plan, reset, 1, 7, 1, 8, 8, 8, missedTarget);
        assertInvalidCaptureRejected(reloaded, plan, reset, 1, 7, 1, 8, 9, 7, missedTarget);
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureOutcome.CaptureRejected(
            null, plan, reset, 1, 7, 1, 8, 9, 9, missedTarget
        ));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureOutcome.CaptureRejected(
            reloaded, null, reset, 1, 7, 1, 8, 9, 9, missedTarget
        ));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureOutcome.CaptureRejected(
            reloaded, plan, null, 1, 7, 1, 8, 9, 9, missedTarget
        ));
        assertThrows(IllegalArgumentException.class, () -> new DeterministicTemporalCaptureOutcome.CaptureRejected(
            reloaded, CapturePlan.empty(), reset, 1, 7, 1, 8, 9, 9, missedTarget
        ));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureOutcome.CaptureRejected(
            reloaded, plan, reset, 1, 7, 1, 8, 9, 9, null
        ));
        assertThrows(ArithmeticException.class, () -> new DeterministicTemporalCaptureOutcome.CaptureRejected(
            reloaded, plan, reset, 1, 1, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
            missedTarget
        ));
        assertInvalidCaptured(reloaded, plan, resetRejected, 1, 7, 1, 8, capturedAtNine);
        assertInvalidCaptured(reloaded, plan, reset, 0, 7, 1, 8, capturedAtNine);
        assertInvalidCaptured(reloaded, plan, reset, 1, -1, 1, 8, capturedAtNine);
        assertInvalidCaptured(reloaded, plan, reset, 1, 7, -1, 6, new CaptureResult(7, List.of()));
        assertInvalidCaptured(reloaded, plan, reset, 1, 7, 1, 7, new CaptureResult(8, List.of()));
        assertInvalidCaptured(reloaded, plan, reset, 1, 7, 1, 8, new CaptureResult(10, List.of()));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureOutcome.Captured(
            null, plan, reset, 1, 7, 1, 8, capturedAtNine
        ));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureOutcome.Captured(
            reloaded, plan, null, 1, 7, 1, 8, capturedAtNine
        ));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureOutcome.Captured(
            reloaded, plan, reset, 1, 7, 1, 8, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new DeterministicTemporalCaptureOutcome.Captured(
            reloaded, CapturePlan.empty(), reset, 1, 7, 1, 8, new CaptureResult(9, List.of())
        ));
        assertInvalidCaptured(reloaded, plan, reset, 1, 7, 1, 8, new CaptureResult(9, List.of()));
        assertInvalidCaptured(reloaded, plan, reset, 1, 7, 1, 8, capturedWithIdentity(
            9, "wrong-name", "colortex0", ResourceCatalog.ResourceKind.TEXTURE
        ));
        assertInvalidCaptured(reloaded, plan, reset, 1, 7, 1, 8, capturedWithIdentity(
            9, "colortex0", "colortex1", ResourceCatalog.ResourceKind.TEXTURE
        ));
        assertInvalidCaptured(reloaded, plan, reset, 1, 7, 1, 8, capturedWithIdentity(
            9, "colortex0", "colortex0", ResourceCatalog.ResourceKind.BUFFER
        ));
        assertThrows(ArithmeticException.class, () -> new DeterministicTemporalCaptureOutcome.Captured(
            reloaded, plan, reset, 1, 1, Long.MAX_VALUE, Long.MAX_VALUE, capturedAtNine
        ));
        assertThrows(ArithmeticException.class, () -> new DeterministicTemporalCaptureOutcome.Captured(
            reloaded, plan, reset, 1, 0, Long.MAX_VALUE, Long.MAX_VALUE,
            new CaptureResult(Long.MAX_VALUE, List.of())
        ));
        assertThrows(NullPointerException.class, () -> new DeterministicTemporalCaptureOutcome.Captured(
            reloaded, null, reset, 1, 7, 1, 8, capturedAtNine
        ));
    }

    @Test
    void deterministicTemporalCapturedRequiresExactResourceFramesAndOutputs() {
        DeterministicTemporalCaptureReloaded reloaded = reloaded(
            ContextApplyResult.success(testContext()),
            ReloadResult.success(EffectiveShaderSettings.empty(), List.of())
        );
        CapturePlan plan = capturePlanWithOutputs();
        TemporalResetResult reset = new TemporalResetResult(true);
        List<CapturePlan.ArtifactOutputSpec> outputs = plan.targets().get(0).outputs();
        List<CaptureResult.CapturedArtifact> exact = outputs.stream()
            .map(RuntimeAdapterContractTest::capturedArtifact)
            .toList();
        CaptureResult exactCapture = capturedWithArtifacts(plan, 9, 9, exact);

        var captured = new DeterministicTemporalCaptureOutcome.Captured(
            reloaded, plan, reset, 1, 7, 1, 8, exactCapture
        );
        assertEquals(exact, captured.capture().groups().get(0).artifacts());

        assertInvalidCaptured(reloaded, plan, reset, 1, 7, 1, 8,
            capturedWithArtifacts(plan, 9, 8, exact));
        assertInvalidCaptured(reloaded, plan, reset, 1, 7, 1, 8,
            capturedWithArtifacts(plan, 9, 9, exact.subList(0, 2)));
        assertInvalidCaptured(reloaded, plan, reset, 1, 7, 1, 8,
            capturedWithArtifacts(plan, 9, 9, List.of(exact.get(1), exact.get(0), exact.get(2))));

        CapturePlan.ArtifactOutputSpec primary = outputs.get(0);
        assertInvalidCaptured(reloaded, plan, reset, 1, 7, 1, 8, capturedWithArtifacts(plan, 9, 9, List.of(
            new CaptureResult.CapturedArtifact(
                "wrong.png", primary.format(), primary.role(), primary.subresourceIndex()
            ),
            exact.get(1),
            exact.get(2)
        )));
        assertInvalidCaptured(reloaded, plan, reset, 1, 7, 1, 8, capturedWithArtifacts(plan, 9, 9, List.of(
            new CaptureResult.CapturedArtifact(
                primary.fileName(), CapturePlan.ArtifactFormat.EXR, primary.role(), primary.subresourceIndex()
            ),
            exact.get(1),
            exact.get(2)
        )));
        assertInvalidCaptured(reloaded, plan, reset, 1, 7, 1, 8, capturedWithArtifacts(plan, 9, 9, List.of(
            new CaptureResult.CapturedArtifact(
                primary.fileName(), primary.format(), CapturePlan.ArtifactRole.METADATA, primary.subresourceIndex()
            ),
            exact.get(1),
            exact.get(2)
        )));

        CapturePlan.ArtifactOutputSpec subresource = outputs.get(1);
        assertInvalidCaptured(reloaded, plan, reset, 1, 7, 1, 8, capturedWithArtifacts(plan, 9, 9, List.of(
            exact.get(0),
            new CaptureResult.CapturedArtifact(
                subresource.fileName(), subresource.format(), subresource.role(), 1
            ),
            exact.get(2)
        )));

        CapturePlan emptyOutputs = testCapturePlan();
        assertInvalidCaptured(reloaded, emptyOutputs, reset, 1, 7, 1, 8,
            capturedWithArtifacts(emptyOutputs, 9, 9, List.of(exact.get(0))));
    }

    @Test
    void deterministicTemporalCaptureMethodIsARequiredHardCut() throws NoSuchMethodException {
        var method = VibrisRuntimeAdapter.class.getMethod(
            "captureDeterministicTemporalPhase",
            DeterministicTemporalCaptureRequest.class,
            DeterministicTemporalCapturePlanner.class,
            ArtifactSink.class,
            CancellationToken.class
        );

        assertTrue(Modifier.isAbstract(method.getModifiers()));
        assertTrue(!method.isDefault());
        assertEquals(CompletionStage.class, method.getReturnType());
        assertEquals(1, Arrays.stream(VibrisRuntimeAdapter.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals("captureDeterministicTemporalPhase"))
            .count());
        assertRequiredSequenceMethod("beginDeterministicSequence");
        assertRequiredSequenceMethod("endDeterministicSequence");
        var plannerMethod = DeterministicTemporalCapturePlanner.class.getMethod(
            "plan", ResourceCatalog.class, CompileCatalog.class
        );
        assertTrue(Modifier.isAbstract(plannerMethod.getModifiers()));
        assertTrue(!plannerMethod.isDefault());
        assertEquals(DeterministicTemporalCapturePlanning.class, plannerMethod.getReturnType());
        assertTrue(DeterministicTemporalCaptureOutcome.class.isSealed());
        assertEquals(List.of(
            DeterministicTemporalCaptureOutcome.FailureKind.CANCELLED,
            DeterministicTemporalCaptureOutcome.FailureKind.RESOURCE_NOT_FOUND,
            DeterministicTemporalCaptureOutcome.FailureKind.ARTIFACT_TOO_LARGE,
            DeterministicTemporalCaptureOutcome.FailureKind.ARTIFACT_QUOTA_EXCEEDED,
            DeterministicTemporalCaptureOutcome.FailureKind.INVALID_CAPTURE,
            DeterministicTemporalCaptureOutcome.FailureKind.MISSED_TARGET,
            DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED,
            DeterministicTemporalCaptureOutcome.FailureKind.CLEANUP_FAILED
        ), List.of(DeterministicTemporalCaptureOutcome.FailureKind.values()));
        assertEquals(Set.of(
            DeterministicTemporalCaptureOutcome.ContextRejected.class,
            DeterministicTemporalCaptureOutcome.ReloadRejected.class,
            DeterministicTemporalCaptureOutcome.PlanningRejected.class,
            DeterministicTemporalCaptureOutcome.ResetRejected.class,
            DeterministicTemporalCaptureOutcome.WarmupRejected.class,
            DeterministicTemporalCaptureOutcome.CaptureRejected.class,
            DeterministicTemporalCaptureOutcome.Captured.class
        ), Set.of(DeterministicTemporalCaptureOutcome.class.getPermittedSubclasses()));
    }

    private static void assertRequiredSequenceMethod(String name) throws NoSuchMethodException {
        var method = VibrisRuntimeAdapter.class.getMethod(name, CancellationToken.class);
        assertTrue(Modifier.isAbstract(method.getModifiers()));
        assertTrue(!method.isDefault());
        assertEquals(CompletionStage.class, method.getReturnType());
        assertEquals(1, Arrays.stream(VibrisRuntimeAdapter.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(name))
            .count());
    }

    @Test
    void effectiveSettingsAreCompleteCanonicalAndDeterministic() {
        var quality = new EffectiveShaderSettings.Setting(
            "QUALITY", "high", "medium", EffectiveShaderSettings.Origin.REQUEST_OVERRIDE
        );
        var shadows = new EffectiveShaderSettings.Setting(
            "SHADOWS", "true", "true", EffectiveShaderSettings.Origin.DEFAULT
        );
        var input = new ArrayList<>(List.of(shadows, quality));

        EffectiveShaderSettings override = EffectiveShaderSettings.of(input);
        input.clear();
        assertEquals(List.of(quality, shadows), override.settings());
        assertEquals(Map.of("QUALITY", "high", "SHADOWS", "true"), override.values());
        assertEquals(List.of(quality), override.changedFromDefault());
        assertThrows(UnsupportedOperationException.class, () -> override.settings().clear());

        EffectiveShaderSettings preserved = EffectiveShaderSettings.of(List.of(
            new EffectiveShaderSettings.Setting(
                "SHADOWS", "true", "true", EffectiveShaderSettings.Origin.PRESERVED_CURRENT
            ),
            new EffectiveShaderSettings.Setting(
                "QUALITY", "high", "medium", EffectiveShaderSettings.Origin.PRESERVED_CURRENT
            )
        ));
        assertEquals(override.settingsSha256(), preserved.settingsSha256());
        assertTrue(override.hasSameResolvedState(preserved));
        assertNotEquals(override.settings(), preserved.settings());

        assertThrows(IllegalArgumentException.class, () -> EffectiveShaderSettings.of(List.of(
            quality,
            new EffectiveShaderSettings.Setting(
                "QUALITY", "low", "medium", EffectiveShaderSettings.Origin.PRESET
            )
        )));
        assertThrows(IllegalArgumentException.class, () -> new EffectiveShaderSettings(
            List.of(quality), "0".repeat(64)
        ));
        assertThrows(NullPointerException.class, () -> ReloadResult.success(null, List.of()));
    }

    @Test
    void namedPassResourceContractIsCanonicalAndStrict() {
        var texture = ResourceCatalog.ResourceDescriptor.of(
            "colortex0",
            ResourceCatalog.ResourceKind.TEXTURE,
            List.of(ResourceCatalog.TextureView.ALT, ResourceCatalog.TextureView.CURRENT),
            1920, 1080, 1, 4, 1, "RGBA16F", 4, ResourceCatalog.ScalarType.FLOAT16,
            1920L * 1080 * 8, 7, "", "render_target", "TEXTURE_2D", "RGBA", "float", 16,
            "RGBA", "HALF_FLOAT"
        );
        var buffer = ResourceCatalog.ResourceDescriptor.of(
            "sceneData",
            ResourceCatalog.ResourceKind.BUFFER,
            List.of(),
            0, 0, 0, 0, 0, "binary", 0, ResourceCatalog.ScalarType.UNSPECIFIED,
            4096, 7, "", "shader_storage", "", "", "", 0, "", ""
        );
        var pass = ResourceCatalog.PassDescriptor.of(
            ResourceCatalog.PassStage.COMPOSITE,
            "composite21",
            3,
            List.of("sceneData", "colortex0")
        );

        ResourceCatalog catalog = ResourceCatalog.of(List.of(buffer, texture), List.of(pass));
        assertEquals(List.of("colortex0", "sceneData"),
            catalog.resources().stream().map(ResourceCatalog.ResourceDescriptor::logicalName).toList());
        assertEquals(List.of(ResourceCatalog.TextureView.CURRENT, ResourceCatalog.TextureView.ALT),
            catalog.resources().get(0).availableViews());
        assertEquals("composite/composite21", catalog.passes().get(0).passId());
        assertEquals(catalog.mappingSha256(),
            ResourceCatalog.of(List.of(texture, buffer), List.of(pass)).mappingSha256());

        var target = new CapturePlan.Target(
            new CapturePlan.ResourceSelector(
                ResourceCatalog.ResourceKind.TEXTURE,
                "colortex0",
                ResourceCatalog.TextureView.CURRENT,
                1,
                0
            ),
            CapturePlan.ArtifactFormat.PNG,
            "shade-diffuse",
            List.of()
        );
        var request = new CapturePlan.AfterPassRequest(catalog.mappingSha256(), pass, target);
        assertEquals("composite/composite21", request.pass().passId());
        assertThrows(IllegalArgumentException.class, () -> new CapturePlan.ResourceSelector(
            ResourceCatalog.ResourceKind.TEXTURE, "colortex0.main", ResourceCatalog.TextureView.MAIN, 0, 0
        ));
        assertThrows(IllegalArgumentException.class, () -> new CapturePlan.ResourceSelector(
            ResourceCatalog.ResourceKind.BUFFER, "sceneData", ResourceCatalog.TextureView.CURRENT, 0, 0
        ));
        assertThrows(IllegalArgumentException.class, () -> ResourceCatalog.PassDescriptor.of(
            ResourceCatalog.PassStage.COMPOSITE, "composite21", 3, List.of("colortex0.main")
        ));
        assertThrows(IllegalArgumentException.class, () -> ResourceCatalog.of(
            List.of(texture, texture), List.of(pass)
        ));
    }

    @Test
    void compileCatalogCoversEveryTerminalStateAndHasStableIdentities() {
        String sourceHash = "a".repeat(64);
        var compileError = CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.ERROR, "composite.fsh", 17, 3, "compile failed", "compile.log"
        );
        var linkError = CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.ERROR, "final", 0, 0, "link failed", "link.log"
        );
        var warning = CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.WARNING, "composite.fsh", 4, 1, "unused value"
        );
        var graphics = CompileCatalog.ProgramEntry.of(
            "graphics", "composite", List.of(CompileCatalog.ShaderStage.FRAGMENT, CompileCatalog.ShaderStage.VERTEX),
            CompileCatalog.CompileState.SUCCEEDED, CompileCatalog.CompileState.SUCCEEDED,
            sourceHash, List.of(warning)
        );
        var compute = CompileCatalog.ProgramEntry.of(
            "compute", "prepare", List.of(CompileCatalog.ShaderStage.COMPUTE),
            CompileCatalog.CompileState.SUCCEEDED, CompileCatalog.CompileState.SUCCEEDED,
            "b".repeat(64), List.of()
        );
        var missing = CompileCatalog.ProgramEntry.of(
            "missing", "shadow", List.of(CompileCatalog.ShaderStage.VERTEX, CompileCatalog.ShaderStage.FRAGMENT),
            CompileCatalog.CompileState.NOT_PRESENT, CompileCatalog.CompileState.NOT_PRESENT, "", List.of()
        );
        var compileFailed = CompileCatalog.ProgramEntry.of(
            "compile-failed", "deferred",
            List.of(CompileCatalog.ShaderStage.VERTEX, CompileCatalog.ShaderStage.FRAGMENT),
            CompileCatalog.CompileState.FAILED, CompileCatalog.CompileState.NOT_APPLICABLE,
            "c".repeat(64), List.of(warning, compileError)
        );
        var linkFailed = CompileCatalog.ProgramEntry.of(
            "link-failed", "final", List.of(CompileCatalog.ShaderStage.VERTEX, CompileCatalog.ShaderStage.FRAGMENT),
            CompileCatalog.CompileState.SUCCEEDED, CompileCatalog.CompileState.FAILED,
            "d".repeat(64), List.of(linkError)
        );

        List<CompileCatalog.ProgramEntry> unordered = new ArrayList<>(
            List.of(missing, graphics, linkFailed, compute, compileFailed)
        );
        CompileCatalog first = CompileCatalog.of(unordered, 42);
        java.util.Collections.reverse(unordered);
        CompileCatalog second = CompileCatalog.of(unordered, 42);

        assertEquals(List.of("compile-failed", "compute", "graphics", "link-failed", "missing"),
            first.programs().stream().map(CompileCatalog.ProgramEntry::programId).toList());
        assertEquals(List.of(CompileCatalog.ShaderStage.VERTEX, CompileCatalog.ShaderStage.FRAGMENT),
            graphics.stages());
        assertEquals(first.mappingSha256(), second.mappingSha256());
        assertEquals(first.programs(), second.programs());
        assertEquals(compileError.fingerprintSha256(), CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.ERROR, "composite.fsh", 17, 3, "compile failed", "other.log"
        ).fingerprintSha256());
        assertEquals(List.of(compileError.fingerprintSha256(), warning.fingerprintSha256()).stream().sorted().toList(),
            compileFailed.diagnostics().stream().map(CompileCatalog.Diagnostic::fingerprintSha256).toList());
        assertThrows(UnsupportedOperationException.class, () -> first.programs().clear());
        assertThrows(UnsupportedOperationException.class, () -> graphics.stages().clear());
        assertThrows(IllegalArgumentException.class, () -> new CompileCatalog(
            first.programs(), "0".repeat(64), 42
        ));
    }

    @Test
    void publicValueValidationRemainsIntact() {
        assertThrows(IllegalArgumentException.class, () -> new SceneContext.Resolution(0, 1080));
        assertTrue(!SceneContext.Resolution.unspecified().isSpecified());
        assertThrows(IllegalArgumentException.class, () -> new SceneContext(
            "save", "dimension", "time", "weather", "camera", Double.NaN,
            new SceneContext.Resolution(1, 1), "settings"
        ));
    }

    @Test
    void kotlinDataCarriersPreserveJavaRecordAbi() {
        assertRecord(DeterministicTemporalCaptureRequest.class,
            "context", "preserveCurrentSettings", "settings", "warmupFrames");
        assertRecord(DeterministicTemporalCapturePlanning.Planned.class, "plan");
        assertRecord(DeterministicTemporalCapturePlanning.Rejected.class, "failure");
        assertRecord(DeterministicTemporalCaptureReloaded.class,
            "context", "reload", "reloadCompletedAtUnixMs", "resourceCatalog", "compileCatalog");
        assertRecord(DeterministicTemporalCaptureOutcome.ContextRejected.class, "context", "failure");
        assertRecord(DeterministicTemporalCaptureOutcome.ReloadRejected.class,
            "context", "reload", "failure");
        assertRecord(DeterministicTemporalCaptureOutcome.PlanningRejected.class, "reloaded", "failure");
        assertRecord(DeterministicTemporalCaptureOutcome.ResetRejected.class,
            "reloaded", "plan", "reset", "failure");
        assertRecord(DeterministicTemporalCaptureOutcome.Failure.class, "kind", "message");
        assertRecord(DeterministicTemporalCaptureOutcome.WarmupRejected.class,
            "reloaded", "plan", "reset", "resetCompletedAtUnixMs", "warmupFrames", "anchorFrame",
            "completedFrames", "currentFrame", "failure");
        assertRecord(DeterministicTemporalCaptureOutcome.CaptureRejected.class,
            "reloaded", "plan", "reset", "resetCompletedAtUnixMs", "warmupFrames", "anchorFrame",
            "warmupEndFrame", "targetFrame", "terminalFrame", "failure");
        assertRecord(DeterministicTemporalCaptureOutcome.Captured.class,
            "reloaded", "plan", "reset", "resetCompletedAtUnixMs", "warmupFrames", "anchorFrame",
            "warmupEndFrame", "capture");
        assertRecord(CapturePlan.class, "targets");
        assertRecord(CapturePlan.ResourceSelector.class, "kind", "logicalName", "textureView", "mipLevel", "layer", "region");
        assertRecord(CapturePlan.Target.class, "resource", "format", "artifactName", "outputs");
        assertRecord(CapturePlan.AfterPassRequest.class, "mappingSha256", "pass", "target");
        assertRecord(CapturePlan.AfterPassReceipt.class, "request", "passOccurrence", "physicalName", "capture");
        assertRecord(CaptureResult.class, "frameId", "groups");
        assertRecord(CompileCatalog.class, "programs", "mappingSha256", "shaderGeneration");
        assertRecord(CompileCatalog.ProgramEntry.class, "programId", "passId", "stages", "compileState",
            "linkState", "patchedSourceSha256", "diagnostics");
        assertRecord(CompileCatalog.Diagnostic.class, "severity", "fileName", "line", "column", "message",
            "fingerprintSha256", "logPath");
        assertRecord(ContextValidationResult.class, "valid", "errors");
        assertRecord(EffectiveShaderSettings.class, "settings", "settingsSha256");
        assertRecord(EffectiveShaderSettings.Setting.class, "name", "value", "defaultValue", "origin");
        assertRecord(ReloadResult.class, "successful", "activeStatePreserved", "effectiveSettings", "diagnostics");
        assertRecord(ResourceCatalog.class, "resources", "passes", "mappingSha256", "shaderGeneration");
        assertRecord(RuntimeEnvironment.class, "minecraftVersion", "irisVersion", "vibrisVersion", "javaVersion",
            "operatingSystem", "gpuVendor", "gpuRenderer", "openglVersion", "driverVersion");
        assertRecord(ResourceCatalog.PassDescriptor.class,
            "passId", "stage", "programId", "order", "readableResources", "programs");
        assertRecord(ResourceCatalog.ResourceDescriptor.class,
            "logicalName", "kind", "availableViews", "width", "height", "depth", "mipLevels", "layers",
            "internalFormat", "channelCount", "scalarType", "byteSize", "frameId", "semanticLabel", "category",
            "textureTarget", "channelLayout", "numericClass", "componentBits", "readbackFormat", "readbackType",
            "region", "sourceWidth", "sourceHeight");
    }

    private static void assertRecord(Class<?> type, String... components) {
        assertTrue(type.isRecord(), type.getName() + " must remain a Java record");
        assertEquals(java.lang.Record.class, type.getSuperclass());
        assertEquals(List.of(components), Arrays.stream(type.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList());
    }

    private static final class TestAdapter implements VibrisRuntimeAdapter {
        @Override
        public CompletionStage<RuntimeEnvironment> getRuntimeEnvironment() {
            return CompletableFuture.completedFuture(new RuntimeEnvironment(
                "test-minecraft", "test-iris", "test-vibris", "test-java", "test-os",
                "test-gpu-vendor", "test-gpu-renderer", "test-opengl", "test-driver"
            ));
        }

        @Override
        public CompletionStage<RuntimeStatus> getStatus() {
            return CompletableFuture.completedFuture(new RuntimeStatus(true, "test-save", "minecraft:overworld", ""));
        }

        @Override
        public CompletionStage<ContextApplyResult> ensureWorldAndContext(
            SceneContext context,
            CancellationToken cancellation
        ) {
            return CompletableFuture.completedFuture(ContextApplyResult.success(context));
        }

        @Override
        public CompletionStage<ReloadResult> reloadVibrisShaderpack(
            Map<String, String> config, CancellationToken cancellation
        ) {
            return CompletableFuture.completedFuture(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));
        }

        @Override
        public CompletionStage<CompileCatalog> getCompileCatalog(CancellationToken cancellation) {
            return CompletableFuture.completedFuture(CompileCatalog.empty(0));
        }

        @Override
        public CompletionStage<TemporalResetResult> resetTemporalState(CancellationToken cancellation) {
            return CompletableFuture.completedFuture(new TemporalResetResult(true));
        }

        @Override
        public CompletionStage<Long> waitRenderedFrames(int frameCount, CancellationToken cancellation) {
            return CompletableFuture.completedFuture((long) frameCount);
        }

        @Override
        public ResourceCatalog getResourceCatalog() {
            return ResourceCatalog.empty();
        }

        @Override
        public CompletionStage<CaptureResult> capture(
            CapturePlan plan,
            ArtifactSink sink,
            CancellationToken cancellation
        ) {
            return CompletableFuture.completedFuture(new CaptureResult(9, List.of()));
        }

        @Override
        public CompletionStage<DeterministicTemporalCaptureOutcome> captureDeterministicTemporalPhase(
            DeterministicTemporalCaptureRequest request,
            DeterministicTemporalCapturePlanner planner,
            ArtifactSink sink,
            CancellationToken cancellation
        ) {
            ResourceCatalog resources = ResourceCatalog.of(List.of(textureDescriptor("colortex0", 9)), List.of());
            CompileCatalog compile = CompileCatalog.empty(1);
            var reloaded = new DeterministicTemporalCaptureReloaded(
                ContextApplyResult.success(request.context()),
                ReloadResult.success(EffectiveShaderSettings.empty(), List.of()),
                1,
                resources,
                compile
            );
            var planning = planner.plan(resources, compile);
            if (planning instanceof DeterministicTemporalCapturePlanning.Rejected rejected) {
                return CompletableFuture.completedFuture(new DeterministicTemporalCaptureOutcome.PlanningRejected(
                    reloaded, rejected.failure()
                ));
            }
            CapturePlan plan = ((DeterministicTemporalCapturePlanning.Planned) planning).plan();
            return CompletableFuture.completedFuture(new DeterministicTemporalCaptureOutcome.Captured(
                reloaded,
                plan,
                new TemporalResetResult(true),
                1,
                request.warmupFrames(),
                1,
                1 + request.warmupFrames(),
                captured(plan, 2L + request.warmupFrames())
            ));
        }

        @Override
        public CompletionStage<Void> beginDeterministicSequence(CancellationToken cancellation) {
            cancellation.throwIfCancellationRequested();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> endDeterministicSequence(CancellationToken cancellation) {
            cancellation.throwIfCancellationRequested();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<CapturePlan.AfterPassReceipt> captureAfterPass(
            CapturePlan.AfterPassRequest request,
            ArtifactSink sink,
            CancellationToken cancellation
        ) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("not used"));
        }

        @Override
        public void close() {
        }
    }

    private static ResourceCatalog.ResourceDescriptor textureDescriptor(String name, long frameId) {
        return ResourceCatalog.ResourceDescriptor.of(
            name,
            ResourceCatalog.ResourceKind.TEXTURE,
            List.of(ResourceCatalog.TextureView.CURRENT),
            1, 1, 1, 1, 1, "RGBA8", 4, ResourceCatalog.ScalarType.UINT8,
            4, frameId, name, "render_target", "TEXTURE_2D", "RGBA", "unorm", 8, "RGBA", "UNSIGNED_BYTE"
        );
    }

    private static SceneContext testContext() {
        return new SceneContext(
            "test-save", "minecraft:overworld", "noon", "clear", "origin", 70.0,
            new SceneContext.Resolution(640, 360), "default"
        );
    }

    private static CapturePlan testCapturePlan() {
        return new CapturePlan(List.of(new CapturePlan.Target(
            new CapturePlan.ResourceSelector(
                ResourceCatalog.ResourceKind.TEXTURE,
                "colortex0",
                ResourceCatalog.TextureView.CURRENT,
                0,
                0
            ),
            CapturePlan.ArtifactFormat.PNG,
            "colortex0",
            List.of()
        )));
    }

    private static CapturePlan capturePlanWithOutputs() {
        return new CapturePlan(List.of(new CapturePlan.Target(
            new CapturePlan.ResourceSelector(
                ResourceCatalog.ResourceKind.TEXTURE,
                "colortex0",
                ResourceCatalog.TextureView.CURRENT,
                0,
                0
            ),
            CapturePlan.ArtifactFormat.PNG,
            "colortex0",
            List.of(
                new CapturePlan.ArtifactOutputSpec(
                    "colortex0.png", CapturePlan.ArtifactFormat.PNG, CapturePlan.ArtifactRole.PRIMARY, null
                ),
                new CapturePlan.ArtifactOutputSpec(
                    "colortex0-layer-0.png", CapturePlan.ArtifactFormat.PNG,
                    CapturePlan.ArtifactRole.SUBRESOURCE, 0
                ),
                new CapturePlan.ArtifactOutputSpec(
                    "colortex0.json", CapturePlan.ArtifactFormat.JSON, CapturePlan.ArtifactRole.METADATA, null
                )
            )
        )));
    }

    private static CaptureResult.CapturedArtifact capturedArtifact(CapturePlan.ArtifactOutputSpec output) {
        return new CaptureResult.CapturedArtifact(
            output.fileName(), output.format(), output.role(), output.subresourceIndex()
        );
    }

    private static CaptureResult capturedWithArtifacts(
        CapturePlan plan,
        long frameId,
        long resourceFrameId,
        List<CaptureResult.CapturedArtifact> artifacts
    ) {
        CapturePlan.Target target = plan.targets().get(0);
        return new CaptureResult(frameId, List.of(new CaptureResult.ArtifactGroup(
            target.artifactName(),
            textureDescriptor(target.resource().logicalName(), resourceFrameId),
            artifacts
        )));
    }

    private static CaptureResult captured(CapturePlan plan, long frameId) {
        return new CaptureResult(frameId, plan.targets().stream().map(target -> new CaptureResult.ArtifactGroup(
            target.artifactName(),
            textureDescriptor(target.resource().logicalName(), frameId),
            List.of()
        )).toList());
    }

    private static CaptureResult capturedWithIdentity(
        long frameId,
        String artifactName,
        String logicalName,
        ResourceCatalog.ResourceKind kind
    ) {
        ResourceCatalog.ResourceDescriptor resource = kind == ResourceCatalog.ResourceKind.TEXTURE
            ? textureDescriptor(logicalName, frameId)
            : new ResourceCatalog.ResourceDescriptor(
                logicalName, kind, List.of(), 0, 0, 0, 0, 0, "", 0,
                ResourceCatalog.ScalarType.UNSPECIFIED, 0, frameId, "", "", "", "", "", 0, "", ""
            );
        return new CaptureResult(frameId, List.of(new CaptureResult.ArtifactGroup(
            artifactName, resource, List.of()
        )));
    }

    private static DeterministicTemporalCaptureReloaded reloaded(
        ContextApplyResult context,
        ReloadResult reload
    ) {
        return new DeterministicTemporalCaptureReloaded(
            context, reload, 1, ResourceCatalog.empty(), CompileCatalog.empty(1)
        );
    }

    private static DeterministicTemporalCaptureOutcome.Failure failure(
        DeterministicTemporalCaptureOutcome.FailureKind kind
    ) {
        return new DeterministicTemporalCaptureOutcome.Failure(kind, kind.name());
    }

    private static void assertInvalidCaptured(
        DeterministicTemporalCaptureReloaded reloaded,
        CapturePlan plan,
        TemporalResetResult reset,
        long resetCompletedAtUnixMs,
        int warmupFrames,
        long anchorFrame,
        long warmupEndFrame,
        CaptureResult capture
    ) {
        assertThrows(IllegalArgumentException.class, () -> new DeterministicTemporalCaptureOutcome.Captured(
            reloaded, plan, reset, resetCompletedAtUnixMs, warmupFrames, anchorFrame, warmupEndFrame, capture
        ));
    }

    private static void assertInvalidWarmupRejected(
        DeterministicTemporalCaptureReloaded reloaded,
        CapturePlan plan,
        TemporalResetResult reset,
        long resetCompletedAtUnixMs,
        int warmupFrames,
        long anchorFrame,
        int completedFrames,
        long currentFrame,
        DeterministicTemporalCaptureOutcome.Failure failure
    ) {
        assertThrows(IllegalArgumentException.class, () -> new DeterministicTemporalCaptureOutcome.WarmupRejected(
            reloaded, plan, reset, resetCompletedAtUnixMs, warmupFrames, anchorFrame, completedFrames, currentFrame,
            failure
        ));
    }

    private static void assertInvalidCaptureRejected(
        DeterministicTemporalCaptureReloaded reloaded,
        CapturePlan plan,
        TemporalResetResult reset,
        long resetCompletedAtUnixMs,
        int warmupFrames,
        long anchorFrame,
        long warmupEndFrame,
        long targetFrame,
        long terminalFrame,
        DeterministicTemporalCaptureOutcome.Failure failure
    ) {
        assertThrows(IllegalArgumentException.class, () -> new DeterministicTemporalCaptureOutcome.CaptureRejected(
            reloaded, plan, reset, resetCompletedAtUnixMs, warmupFrames, anchorFrame, warmupEndFrame, targetFrame,
            terminalFrame, failure
        ));
    }
}
