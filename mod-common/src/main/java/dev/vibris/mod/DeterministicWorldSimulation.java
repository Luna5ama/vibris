package dev.vibris.mod;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;

import java.util.concurrent.CompletableFuture;

final class DeterministicWorldSimulation {
	private IntegratedServer appliedServer;
	private ClientLevel appliedLevel;
	private boolean frozen;

	void apply(Minecraft minecraft, boolean freeze) {
		RenderSystem.assertOnRenderThread();
		IntegratedServer server = minecraft.getSingleplayerServer();
		ClientLevel level = minecraft.level;
		if (server == null || level == null) {
			appliedServer = null;
			appliedLevel = null;
			return;
		}
		if (server == appliedServer && level == appliedLevel && freeze == frozen &&
			level.tickRateManager().isFrozen() == freeze) {
			return;
		}

		CompletableFuture<Void> applied = new CompletableFuture<>();
		server.execute(() -> {
			try {
				server.tickRateManager().setFrozen(freeze);
				applied.complete(null);
			} catch (Throwable failure) {
				applied.completeExceptionally(failure);
			}
		});
		applied.join();
		level.tickRateManager().setFrozen(freeze);
		appliedServer = server;
		appliedLevel = level;
		frozen = freeze;
	}

	boolean isFrozen(ClientLevel level) {
		return level != null && appliedLevel == level && frozen;
	}
}
