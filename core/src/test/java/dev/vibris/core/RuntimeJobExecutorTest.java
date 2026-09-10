package dev.vibris.core;

import dev.vibris.api.EffectiveShaderSettings;
import dev.vibris.api.CompileCatalog;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.protocol.v2.Action;
import dev.vibris.protocol.v2.ActionKind;
import dev.vibris.protocol.v2.ActionSequence;
import dev.vibris.protocol.v2.ActivateSource;
import dev.vibris.protocol.v2.ErrorCode;
import dev.vibris.protocol.v2.CompileValidationCase;
import dev.vibris.protocol.v2.CompileValidationRequest;
import dev.vibris.protocol.v2.JobSpec;
import dev.vibris.protocol.v2.InspectShader;
import dev.vibris.protocol.v2.GetGpuMetrics;
import dev.vibris.protocol.v2.LoadShader;
import dev.vibris.protocol.v2.PreparedSourceRef;
import dev.vibris.protocol.v2.ReceiptStatus;
import dev.vibris.protocol.v2.ResetTemporalState;
import dev.vibris.protocol.v2.RestorePolicy;
import dev.vibris.protocol.v2.SceneContext;
import dev.vibris.protocol.v2.ShaderConfig;
import dev.vibris.protocol.v2.WaitFrames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeJobExecutorTest {
    private static final String WORKSPACE_ID = "11111111-1111-4111-8111-111111111111";

    @TempDir
    Path pending;

    @Test
    void loadAppliesContextResetsTemporalStateAndReturnsTypedReceipts() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        EffectiveShaderSettings runtimeSettings = EffectiveShaderSettings.of(List.of(
            new EffectiveShaderSettings.Setting(
                "SETTING_SAMPLE_COUNT",
                "64",
                "16",
                EffectiveShaderSettings.Origin.PRESET
            )
        ));
        fixture.runtime.reloads.add(ReloadResult.success(runtimeSettings, List.of()));
        CoreJob job = fixture.job(source, ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setLoadShader(LoadShader.newBuilder()
                .setSourceUuid(source.uuid)
                .setSourceId("candidate")
                .setConfigId("quality")
                .setConfig(ShaderConfig.newBuilder().putValues("SETTING_SAMPLE_COUNT", "32"))))
            .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(3)))
            .build());

        TerminalResult terminal = fixture.executor.execute(job, ignored -> {});

        assertEquals(List.of("link:A", "reload", "context", "reset", "compile_catalog", "frames"),
            fixture.runtime.events);
        assertEquals("32", fixture.runtime.lastShaderConfig.get("SETTING_SAMPLE_COUNT"));
        assertEquals(2, terminal.completed().getResult().getActionReceiptsCount());
        assertEquals(ActionKind.ACTION_KIND_LOAD_SHADER,
            terminal.completed().getResult().getActionReceipts(0).getKind());
        assertEquals(source.uuid,
            terminal.completed().getResult().getActionReceipts(0).getRuntimeMutation().getSourceUuid());
        assertFalse(terminal.completed().getResult().getActionReceipts(0)
            .getRuntimeMutation().getSourceSha256().isBlank());
        assertFalse(terminal.completed().getResult().getActionReceipts(0)
            .getRuntimeMutation().getSceneSha256().isBlank());
        var effective = terminal.completed().getResult().getActionReceipts(0)
            .getRuntimeMutation().getEffectiveSettings();
        assertEquals(runtimeSettings.settingsSha256(), effective.getSettingsSha256());
        assertEquals("64", effective.getSettings(0).getValue());
        assertEquals(dev.vibris.protocol.v2.ShaderSettingOrigin.SHADER_SETTING_ORIGIN_PRESET,
            effective.getSettings(0).getOrigin());
        assertTrue(effective.getSettings(0).getChangedFromDefault());
        assertEquals(runtimeSettings.settingsSha256(),
            terminal.completed().getResult().getRestoration().getActualSettingsSha256());
        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK,
            terminal.completed().getResult().getActionReceipts(1).getStatus());
        assertEquals(3,
            terminal.completed().getResult().getActionReceipts(1).getWaitFrames().getCompletedFrames());
        assertEquals(0,
            terminal.completed().getResult().getActionReceipts(1).getWaitFrames().getStartFrame());
        assertEquals(3,
            terminal.completed().getResult().getActionReceipts(1).getWaitFrames().getEndFrame());
        var provenance = terminal.completed().getResult().getProvenance();
        assertEquals(source.uuid, provenance.getActiveSourceUuid());
        assertEquals(runtimeSettings.settingsSha256(), provenance.getConfigSha256());
        assertFalse(provenance.getSceneSha256().isBlank());
        assertEquals(fixture.runtime.compileCatalog.mappingSha256(), provenance.getPassMappingSha256());
        assertTrue(provenance.getShaderLoadedAtUnixMs() > 0);
        assertEquals("test-minecraft", provenance.getEnvironment().getMinecraftVersion());
        assertEquals("test-iris", provenance.getEnvironment().getIrisVersion());
        assertEquals("test-vibris", provenance.getEnvironment().getVibrisVersion());
        assertEquals("test-java", provenance.getEnvironment().getJavaVersion());
        assertEquals("test-os", provenance.getEnvironment().getOperatingSystem());
        assertEquals("test-gpu-vendor", provenance.getEnvironment().getGpuVendor());
        assertEquals("test-gpu-renderer", provenance.getEnvironment().getGpuRenderer());
        assertEquals("test-opengl", provenance.getEnvironment().getOpenglVersion());
        assertEquals("test-driver", provenance.getEnvironment().getDriverVersion());
        assertEquals(dev.vibris.protocol.v2.VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED,
            provenance.getVcsCheckoutState());
    }

    @Test
    void detachedCheckoutStateAndExactHeadFlowToResultProvenance() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source(
            "detached",
            dev.vibris.protocol.v2.VcsCheckoutState.VCS_CHECKOUT_STATE_DETACHED,
            ""
        );

        TerminalResult terminal = fixture.executor.execute(fixture.loadJob(source), ignored -> {});
        var provenance = terminal.completed().getResult().getProvenance();

        assertEquals(dev.vibris.protocol.v2.VcsCheckoutState.VCS_CHECKOUT_STATE_DETACHED,
            provenance.getVcsCheckoutState());
        assertEquals("", provenance.getBranch());
        assertEquals("a".repeat(40), provenance.getStartHead());
    }

    @Test
    void equivalentFreshSourceReusesPipelineAndRestoresExactSourceWithoutReload() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.source("same");
        fixture.executor.execute(fixture.loadJob(baseline), ignored -> {});
        fixture.runtime.events.clear();
        Source equivalent = fixture.source("same");

        TerminalResult terminal = fixture.executor.execute(
            fixture.loadJob(equivalent, ShaderConfig.newBuilder().setPreserveCurrent(true).build(), true),
            ignored -> {});

        assertEquals(List.of("link:same", "context", "reset", "compile_catalog", "link:same", "context", "reset"),
            fixture.runtime.events);
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
        assertEquals(baseline.uuid, terminal.completed().getResult().getRestoration().getActualSourceUuid());
    }

    @Test
    void equivalentFreshSourceAndResolvedConfigReuseLoadedPipeline() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.source("same");
        EffectiveShaderSettings settings = EffectiveShaderSettings.of(List.of(
            new EffectiveShaderSettings.Setting(
                "QUALITY", "high", "low", EffectiveShaderSettings.Origin.REQUEST_OVERRIDE)
        ));
        ShaderConfig config = ShaderConfig.newBuilder().putValues("QUALITY", "high").build();
        fixture.runtime.reloads.add(ReloadResult.success(settings, List.of()));
        fixture.executor.execute(fixture.loadJob(baseline, config, false), ignored -> {});
        fixture.runtime.events.clear();
        Source equivalent = fixture.source("same");

        fixture.executor.execute(fixture.loadJob(equivalent, config, false), ignored -> {});

        assertEquals(List.of("link:same", "context", "reset", "compile_catalog"), fixture.runtime.events);
        assertEquals(equivalent.uuid, fixture.registry.activeUuid());
    }

    @Test
    void changedResolvedConfigReloadsEquivalentFreshSource() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.source("same");
        EffectiveShaderSettings high = EffectiveShaderSettings.of(List.of(
            new EffectiveShaderSettings.Setting(
                "QUALITY", "high", "low", EffectiveShaderSettings.Origin.REQUEST_OVERRIDE)
        ));
        EffectiveShaderSettings low = EffectiveShaderSettings.of(List.of(
            new EffectiveShaderSettings.Setting(
                "QUALITY", "low", "low", EffectiveShaderSettings.Origin.DEFAULT)
        ));
        fixture.runtime.reloads.add(ReloadResult.success(high, List.of()));
        fixture.executor.execute(fixture.loadJob(
            baseline, ShaderConfig.newBuilder().putValues("QUALITY", "high").build(), false), ignored -> {});
        fixture.runtime.events.clear();
        Source equivalent = fixture.source("same");
        fixture.runtime.reloads.add(ReloadResult.success(low, List.of()));

        fixture.executor.execute(fixture.loadJob(
            equivalent, ShaderConfig.getDefaultInstance(), false), ignored -> {});

        assertEquals(List.of("link:same", "reload", "context", "reset", "compile_catalog"),
            fixture.runtime.events);
        assertEquals(equivalent.uuid, fixture.registry.activeUuid());
    }

    @Test
    void repeatedEquivalentFreshSourcesKeepOneLoadedPipelineAndOneOwnedSnapshot() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.source("same");
        fixture.executor.execute(fixture.loadJob(baseline), ignored -> {});
        fixture.activator.release(List.of(baseline.lease));
        fixture.runtime.events.clear();

        for (int index = 0; index < 64; index++) {
            Source equivalent = fixture.source("same");
            fixture.executor.execute(fixture.loadJob(equivalent), ignored -> {});
            fixture.activator.release(List.of(equivalent.lease));
        }

        assertEquals(0, fixture.runtime.events.stream().filter("reload"::equals).count());
        try (var entries = Files.list(pending)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void failedCandidateReloadRestoresPreviousSourceAndKeepsTypedCompileFailure() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.source("baseline");
        fixture.activate(baseline);
        Source candidate = fixture.source("candidate");
        fixture.runtime.reloads.add(ReloadResult.failure(List.of(error("candidate failed"))));
        fixture.runtime.reloads.add(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.activateJob(candidate), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_SHADER_COMPILE_FAILED, failure.code);
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
        assertTrue(fixture.activator.ready());
        assertEquals(List.of("link:baseline", "link:candidate", "reload", "link:baseline", "reload"),
            fixture.runtime.events);
        assertEquals(1, failure.artifacts.size());
        assertTrue(Files.readString(Path.of(failure.artifacts.getFirst().getRelativePath()))
            .contains("candidate failed"));
    }

    @Test
    void resetFailureStopsBeforeWaitFrames() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        fixture.runtime.reset = new TemporalResetResult(false);
        CoreJob job = fixture.job(source, ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setActivateSource(ActivateSource.newBuilder()
                .setSourceUuid(source.uuid)))
            .addActions(Action.newBuilder().setResetTemporalState(ResetTemporalState.getDefaultInstance()))
            .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(3)))
            .build());

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(job, ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_INTERNAL, failure.code);
        assertFalse(fixture.runtime.events.contains("frames"));
        assertEquals(List.of(0, 1, 2),
            failure.actionReceipts.stream().map(receipt -> receipt.getActionIndex()).toList());
        assertEquals(List.of(
                ReceiptStatus.RECEIPT_STATUS_OK,
                ReceiptStatus.RECEIPT_STATUS_FAILED,
                ReceiptStatus.RECEIPT_STATUS_CANCELLED),
            failure.actionReceipts.stream().map(receipt -> receipt.getStatus()).toList());
        assertTrue(failure.actionReceipts.get(1).hasError());
        assertEquals(ErrorCode.ERROR_CODE_INTERNAL, failure.actionReceipts.get(1).getError().getCode());
        assertTrue(failure.actionReceipts.get(2).hasError());
        assertEquals(ErrorCode.ERROR_CODE_CANCELLED, failure.actionReceipts.get(2).getError().getCode());
        assertTrue(failure.preludeReceipts.isEmpty());

        var terminal = ProtocolMessages.failure(
            job.submission.getJobId(),
            job.requestId,
            failure.code,
            failure.getMessage(),
            failure.artifacts,
            failure.restoration,
            failure.actionReceipts,
            failure.preludeReceipts);
        assertEquals(3, terminal.failed().getActionReceiptsCount());
    }

    @Test
    void routesSynthesizedLoadToPreludeAndKeepsUserIndicesContiguous() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        CoreJob job = fixture.job(source, ActionSequence.newBuilder()
            .addActions(Action.newBuilder()
                .setPrelude(true)
                .setLoadShader(LoadShader.newBuilder()
                    .setSourceUuid(source.uuid)
                    .setSourceId("candidate")
                    .setConfigId("preserve")
                    .setConfig(ShaderConfig.newBuilder().setPreserveCurrent(true))))
            .addActions(Action.newBuilder().setResetTemporalState(ResetTemporalState.getDefaultInstance()))
            .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(2)))
            .build());

        TerminalResult terminal = fixture.executor.execute(job, ignored -> {});
        var result = terminal.completed().getResult();

        assertEquals(1, result.getPreludeReceiptsCount());
        assertEquals(3, result.getPreludeReceiptsCount() + result.getActionReceiptsCount());
        assertEquals(0, result.getPreludeReceipts(0).getActionIndex());
        assertEquals(ActionKind.ACTION_KIND_LOAD_SHADER, result.getPreludeReceipts(0).getKind());
        assertEquals(List.of(0, 1),
            result.getActionReceiptsList().stream().map(receipt -> receipt.getActionIndex()).toList());
        assertEquals(ActionKind.ACTION_KIND_RESET_TEMPORAL_STATE, result.getActionReceipts(0).getKind());
        assertTrue(result.getActionReceipts(0).hasResetTemporal());
        assertTrue(result.getActionReceipts(0).getResetTemporal().getCompletedAtUnixMs() > 0);
        assertEquals(ActionKind.ACTION_KIND_WAIT_FRAMES, result.getActionReceipts(1).getKind());
    }

    @Test
    void shaderInspectionReturnsTheCanonicalTypedCatalog() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        var diagnostic = CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.ERROR, "final.fsh", 9, 2, "link failed"
        );
        fixture.runtime.compileCatalog = CompileCatalog.of(List.of(
            CompileCatalog.ProgramEntry.of(
                "final", "final", List.of(CompileCatalog.ShaderStage.VERTEX, CompileCatalog.ShaderStage.FRAGMENT),
                CompileCatalog.CompileState.SUCCEEDED, CompileCatalog.CompileState.FAILED,
                "a".repeat(64), List.of(diagnostic)
            )
        ), 12);
        CoreJob job = fixture.job(source, ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setInspectShader(InspectShader.getDefaultInstance()))
            .build());

        TerminalResult terminal = fixture.executor.execute(job, ignored -> {});
        var receipt = terminal.completed().getResult().getActionReceipts(0);
        var catalog = receipt.getShaderInspection().getCatalog();

        assertEquals(List.of("compile_catalog"), fixture.runtime.events);
        assertEquals(ActionKind.ACTION_KIND_INSPECT_SHADER, receipt.getKind());
        assertEquals(fixture.runtime.compileCatalog.mappingSha256(), catalog.getMappingSha256());
        assertEquals(12, catalog.getShaderGeneration());
        assertEquals(dev.vibris.protocol.v2.CompileState.COMPILE_STATE_FAILED,
            catalog.getPrograms(0).getLinkState());
        assertEquals(diagnostic.fingerprintSha256(),
            catalog.getPrograms(0).getDiagnostics(0).getFingerprintSha256());
    }

    @Test
    void gpuMetricsReturnsTypedReceiptAndFailsMalformedOutput() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        fixture.runtime.actionResponses.add(canonicalGpuMetrics());
        CoreJob job = fixture.job(source, ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setGetGpuMetrics(
                GetGpuMetrics.newBuilder().setFrames(3).addMetricIds("begin3_total")))
            .build());

        TerminalResult terminal = fixture.executor.execute(job, ignored -> {});
        var receipt = terminal.completed().getResult().getActionReceipts(0);

        assertTrue(receipt.hasGpuMetrics());
        assertFalse(receipt.hasEmpty());
        assertEquals(3, receipt.getGpuMetrics().getSampledFrames());
        assertEquals(1, receipt.getGpuMetrics().getMetricsCount());
        assertEquals("begin3_total", receipt.getGpuMetrics().getMetrics(0).getMetricId());
        assertEquals(List.of(800L, 900L, 1000L),
            receipt.getGpuMetrics().getMetrics(0).getSamplesNsList());

        Fixture malformed = new Fixture();
        Source malformedSource = malformed.source("B");
        malformed.runtime.actionResponses.add("{}");
        CoreJob malformedJob = malformed.job(malformedSource, ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setGetGpuMetrics(GetGpuMetrics.newBuilder().setFrames(3)))
            .build());

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> malformed.executor.execute(malformedJob, ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_NO_GPU_SAMPLES, failure.code);
        assertEquals(ReceiptStatus.RECEIPT_STATUS_FAILED, failure.actionReceipts.getFirst().getStatus());
        assertEquals(ErrorCode.ERROR_CODE_NO_GPU_SAMPLES,
            failure.actionReceipts.getFirst().getError().getCode());
    }

    @Test
    void compileValidationReturnsCompleteCatalogDiffAndRestoresWithoutRendering() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.source("baseline");
        fixture.runtime.reloads.add(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));
        fixture.executor.execute(fixture.loadJob(baseline), ignored -> {});
        fixture.runtime.events.clear();

        Source candidate = fixture.source("candidate");
        var unchanged = CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.WARNING, "common.glsl", 4, 1, "shared warning");
        var added = CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.ERROR, "candidate.fsh", 9, 2, "candidate failed");
        var resolved = CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.WARNING, "baseline.glsl", 7, 1, "baseline warning");
        fixture.runtime.reloads.add(ReloadResult.failure(List.of(error("candidate failed"))));
        fixture.runtime.reloads.add(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));
        fixture.runtime.compileCatalogs.add(catalog(List.of(unchanged, resolved), false));
        fixture.runtime.compileCatalogs.add(catalog(List.of(unchanged, added), true));

        CompileValidationRequest validation = CompileValidationRequest.newBuilder()
            .setBaseline(compileCase("baseline", baseline, "base"))
            .addCases(compileCase("candidate", candidate, "quality"))
            .build();
        TerminalResult terminal = fixture.executor.execute(
            fixture.compileJob(List.of(baseline, candidate), validation), ignored -> {});
        var result = terminal.completed().getResult();
        var compile = result.getCompileValidation().getCases(0);

        assertEquals(1, compile.getAddedDiagnosticsCount());
        assertEquals(1, compile.getResolvedDiagnosticsCount());
        assertEquals(1, compile.getUnchangedDiagnosticsCount());
        assertEquals(added.fingerprintSha256(), compile.getAddedDiagnostics(0).getFingerprintSha256());
        assertEquals(resolved.fingerprintSha256(), compile.getResolvedDiagnostics(0).getFingerprintSha256());
        assertEquals(CompileCatalogProtocol.INSTANCE.toProtocol(catalog(List.of(unchanged, added), true)),
            compile.getCatalog());
        assertEquals(candidate.uuid, compile.getProvenance().getActiveSourceUuid());
        assertEquals(candidate.uuid, result.getProvenance().getActiveSourceUuid());
        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK, result.getRestoration().getStatus());
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
        assertFalse(fixture.runtime.events.contains("frames"));
        assertEquals(1, fixture.runtime.events.stream().filter("context"::equals).count());
        assertEquals(2, fixture.runtime.events.stream().filter("compile_catalog"::equals).count());
    }

    @Test
    void compileValidationFailsClosedWhenBaselineDoesNotCompile() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.source("baseline");
        fixture.runtime.reloads.add(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));
        fixture.executor.execute(fixture.loadJob(baseline), ignored -> {});
        fixture.runtime.events.clear();

        Source candidate = fixture.source("candidate");
        var baselineError = CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.ERROR, "baseline.fsh", 3, 1, "baseline failed");
        fixture.runtime.compileCatalogs.add(catalog(baselineError, true));
        CompileValidationRequest validation = CompileValidationRequest.newBuilder()
            .setBaseline(compileCase("baseline", baseline, "base"))
            .addCases(compileCase("candidate", candidate, "quality"))
            .build();

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.compileJob(List.of(baseline, candidate), validation), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_SHADER_COMPILE_FAILED, failure.code);
        assertEquals(1, fixture.runtime.events.stream().filter("compile_catalog"::equals).count());
        assertFalse(fixture.runtime.events.contains("frames"));
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
    }

    @Test
    void actionRejectsSourceOutsidePreparedSetBeforeActivation() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        CoreJob job = fixture.job(source, ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setActivateSource(ActivateSource.newBuilder()
                .setSourceUuid(UUID.randomUUID().toString())))
            .build());

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(job, ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_INVALID_SOURCE, failure.code);
        assertTrue(fixture.runtime.events.isEmpty());
    }

    @Test
    void sourceIdentityChangedDuringReloadDoesNotDeleteReplacement() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        Path original = source.path;
        Path moved = pending.resolve("moved-source");
        fixture.runtime.beforeReloadResult = () -> {
            try {
                Files.move(original, moved);
                Files.createDirectory(original);
                Files.writeString(original.resolve("sentinel.txt"), "external");
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        };

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.activateJob(source), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, failure.code);
        assertTrue(Files.isRegularFile(original.resolve("sentinel.txt")));
        assertTrue(fixture.activator.ready());
    }

    @Test
    void activeLinkTamperFailsAtFinalBoundaryAndMarksCoreNotReady() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        fixture.link.tampered = true;

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.activateJob(source), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, failure.code);
        assertFalse(fixture.activator.ready());
    }

    private static ReloadResult.Diagnostic error(String marker) {
        return new ReloadResult.Diagnostic(ReloadResult.Severity.ERROR, "composite.fsh", 17, marker);
    }

    private static CompileCatalog catalog(CompileCatalog.Diagnostic diagnostic, boolean failed) {
        return catalog(List.of(diagnostic), failed);
    }

    private static CompileCatalog catalog(List<CompileCatalog.Diagnostic> diagnostics, boolean failed) {
        return CompileCatalog.of(List.of(CompileCatalog.ProgramEntry.of(
            "final", "final", List.of(CompileCatalog.ShaderStage.FRAGMENT),
            failed ? CompileCatalog.CompileState.FAILED : CompileCatalog.CompileState.SUCCEEDED,
            failed ? CompileCatalog.CompileState.NOT_APPLICABLE : CompileCatalog.CompileState.SUCCEEDED,
            "a".repeat(64), diagnostics)), 8);
    }

    private static String canonicalGpuMetrics() {
        return """
            {
              "timingUnit":"ns",
              "sampledFrames":3,
              "gpuTimings":{
                "begin3_total":{"avg":900,"p5":810,"p95":990,"p50":900,"samples":[800,900,1000]},
                "begin3_compute":{"avg":300,"p5":250,"p95":350,"p50":300,"samples":[250,300,350]}
              },
              "gpuTimingScopes":[
                {"metric":"begin3_total","kind":"framework_total","framework_pass":"begin3","stage":null},
                {"metric":"begin3_compute","kind":"compatibility_aggregate","framework_pass":"begin3","stage":"compute"}
              ],
              "gpuProgramTimings":[{
                "metric":"begin3_a_compute",
                "kind":"program",
                "program":"begin3_a",
                "stage":"compute",
                "source":"GenerateSkyViewLUT.comp.glsl",
                "defines":{"SKY_VIEW_SAMPLES":"32"},
                "dispatch":"direct:120x68x1",
                "framework_pass":"begin3",
                "compatibility_metric":"begin3_compute",
                "statistics":{"avg":300,"p5":250,"p95":350,"p50":300,"samples":[250,300,350]}
              }]
            }
            """;
    }

    private final class Fixture {
        final RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        final SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        final RecordingLink link = new RecordingLink(runtime.events);
        final SourceActivator activator = new SourceActivator(registry, link);
        final ArtifactManager artifacts = new ArtifactManager(
            pending.resolveSibling(pending.getFileName() + "-artifacts"));
        final RuntimeJobExecutor executor = new RuntimeJobExecutor(runtime, new CoreProbe(), activator, artifacts);

        Source source(String marker) throws Exception {
            return source(
                marker,
                dev.vibris.protocol.v2.VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED,
                "main"
            );
        }

        Source source(
            String marker,
            dev.vibris.protocol.v2.VcsCheckoutState checkoutState,
            String branch
        ) throws Exception {
            String uuid = UUID.randomUUID().toString();
            PreparedSourceRef reference = SourceTestArchive.source(
                pending, uuid, marker, checkoutState, branch).toBuilder()
                .setRequestedRevision("workspace")
                .setResolvedRevision("a".repeat(40))
                .setStartHead("a".repeat(40))
                .setOrigin(dev.vibris.protocol.v2.SourceOrigin.newBuilder()
                    .setWorkspace(dev.vibris.protocol.v2.WorkspaceOrigin.newBuilder().setDisplayName("fixture")))
                .build();
            List<SourceRegistry.Lease> leases = registry.reserve(registry.validate(List.of(reference)));
            registry.accept(leases);
            return new Source(uuid, pending.resolve(uuid), leases.getFirst());
        }

        void activate(Source source) throws Exception {
            SourceActivator.Activation activation = activator.begin(source.lease);
            activator.commit(activation);
            activator.release(List.of(source.lease));
        }

        CoreJob activateJob(Source source) {
            return job(source, ActionSequence.newBuilder()
                .addActions(Action.newBuilder().setActivateSource(ActivateSource.newBuilder()
                    .setSourceUuid(source.uuid)))
                .build());
        }

        CoreJob loadJob(Source source) {
            return loadJob(
                source,
                ShaderConfig.newBuilder().setPreserveCurrent(true).build(),
                false
            );
        }

        CoreJob loadJob(Source source, ShaderConfig config, boolean restore) {
            return job(source, ActionSequence.newBuilder()
                .addActions(Action.newBuilder().setLoadShader(LoadShader.newBuilder()
                    .setSourceUuid(source.uuid)
                    .setSourceId("source")
                    .setConfigId("config")
                    .setConfig(config)))
                .build(), restore);
        }

        CompileValidationCase compileCase(String id, Source source, String configId) {
            return RuntimeJobExecutorTest.compileCase(id, source, configId);
        }

        CoreJob compileJob(List<Source> sources, CompileValidationRequest validation) {
            JobSpec.Builder spec = JobSpec.newBuilder()
                .setJobId("job-" + UUID.randomUUID())
                .setContext(SceneContext.newBuilder()
                    .setSaveId("save")
                    .setDimensionId("minecraft:overworld")
                    .setFov(70.0))
                .setCompileValidation(validation);
            sources.forEach(source -> spec.addSources(source.lease.reference()));
            CoreJob job = new CoreJob(spec.build(), spec.getJobId(), WORKSPACE_ID, "message", null);
            job.initialize(sources.stream().map(Source::lease).toList());
            return job;
        }

        CoreJob job(Source source, ActionSequence actions) {
            return job(source, actions, false);
        }

        CoreJob job(Source source, ActionSequence actions, boolean restore) {
            JobSpec spec = JobSpec.newBuilder()
                .setJobId("job-" + UUID.randomUUID())
                .setContext(SceneContext.newBuilder()
                    .setSaveId("save")
                    .setDimensionId("minecraft:overworld")
                    .setFov(70.0))
                .addSources(source.lease.reference())
                .setActionSequence(actions)
                .setRestoreState(RestorePolicy.newBuilder().setOnSuccess(restore).setOnError(restore))
                .build();
            CoreJob job = new CoreJob(spec, spec.getJobId(), WORKSPACE_ID, "message", null);
            job.initialize(List.of(source.lease));
            return job;
        }
    }

    private static CompileValidationCase compileCase(String id, Source source, String configId) {
        return CompileValidationCase.newBuilder()
            .setCaseId(id)
            .setSourceId(source.uuid)
            .setConfigId(configId)
            .setConfig(ShaderConfig.newBuilder().setPreserveCurrent(true))
            .build();
    }

    private static final class RecordingLink implements ShaderLink {
        private final List<String> events;
        private boolean tampered;

        RecordingLink(List<String> events) {
            this.events = events;
        }

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
        public boolean retainsActiveSource() throws Failure {
            if (tampered) throw new Failure("active shader link changed", false);
            return true;
        }
    }

    private record Source(String uuid, Path path, SourceRegistry.Lease lease) {
    }
}
