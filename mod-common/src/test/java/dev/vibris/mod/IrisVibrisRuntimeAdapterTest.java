package dev.vibris.mod;

import dev.vibris.api.CancellationToken;
import dev.vibris.api.ArtifactSink;
import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.CompileCatalog;
import dev.vibris.api.ContextApplyResult;
import dev.vibris.api.DeterministicTemporalCaptureOutcome;
import dev.vibris.api.DeterministicTemporalCapturePlanner;
import dev.vibris.api.DeterministicTemporalCaptureRequest;
import dev.vibris.api.EffectiveShaderSettings;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.api.RuntimeEnvironment;
import dev.vibris.api.RuntimeStatus;
import dev.vibris.api.SceneContext;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.core.RenderedFrameClock;
import dev.vibris.core.DeterministicTemporalCaptureScheduler;
import dev.vibris.core.ThreadBoundVibrisRuntimeAdapter;
import dev.vibris.core.VibrisRuntimeHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisVibrisRuntimeAdapterTest {
	@Test
	void activateContextAndWaitRenderedFrames() {
		ControlledHost host = new ControlledHost();
		RenderedFrameClock frames = new RenderedFrameClock();
		ThreadBoundVibrisRuntimeAdapter adapter = new ThreadBoundVibrisRuntimeAdapter(host, frames);
		SceneContext expected = new SceneContext(
			"shader-test-world",
			"minecraft:overworld",
			"sunset",
			"clear",
			"village-rooftop",
			70.0,
			new SceneContext.Resolution(1280, 720),
			"automation"
		);

		var context = adapter.ensureWorldAndContext(expected, CancellationToken.none()).toCompletableFuture();
		assertFalse(context.isDone(), "context work must be queued to the Minecraft client thread");
		host.runClientTasks();
		assertEquals(expected, context.join().context());
		assertEquals(expected, host.appliedContext);

		var wait = adapter.waitRenderedFrames(32, CancellationToken.none()).toCompletableFuture();
		for (int frame = 0; frame < 31; frame++) frames.renderedFrame();
		assertFalse(wait.isDone(), "31 render-tail notifications must not satisfy a 32-frame wait");
		frames.renderedFrame();
		host.runClientTasks();
		assertEquals(32L, wait.join());
		assertTrue(host.clientThreadCalls > 0);
	}

	@Test
	void cancelledFrameWaitCompletesWithoutAnotherFrame() throws Exception {
		RenderedFrameClock frames = new RenderedFrameClock();
		CancellationToken.Source cancellation = CancellationToken.source();
		var wait = frames.waitRenderedFrames(32, cancellation.token()).toCompletableFuture();

		cancellation.cancel();
		assertThrows(CancellationException.class, () -> wait.get(1, TimeUnit.SECONDS));
	}

	@Test
	void runtimeEnvironmentIsQueriedOnClientThread() {
		ControlledHost host = new ControlledHost();
		ThreadBoundVibrisRuntimeAdapter adapter = new ThreadBoundVibrisRuntimeAdapter(
			host, new RenderedFrameClock());

		var environment = adapter.getRuntimeEnvironment().toCompletableFuture();
		assertFalse(environment.isDone());
		host.runClientTasks();

		assertEquals("1.21.11", environment.join().minecraftVersion());
		assertTrue(host.clientThreadCalls > 0);
	}

	private static final class ControlledHost implements VibrisRuntimeHost {
		private final Queue<Runnable> clientTasks = new ArrayDeque<>();
		private SceneContext appliedContext;
		private boolean clientThread;
		private int clientThreadCalls;

		@Override
		public boolean isClientThread() {
			return clientThread;
		}

		@Override
		public void executeOnClient(Runnable task) {
			clientTasks.add(task);
		}

		@Override
		public RuntimeEnvironment runtimeEnvironment() {
			requireClientThread();
			return new RuntimeEnvironment(
				"1.21.11", "iris-test", "vibris-test", "21.0.8", "Windows x86_64",
				"NVIDIA Corporation", "NVIDIA GeForce RTX", "4.6.0", "4.6.0 NVIDIA");
		}

		@Override
		public RuntimeStatus status() {
			requireClientThread();
			return new RuntimeStatus(true, "shader-test-world", "minecraft:overworld", "source-a");
		}

		@Override
		public CompletableFuture<ContextApplyResult> applyContext(
			SceneContext context,
			CancellationToken cancellation
		) {
			requireClientThread();
			appliedContext = context;
			return CompletableFuture.completedFuture(ContextApplyResult.success(context));
		}

		@Override
		public ReloadResult reload(Map<String, String> config, CancellationToken cancellation) {
			requireClientThread();
			return ReloadResult.success(EffectiveShaderSettings.empty(), List.of());
		}

		@Override
		public TemporalResetResult resetTemporal(CancellationToken cancellation) {
			requireClientThread();
			return new TemporalResetResult(true);
		}

		@Override
		public CompletableFuture<DeterministicTemporalCaptureOutcome> captureDeterministicTemporalPhase(
			DeterministicTemporalCaptureRequest request,
			DeterministicTemporalCapturePlanner planner,
			ArtifactSink sink,
			DeterministicTemporalCaptureScheduler scheduler,
			CancellationToken cancellation
		) {
			requireClientThread();
			return CompletableFuture.failedFuture(
				new UnsupportedOperationException("No deterministic capture phase in this runtime fixture"));
		}

		@Override
		public void beginDeterministicSequence(CancellationToken cancellation) {
			requireClientThread();
			cancellation.throwIfCancellationRequested();
		}

		@Override
		public void endDeterministicSequence(CancellationToken cancellation) {
			requireClientThread();
			cancellation.throwIfCancellationRequested();
		}

		@Override
		public CompileCatalog compileCatalog(CancellationToken cancellation) {
			requireClientThread();
			return CompileCatalog.empty(0);
		}

		@Override
		public ResourceCatalog resourceCatalog(long frameId) {
			requireClientThread();
			return ResourceCatalog.empty();
		}

		@Override
		public CaptureResult capture(
			CapturePlan plan,
			ArtifactSink sink,
			long frameId,
			CancellationToken cancellation
		) {
			requireClientThread();
			return new CaptureResult(frameId, List.of());
		}

		@Override
		public CompletableFuture<CapturePlan.AfterPassReceipt> captureAfterPass(
			CapturePlan.AfterPassRequest request,
			ArtifactSink sink,
			CancellationToken cancellation
		) {
			requireClientThread();
			return CompletableFuture.failedFuture(
				new UnsupportedOperationException("No pass boundary in this runtime fixture"));
		}

		@Override
		public void close() {
		}

		void runClientTasks() {
			clientThread = true;
			try {
				while (!clientTasks.isEmpty()) clientTasks.remove().run();
			} finally {
				clientThread = false;
			}
		}

		private void requireClientThread() {
			assertTrue(clientThread);
			clientThreadCalls++;
		}
	}
}
