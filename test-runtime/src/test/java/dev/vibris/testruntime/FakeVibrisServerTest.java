package dev.vibris.testruntime;

import dev.vibris.protocol.v2.ClientHello;
import dev.vibris.protocol.v2.ClientMessage;
import dev.vibris.protocol.v2.GetServerInfoRequest;
import dev.vibris.protocol.v2.GetServerInfoResponse;
import dev.vibris.protocol.v2.GetStatusRequest;
import dev.vibris.protocol.v2.ListPresetsRequest;
import dev.vibris.protocol.v2.Ping;
import dev.vibris.protocol.v2.ProtocolVersion;
import dev.vibris.protocol.v2.ServerMessage;
import dev.vibris.protocol.v2.VibrisControlGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeVibrisServerTest {
    private static final ProtocolVersion V2 = ProtocolVersion.newBuilder().setMajor(3).setMinor(1).build();

    @TempDir
    Path temp;

    @Test
    void startsAndSerializesDeterministicUnaryResponses() throws Exception {
        try (RunningServer running = RunningServer.start(temp.resolve("unary"))) {
            VibrisControlGrpc.VibrisControlBlockingStub client = VibrisControlGrpc.newBlockingStub(running.channel());
            GetServerInfoResponse response = client.getServerInfo(GetServerInfoRequest.getDefaultInstance());
            GetServerInfoResponse parsed = GetServerInfoResponse.parseFrom(response.toByteArray());

            assertTrue(running.server().port() > 0);
            assertEquals(3, parsed.getProtocolVersion().getMajor());
            assertTrue(parsed.getServer().getStatus().getReadiness().getCoreOnline());
            assertEquals(
                temp.resolve("unary/pending-shaders").toAbsolutePath().normalize().toString(),
                parsed.getServer().getPendingSourceRoot()
            );
            assertEquals(5, parsed.getServer().getCapabilitiesCount());
            assertEquals(1, client.listPresets(ListPresetsRequest.getDefaultInstance()).getPresetsCount());
            assertTrue(client.getStatus(GetStatusRequest.getDefaultInstance()).getStatus().getReadiness().getCoreOnline());
        }
    }

    @Test
    void advertisesExplicitSourceAndArtifactRoots() throws Exception {
        Path pendingRoot = temp.resolve("explicit-pending");
        Path artifactRoot = temp.resolve("explicit-artifacts");
        try (FakeVibrisServer server = FakeVibrisServer.start(0, pendingRoot, artifactRoot)) {
            ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().build();
            try {
                GetServerInfoResponse response = VibrisControlGrpc.newBlockingStub(channel)
                    .getServerInfo(GetServerInfoRequest.getDefaultInstance());
                assertEquals(pendingRoot.toAbsolutePath().normalize().toString(),
                    response.getServer().getPendingSourceRoot());
                assertTrue(response.getServer().getStatus().getArtifactCapacity().getCapBytes() > 0);
            } finally {
                channel.shutdownNow();
                assertTrue(channel.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void helloThenPingReturnsNegotiatedHelloAndCallerMessageId() throws Exception {
        try (RunningServer running = RunningServer.start(temp.resolve("hello"))) {
            Responses responses = new Responses();
            StreamObserver<ClientMessage> requests = VibrisControlGrpc.newStub(running.channel()).control(responses);
            requests.onNext(hello(V2, "hello-message"));

            ServerMessage serverHello = responses.next();
            assertTrue(serverHello.hasServerHello());
            assertEquals(3, serverHello.getProtocolVersion().getMajor());
            assertEquals(1, serverHello.getProtocolVersion().getMinor());

            requests.onNext(ClientMessage.newBuilder()
                .setProtocolVersion(V2)
                .setMessageId("caller-ping")
                .setRequestId("request-1")
                .setWorkspaceId("workspace-1")
                .setPing(Ping.newBuilder().setSequence(7).setClientTimeUnixMs(1234))
                .build());
            ServerMessage pong = responses.next();

            assertTrue(pong.hasPong());
            assertEquals("caller-ping", pong.getMessageId());
            assertEquals(7, pong.getPong().getSequence());
            assertEquals(1234, pong.getPong().getClientTimeUnixMs());
            assertEquals(1234, pong.getPong().getServerTimeUnixMs());
            requests.onCompleted();
            assertTrue(responses.awaitTerminal());
        }
    }

    @Test
    void rejectsMajorMismatchWithoutServerHello() throws Exception {
        try (RunningServer running = RunningServer.start(temp.resolve("mismatch"))) {
            Responses responses = new Responses();
            StreamObserver<ClientMessage> requests = VibrisControlGrpc.newStub(running.channel()).control(responses);
            ProtocolVersion v1 = ProtocolVersion.newBuilder().setMajor(1).setMinor(0).build();
            requests.onNext(hello(v1, "hello-v1"));

            Throwable error = responses.awaitError();
            assertTrue(responses.isEmpty());
            assertEquals(Status.Code.FAILED_PRECONDITION, Status.fromThrowable(error).getCode());
            assertEquals("UNSUPPORTED_VERSION", Status.fromThrowable(error).getDescription());
        }
    }

    @Test
    void requiresClientHelloAsFirstControlMessage() throws Exception {
        try (RunningServer running = RunningServer.start(temp.resolve("first-message"))) {
            Responses responses = new Responses();
            StreamObserver<ClientMessage> requests = VibrisControlGrpc.newStub(running.channel()).control(responses);
            requests.onNext(ClientMessage.newBuilder()
                .setProtocolVersion(V2)
                .setMessageId("ping-before-hello")
                .setPing(Ping.newBuilder().setSequence(1))
                .build());

            Throwable error = responses.awaitError();
            assertTrue(responses.isEmpty());
            assertEquals("CLIENT_HELLO_REQUIRED", Status.fromThrowable(error).getDescription());
        }
    }

    @Test
    void closeIsIdempotentAndTerminatesServer() throws Exception {
        FakeVibrisServer server = FakeVibrisServer.start(0, temp.resolve("close"));
        assertFalse(server.isTerminated());

        server.close();
        server.close();

        assertTrue(server.isTerminated());
    }

    private static ClientMessage hello(ProtocolVersion version, String messageId) {
        return ClientMessage.newBuilder()
            .setProtocolVersion(version)
            .setMessageId(messageId)
            .setRequestId("request-1")
            .setWorkspaceId("workspace-1")
            .setClientHello(ClientHello.newBuilder()
                .setClientVersion("test")
                .setProcessInstanceId("58f2e5d8-6587-4d0d-a334-4e259466fb27"))
            .build();
    }

    private record RunningServer(FakeVibrisServer server, ManagedChannel channel) implements AutoCloseable {
        private static RunningServer start(Path workRoot) throws Exception {
            FakeVibrisServer server = FakeVibrisServer.start(0, workRoot);
            ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().build();
            return new RunningServer(server, channel);
        }

        @Override
        public void close() throws Exception {
            channel.shutdownNow();
            assertTrue(channel.awaitTermination(5, TimeUnit.SECONDS));
            server.close();
        }
    }

    private static final class Responses implements StreamObserver<ServerMessage> {
        private final ArrayBlockingQueue<ServerMessage> messages = new ArrayBlockingQueue<>(4);
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public void onNext(ServerMessage message) {
            assertTrue(messages.offer(message), "Response queue capacity exceeded");
        }

        @Override
        public void onError(Throwable throwable) {
            error.set(throwable);
            terminal.countDown();
        }

        @Override
        public void onCompleted() {
            terminal.countDown();
        }

        private ServerMessage next() throws InterruptedException {
            ServerMessage message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "Timed out waiting for server response");
            return message;
        }

        private Throwable awaitError() throws InterruptedException {
            assertTrue(terminal.await(5, TimeUnit.SECONDS), "Timed out waiting for stream failure");
            Throwable throwable = error.get();
            assertNotNull(throwable, "Expected stream failure");
            return throwable;
        }

        private boolean awaitTerminal() throws InterruptedException {
            return terminal.await(5, TimeUnit.SECONDS);
        }

        private boolean isEmpty() {
            return messages.isEmpty();
        }
    }
}
