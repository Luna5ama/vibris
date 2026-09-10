package dev.vibris.core;

import dev.vibris.api.ArtifactSink;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.EffectiveShaderSettings;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.protocol.v2.Action;
import dev.vibris.protocol.v2.ActionKind;
import dev.vibris.protocol.v2.ActionSequence;
import dev.vibris.protocol.v2.ActivateSource;
import dev.vibris.protocol.v2.ArtifactFormat;
import dev.vibris.protocol.v2.ArtifactKind;
import dev.vibris.protocol.v2.DumpBuffer;
import dev.vibris.protocol.v2.DumpBufferAfterPass;
import dev.vibris.protocol.v2.DumpTexture;
import dev.vibris.protocol.v2.DumpTextureAfterPass;
import dev.vibris.protocol.v2.ErrorCode;
import dev.vibris.protocol.v2.GetPatchedShaders;
import dev.vibris.protocol.v2.JobSpec;
import dev.vibris.protocol.v2.LoadShader;
import dev.vibris.protocol.v2.PreparedSourceRef;
import dev.vibris.protocol.v2.ReceiptStatus;
import dev.vibris.protocol.v2.RestorePolicy;
import dev.vibris.protocol.v2.SceneContext;
import dev.vibris.protocol.v2.ShaderConfig;
import dev.vibris.protocol.v2.TakeScreenshot;
import dev.vibris.protocol.v2.ResourceSelector;
import dev.vibris.protocol.v2.TextureView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeJobExecutorCaptureTest {
    private static final String WORKSPACE_ID = "11111111-1111-4111-8111-111111111111";

    @TempDir
    Path temp;

    @Test
    void capturesScreenshotTextureAndBufferIntoStrictV2Artifacts() throws Exception {
        Fixture fixture = new Fixture();
        ResourceCatalog.ResourceDescriptor screenshot = resource(
            "final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 77, 16);
        ResourceCatalog.ResourceDescriptor texture = resource(
            "colortex0", ResourceCatalog.ResourceKind.TEXTURE, 77, 16);
        ResourceCatalog.ResourceDescriptor buffer = resource(
            "radiance_cache", ResourceCatalog.ResourceKind.BUFFER, 77, 8);
        fixture.runtime.catalog = ResourceCatalog.of(List.of(screenshot, texture, buffer), List.of());
        LinkedHashMap<String, ResourceCatalog.ResourceDescriptor> captured = new LinkedHashMap<>();
        captured.put("screenshot", screenshot);
        captured.put("colortex0", texture);
        captured.put("radiance_cache", buffer);
        fixture.runtime.captureResult = captureResult(77, captured);
        fixture.runtime.captureFiles.put("screenshot.png", new byte[]{1, 2, 3});
        fixture.runtime.captureFiles.put("colortex0.bin", new byte[]{4, 5, 6, 7});
        fixture.runtime.captureFiles.put("colortex0.json", "{}".getBytes(StandardCharsets.UTF_8));
        fixture.runtime.captureFiles.put("radiance_cache.bin", new byte[]{8, 9});
        fixture.runtime.captureFiles.put("radiance_cache.json", "{}".getBytes(StandardCharsets.UTF_8));

        TerminalResult terminal = fixture.executor.execute(fixture.job(ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setTakeScreenshot(TakeScreenshot.newBuilder()
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG).setArtifactName("screenshot")))
            .addActions(Action.newBuilder().setDumpTexture(DumpTexture.newBuilder()
                .setResource(ResourceSelector.newBuilder().setLogicalName("colortex0")
                    .setView(TextureView.TEXTURE_VIEW_CURRENT))
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_BIN).setArtifactName("colortex0")))
            .addActions(Action.newBuilder().setDumpBuffer(DumpBuffer.newBuilder()
                .setResource(ResourceSelector.newBuilder().setLogicalName("radiance_cache"))
                .setArtifactName("radiance_cache")))
            .build()), ignored -> {});

        var result = terminal.completed().getResult();
        assertEquals(List.of("link:A", "reload", "context", "capture"), fixture.runtime.events);
        assertEquals(result.getActionReceipts(0).getRuntimeMutation().getSourceUuid(),
            result.getProvenance().getActiveSourceUuid());
        assertEquals(7, result.getArtifactsCount());
        assertEquals(List.of(
                ArtifactKind.ARTIFACT_KIND_SCREENSHOT,
                ArtifactKind.ARTIFACT_KIND_TEXTURE,
                ArtifactKind.ARTIFACT_KIND_TEXTURE,
                ArtifactKind.ARTIFACT_KIND_BUFFER,
                ArtifactKind.ARTIFACT_KIND_BUFFER,
                ArtifactKind.ARTIFACT_KIND_SHADER_COMPILE_LOG,
                ArtifactKind.ARTIFACT_KIND_MANIFEST),
            result.getArtifactsList().stream().map(artifact -> artifact.getKind()).toList());
        assertEquals(List.of(
                ActionKind.ACTION_KIND_ACTIVATE_SOURCE,
                ActionKind.ACTION_KIND_TAKE_SCREENSHOT,
                ActionKind.ACTION_KIND_DUMP_TEXTURE,
                ActionKind.ACTION_KIND_DUMP_BUFFER),
            result.getActionReceiptsList().stream().map(receipt -> receipt.getKind()).toList());
        assertEquals(List.of(0, 1, 2, 3),
            result.getActionReceiptsList().stream().map(receipt -> receipt.getActionIndex()).toList());
        assertTrue(result.getActionReceiptsList().stream()
            .allMatch(receipt -> receipt.getStatus() == ReceiptStatus.RECEIPT_STATUS_OK));
        assertEquals(1, result.getActionReceipts(1).getCapture().getArtifactsCount());
        assertEquals("final", result.getActionReceipts(1).getCapture().getResource().getLogicalName());
        assertEquals(2, result.getActionReceipts(2).getCapture().getArtifactsCount());
        assertEquals("colortex0", result.getActionReceipts(2).getCapture().getResource().getLogicalName());
        assertEquals(2, result.getActionReceipts(3).getCapture().getArtifactsCount());
        assertEquals("radiance_cache", result.getActionReceipts(3).getCapture().getResource().getLogicalName());
        assertTrue(result.getArtifactsList().stream()
            .map(artifact -> Path.of(artifact.getRelativePath()))
            .allMatch(Files::isRegularFile));
        assertFalse(result.getResultManifestId().isBlank());
    }

    @Test
    void freshRuntimeLoadsThenPlansScreenshotFromPostLoadCatalog() throws Exception {
        Fixture fixture = new Fixture();
        EffectiveShaderSettings high = EffectiveShaderSettings.of(List.of(
            new EffectiveShaderSettings.Setting(
                "QUALITY", "high", "low", EffectiveShaderSettings.Origin.REQUEST_OVERRIDE)
        ));
        EffectiveShaderSettings low = EffectiveShaderSettings.of(List.of(
            new EffectiveShaderSettings.Setting(
                "QUALITY", "low", "low", EffectiveShaderSettings.Origin.DEFAULT)
        ));
        fixture.runtime.reloads.add(ReloadResult.success(high, List.of()));
        TerminalResult bootstrap = fixture.executor.execute(
            fixture.loadJob(false, ActionSequence.getDefaultInstance(), false),
            ignored -> {}
        );
        assertEquals(1, bootstrap.completed().getResult().getActionReceiptsCount());
        assertEquals(ActionKind.ACTION_KIND_LOAD_SHADER,
            bootstrap.completed().getResult().getActionReceipts(0).getKind());
        assertEquals(1, fixture.runtime.shaderConfigs.size());

        ResourceCatalog.ResourceDescriptor screenshot = resource(
            "final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 12, 16);
        fixture.runtime.catalog = ResourceCatalog.empty();
        fixture.runtime.beforeReloadResult = () -> fixture.runtime.catalog =
            ResourceCatalog.of(List.of(screenshot), List.of());
        fixture.runtime.captureResult = captureResult(12, Map.of("screenshot", screenshot));
        fixture.runtime.captureFiles.put("screenshot.png", new byte[]{1, 2, 3});
        fixture.runtime.reloads.add(ReloadResult.success(low, List.of()));
        fixture.runtime.reloads.add(ReloadResult.success(high, List.of()));

        TerminalResult terminal = fixture.executor.execute(
            fixture.loadJob(
                true,
                screenshotActions(),
                true,
                ShaderConfig.newBuilder().putValues("QUALITY", "low").build()
            ),
            ignored -> {}
        );

        var result = terminal.completed().getResult();
        assertEquals(1, result.getPreludeReceiptsCount());
        assertEquals(ActionKind.ACTION_KIND_LOAD_SHADER, result.getPreludeReceipts(0).getKind());
        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK, result.getPreludeReceipts(0).getStatus());
        assertEquals(1, result.getActionReceiptsCount());
        assertEquals(ActionKind.ACTION_KIND_TAKE_SCREENSHOT, result.getActionReceipts(0).getKind());
        assertEquals("final", result.getActionReceipts(0).getCapture().getResource().getLogicalName());
        assertEquals(3, fixture.runtime.shaderConfigs.size(),
            "the screenshot job must execute one load and one transactional restore");
        assertTrue(result.getArtifactsList().stream().anyMatch(artifact ->
            artifact.getKind() == ArtifactKind.ARTIFACT_KIND_SCREENSHOT));
    }

    @Test
    void groupsExactAfterPassRequestsAndPublishesCompleteReceipts() throws Exception {
        Fixture fixture = new Fixture();
        ResourceCatalog.ResourceDescriptor texture = resource(
            "colortex0", ResourceCatalog.ResourceKind.TEXTURE, 91, 16);
        ResourceCatalog.ResourceDescriptor buffer = resource(
            "scene_ssbo", ResourceCatalog.ResourceKind.BUFFER, 91, 4);
        ResourceCatalog.PassDescriptor pass = ResourceCatalog.PassDescriptor.of(
            ResourceCatalog.PassStage.COMPOSITE,
            "composite21",
            0,
            List.of("colortex0", "scene_ssbo")
        );
        fixture.runtime.catalog = ResourceCatalog.of(List.of(texture, buffer), List.of(pass));
        List<PendingAfterPass> pending = new ArrayList<>();
        fixture.runtime.afterPassOperation = (request, sink, cancellation) -> {
            CompletableFuture<CapturePlan.AfterPassReceipt> future = new CompletableFuture<>();
            pending.add(new PendingAfterPass(request, sink, future));
            if (pending.size() == 3) {
                for (PendingAfterPass capture : pending) {
                    try {
                        ResourceCatalog.ResourceDescriptor capturedResource =
                            capture.request().target().resource().kind() == ResourceCatalog.ResourceKind.BUFFER
                                ? buffer : texture;
                        writeAfterPassArtifacts(capture.sink(), capture.request().target());
                        ResourceCatalog.TextureView view = capture.request().target().resource().textureView();
                        String physicalName = view == null
                            ? "scene_ssbo"
                            : switch (view) {
                                case CURRENT -> "colortex0.main";
                                case ALTERNATE -> "colortex0.alt";
                                default -> throw new AssertionError("unexpected texture view");
                            };
                        capture.future().complete(new CapturePlan.AfterPassReceipt(
                            capture.request(),
                            2,
                            physicalName,
                            captureResult(capture.request().target(), capturedResource, 91)
                        ));
                    } catch (Exception exception) {
                        capture.future().completeExceptionally(exception);
                    }
                }
            }
            return future;
        };

        TerminalResult terminal = fixture.executor.execute(fixture.job(ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setDumpTextureAfterPass(DumpTextureAfterPass.newBuilder()
                .setPassId(pass.passId())
                .setResource(ResourceSelector.newBuilder().setLogicalName("colortex0")
                    .setView(TextureView.TEXTURE_VIEW_CURRENT))
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG).setArtifactName("current-after")))
            .addActions(Action.newBuilder().setDumpTextureAfterPass(DumpTextureAfterPass.newBuilder()
                .setPassId(pass.passId())
                .setResource(ResourceSelector.newBuilder().setLogicalName("colortex0")
                    .setView(TextureView.TEXTURE_VIEW_ALTERNATE))
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG).setArtifactName("alternate-after")))
            .addActions(Action.newBuilder().setDumpBufferAfterPass(DumpBufferAfterPass.newBuilder()
                .setPassId(pass.passId())
                .setResource(ResourceSelector.newBuilder().setLogicalName("scene_ssbo"))
                .setArtifactName("buffer-after")))
            .build()), ignored -> {});

        var result = terminal.completed().getResult();
        assertEquals(List.of(
            "link:A", "reload", "context",
            "capture_after_pass:current-after",
            "capture_after_pass:alternate-after",
            "capture_after_pass:buffer-after"
        ), fixture.runtime.events);
        assertEquals(3, pending.size());
        assertEquals(4, result.getActionReceiptsCount());
        var current = result.getActionReceipts(1).getCapture();
        var alternate = result.getActionReceipts(2).getCapture();
        var capturedBuffer = result.getActionReceipts(3).getCapture();
        assertEquals(pass.passId(), current.getPassId());
        assertEquals(2, current.getPassOccurrence());
        assertEquals(91, current.getFrameId());
        assertEquals("colortex0", current.getResource().getLogicalName());
        assertEquals("colortex0.main", current.getResource().getPhysicalName());
        assertEquals(List.of(TextureView.TEXTURE_VIEW_CURRENT), current.getResource().getAvailableViewsList());
        assertTrue(current.getArtifactsList().stream().allMatch(artifact ->
            artifact.getResource().getPhysicalName().equals("colortex0.main") &&
                artifact.getResource().getAvailableViewsList().equals(List.of(TextureView.TEXTURE_VIEW_CURRENT))));
        assertEquals("colortex0.alt", alternate.getResource().getPhysicalName());
        assertEquals(List.of(TextureView.TEXTURE_VIEW_ALTERNATE), alternate.getResource().getAvailableViewsList());
        assertEquals("scene_ssbo", capturedBuffer.getResource().getPhysicalName());
        assertEquals(4, capturedBuffer.getResource().getByteSize());
        assertTrue(result.getArtifactsList().stream().allMatch(artifact -> !artifact.getSha256().isBlank()));
        assertTrue(result.getArtifactsList().stream()
            .map(artifact -> Path.of(artifact.getRelativePath()))
            .allMatch(Files::isRegularFile));
        assertTrue(result.getArtifactsList().stream().anyMatch(artifact ->
            artifact.getKind() == ArtifactKind.ARTIFACT_KIND_MANIFEST &&
                artifact.getArtifactId().equals(result.getResultManifestId())));
        var bin = result.getArtifactsList().stream()
            .filter(artifact -> Path.of(artifact.getRelativePath()).getFileName().toString().equals("buffer-after.bin"))
            .findFirst().orElseThrow();
        assertArrayEquals(new byte[]{0x11, 0x22, 0x33, 0x44},
            Files.readAllBytes(Path.of(bin.getRelativePath())));
    }

    @Test
    void afterPassTimeoutCancelsAndReleasesThePendingRegistration() throws Exception {
        Fixture fixture = new Fixture();
        ResourceCatalog.PassDescriptor pass = afterPassCatalog(fixture);
        AtomicInteger pending = new AtomicInteger();
        fixture.runtime.afterPassOperation = (request, sink, cancellation) ->
            cancellationFuture(cancellation, pending, null);
        CoreJob job = fixture.job(singleAfterPassAction(pass), 100);

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(job, ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_EXECUTION_TIMEOUT, failure.code);
        assertEquals(0, pending.get());
        assertNoTemporaryOrManifest(fixture.artifactRoot);
    }

    @Test
    void explicitCancellationReleasesThePendingAfterPassRegistration() throws Exception {
        Fixture fixture = new Fixture();
        ResourceCatalog.PassDescriptor pass = afterPassCatalog(fixture);
        AtomicInteger pending = new AtomicInteger();
        CountDownLatch registered = new CountDownLatch(1);
        fixture.runtime.afterPassOperation = (request, sink, cancellation) ->
            cancellationFuture(cancellation, pending, registered);
        CoreJob job = fixture.job(singleAfterPassAction(pass));
        CompletableFuture<RuntimeJobExecutor.Failure> execution = CompletableFuture.supplyAsync(() -> {
            try {
                fixture.executor.execute(job, ignored -> {});
                throw new AssertionError("cancelled job completed successfully");
            } catch (RuntimeJobExecutor.Failure failure) {
                return failure;
            }
        });

        assertTrue(registered.await(5, TimeUnit.SECONDS));
        job.cancellation.cancel();
        RuntimeJobExecutor.Failure failure = execution.get(5, TimeUnit.SECONDS);

        assertEquals(ErrorCode.ERROR_CODE_CANCELLED, failure.code);
        assertEquals(0, pending.get());
        assertNoTemporaryOrManifest(fixture.artifactRoot);
    }

    @Test
    void groupedAfterPassErrorCancelsItsSiblingAndRollsBackArtifacts() throws Exception {
        Fixture fixture = new Fixture();
        ResourceCatalog.PassDescriptor pass = afterPassCatalog(fixture);
        AtomicInteger pending = new AtomicInteger();
        List<CompletableFuture<CapturePlan.AfterPassReceipt>> stages = new ArrayList<>();
        fixture.runtime.afterPassOperation = (request, sink, cancellation) -> {
            CompletableFuture<CapturePlan.AfterPassReceipt> stage = stages.isEmpty()
                ? new CompletableFuture<>()
                : cancellationFuture(cancellation, pending, null);
            if (stages.isEmpty()) pending.incrementAndGet();
            stages.add(stage);
            if (stages.size() == 2) {
                pending.decrementAndGet();
                stages.getFirst().completeExceptionally(new IllegalStateException("capture failed"));
            }
            return stage;
        };
        ActionSequence actions = ActionSequence.newBuilder()
            .addAllActions(singleAfterPassAction(pass).getActionsList())
            .addAllActions(singleAfterPassAction(pass, "second-after").getActionsList())
            .build();

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.job(actions), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_CAPTURE_FAILED, failure.code);
        assertEquals(0, pending.get());
        assertNoTemporaryOrManifest(fixture.artifactRoot);
    }

    @Test
    void keepsScreenshotFrameDelayInsideItsSingleCaptureReceipt() throws Exception {
        Fixture fixture = new Fixture();
        ResourceCatalog.ResourceDescriptor screenshot = resource(
            "final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 2, 16);
        fixture.runtime.catalog = ResourceCatalog.of(List.of(screenshot), List.of());
        fixture.runtime.captureResult = captureResult(2, Map.of("delayed", screenshot));
        fixture.runtime.captureFiles.put("delayed.png", new byte[]{1, 2, 3});

        TerminalResult terminal = fixture.executor.execute(fixture.job(ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setTakeScreenshot(TakeScreenshot.newBuilder()
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG)
                .setArtifactName("delayed")
                .setAfterFrames(2)))
            .build()), ignored -> {});

        var result = terminal.completed().getResult();
        assertEquals(List.of("link:A", "reload", "context", "frames", "capture"), fixture.runtime.events);
        assertEquals(2, result.getActionReceiptsCount());
        var screenshotReceipt = result.getActionReceipts(1);
        assertEquals(1, screenshotReceipt.getActionIndex());
        assertEquals(ActionKind.ACTION_KIND_TAKE_SCREENSHOT, screenshotReceipt.getKind());
        assertTrue(screenshotReceipt.hasCapture());
        assertTrue(screenshotReceipt.getCapture().hasInternalWait());
        assertEquals(2, screenshotReceipt.getCapture().getInternalWait().getRequestedFrames());
        assertEquals(0, screenshotReceipt.getCapture().getInternalWait().getStartFrame());
        assertEquals(2, screenshotReceipt.getCapture().getInternalWait().getEndFrame());
    }

    @Test
    void unknownResourceAfterActivationAbortsArtifactJob() throws Exception {
        Fixture fixture = new Fixture();
        fixture.runtime.catalog = ResourceCatalog.of(List.of(
            resource("final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 1, 16)), List.of());
        ActionSequence actions = ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setDumpTexture(DumpTexture.newBuilder()
                .setResource(ResourceSelector.newBuilder().setLogicalName("missing")
                    .setView(TextureView.TEXTURE_VIEW_CURRENT))
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_BIN).setArtifactName("missing")))
            .build();

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.job(actions), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_RESOURCE_NOT_FOUND, failure.code);
        assertFalse(fixture.runtime.events.contains("capture"));
        assertNoTemporaryOrManifest(fixture.artifactRoot);
    }

    @Test
    void runtimeQuotaOverrunUsesTypedFailureAndRollsBack() throws Exception {
        Fixture fixture = new Fixture(64);
        ResourceCatalog.ResourceDescriptor screenshot = resource(
            "final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 1, 0);
        fixture.runtime.catalog = ResourceCatalog.of(List.of(screenshot), List.of());
        fixture.runtime.captureResult = captureResult(1, Map.of("screenshot", screenshot));
        fixture.runtime.captureFiles.put("screenshot.png", new byte[128]);

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.job(screenshotActions()), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_ARTIFACT_TOO_LARGE, failure.code);
        assertNoTemporaryOrManifest(fixture.artifactRoot);
    }

    @Test
    void missingRuntimeArtifactFailsBeforeManifestCommit() throws Exception {
        Fixture fixture = new Fixture();
        ResourceCatalog.ResourceDescriptor screenshot = resource(
            "final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 1, 16);
        fixture.runtime.catalog = ResourceCatalog.of(List.of(screenshot), List.of());
        fixture.runtime.captureResult = captureResult(1, Map.of("screenshot", screenshot));

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.job(screenshotActions()), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_CAPTURE_FAILED, failure.code);
        assertNoTemporaryOrManifest(fixture.artifactRoot);
    }

    @Test
    void capturesPatchedShadersWithStrictV2ArtifactMetadata() throws Exception {
        Fixture fixture = new Fixture();
        ResourceCatalog.ResourceDescriptor resource = resource(
            "patched_shaders", ResourceCatalog.ResourceKind.PATCHED_SHADERS, 81, 12);
        fixture.runtime.patchedShaderFiles.put("patched.001_begin.vsh",
            "vertex".getBytes(StandardCharsets.UTF_8));
        fixture.runtime.patchedShaderFiles.put("patched.002_begin.json",
            "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        fixture.runtime.patchedShaderResult = new CaptureResult(81, List.of(new CaptureResult.ArtifactGroup(
            "patched", resource, List.of(
                new CaptureResult.CapturedArtifact("patched.001_begin.vsh",
                    dev.vibris.api.CapturePlan.ArtifactFormat.TEXT,
                    dev.vibris.api.CapturePlan.ArtifactRole.SUBRESOURCE, 0),
                new CaptureResult.CapturedArtifact("patched.002_begin.json",
                    dev.vibris.api.CapturePlan.ArtifactFormat.JSON,
                    dev.vibris.api.CapturePlan.ArtifactRole.METADATA, 0)))));

        TerminalResult terminal = fixture.executor.execute(fixture.job(ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setGetPatchedShaders(GetPatchedShaders.newBuilder()
                .setArtifactName("patched")))
            .build()), ignored -> {});

        var result = terminal.completed().getResult();
        assertEquals(ActionKind.ACTION_KIND_GET_PATCHED_SHADERS,
            result.getActionReceipts(1).getKind());
        assertTrue(result.getActionReceipts(1).hasPatchedShaders());
        assertEquals(81, result.getActionReceipts(1).getPatchedShaders().getShaderGeneration());
        assertEquals(2, result.getActionReceipts(1).getPatchedShaders().getArtifactsCount());
        assertEquals(2, result.getArtifactsList().stream()
            .filter(artifact -> artifact.getKind() == ArtifactKind.ARTIFACT_KIND_PATCHED_SHADER)
            .count());
        assertTrue(result.getArtifactsList().stream()
            .filter(artifact -> artifact.getKind() == ArtifactKind.ARTIFACT_KIND_PATCHED_SHADER)
            .allMatch(artifact -> Files.isRegularFile(Path.of(artifact.getRelativePath()))));
    }

    private static ActionSequence screenshotActions() {
        return ActionSequence.newBuilder().addActions(Action.newBuilder().setTakeScreenshot(
            TakeScreenshot.newBuilder().setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG)
                .setArtifactName("screenshot"))).build();
    }

    private static ResourceCatalog.PassDescriptor afterPassCatalog(Fixture fixture) {
        ResourceCatalog.ResourceDescriptor texture = resource(
            "colortex0", ResourceCatalog.ResourceKind.TEXTURE, 1, 16);
        ResourceCatalog.PassDescriptor pass = ResourceCatalog.PassDescriptor.of(
            ResourceCatalog.PassStage.COMPOSITE,
            "composite21",
            0,
            List.of(texture.logicalName())
        );
        fixture.runtime.catalog = ResourceCatalog.of(List.of(texture), List.of(pass));
        return pass;
    }

    private static ActionSequence singleAfterPassAction(ResourceCatalog.PassDescriptor pass) {
        return singleAfterPassAction(pass, "texture-after");
    }

    private static ActionSequence singleAfterPassAction(
        ResourceCatalog.PassDescriptor pass,
        String artifactName
    ) {
        return ActionSequence.newBuilder().addActions(
            Action.newBuilder().setDumpTextureAfterPass(DumpTextureAfterPass.newBuilder()
                .setPassId(pass.passId())
                .setResource(ResourceSelector.newBuilder().setLogicalName("colortex0")
                    .setView(TextureView.TEXTURE_VIEW_CURRENT))
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG)
                .setArtifactName(artifactName))
        ).build();
    }

    private static CompletableFuture<CapturePlan.AfterPassReceipt> cancellationFuture(
        CancellationToken cancellation,
        AtomicInteger pending,
        CountDownLatch registered
    ) {
        pending.incrementAndGet();
        if (registered != null) registered.countDown();
        return CompletableFuture.supplyAsync(() -> {
            try {
                while (!cancellation.isCancellationRequested()) LockSupport.parkNanos(100_000);
                throw new CancellationException("pending after-pass capture cancelled");
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    private static void assertNoTemporaryOrManifest(Path root) throws Exception {
        try (var files = Files.walk(root)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp") ||
                path.getFileName().toString().equals("manifest.json")));
        }
    }

    private static CaptureResult captureResult(
        long frameId, Map<String, ResourceCatalog.ResourceDescriptor> resources) {
        return new CaptureResult(frameId, resources.entrySet().stream().map(entry -> {
            String name = entry.getKey();
            ResourceCatalog.ResourceDescriptor resource = entry.getValue();
            dev.vibris.api.CapturePlan.ArtifactFormat format =
                resource.kind() == ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER
                    ? dev.vibris.api.CapturePlan.ArtifactFormat.PNG
                    : dev.vibris.api.CapturePlan.ArtifactFormat.BIN;
            var files = new java.util.ArrayList<CaptureResult.CapturedArtifact>();
            files.add(new CaptureResult.CapturedArtifact(name + "." + format.name().toLowerCase(), format,
                dev.vibris.api.CapturePlan.ArtifactRole.PRIMARY, null));
            if (resource.kind() != ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER) {
                files.add(new CaptureResult.CapturedArtifact(name + ".json",
                    dev.vibris.api.CapturePlan.ArtifactFormat.JSON,
                    dev.vibris.api.CapturePlan.ArtifactRole.METADATA, null));
            }
            return new CaptureResult.ArtifactGroup(name, resource, files);
        }).toList());
    }

    private static CaptureResult captureResult(
        CapturePlan.Target target,
        ResourceCatalog.ResourceDescriptor resource,
        long frameId
    ) {
        return new CaptureResult(frameId, List.of(new CaptureResult.ArtifactGroup(
            target.artifactName(),
            resource,
            target.outputs().stream().map(output -> new CaptureResult.CapturedArtifact(
                output.fileName(), output.format(), output.role(), output.subresourceIndex()
            )).toList()
        )));
    }

    private static void writeAfterPassArtifacts(ArtifactSink sink, CapturePlan.Target target) throws Exception {
        for (CapturePlan.ArtifactOutputSpec output : target.outputs()) {
            byte[] bytes = output.format() == CapturePlan.ArtifactFormat.JSON
                ? "{\"after_pass\":true}".getBytes(StandardCharsets.UTF_8)
                : target.resource().kind() == ResourceCatalog.ResourceKind.BUFFER
                    ? new byte[]{0x11, 0x22, 0x33, 0x44}
                    : new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47};
            try (var stream = sink.open(output.fileName())) {
                stream.write(bytes);
            }
        }
    }

    private record PendingAfterPass(
        CapturePlan.AfterPassRequest request,
        ArtifactSink sink,
        CompletableFuture<CapturePlan.AfterPassReceipt> future
    ) {}

    private static ResourceCatalog.ResourceDescriptor resource(
        String name, ResourceCatalog.ResourceKind kind, long frame, long bytes) {
        return ResourceCatalog.ResourceDescriptor.of(
            name,
            kind,
            kind == ResourceCatalog.ResourceKind.TEXTURE
                ? List.of(ResourceCatalog.TextureView.CURRENT, ResourceCatalog.TextureView.ALTERNATE)
                : List.of(),
            2, 2, 1, 1, 1, "RGBA8", 4,
            ResourceCatalog.ScalarType.UINT8, bytes, frame, name, "", "", "RGBA", "unorm", 8,
            "RGBA", "UNSIGNED_BYTE"
        );
    }

    private final class Fixture {
        final Path pending = Files.createDirectory(temp.resolve("pending"));
        final Path artifactRoot = temp.resolve("artifacts");
        final RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        final SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        final SourceActivator activator = new SourceActivator(registry, new RecordingLink(runtime.events));
        final ArtifactManager artifacts;
        final RuntimeJobExecutor executor;
        final SourceRegistry.Lease source = source();

        Fixture() throws Exception {
            this(ArtifactManager.DEFAULT_QUOTA_BYTES);
        }

        Fixture(long quotaBytes) throws Exception {
            artifacts = new ArtifactManager(artifactRoot, quotaBytes);
            executor = new RuntimeJobExecutor(runtime, new CoreProbe(), activator, artifacts);
        }

        CoreJob job(ActionSequence actions) {
            return job(actions, 0);
        }

        CoreJob job(ActionSequence actions, long timeoutMs) {
            ActionSequence sequence = ActionSequence.newBuilder()
                .addActions(Action.newBuilder().setActivateSource(
                    ActivateSource.newBuilder().setSourceUuid(source.uuid())))
                .addAllActions(actions.getActionsList())
                .build();
            JobSpec spec = JobSpec.newBuilder()
                .setJobId("job-" + UUID.randomUUID())
                .setContext(SceneContext.newBuilder().setSaveId("save")
                    .setDimensionId("minecraft:overworld").setFov(70.0))
                .addSources(source.reference())
                .setActionSequence(sequence)
                .build();
            if (timeoutMs > 0) spec = spec.toBuilder()
                .setTimeouts(spec.getTimeouts().toBuilder().setExecutionTimeoutMs(timeoutMs))
                .build();
            CoreJob job = new CoreJob(spec, spec.getJobId(), WORKSPACE_ID, "message", null);
            job.initialize(List.of(source));
            return job;
        }

        CoreJob loadJob(boolean prelude, ActionSequence actions, boolean restore) {
            return loadJob(
                prelude,
                actions,
                restore,
                ShaderConfig.newBuilder().setPreserveCurrent(true).build()
            );
        }

        CoreJob loadJob(boolean prelude, ActionSequence actions, boolean restore, ShaderConfig config) {
            Action load = Action.newBuilder()
                .setPrelude(prelude)
                .setLoadShader(LoadShader.newBuilder()
                    .setSourceUuid(source.uuid())
                    .setSourceId("source")
                    .setConfigId("config")
                    .setConfig(config))
                .build();
            JobSpec spec = JobSpec.newBuilder()
                .setJobId("job-" + UUID.randomUUID())
                .setContext(SceneContext.newBuilder().setSaveId("save")
                    .setDimensionId("minecraft:overworld").setFov(70.0))
                .addSources(source.reference())
                .setActionSequence(ActionSequence.newBuilder()
                    .addActions(load)
                    .addAllActions(actions.getActionsList()))
                .setRestoreState(RestorePolicy.newBuilder()
                    .setOnSuccess(restore)
                    .setOnError(restore))
                .build();
            CoreJob job = new CoreJob(spec, spec.getJobId(), WORKSPACE_ID, "message", null);
            job.initialize(List.of(source));
            return job;
        }

        private SourceRegistry.Lease source() {
            try {
                String uuid = UUID.randomUUID().toString();
                PreparedSourceRef reference = SourceTestArchive.source(pending, uuid, "A",
                    dev.vibris.protocol.v2.VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED, "main");
                List<SourceRegistry.Lease> leases = registry.reserve(registry.validate(List.of(reference)));
                registry.accept(leases);
                return leases.getFirst();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
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
            events.add("link:A");
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
}
