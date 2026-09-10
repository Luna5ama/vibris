package dev.vibris.core;

import dev.vibris.api.ArtifactSink;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.CompileCatalog;
import dev.vibris.api.ContextApplyResult;
import dev.vibris.api.DeterministicTemporalCaptureOutcome;
import dev.vibris.api.DeterministicTemporalCapturePlanner;
import dev.vibris.api.DeterministicTemporalCaptureRequest;
import dev.vibris.api.EffectiveShaderSettings;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.api.SceneContext;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.api.VibrisRuntimeAdapter;
import dev.vibris.protocol.v2.Action;
import dev.vibris.protocol.v2.ActionSequence;
import dev.vibris.protocol.v2.ArtifactFormat;
import dev.vibris.protocol.v2.JobSpec;
import dev.vibris.protocol.v2.LoadShader;
import dev.vibris.protocol.v2.PreparedSourceRef;
import dev.vibris.protocol.v2.ResetTemporalState;
import dev.vibris.protocol.v2.Resolution;
import dev.vibris.protocol.v2.ShaderConfig;
import dev.vibris.protocol.v2.TakeScreenshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeJobExecutorDeterministicRestorationTest {
    private static final String WORKSPACE_ID = "11111111-1111-4111-8111-111111111111";
    private static final DeterministicTemporalCapturePlanner UNUSED_PLANNER = (resources, compile) -> {
        throw new AssertionError("planner must not run in a pre-planning failure test");
    };

    @TempDir
    Path temp;

    @Test
    void contextRejectionRestoresPreviousLinkAndContext() throws Exception {
        Fixture fixture = new Fixture();
        Source first = fixture.source("A");
        Source second = fixture.source("B");
        fixture.establish(first);
        fixture.runtime.outcome = new DeterministicTemporalCaptureOutcome.ContextRejected(
            ContextApplyResult.failure(fixture.nextContext, "rejected"),
            failure("context rejected")
        );

        fixture.capture(second, ShaderConfig.newBuilder().setPreserveCurrent(true).build());

        assertEquals(first.lease.uuid(), fixture.registry.activeUuid());
        assertEquals(List.of("link:A", "link:B", "link:A"), fixture.link.events);
        assertEquals(fixture.previousContext, fixture.runtime.contexts.getLast());
        assertEquals(1, fixture.runtime.reloadConfigs.size());
    }

    @Test
    void destructiveReloadRejectionRestoresExplicitSettingsAndContext() throws Exception {
        Fixture fixture = new Fixture();
        Source first = fixture.source("A");
        Source second = fixture.source("B");
        fixture.establish(first);
        fixture.runtime.outcome = new DeterministicTemporalCaptureOutcome.ReloadRejected(
            ContextApplyResult.success(fixture.nextContext),
            ReloadResult.failure(List.of()),
            failure("reload rejected")
        );

        fixture.capture(second, ShaderConfig.newBuilder().putValues("MODE", "new").build());

        assertEquals(first.lease.uuid(), fixture.registry.activeUuid());
        assertEquals(Map.of("MODE", "old"), fixture.runtime.reloadConfigs.getLast());
        assertEquals(fixture.previousContext, fixture.runtime.contexts.getLast());
        assertEquals(2, fixture.runtime.reloadConfigs.size());
    }

    @Test
    void preservingReloadRejectionDoesNotReloadButRestoresContext() throws Exception {
        Fixture fixture = new Fixture();
        Source first = fixture.source("A");
        Source second = fixture.source("B");
        fixture.establish(first);
        fixture.runtime.outcome = new DeterministicTemporalCaptureOutcome.ReloadRejected(
            ContextApplyResult.success(fixture.nextContext),
            ReloadResult.failurePreservingActiveState(fixture.runtime.settings, List.of()),
            failure("reload rejected")
        );

        fixture.capture(second, ShaderConfig.newBuilder().setPreserveCurrent(true).build());

        assertEquals(first.lease.uuid(), fixture.registry.activeUuid());
        assertEquals(1, fixture.runtime.reloadConfigs.size());
        assertEquals(fixture.previousContext, fixture.runtime.contexts.getLast());
    }

    @Test
    void preservingReloadRejectionWithMismatchedSettingsFailsClosed() throws Exception {
        Fixture fixture = new Fixture();
        Source first = fixture.source("A");
        Source second = fixture.source("B");
        fixture.establish(first);
        fixture.runtime.outcome = new DeterministicTemporalCaptureOutcome.ReloadRejected(
            ContextApplyResult.success(fixture.nextContext),
            ReloadResult.failurePreservingActiveState(settings("foreign"), List.of()),
            failure("reload rejected")
        );

        RuntimeJobExecutor.DeterministicPhaseFailure phaseFailure = assertThrows(
            RuntimeJobExecutor.DeterministicPhaseFailure.class,
            () -> fixture.capture(second, ShaderConfig.newBuilder().setPreserveCurrent(true).build())
        );
        RuntimeJobExecutor.Failure failure = phaseFailure.getFailure();

        assertEquals(RuntimeJobExecutor.DeterministicFailurePhase.LOAD, phaseFailure.getPhase());
        assertEquals(dev.vibris.protocol.v2.ErrorCode.ERROR_CODE_RESTORE_FAILED, failure.code);
        assertFalse(fixture.activator.ready());
    }

    @Test
    void contextRejectionWithoutPriorSnapshotFailsClosed() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        fixture.runtime.outcome = new DeterministicTemporalCaptureOutcome.ContextRejected(
            ContextApplyResult.failure(fixture.nextContext, "rejected"),
            failure("context rejected")
        );

        RuntimeJobExecutor.DeterministicPhaseFailure phaseFailure = assertThrows(
            RuntimeJobExecutor.DeterministicPhaseFailure.class,
            () -> fixture.capture(source, ShaderConfig.newBuilder().setPreserveCurrent(true).build())
        );
        RuntimeJobExecutor.Failure failure = phaseFailure.getFailure();

        assertEquals(RuntimeJobExecutor.DeterministicFailurePhase.LOAD, phaseFailure.getPhase());
        assertEquals(dev.vibris.protocol.v2.ErrorCode.ERROR_CODE_RESTORE_FAILED, failure.code);
        assertEquals("", fixture.registry.activeUuid());
        assertFalse(fixture.activator.ready());
    }

    @Test
    void reloadRejectionWithoutPriorSnapshotFailsClosed() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        fixture.runtime.outcome = new DeterministicTemporalCaptureOutcome.ReloadRejected(
            ContextApplyResult.success(fixture.nextContext),
            ReloadResult.failurePreservingActiveState(EffectiveShaderSettings.empty(), List.of()),
            failure("reload rejected")
        );

        RuntimeJobExecutor.DeterministicPhaseFailure phaseFailure = assertThrows(
            RuntimeJobExecutor.DeterministicPhaseFailure.class,
            () -> fixture.capture(source, ShaderConfig.newBuilder().setPreserveCurrent(true).build())
        );
        RuntimeJobExecutor.Failure failure = phaseFailure.getFailure();

        assertEquals(RuntimeJobExecutor.DeterministicFailurePhase.LOAD, phaseFailure.getPhase());
        assertEquals(dev.vibris.protocol.v2.ErrorCode.ERROR_CODE_RESTORE_FAILED, failure.code);
        assertEquals("", fixture.registry.activeUuid());
        assertFalse(fixture.activator.ready());
    }

    @Test
    void sameSourceExceptionalCompletionRestoresExactSettingsAndContext() throws Exception {
        Fixture fixture = new Fixture();
        Source first = fixture.source("A");
        fixture.establish(first);
        fixture.runtime.compoundFailure = new IllegalStateException("compound failed");

        RuntimeJobExecutor.DeterministicPhaseFailure phaseFailure = assertThrows(
            RuntimeJobExecutor.DeterministicPhaseFailure.class,
            () -> fixture.capture(first, ShaderConfig.newBuilder().putValues("MODE", "new").build())
        );
        RuntimeJobExecutor.Failure failure = phaseFailure.getFailure();

        assertEquals(RuntimeJobExecutor.DeterministicFailurePhase.CAPTURE, phaseFailure.getPhase());
        assertEquals(dev.vibris.protocol.v2.ErrorCode.ERROR_CODE_CAPTURE_FAILED, failure.code);
        assertEquals(first.lease.uuid(), fixture.registry.activeUuid());
        assertEquals(Map.of("MODE", "old"), fixture.runtime.reloadConfigs.getLast());
        assertEquals(fixture.previousContext, fixture.runtime.contexts.getLast());
    }

    @Test
    void unverifiableExceptionalRestorationFailsClosed() throws Exception {
        Fixture fixture = new Fixture();
        Source first = fixture.source("A");
        fixture.establish(first);
        fixture.runtime.reloadSettings = settings("wrong");
        fixture.runtime.compoundFailure = new IllegalStateException("compound failed");

        RuntimeJobExecutor.DeterministicPhaseFailure phaseFailure = assertThrows(
            RuntimeJobExecutor.DeterministicPhaseFailure.class,
            () -> fixture.capture(first, ShaderConfig.newBuilder().putValues("MODE", "new").build())
        );
        RuntimeJobExecutor.Failure failure = phaseFailure.getFailure();

        assertEquals(RuntimeJobExecutor.DeterministicFailurePhase.CAPTURE, phaseFailure.getPhase());
        assertEquals(dev.vibris.protocol.v2.ErrorCode.ERROR_CODE_RESTORE_FAILED, failure.code);
        assertFalse(fixture.activator.ready());
    }

    @Test
    void rollbackCleanupFailureWinsAndRetainsTypedRejection() throws Exception {
        Fixture fixture = new Fixture();
        Source first = fixture.source("A");
        Source second = fixture.source("B");
        fixture.establish(first);
        fixture.runtime.writeThenRemoveArtifact = true;
        fixture.runtime.outcome = new DeterministicTemporalCaptureOutcome.ContextRejected(
            ContextApplyResult.failure(fixture.nextContext, "rejected"),
            failure("context rejected")
        );

        RuntimeJobExecutor.DeterministicPhaseFailure phaseFailure = assertThrows(
            RuntimeJobExecutor.DeterministicPhaseFailure.class,
            () -> fixture.capture(second, ShaderConfig.newBuilder().setPreserveCurrent(true).build())
        );
        RuntimeJobExecutor.Failure failure = phaseFailure.getFailure();

        assertEquals(RuntimeJobExecutor.DeterministicFailurePhase.LOAD, phaseFailure.getPhase());
        assertEquals(dev.vibris.protocol.v2.ErrorCode.ERROR_CODE_CAPTURE_FAILED, failure.code);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("context rejected", failure.getSuppressed()[0].getMessage());
    }

    private static DeterministicTemporalCaptureOutcome.Failure failure(String message) {
        return new DeterministicTemporalCaptureOutcome.Failure(
            DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED,
            message
        );
    }

    private static EffectiveShaderSettings settings(String value) {
        return EffectiveShaderSettings.of(List.of(new EffectiveShaderSettings.Setting(
            "MODE",
            value,
            "default",
            EffectiveShaderSettings.Origin.REQUEST_OVERRIDE
        )));
    }

    private final class Fixture {
        final SceneContext previousContext = context("old-save");
        final SceneContext nextContext = context("new-save");
        final RuntimeState runtime = new RuntimeState();
        final VibrisRuntimeAdapter adapter = runtime.proxy();
        final Path pending = Files.createDirectory(temp.resolve("pending"));
        final SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        final RecordingLink link = new RecordingLink();
        final SourceActivator activator = new SourceActivator(registry, link);
        final ArtifactManager artifacts = new ArtifactManager(temp.resolve("artifacts"));
        final RuntimeJobExecutor executor = new RuntimeJobExecutor(adapter, new CoreProbe(), activator, artifacts);

        Fixture() throws Exception {
            runtime.artifactRoot = temp.resolve("artifacts");
        }

        void establish(Source source) throws Exception {
            CoreJob job = job(source, previousContext);
            executor.loadShader(
                job,
                source.lease,
                ShaderConfig.newBuilder().putValues("MODE", "old").build(),
                ignored -> {},
                Long.MAX_VALUE
            );
        }

        DeterministicTemporalCaptureOutcome capture(Source source, ShaderConfig config) throws Exception {
            CoreJob job = job(source, nextContext);
            CaptureJobExecutor captures = new CaptureJobExecutor(artifacts, 128);
            CaptureJobExecutor.ActionPrepared action = captures.prepareActions(
                job,
                ResourceCatalog.empty(),
                List.of()
            );
            try (CaptureJobExecutor.Prepared prepared = action.prepared()) {
                return executor.captureDeterministicTemporalPhase(
                    job,
                    source.lease,
                    config,
                    UNUSED_PLANNER,
                    ignored -> {},
                    Long.MAX_VALUE,
                    prepared,
                    3
                );
            }
        }

        Source source(String marker) throws Exception {
            String uuid = UUID.randomUUID().toString();
            PreparedSourceRef reference = SourceTestArchive.source(pending, uuid, marker,
                dev.vibris.protocol.v2.VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED, "main");
            List<SourceRegistry.Lease> leases = registry.reserve(registry.validate(List.of(reference)));
            registry.accept(leases);
            return new Source(leases.getFirst());
        }

        CoreJob job(Source source, SceneContext context) {
            dev.vibris.protocol.v2.SceneContext protocol = dev.vibris.protocol.v2.SceneContext.newBuilder()
                .setSaveId(context.saveId())
                .setDimensionId(context.dimensionId())
                .setTimePresetId(context.timePresetId())
                .setWeatherPresetId(context.weatherPresetId())
                .setCameraPresetId(context.cameraPresetId())
                .setFov(context.fov())
                .setResolution(Resolution.newBuilder().setWidth(1280).setHeight(720))
                .setSettingsPresetId(context.settingsPresetId())
                .build();
            JobSpec spec = JobSpec.newBuilder()
                .setJobId("job-" + UUID.randomUUID())
                .setContext(protocol)
                .addSources(source.lease.reference())
                .setActionSequence(ActionSequence.newBuilder()
                    .addActions(Action.newBuilder().setPrelude(true).setLoadShader(LoadShader.newBuilder()
                        .setSourceUuid(source.lease.uuid())
                        .setSourceId("source")
                        .setConfigId("config")
                        .setConfig(ShaderConfig.newBuilder().setPreserveCurrent(true))))
                    .addActions(Action.newBuilder()
                        .setResetTemporalState(ResetTemporalState.getDefaultInstance()))
                    .addActions(Action.newBuilder().setTakeScreenshot(TakeScreenshot.newBuilder()
                        .setArtifactName("shot")
                        .setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG))))
                .build();
            CoreJob job = new CoreJob(spec, spec.getJobId(), WORKSPACE_ID, "message", null);
            job.initialize(List.of(source.lease));
            return job;
        }
    }

    private static final class RuntimeState {
        final EffectiveShaderSettings settings = settings("old");
        final List<Map<String, String>> reloadConfigs = new ArrayList<>();
        final List<SceneContext> contexts = new ArrayList<>();
        EffectiveShaderSettings reloadSettings = settings;
        DeterministicTemporalCaptureOutcome outcome;
        RuntimeException compoundFailure;
        Path artifactRoot;
        boolean writeThenRemoveArtifact;

        VibrisRuntimeAdapter proxy() {
            return (VibrisRuntimeAdapter) Proxy.newProxyInstance(
                VibrisRuntimeAdapter.class.getClassLoader(),
                new Class<?>[]{VibrisRuntimeAdapter.class},
                (ignored, method, arguments) -> switch (method.getName()) {
                    case "reloadVibrisShaderpack" -> {
                        @SuppressWarnings("unchecked")
                        Map<String, String> config = (Map<String, String>) arguments[0];
                        reloadConfigs.add(config == null ? null : Map.copyOf(config));
                        yield CompletableFuture.completedFuture(ReloadResult.success(reloadSettings, List.of()));
                    }
                    case "ensureWorldAndContext" -> {
                        SceneContext context = (SceneContext) arguments[0];
                        contexts.add(context);
                        yield CompletableFuture.completedFuture(ContextApplyResult.success(context));
                    }
                    case "resetTemporalState" ->
                        CompletableFuture.completedFuture(new TemporalResetResult(true));
                    case "getCompileCatalog" -> CompletableFuture.completedFuture(CompileCatalog.empty(1));
                    case "captureDeterministicTemporalPhase" -> {
                        DeterministicTemporalCaptureRequest request =
                            (DeterministicTemporalCaptureRequest) arguments[0];
                        contexts.add(request.context());
                        if (writeThenRemoveArtifact) {
                            ArtifactSink sink = (ArtifactSink) arguments[2];
                            try (var output = sink.open("orphan.bin")) {
                                output.write(1);
                            }
                            try (var paths = Files.walk(artifactRoot)) {
                                Path orphan = paths.filter(path -> path.getFileName().toString().equals("orphan.bin"))
                                    .findFirst()
                                    .orElseThrow();
                                Files.delete(orphan);
                            }
                        }
                        if (compoundFailure != null) {
                            yield CompletableFuture.failedFuture(compoundFailure);
                        }
                        yield CompletableFuture.completedFuture(outcome);
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                }
            );
        }
    }

    private static final class RecordingLink implements ShaderLink {
        final List<String> events = new ArrayList<>();

        @Override
        public void switchTo(SourceRegistry.Lease source, OwnershipCheck ownership) throws Failure {
            ownership.verify();
            try {
                events.add("link:" + Files.readString(source.directory().resolve("main.glsl")));
            } catch (java.io.IOException exception) {
                throw new Failure("test source could not be read", true, exception);
            }
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

    private record Source(SourceRegistry.Lease lease) {
    }

    private static SceneContext context(String save) {
        return new SceneContext(
            save,
            "minecraft:overworld",
            "noon",
            "clear",
            "origin",
            70.0,
            new SceneContext.Resolution(1280, 720),
            "night-gi-1-720p"
        );
    }
}
