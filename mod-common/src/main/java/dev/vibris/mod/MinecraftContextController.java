package dev.vibris.mod;

import dev.luna5ama.vibris.capture.VibrisPresetCatalog;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.ContextApplyResult;
import dev.vibris.api.SceneContext;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import dev.vibris.mod.mixinterface.VibrisTerrainQuiescence;
import dev.vibris.mod.mixin.minecraft.WorldOpenFlowsInvoker;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.ContentValidationException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.LongPredicate;

final class MinecraftContextController {
	private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
	private static final int STABLE_RENDERED_FRAMES = 8;
	private static final long STABLE_SCENE_NANOS = TimeUnit.SECONDS.toNanos(1);
	private final Minecraft minecraft;
	private final VibrisPresetCatalog presets;
	private volatile boolean closed;
	private volatile ContextOperation activeOperation;

	MinecraftContextController(Minecraft minecraft, VibrisPresetCatalog presets) {
		this.minecraft = minecraft;
		this.presets = presets;
	}

	ContextOperation begin(SceneContext context, CancellationToken cancellation) {
		cancellation.throwIfCancellationRequested();
		ContextOperation operation = new ContextOperation(
			context,
			cancellation,
			System.nanoTime() + TIMEOUT_NANOS,
			sceneReadyGate()
		);
		String rejection = null;
		synchronized (this) {
			if (closed) {
				rejection = "The Vibris runtime is closed.";
			} else if (activeOperation != null && !activeOperation.released) {
				rejection = "Another Minecraft context operation is already active.";
			} else {
				activeOperation = operation;
			}
		}
		if (rejection != null) {
			fail(operation, rejection);
			return operation;
		}
		watch(operation);
		continuePreparation(operation);
		return operation;
	}

	ContextOperation beginCurrent(SceneContext context, CancellationToken cancellation) {
		cancellation.throwIfCancellationRequested();
		ContextOperation operation = new ContextOperation(
			context,
			cancellation,
			System.nanoTime() + TIMEOUT_NANOS,
			sceneReadyGate()
		);
		String rejection = null;
		synchronized (this) {
			if (closed) {
				rejection = "The Vibris runtime is closed.";
			} else if (activeOperation != null && !activeOperation.released) {
				rejection = "Another Minecraft context operation is already active.";
			} else {
				activeOperation = operation;
			}
		}
		if (rejection != null) {
			fail(operation, rejection);
			return operation;
		}
		try {
			operation.resolved = presets.resolve(operation.context);
			String mismatch = clientMismatch(operation.context, operation.resolved);
			if (mismatch != null) {
				fail(operation, "The retained deterministic context changed: " + mismatch);
				return operation;
			}
		} catch (Throwable failure) {
			fail(operation, failureMessage(failure));
			return operation;
		}
		watch(operation);
		operation.prepared.complete(ContextApplyResult.success(operation.context));
		return operation;
	}

	CompletionStage<ContextApplyResult> awaitSceneReady(ContextOperation operation) {
		if (!isActive(operation)) {
			if (!operation.sceneReady.isDone()) {
				operation.sceneReady.complete(ContextApplyResult.failure(
					operation.context, "The Minecraft context operation is no longer active."));
			}
			return operation.sceneReady;
		}
		ContextApplyResult prepared = operation.prepared.getNow(null);
		if (prepared == null) {
			throw new IllegalStateException("The Minecraft context must be prepared before waiting for scene readiness");
		}
		if (!prepared.successful()) {
			operation.sceneReady.complete(prepared);
			return operation.sceneReady;
		}
		if (!operation.readinessStarted) {
			operation.readinessStarted = true;
			operation.deadline = System.nanoTime() + TIMEOUT_NANOS;
			operation.lastMismatch = "no completed world-render frame has been observed";
			operation.gate.reset();
		}
		return operation.sceneReady;
	}

	void release(ContextOperation operation) {
		synchronized (operation) {
			if (operation.released) return;
			if (operation.serverMutationInFlight || operation.worldTransitionInFlight) {
				operation.releaseRequested = true;
				return;
			}
			operation.releaseRequested = false;
			operation.released = true;
		}
		synchronized (this) {
			if (activeOperation == operation) activeOperation = null;
		}
	}

	private void continuePreparation(ContextOperation operation) {
		if (abort(operation)) return;
		if (minecraft.getLevelSource() == null) {
			operation.lastMismatch = "Minecraft initialization is incomplete";
			pollLater(operation, () -> continuePreparation(operation));
			return;
		}

		if (operation.resolved == null) {
			try {
				operation.resolved = presets.resolve(operation.context);
			} catch (IllegalArgumentException exception) {
				fail(operation, exception.getMessage());
				return;
			}
		}

		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server != null && requiresSaveSwitch(runningSave(server), operation.resolved.saveName())) {
			switchSave(operation);
		} else if (server != null) {
			applyLoadedSave(operation, server);
		} else {
			openSave(operation);
		}
	}

	private void openSave(ContextOperation operation) {
		if (abort(operation)) return;
		if (!minecraft.getLevelSource().levelExists(operation.resolved.saveName())) {
			fail(operation, "The configured singleplayer save does not exist.");
			return;
		}
		LevelStorageSource.LevelStorageAccess access = acquireSaveAccess(operation);
		if (access == null) return;
		WorldOpenFlowsInvoker flows = acquireWorldOpenFlows(operation, access);
		if (flows == null) return;
		if (abort(operation)) {
			access.safeClose();
			drainPendingTerminal(operation);
			return;
		}
		if (!acquireWorldTransition(operation, access)) {
			access.safeClose();
			if (!drainPendingTerminal(operation) && isActive(operation)) {
				fail(operation, "Failed to acquire the Minecraft world-transition lease.");
			}
			return;
		}
		if (abort(operation)) {
			access.safeClose();
			if (!releaseWorldTransition(operation)) return;
			drainPendingTerminal(operation);
			releaseIfRequested(operation);
			return;
		}
		openSaveWithLease(operation, flows, access);
	}

	private LevelStorageSource.LevelStorageAccess acquireSaveAccess(ContextOperation operation) {
		try {
			return minecraft.getLevelSource().validateAndCreateAccess(operation.resolved.saveName());
		} catch (IOException | ContentValidationException exception) {
			fail(operation, failureMessage(exception));
			return null;
		}
	}

	private WorldOpenFlowsInvoker acquireWorldOpenFlows(
		ContextOperation operation,
		LevelStorageSource.LevelStorageAccess access
	) {
		try {
			return (WorldOpenFlowsInvoker) minecraft.createWorldOpenFlows();
		} catch (Throwable exception) {
			access.safeClose();
			fail(operation, failureMessage(exception));
			return null;
		}
	}

	private void openSaveWithLease(
		ContextOperation operation,
		WorldOpenFlowsInvoker flows,
		LevelStorageSource.LevelStorageAccess access
	) {
		try {
			flows.iris$openWorldLoadLevelData(
				access,
				() -> minecraft.execute(() -> {
					if (!releaseWorldTransition(operation)) return;
					if (drainPendingTerminal(operation) || releaseIfRequested(operation)) return;
					fail(operation, "Minecraft rejected the configured singleplayer save.");
				})
			);
		} catch (Throwable exception) {
			fail(operation, failureMessage(exception));
		}
		pollForSave(operation);
	}

	private void switchSave(ContextOperation operation) {
		if (abort(operation)) return;
		if (!minecraft.getLevelSource().levelExists(operation.resolved.saveName())) {
			fail(operation, "The configured singleplayer save does not exist.");
			return;
		}
		LevelStorageSource.LevelStorageAccess access = acquireSaveAccess(operation);
		if (access == null) return;
		WorldOpenFlowsInvoker flows = acquireWorldOpenFlows(operation, access);
		if (flows == null) return;
		if (abort(operation)) {
			access.safeClose();
			drainPendingTerminal(operation);
			return;
		}
		if (!acquireWorldTransition(operation, access)) {
			access.safeClose();
			if (!drainPendingTerminal(operation) && isActive(operation)) {
				fail(operation, "Failed to acquire the Minecraft world-transition lease.");
			}
			return;
		}
		if (abort(operation)) {
			access.safeClose();
			if (!releaseWorldTransition(operation)) return;
			drainPendingTerminal(operation);
			releaseIfRequested(operation);
			return;
		}
		try {
			minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
		} catch (Throwable exception) {
			try {
				access.safeClose();
			} catch (Throwable closeFailure) {
				exception.addSuppressed(closeFailure);
			}
			if (!releaseWorldTransition(operation)) return;
			fail(operation, failureMessage(exception));
			return;
		}
		openSaveWithLease(operation, flows, access);
	}

	private void pollForSave(ContextOperation operation) {
		pollLater(operation, () -> {
			if (!hasWorldTransition(operation)) return;
			IntegratedServer server = minecraft.getSingleplayerServer();
			if (server != null && minecraft.player != null &&
				runningSave(server).equals(operation.resolved.saveName())) {
				if (!releaseWorldTransition(operation)) return;
				if (drainPendingTerminal(operation) || releaseIfRequested(operation) || abort(operation)) return;
				applyLoadedSave(operation, server);
			} else {
				abort(operation);
				if (hasWorldTransition(operation) && isActive(operation)) {
					operation.lastMismatch = "the configured save is still loading";
					pollForSave(operation);
				}
			}
		});
	}

	private void applyLoadedSave(ContextOperation operation, IntegratedServer server) {
		if (abort(operation) || !acquireServerMutation(operation)) return;
		try {
			applyOnServer(operation, server).whenComplete((ignored, failure) ->
				minecraft.execute(() -> finishServerMutation(operation, failure)));
		} catch (Throwable failure) {
			finishServerMutation(operation, failure);
		}
	}

	private CompletableFuture<Void> applyOnServer(ContextOperation operation, IntegratedServer server) {
		CompletableFuture<Void> completion = new CompletableFuture<>();
		server.execute(() -> {
			try {
				if (closed || operation.released || hasPendingTerminal(operation) ||
					operation.cancellation.isCancellationRequested() || System.nanoTime() >= operation.deadline) {
					operation.cancellation.throwIfCancellationRequested();
					throw new IllegalStateException("Minecraft context operation is no longer active");
				}
				ResourceKey<Level> dimension = ResourceKey.create(
					Registries.DIMENSION, Identifier.parse(operation.context.dimensionId()));
				ServerLevel level = server.getLevel(dimension);
				ServerPlayer player = minecraft.player == null ? null :
					server.getPlayerList().getPlayer(minecraft.player.getUUID());
				if (level == null || player == null) throw new IllegalStateException("Preset dimension is unavailable");
				applyWeather(level, operation.resolved.tick(), operation.resolved.weather());
				var camera = operation.resolved.camera();
				boolean cameraChanged = player.level() != level || player.getX() != camera.x() ||
					player.getY() != camera.y() || player.getZ() != camera.z() ||
					player.getYRot() != camera.yaw() || player.getXRot() != camera.pitch();
				if (cameraChanged && !player.teleportTo(
					level, camera.x(), camera.y(), camera.z(), Set.of(), camera.yaw(), camera.pitch(), true)) {
					throw new IllegalStateException("Player teleport was rejected");
				}
				player.connection.send(new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(), false));
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
				player.setDeltaMovement(0.0, 0.0, 0.0);
				completion.complete(null);
			} catch (Throwable throwable) {
				completion.completeExceptionally(throwable);
			}
		});
		return completion;
	}

	private void finishServerMutation(ContextOperation operation, Throwable failure) {
		Throwable completionFailure = failure;
		if (completionFailure == null) {
			synchronized (operation) {
				if (!abort(operation)) {
					try {
						applyClientOptions(operation.context, operation.resolved);
					} catch (Throwable clientFailure) {
						completionFailure = clientFailure;
					}
					abort(operation);
				}
			}
		} else {
			abort(operation);
		}
		if (!releaseServerMutation(operation)) return;
		if (drainPendingTerminal(operation) || releaseIfRequested(operation)) return;
		if (completionFailure != null) {
			fail(operation, failureMessage(completionFailure));
			return;
		}
		pollForClientContext(operation);
	}

	private void pollForClientContext(ContextOperation operation) {
		pollLater(operation, () -> {
			if (abort(operation)) return;
			String mismatch = clientMismatch(operation.context, operation.resolved);
			if (mismatch == null) {
				operation.prepared.complete(ContextApplyResult.success(operation.context));
			} else {
				operation.lastMismatch = mismatch;
				pollForClientContext(operation);
			}
		});
	}

	void renderedFrameTail(long frameId) {
		ContextOperation operation = activeOperation;
		if (operation == null || !operation.readinessStarted || operation.sceneReady.isDone() ||
			operation.completionScheduled) {
			return;
		}
		long now = System.nanoTime();
		if (abort(operation)) return;

		SceneReadiness readiness = inspectScene(operation);
		if (abort(operation)) return;
		operation.lastMismatch = readiness.ready() ? "scene render data is not stable" : readiness.mismatch();
		if (!operation.gate.observe(frameId, now, readiness.ready(), readiness.signature())) return;

		operation.completionScheduled = true;
		VibrisTerrainQuiescence.TerrainSnapshot selectedTerrain = readiness.signature().terrain();
		minecraft.schedule(() -> revalidateSelectedScene(operation, selectedTerrain));
	}

	private void revalidateSelectedScene(
		ContextOperation operation,
		VibrisTerrainQuiescence.TerrainSnapshot selectedTerrain
	) {
		if (operation.sceneReady.isDone() || abort(operation)) return;

		SceneReadiness readiness = inspectScene(operation);
		if (abort(operation)) return;
		if (readiness.ready() && Objects.equals(readiness.signature().terrain(), selectedTerrain) &&
			operation.gate.matches(readiness.signature())) {
			operation.sceneReady.complete(ContextApplyResult.success(operation.context));
			return;
		}

		operation.completionScheduled = false;
		operation.gate.reset();
		operation.lastMismatch = readiness.ready() ?
			"scene render data changed during final validation" : readiness.mismatch();
		if (System.nanoTime() >= operation.deadline) {
			timeout(operation);
		}
	}

	private SceneReadiness inspectScene(ContextOperation operation) {
		String mismatch = clientMismatch(operation.context, operation.resolved);
		if (mismatch != null) return SceneReadiness.notReady(mismatch);
		return inspectRenderedTerrain();
	}

	SceneReadiness inspectRenderedTerrain() {
		if (minecraft.level == null || minecraft.player == null) {
			return SceneReadiness.notReady("client world is not ready");
		}

		BlockPos camera = minecraft.gameRenderer.getMainCamera().blockPosition();
		int chunkX = camera.getX() >> 4;
		int chunkZ = camera.getZ() >> 4;
		int renderDistance = minecraft.options.getEffectiveRenderDistance();
		var readyChunks = ChunkTrackerHolder.get(minecraft.level).getReadyChunks();
		ViewCoverage coverage = inspectRenderViewCoverage(chunkX, chunkZ, renderDistance, packed -> {
			int x = unpackChunkX(packed);
			int z = unpackChunkZ(packed);
			return readyChunks.contains(packed) && minecraft.level.getChunkSource().getChunk(
				x, z, ChunkStatus.FULL, false) != null;
		});
		if (!coverage.complete()) {
			return SceneReadiness.notReady("render-view chunks are incomplete: missing " + coverage.missing() +
				"/" + coverage.expected() + ", first=(" + coverage.firstMissingX() + "," +
				coverage.firstMissingZ() + "), center=(" + chunkX + "," + chunkZ + "), radius=" + renderDistance);
		}
		if (!minecraft.level.isOutsideBuildHeight(camera.getY()) &&
			!minecraft.levelRenderer.isSectionCompiledAndVisible(camera)) {
			return SceneReadiness.notReady("the rendered camera section is not compiled");
		}

		SodiumWorldRenderer terrain = SodiumWorldRenderer.instanceNullable();
		if (terrain == null) return SceneReadiness.notReady("terrain renderer is unavailable");
		VibrisTerrainQuiescence.TerrainSnapshot terrainSnapshot =
			((VibrisTerrainQuiescence) terrain).iris$captureTerrainSnapshot();
		if (!terrainSnapshot.quiescent()) {
			return SceneReadiness.notReady("terrain renderer is not quiescent and fully visible: " +
				terrainSnapshot.mismatch());
		}
		return new SceneReadiness(
			true,
			new SceneSignature(chunkX, chunkZ, renderDistance, terrainSnapshot),
			""
		);
	}

	static ViewCoverage inspectRenderViewCoverage(
		int centerX,
		int centerZ,
		int renderDistance,
		LongPredicate ready
	) {
		if (renderDistance < 0) throw new IllegalArgumentException("Render distance must not be negative");
		int expected = 0;
		int missing = 0;
		int firstMissingX = 0;
		int firstMissingZ = 0;
		for (int x = centerX - renderDistance; x <= centerX + renderDistance; x++) {
			for (int z = centerZ - renderDistance; z <= centerZ + renderDistance; z++) {
				if (!isInViewDistance(centerX, centerZ, renderDistance, x, z)) continue;
				expected++;
				if (ready.test(packChunk(x, z))) continue;
				if (missing == 0) {
					firstMissingX = x;
					firstMissingZ = z;
				}
				missing++;
			}
		}
		return new ViewCoverage(expected, missing, firstMissingX, firstMissingZ);
	}

	private static boolean isInViewDistance(int centerX, int centerZ, int distance, int x, int z) {
		long deltaX = Math.max(0L, Math.abs((long) x - centerX) - 1L);
		long deltaZ = Math.max(0L, Math.abs((long) z - centerZ) - 1L);
		return deltaX * deltaX + deltaZ * deltaZ < (long) distance * distance;
	}

	static long packChunk(int x, int z) {
		return x & 0xFFFFFFFFL | (z & 0xFFFFFFFFL) << 32;
	}

	static int unpackChunkX(long packed) {
		return (int) packed;
	}

	static int unpackChunkZ(long packed) {
		return (int) (packed >>> 32);
	}

	private void watch(ContextOperation operation) {
		pollLater(operation, () -> {
			if (!isActive(operation) || operation.sceneReady.isDone() && !hasUnsettledSideEffects(operation)) return;
			abort(operation);
			if (isActive(operation) && (!operation.sceneReady.isDone() || hasUnsettledSideEffects(operation))) {
				watch(operation);
			}
		});
	}

	private boolean abort(ContextOperation operation) {
		if (!isActive(operation)) return true;
		if (hasPendingTerminal(operation)) return true;
		if (closed) {
			fail(operation, "Vibris runtime closed while applying the Minecraft context.");
			return true;
		}
		if (operation.cancellation.isCancellationRequested()) {
			cancel(operation);
			return true;
		}
		if (System.nanoTime() >= operation.deadline) {
			timeout(operation);
			return true;
		}
		return false;
	}

	private boolean isActive(ContextOperation operation) {
		return activeOperation == operation && !operation.released;
	}

	private void timeout(ContextOperation operation) {
		fail(operation, "Timed out applying the Minecraft context: " + operation.lastMismatch);
	}

	private void fail(ContextOperation operation, String message) {
		requestTerminal(operation, new PendingTerminal(false, message));
	}

	private void cancel(ContextOperation operation) {
		requestTerminal(operation, new PendingTerminal(true, ""));
	}

	void close() {
		ContextOperation operation;
		synchronized (this) {
			operation = activeOperation;
			if (operation == null) {
				closed = true;
				return;
			}
			synchronized (operation) {
				closed = true;
				if (!operation.released && operation.pendingTerminal == null) {
					operation.pendingTerminal = new PendingTerminal(
						false, "Vibris runtime closed while applying the Minecraft context.");
				}
			}
		}
		drainPendingTerminal(operation);
	}

	private void requestTerminal(ContextOperation operation, PendingTerminal terminal) {
		synchronized (operation) {
			if (operation.released || operation.pendingTerminal != null) return;
			operation.pendingTerminal = terminal;
		}
		drainPendingTerminal(operation);
	}

	private boolean drainPendingTerminal(ContextOperation operation) {
		PendingTerminal terminal;
		synchronized (operation) {
			terminal = operation.pendingTerminal;
			if (terminal == null) return false;
			if (operation.serverMutationInFlight || operation.worldTransitionInFlight) return true;
		}
		if (terminal.cancelled()) {
			operation.prepared.cancel(false);
			operation.sceneReady.cancel(false);
		} else {
			ContextApplyResult failure = ContextApplyResult.failure(operation.context, terminal.message());
			operation.prepared.complete(failure);
			operation.sceneReady.complete(failure);
		}
		release(operation);
		return true;
	}

	private boolean acquireWorldTransition(
		ContextOperation operation,
		LevelStorageSource.LevelStorageAccess access
	) {
		synchronized (operation) {
			if (!isActive(operation) || operation.pendingTerminal != null || operation.worldTransitionInFlight) {
				return false;
			}
			operation.worldTransitionInFlight = true;
			operation.worldAccess = access;
			return true;
		}
	}

	private boolean releaseWorldTransition(ContextOperation operation) {
		synchronized (operation) {
			if (!operation.worldTransitionInFlight) return false;
			operation.worldTransitionInFlight = false;
			operation.worldAccess = null;
			return true;
		}
	}

	private boolean hasWorldTransition(ContextOperation operation) {
		synchronized (operation) {
			return operation.worldTransitionInFlight;
		}
	}

	private boolean acquireServerMutation(ContextOperation operation) {
		synchronized (operation) {
			if (!isActive(operation) || operation.pendingTerminal != null || operation.serverMutationInFlight) {
				return false;
			}
			operation.serverMutationInFlight = true;
			return true;
		}
	}

	private boolean releaseServerMutation(ContextOperation operation) {
		synchronized (operation) {
			if (!operation.serverMutationInFlight) return false;
			operation.serverMutationInFlight = false;
			return true;
		}
	}

	private boolean hasPendingTerminal(ContextOperation operation) {
		synchronized (operation) {
			return operation.pendingTerminal != null;
		}
	}

	private boolean hasUnsettledSideEffects(ContextOperation operation) {
		synchronized (operation) {
			return operation.serverMutationInFlight || operation.worldTransitionInFlight;
		}
	}

	private boolean releaseIfRequested(ContextOperation operation) {
		synchronized (operation) {
			if (!operation.releaseRequested) return false;
		}
		release(operation);
		return true;
	}

	private String clientMismatch(SceneContext context, VibrisPresetCatalog.ResolvedContext resolved) {
		if (minecraft.level == null || minecraft.player == null) return "client world is not ready";
		String dimension = minecraft.level.dimension().identifier().toString();
		if (!dimension.equals(context.dimensionId())) return "dimension is " + dimension;
		long dayTime = minecraft.level.getDayTime();
		if (dayTime != resolved.tick()) return "day time is " + dayTime;
		float rain = minecraft.level.getRainLevel(1.0f);
		float thunder = minecraft.level.getThunderLevel(1.0f);
		float expectedRain = resolved.weather().equals("clear") ? 0.0f : 1.0f;
		float expectedThunder = resolved.weather().equals("thunder") ? 1.0f : 0.0f;
		if (rain != expectedRain) return "rain level is " + rain;
		if (thunder != expectedThunder) return "thunder level is " + thunder;
		var camera = resolved.camera();
		if (minecraft.player.position().distanceToSqr(camera.x(), camera.y(), camera.z()) > 0.01) {
			return "player position is " + minecraft.player.position();
		}
		if (angleDifference(minecraft.player.getYRot(), camera.yaw()) > 0.1f) {
			return "player yaw is " + minecraft.player.getYRot();
		}
		if (Math.abs(minecraft.player.getXRot() - camera.pitch()) > 0.1f) {
			return "player pitch is " + minecraft.player.getXRot();
		}
		int fov = minecraft.options.fov().get();
		if (fov != (int) Math.round(context.fov())) return "field of view is " + fov;
		if (context.resolution().isSpecified() &&
			(minecraft.getWindow().getWidth() != context.resolution().width() ||
				minecraft.getWindow().getHeight() != context.resolution().height())) {
			return "window size is " + minecraft.getWindow().getWidth() + "x" + minecraft.getWindow().getHeight();
		}
		return null;
	}

	private void applyClientOptions(SceneContext context, VibrisPresetCatalog.ResolvedContext resolved) {
		if (minecraft.level != null) {
			minecraft.level.setTimeFromServer(minecraft.level.getGameTime(), resolved.tick(), false);
			minecraft.level.setRainLevel(resolved.weather().equals("clear") ? 0.0f : 1.0f);
			minecraft.level.setThunderLevel(resolved.weather().equals("thunder") ? 1.0f : 0.0f);
		}
		if (minecraft.player != null) {
			var camera = resolved.camera();
			minecraft.player.snapTo(camera.x(), camera.y(), camera.z(), camera.yaw(), camera.pitch());
		}
		minecraft.options.fov().set((int) Math.round(context.fov()));
		if (context.resolution().isSpecified()) {
			minecraft.getWindow().setWindowed(context.resolution().width(), context.resolution().height());
			minecraft.resizeDisplay();
		}
	}

	static SceneReadyGate sceneReadyGate() {
		return new SceneReadyGate(STABLE_RENDERED_FRAMES, STABLE_SCENE_NANOS);
	}

	static final class SceneReadyGate {
		private final int stableFrames;
		private final long stableNanos;
		private long firstStableNanos = -1;
		private long lastNanos = -1;
		private long lastFrame = -1;
		private int consecutiveReadyFrames;
		private SceneSignature signature;

		SceneReadyGate(int stableFrames, long stableNanos) {
			if (stableFrames < 1) throw new IllegalArgumentException("Stable frame count must be positive");
			if (stableNanos < 0) throw new IllegalArgumentException("Stable duration must not be negative");
			this.stableFrames = stableFrames;
			this.stableNanos = stableNanos;
		}

		boolean observe(long frame, long nowNanos, boolean ready, SceneSignature signature) {
			if (!ready || frame < 0 || nowNanos < 0 || signature == null) {
				reset();
				return false;
			}
			boolean continues = lastFrame >= 0 && frame == lastFrame + 1 && nowNanos >= lastNanos &&
				signature.equals(this.signature);
			if (continues) {
				consecutiveReadyFrames++;
			} else {
				consecutiveReadyFrames = 1;
				firstStableNanos = nowNanos;
			}
			lastFrame = frame;
			lastNanos = nowNanos;
			this.signature = signature;
			return consecutiveReadyFrames >= stableFrames && nowNanos - firstStableNanos >= stableNanos;
		}

		boolean matches(SceneSignature signature) {
			return this.signature != null && this.signature.equals(signature);
		}

		void reset() {
			firstStableNanos = -1;
			lastNanos = -1;
			lastFrame = -1;
			consecutiveReadyFrames = 0;
			signature = null;
		}
	}

	static record SceneSignature(
		int cameraChunkX,
		int cameraChunkZ,
		int renderDistance,
		VibrisTerrainQuiescence.TerrainSnapshot terrain
	) {
	}

	static record ViewCoverage(int expected, int missing, int firstMissingX, int firstMissingZ) {
		boolean complete() {
			return missing == 0;
		}
	}

	record SceneReadiness(boolean ready, SceneSignature signature, String mismatch) {
		private static SceneReadiness notReady(String mismatch) {
			return new SceneReadiness(false, null, mismatch);
		}
	}

	private record PendingTerminal(boolean cancelled, String message) {
	}

	static final class ContextOperation {
		private final SceneContext context;
		private final CancellationToken cancellation;
		private volatile long deadline;
		private final SceneReadyGate gate;
		private final CompletableFuture<ContextApplyResult> prepared = new CompletableFuture<>();
		private final CompletableFuture<ContextApplyResult> sceneReady = new CompletableFuture<>();
		private volatile boolean released;
		private boolean releaseRequested;
		private boolean serverMutationInFlight;
		private boolean worldTransitionInFlight;
		private LevelStorageSource.LevelStorageAccess worldAccess;
		private PendingTerminal pendingTerminal;
		private VibrisPresetCatalog.ResolvedContext resolved;
		private String lastMismatch = "Minecraft context preparation has not started";
		private boolean readinessStarted;
		private boolean completionScheduled;

		private ContextOperation(
			SceneContext context,
			CancellationToken cancellation,
			long deadline,
			SceneReadyGate gate
		) {
			this.context = context;
			this.cancellation = cancellation;
			this.deadline = deadline;
			this.gate = gate;
		}

		CompletionStage<ContextApplyResult> preparation() {
			return prepared;
		}
	}

	private void pollLater(ContextOperation operation, Runnable check) {
		if (isActive(operation) && !operation.sceneReady.isDone()) {
			CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS, minecraft).execute(check);
		}
	}

	private static void applyWeather(ServerLevel level, long tick, String weather) {
		level.getGameRules().set(GameRules.ADVANCE_TIME, false, level.getServer());
		level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, level.getServer());
		level.setDayTime(tick);
		boolean rain = !weather.equals("clear");
		boolean thunder = weather.equals("thunder");
		if (!weather.equals("clear") && !weather.equals("rain") && !thunder) {
			throw new IllegalArgumentException("Unknown weather preset: " + weather);
		}
		level.setWeatherParameters(rain ? 0 : 6000, rain ? 6000 : 0, rain, thunder);
		level.setRainLevel(rain ? 1.0f : 0.0f);
		level.setThunderLevel(thunder ? 1.0f : 0.0f);
	}

	static String runningSave(IntegratedServer server) {
		Path save = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
		return save == null ? "" : save.toString();
	}

	static boolean requiresSaveSwitch(String runningSave, String requestedSave) {
		return !runningSave.equals(requestedSave);
	}

	private static float angleDifference(float first, float second) {
		float difference = (first - second) % 360.0f;
		if (difference > 180.0f) difference -= 360.0f;
		if (difference < -180.0f) difference += 360.0f;
		return Math.abs(difference);
	}

	private static String failureMessage(Throwable failure) {
		Throwable cause = failure.getCause() == null ? failure : failure.getCause();
		return "Failed to apply the Minecraft context: " + cause.getMessage();
	}
}
