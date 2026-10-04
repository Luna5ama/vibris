package dev.vibris.core;

import dev.vibris.api.SceneContext;
import dev.vibris.api.ScenePreset;
import dev.vibris.protocol.v2.ClientHello;
import dev.vibris.protocol.v2.ClientMessage;
import dev.vibris.protocol.v2.ErrorCode;
import dev.vibris.protocol.v2.GetServerInfoRequest;
import dev.vibris.protocol.v2.GetServerInfoResponse;
import dev.vibris.protocol.v2.GetStatusRequest;
import dev.vibris.protocol.v2.GetStatusResponse;
import dev.vibris.protocol.v2.ListPresetsRequest;
import dev.vibris.protocol.v2.ListPresetsResponse;
import dev.vibris.protocol.v2.ListResourcesRequest;
import dev.vibris.protocol.v2.ManageArtifactsRequest;
import dev.vibris.protocol.v2.RequestRestartRequest;
import dev.vibris.protocol.v2.RequestRestartResponse;
import dev.vibris.protocol.v2.ServerMessage;
import dev.vibris.protocol.v2.ValidateContextRequest;
import dev.vibris.protocol.v2.ValidateContextResponse;
import dev.vibris.protocol.v2.VibrisControlGrpc;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class VibrisBootstrapTest {
    @TempDir
    Path temp;

    @Test
    void configPreservesJavaRecordAbi() {
        assertTrue(VibrisBootstrap.Config.class.isRecord());
        assertEquals(java.lang.Record.class, VibrisBootstrap.Config.class.getSuperclass());
        assertEquals(
            List.of("port", "pendingShadersRoot", "artifactRoot", "shaderpackRoot"),
            java.util.Arrays.stream(VibrisBootstrap.Config.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList()
        );
    }

    @Test
    void configCanonicalConstructorNormalizesPaths() {
        Path relativePending = Path.of("build", ".", "pending-relative");
        Path relativeArtifacts = Path.of("build", "intermediate", "..", "artifacts-relative");
        Path absoluteShaderpack = temp.resolve("shaderpack-parent").resolve("..").resolve("shaderpack");

        VibrisBootstrap.Config config = new VibrisBootstrap.Config(
            50_051,
            relativePending,
            relativeArtifacts,
            absoluteShaderpack
        );

        assertEquals(relativePending.toAbsolutePath().normalize(), config.pendingShadersRoot());
        assertEquals(relativeArtifacts.toAbsolutePath().normalize(), config.artifactRoot());
        assertEquals(absoluteShaderpack.toAbsolutePath().normalize(), config.shaderpackRoot());
    }

    @Test
    void missingServerConfigWritesV4DefaultsAndStartsReady() throws Exception {
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        AtomicReference<BindableService> captured = new AtomicReference<>();

        VibrisBootstrap bootstrap = VibrisBootstrap.start(temp, runtime, (address, service) -> {
            assertEquals(new InetSocketAddress("127.0.0.1", 50051), address);
            captured.set(service);
            return new TestListener();
        });

        assertTrue(bootstrap.ready());
        String defaultConfig = Files.readString(temp.resolve("config/vibris/server.json"));
        assertTrue(defaultConfig.contains("\"schema_version\": 4"));
        assertTrue(defaultConfig.contains("\"vibris_root\":"));
        assertFalse(defaultConfig.contains("\"pending_source_root\":"));
        assertFalse(defaultConfig.contains("\"artifact_root\":"));
        assertTrue(defaultConfig.contains("\"restart_executable\": \"\""));
        Path defaultVibrisRoot = temp.resolve("vibris").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(defaultVibrisRoot.resolve("pending"), NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(defaultVibrisRoot.resolve("artifacts"), NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(defaultVibrisRoot.resolve("replay_capture"), NOFOLLOW_LINKS));
        Path defaultShaderpack = temp.resolve("shaderpacks/vibris").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(defaultShaderpack, NOFOLLOW_LINKS));
        assertEquals(defaultShaderpack, ServerConfiguration.Companion.load(temp).getPaths().shaderpackRoot());
        GetStatusResponse status = status(captured.get());
        assertTrue(status.getStatus().getCanAcceptJob());
        bootstrap.close();
        assertEquals(1, runtime.closeCount);
    }

    @Test
    void missingConfiguredRootsAreCreatedBeforeStartup() throws Exception {
        Path pending = temp.resolve("missing-pending");
        Path artifacts = temp.resolve("missing-artifacts");
        Path shaderpack = temp.resolve("missing-shaderpack");
        writeServerConfig(temp, pending, artifacts, shaderpack, 50123);
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();

        VibrisBootstrap bootstrap = VibrisBootstrap.start(temp, runtime, (address, service) -> {
            assertEquals(new InetSocketAddress("127.0.0.1", 50123), address);
            return new TestListener();
        });

        assertTrue(bootstrap.ready());
        assertTrue(Files.isDirectory(pending, NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(artifacts, NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(shaderpack, NOFOLLOW_LINKS));
        bootstrap.close();
    }

    @Test
    void configuredRootUsesARealWriteProbe() throws Exception {
        String externalRoot = System.getenv("VIBRIS_TEST_WRITABLE_ROOT");
        Path pending = externalRoot == null
            ? Files.createDirectory(temp.resolve("writable-pending"))
            : Path.of(externalRoot);
        Path artifacts = Files.createDirectory(temp.resolve("writable-artifacts"));
        Path shaderpack = Files.createDirectory(temp.resolve("writable-shaderpack"));
        writeServerConfig(temp, pending, artifacts, shaderpack, 50123);

        ServerConfiguration configuration = ServerConfiguration.Companion.load(temp);

        assertEquals(pending.toAbsolutePath().normalize(), configuration.getPaths().pendingShadersRoot());
    }

    @Test
    void gameSideReplayerRootDoesNotFollowConfiguredVibrisRoot() throws Exception {
        Path vibrisRoot = temp.resolve("external-vibris-root");
        Path shaderpack = temp.resolve("external-vibris-shaderpack");
        writeServerConfigV4(temp, vibrisRoot, shaderpack, 50128);

        ServerConfiguration configuration = ServerConfiguration.Companion.load(temp);

        assertEquals(vibrisRoot.toAbsolutePath().normalize(), configuration.getVibrisRoot());
        assertEquals(
            vibrisRoot.resolve("replay_capture").toAbsolutePath().normalize(),
            configuration.getReplayCaptureRoot()
        );
        assertEquals(temp.resolve("vibris").toAbsolutePath().normalize(), configuration.getReplayerRoot());
    }

    @Test
    void nonV2ServerConfigFailsExplicitlyWithoutRewrite() throws Exception {
        Path pending = temp.resolve("v1-pending");
        Path artifacts = temp.resolve("v1-artifacts");
        Path shaderpack = temp.resolve("v1-shaderpack");
        writeServerConfig(temp, pending, artifacts, shaderpack, 50123);
        Path file = temp.resolve("config/vibris/server.json");
        String v1 = Files.readString(file).replace("\"schema_version\": 2", "\"schema_version\": 1");
        Files.writeString(file, v1);

        ServerConfiguration.Failure failure = assertThrows(
            ServerConfiguration.Failure.class,
            () -> ServerConfiguration.Companion.load(temp)
        );

        assertTrue(failure.getMessage().contains("UNSUPPORTED_VERSION"));
        assertEquals(v1, Files.readString(file));
    }

    @Test
    void configuredShaderpackRootRejectsLinksWithoutParentFallback() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("link-pending"));
        Path artifacts = Files.createDirectory(temp.resolve("link-artifacts"));
        Path target = Files.createDirectory(temp.resolve("link-target"));
        Path shaderpack = temp.resolve("shaderpack-link");
        Files.createSymbolicLink(shaderpack, target);
        writeServerConfig(temp, pending, artifacts, shaderpack, 50123);

        ServerConfiguration.Failure failure = assertThrows(
            ServerConfiguration.Failure.class,
            () -> ServerConfiguration.Companion.load(temp)
        );

        assertTrue(failure.getMessage().contains("shaderpack_root"));
        assertTrue(Files.isSymbolicLink(shaderpack));
    }

    @Test
    void notReadyServiceImplementsEveryUnaryOverLoopbackGrpc() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        Path pending = Files.createFile(temp.resolve("not-a-directory"));
        Path artifacts = Files.createDirectory(temp.resolve("grpc-artifacts"));
        Path shaderpack = Files.createDirectory(temp.resolve("grpc-shaderpack"));
        writeServerConfig(temp, pending, artifacts, shaderpack, port);
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();

        VibrisBootstrap bootstrap = VibrisBootstrap.start(temp, runtime);
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", bootstrap.port())
            .usePlaintext()
            .build();
        try {
            var stub = VibrisControlGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
            GetServerInfoResponse info = stub.getServerInfo(GetServerInfoRequest.getDefaultInstance());
            assertEquals(3, info.getProtocolVersion().getMajor());
            assertEquals(ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE,
                info.getServer().getStatus().getLastError().getCode());
            GetStatusResponse response = stub.getStatus(GetStatusRequest.getDefaultInstance());
            assertEquals(3, response.getProtocolVersion().getMajor());
            assertFalse(response.getStatus().getCanStartJob());
            assertEquals(ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE,
                response.getStatus().getLastError().getCode());
            assertTrue(response.getStatus().getLastError().getMessage().contains("pending_source_root"));
            assertUnavailable(() -> stub.listPresets(ListPresetsRequest.getDefaultInstance()));
            assertUnavailable(() -> stub.listResources(ListResourcesRequest.getDefaultInstance()));
            assertUnavailable(() -> stub.validateContext(ValidateContextRequest.getDefaultInstance()));
            assertUnavailable(() -> stub.manageArtifacts(ManageArtifactsRequest.getDefaultInstance()));
            assertUnavailable(() -> stub.requestRestart(RequestRestartRequest.getDefaultInstance()));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            bootstrap.close();
        }
    }

    @Test
    void serverConfigStartsConfiguredService() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("configured-pending"));
        Path artifacts = Files.createDirectory(temp.resolve("ready-artifacts"));
        Path shaderpack = Files.createDirectory(temp.resolve("ready-shaderpack"));
        writeServerConfig(temp, pending, artifacts, shaderpack, 50124);
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();

        VibrisBootstrap bootstrap = VibrisBootstrap.start(temp, runtime, (address, service) -> {
            assertEquals(new InetSocketAddress("127.0.0.1", 50124), address);
            assertTrue(service instanceof VibrisControlService);
            return new TestListener();
        });

        assertTrue(bootstrap.ready());
        assertEquals(pending.toAbsolutePath().normalize(), bootstrap.pendingShadersRoot());
        bootstrap.close();
    }

    @Test
    void configuredRestartSchedulesAfterDrainAndInvokesExactExecutable() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("restart-pending"));
        Path artifacts = Files.createDirectory(temp.resolve("restart-artifacts"));
        Path shaderpack = Files.createDirectory(temp.resolve("restart-shaderpack"));
        Path executable = Files.createFile(temp.resolve("restart minecraft.bat"));
        writeServerConfigV3(temp, pending, artifacts, shaderpack, executable, 50127);
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        AtomicReference<BindableService> captured = new AtomicReference<>();
        AtomicReference<Path> launched = new AtomicReference<>();
        CountDownLatch launch = new CountDownLatch(1);

        VibrisBootstrap bootstrap = VibrisBootstrap.start(
            temp,
            runtime,
            path -> {
                launched.set(path);
                launch.countDown();
            },
            (address, service) -> {
                captured.set(service);
                return new TestListener();
            }
        );
        var service = (VibrisControlService) captured.get();
        AtomicReference<RequestRestartResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch notices = new CountDownLatch(2);
        service.control(restartObserver(notices, failure)).onNext(clientHello("restart-observer-a"));
        service.control(restartObserver(notices, failure)).onNext(clientHello("restart-observer-b"));
        service.requestRestart(
            RequestRestartRequest.newBuilder()
                .setProtocolVersion(ProtocolMessages.V2)
                .setWorkspaceId("restart-test-workspace")
                .setReason("deploy test build")
                .build(),
            observer(response, failure)
        );

        assertNull(failure.get());
        assertTrue(response.get().hasRestart());
        assertFalse(response.get().getAlreadyScheduled());
        assertEquals("deploy test build", response.get().getRestart().getReason());
        assertTrue(notices.await(2, TimeUnit.SECONDS));
        assertTrue(launch.await(2, TimeUnit.SECONDS));
        assertEquals(executable.toAbsolutePath().normalize(), launched.get());
        assertTrue(status(service).getStatus().hasRestart());
        assertFalse(status(service).getStatus().getCanAcceptJob());
        bootstrap.close();
    }

    @Test
    void listPresetsReturnsTagsCompleteContextAndStableHash() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("preset-pending"));
        Path artifacts = Files.createDirectory(temp.resolve("preset-artifacts"));
        Path shaderpack = Files.createDirectory(temp.resolve("preset-shaderpack"));
        writeServerConfig(temp, pending, artifacts, shaderpack, 50126);
        AtomicReference<BindableService> captured = new AtomicReference<>();
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        runtime.presets = List.of(new ScenePreset(
            "sky-noon-1",
            "Sky noon",
            new SceneContext(
                "shader-test-world",
                "minecraft:overworld",
                "sky-noon-1",
                "clear",
                "sky-noon-1",
                70.0,
                new SceneContext.Resolution(1920, 1080),
                "default"
            ),
            "7",
            List.of("regression", "sky")
        ));
        VibrisBootstrap bootstrap = VibrisBootstrap.start(temp, runtime, (address, service) -> {
            captured.set(service);
            return new TestListener();
        });

        ListPresetsResponse response = listPresets(captured.get());

        assertEquals(1, response.getPresetsCount());
        var preset = response.getPresets(0);
        assertEquals("sky-noon-1", preset.getPresetId());
        assertEquals("7", preset.getVersion());
        assertEquals(List.of("regression", "sky"), preset.getTagsList());
        assertTrue(preset.getPresetSha256().matches("[0-9a-f]{64}"));
        assertEquals("clear", preset.getContext().getWeatherPresetId());
        assertEquals(1920, preset.getContext().getResolution().getWidth());
        assertEquals("default", preset.getContext().getSettingsPresetId());
        bootstrap.close();
    }

    @Test
    void validatedContextIsNotPersistedAcrossMinecraftRestarts() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("auto-enter-pending"));
        Path artifacts = Files.createDirectory(temp.resolve("auto-enter-artifacts"));
        Path shaderpack = Files.createDirectory(temp.resolve("auto-enter-shaderpack"));
        writeServerConfig(temp, pending, artifacts, shaderpack, 50125);
        AtomicReference<BindableService> captured = new AtomicReference<>();
        RuntimeTestAdapter configuredRuntime = new RuntimeTestAdapter();
        VibrisBootstrap configured = VibrisBootstrap.start(temp, configuredRuntime, (address, service) -> {
            captured.set(service);
            return new TestListener();
        });
        dev.vibris.protocol.v2.SceneContext context = dev.vibris.protocol.v2.SceneContext.newBuilder()
            .setSaveId("shader-test-world")
            .setDimensionId("minecraft:overworld")
            .setTimePresetId("rooftop")
            .setCameraPresetId("rooftop")
            .setFov(70.0)
            .build();

        ValidateContextResponse validation = validate(captured.get(), context);
        configured.close();

        assertTrue(validation.getValid());
        RuntimeTestAdapter restartedRuntime = new RuntimeTestAdapter();
        VibrisBootstrap restarted = VibrisBootstrap.start(temp, restartedRuntime, (address, service) ->
            new TestListener());
        assertTrue(restartedRuntime.events.isEmpty());
        assertNull(restartedRuntime.lastContext);
        assertFalse(Files.exists(temp.resolve("config/vibris/configured-context.pb")));
        restarted.close();
    }

    @Test
    void startupCleansLinkAndPendingRootBeforeListenerStarts() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending"));
        Path stale = Files.createDirectory(pending.resolve("stale"));
        Files.writeString(stale.resolve("main.glsl"), "stale");
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("sentinel.txt"), "user");
        Files.createSymbolicLink(pending.resolve("stale-link"), outside);
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack"));
        Files.createSymbolicLink(shaderpack.resolve("shaders"), stale);
        Path artifacts = temp.resolve("artifacts");
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        AtomicBoolean listened = new AtomicBoolean();
        TestListener listener = new TestListener(runtime.events);
        VibrisBootstrap.Config config = new VibrisBootstrap.Config(0, pending, artifacts, shaderpack);

        // When
        VibrisBootstrap bootstrap = VibrisBootstrap.start(config, runtime, (address, service) -> {
            assertEquals(new InetSocketAddress("127.0.0.1", 0), address);
            assertDirectoryEmpty(pending);
            assertFalse(Files.exists(shaderpack.resolve("shaders"), NOFOLLOW_LINKS));
            listened.set(true);
            return listener;
        });

        // Then
        assertTrue(listened.get());
        bootstrap.close();
        bootstrap.close();
        assertEquals(1, listener.stopCount);
        assertEquals(1, listener.awaitCount);
        assertEquals(1, runtime.closeCount);
        assertEquals(List.of("listener-stop", "close", "listener-await"), runtime.events);
        assertTrue(Files.isRegularFile(sentinel));
    }

    @Test
    void startupCreatesMissingOwnedRootsBeforeListening() throws Exception {
        Path pending = temp.resolve("pending-new");
        Path artifacts = temp.resolve("artifacts-new");
        Path shaderpack = temp.resolve("shaderpack-new");
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();

        VibrisBootstrap bootstrap = VibrisBootstrap.start(
            new VibrisBootstrap.Config(0, pending, artifacts, shaderpack), runtime,
            (address, service) -> new TestListener());

        assertTrue(Files.isDirectory(pending));
        assertTrue(Files.isDirectory(artifacts));
        assertTrue(Files.isDirectory(shaderpack));
        bootstrap.close();
    }

    @Test
    void startupFailsBeforeListenWhenActivePathIsOrdinaryDirectory() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending-fail"));
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack-fail"));
        Path active = Files.createDirectory(shaderpack.resolve("shaders"));
        Path sentinel = Files.writeString(active.resolve("sentinel.txt"), "user");
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        AtomicBoolean listened = new AtomicBoolean();
        VibrisBootstrap.Config config = new VibrisBootstrap.Config(
            0, pending, temp.resolve("artifacts-fail"), shaderpack);

        // When
        assertThrows(VibrisBootstrap.Failure.class, () -> VibrisBootstrap.start(config, runtime, (address, service) -> {
            listened.set(true);
            return new TestListener();
        }));

        // Then
        assertFalse(listened.get());
        assertTrue(Files.isRegularFile(sentinel));
        assertEquals(1, runtime.closeCount);
    }

    @Test
    void closeDetachesLinkThenClearsPendingRoot() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending-close"));
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack-close"));
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        VibrisBootstrap.Config config = new VibrisBootstrap.Config(
            0, pending, temp.resolve("artifacts-close"), shaderpack);
        VibrisBootstrap bootstrap = VibrisBootstrap.start(config, runtime, (address, service) -> new TestListener());
        Path source = Files.createDirectory(pending.resolve(java.util.UUID.randomUUID().toString()));
        Files.writeString(source.resolve("main.glsl"), "active");
        Files.createSymbolicLink(shaderpack.resolve("shaders"), source);

        // When
        bootstrap.close();

        // Then
        assertFalse(Files.exists(shaderpack.resolve("shaders"), NOFOLLOW_LINKS));
        assertDirectoryEmpty(pending);
    }

    @Test
    void closeStillAwaitsListenerAndClearsPendingWhenRuntimeCloseFails() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-close-fail"));
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack-close-fail"));
        Files.createDirectory(pending.resolve("stale"));
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        runtime.closeFailure = new IllegalStateException("runtime close failed");
        TestListener listener = new TestListener();
        VibrisBootstrap bootstrap = VibrisBootstrap.start(new VibrisBootstrap.Config(
            0, pending, temp.resolve("artifacts-close-fail"), shaderpack), runtime, (address, service) -> listener);
        Files.createDirectory(pending.resolve("shutdown-stale"));

        VibrisBootstrap.Failure failure = assertThrows(VibrisBootstrap.Failure.class, bootstrap::close);

        assertEquals(ErrorCode.ERROR_CODE_INTERNAL, failure.code());
        assertEquals(1, listener.awaitCount);
        assertDirectoryEmpty(pending);
    }

    private static void assertDirectoryEmpty(Path directory) throws IOException {
        try (var children = Files.list(directory)) {
            assertTrue(children.findAny().isEmpty());
        }
    }

    private static void assertUnavailable(Executable call) {
        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, call);
        assertEquals(Status.Code.UNAVAILABLE, failure.getStatus().getCode());
        assertTrue(failure.getStatus().getDescription().contains("ERROR_CODE_SERVER_NOT_AVAILABLE"));
        assertTrue(failure.getStatus().getDescription().contains("pending_source_root"));
    }

    private static GetStatusResponse status(BindableService service) {
        AtomicReference<GetStatusResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ((VibrisControlGrpc.VibrisControlImplBase) service).getStatus(
            GetStatusRequest.getDefaultInstance(),
            new StreamObserver<>() {
                @Override
                public void onNext(GetStatusResponse value) {
                    response.set(value);
                }

                @Override
                public void onError(Throwable throwable) {
                    failure.set(throwable);
                }

                @Override
                public void onCompleted() {
                }
            }
        );
        assertTrue(failure.get() == null, () -> "GetStatus failed: " + failure.get());
        return response.get();
    }

    private static ListPresetsResponse listPresets(BindableService service) {
        AtomicReference<ListPresetsResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ((VibrisControlGrpc.VibrisControlImplBase) service).listPresets(
            ListPresetsRequest.getDefaultInstance(),
            new StreamObserver<>() {
                @Override
                public void onNext(ListPresetsResponse value) {
                    response.set(value);
                }

                @Override
                public void onError(Throwable throwable) {
                    failure.set(throwable);
                }

                @Override
                public void onCompleted() {
                }
            }
        );
        assertTrue(failure.get() == null, () -> "ListPresets failed: " + failure.get());
        return response.get();
    }

    private static ValidateContextResponse validate(
        BindableService service,
        dev.vibris.protocol.v2.SceneContext context
    ) {
        AtomicReference<ValidateContextResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ((VibrisControlGrpc.VibrisControlImplBase) service).validateContext(
            ValidateContextRequest.newBuilder().setContext(context).build(),
            new StreamObserver<>() {
                @Override
                public void onNext(ValidateContextResponse value) {
                    response.set(value);
                }

                @Override
                public void onError(Throwable throwable) {
                    failure.set(throwable);
                }

                @Override
                public void onCompleted() {
                }
            }
        );
        assertTrue(failure.get() == null, () -> "ValidateContext failed: " + failure.get());
        return response.get();
    }

    private static void writeServerConfig(
        Path game,
        Path pending,
        Path artifacts,
        Path shaderpack,
        int port
    ) throws IOException {
        Path config = game.resolve("config/vibris/server.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
            {
              "schema_version": 2,
              "listen_address": "127.0.0.1:%d",
              "pending_source_root": "%s",
              "artifact_root": "%s",
              "artifact_quota_bytes": 3221225472,
              "artifact_ttl_hours": 168,
              "shaderpack_root": "%s",
              "max_source_bytes": 536870912,
              "max_source_files": 100000,
              "max_global_queue": 32,
              "max_actions_per_job": 64
            }
            """.formatted(port, jsonPath(pending), jsonPath(artifacts), jsonPath(shaderpack)));
    }

    private static void writeServerConfigV3(
        Path game,
        Path pending,
        Path artifacts,
        Path shaderpack,
        Path restartExecutable,
        int port
    ) throws IOException {
        Path config = game.resolve("config/vibris/server.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
            {
              "schema_version": 3,
              "listen_address": "127.0.0.1:%d",
              "pending_source_root": "%s",
              "artifact_root": "%s",
              "artifact_quota_bytes": 3221225472,
              "artifact_ttl_hours": 168,
              "shaderpack_root": "%s",
              "max_source_bytes": 536870912,
              "max_source_files": 100000,
              "max_global_queue": 32,
              "max_actions_per_job": 64,
              "restart_executable": "%s"
            }
            """.formatted(
                port,
                jsonPath(pending),
                jsonPath(artifacts),
                jsonPath(shaderpack),
                jsonPath(restartExecutable)
            ));
    }

    private static void writeServerConfigV4(
        Path game,
        Path vibrisRoot,
        Path shaderpack,
        int port
    ) throws IOException {
        Path config = game.resolve("config/vibris/server.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
            {
              "schema_version": 4,
              "listen_address": "127.0.0.1:%d",
              "vibris_root": "%s",
              "artifact_quota_bytes": 3221225472,
              "artifact_ttl_hours": 168,
              "shaderpack_root": "%s",
              "max_source_bytes": 536870912,
              "max_source_files": 100000,
              "max_global_queue": 32,
              "max_actions_per_job": 64,
              "restart_executable": ""
            }
            """.formatted(port, jsonPath(vibrisRoot), jsonPath(shaderpack)));
    }

    private static <T> StreamObserver<T> observer(
        AtomicReference<T> response,
        AtomicReference<Throwable> failure
    ) {
        return new StreamObserver<>() {
            @Override
            public void onNext(T value) {
                response.set(value);
            }

            @Override
            public void onError(Throwable throwable) {
                failure.set(throwable);
            }

            @Override
            public void onCompleted() {
            }
        };
    }

    private static ClientMessage clientHello(String workspaceId) {
        return ClientMessage.newBuilder()
            .setProtocolVersion(ProtocolMessages.V2)
            .setMessageId("hello-" + workspaceId)
            .setWorkspaceId(workspaceId)
            .setClientHello(ClientHello.newBuilder()
                .setClientVersion("restart-test")
                .setProcessInstanceId("process-" + workspaceId))
            .build();
    }

    private static StreamObserver<ServerMessage> restartObserver(
        CountDownLatch notices,
        AtomicReference<Throwable> failure
    ) {
        return new StreamObserver<>() {
            @Override
            public void onNext(ServerMessage message) {
                if (message.hasServerShuttingDown()) notices.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }

            @Override
            public void onCompleted() {
            }
        };
    }

    private static String jsonPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class TestListener implements VibrisBootstrap.Listener {
        private final List<String> events;
        private int stopCount;
        private int awaitCount;

        TestListener() {
            this(new java.util.ArrayList<>());
        }

        TestListener(List<String> events) {
            this.events = events;
        }

        @Override
        public int port() {
            return 0;
        }

        @Override
        public void stopAdmission() {
            events.add("listener-stop");
            stopCount++;
        }

        @Override
        public void awaitTermination() {
            events.add("listener-await");
            awaitCount++;
        }
    }
}
