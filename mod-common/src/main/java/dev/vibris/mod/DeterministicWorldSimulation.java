package dev.vibris.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;

import java.util.concurrent.CompletableFuture;

final class DeterministicWorldSimulation {
	private final MinecraftContextController.SceneReadyGate gate = MinecraftContextController.sceneReadyGate();
	private ClientLevel settledLevel;

	void renderedFrame(Minecraft minecraft, MinecraftContextController contexts, long frameId) {
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.getSingleplayerServer() == null || minecraft.screen != null) {
			gate.reset();
			return;
		}
		if (level == settledLevel) return;
		var readiness = contexts.inspectRenderedTerrain();
		if (gate.observe(frameId, System.nanoTime(), readiness.ready(), readiness.signature())) {
			sceneReady(minecraft, true);
		}
	}

	void sceneReady(Minecraft minecraft, boolean freeze) {
		apply(minecraft, freeze);
		settledLevel = minecraft.level;
		gate.reset();
	}

	void applyIfSettled(Minecraft minecraft, boolean freeze) {
		if (minecraft.level == settledLevel) apply(minecraft, freeze);
	}

	void apply(Minecraft minecraft, boolean freeze) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.level == null) return;

		CompletableFuture<Void> applied = new CompletableFuture<>();
		server.execute(() -> {
			try {
				server.getCommands().performPrefixedCommand(
					server.createCommandSourceStack().withSuppressedOutput(), freeze ? "tick freeze" : "tick unfreeze");
				if (server.tickRateManager().isFrozen() != freeze) {
					throw new IllegalStateException("Minecraft did not apply the requested tick state");
				}
				applied.complete(null);
			} catch (Throwable failure) {
				applied.completeExceptionally(failure);
			}
		});
		applied.join();
	}
}
