package dev.vibris.mod.mixin.minecraft;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.vibris.mod.VibrisTime;
import dev.vibris.mod.IrisVibrisLifecycle;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.opengl.GL11C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

@Mixin(Minecraft.class)
public class MixinMinecraft_VibrisFrameThrottle {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void iris$blockUserInputDuringVibrisActivity(boolean tick, CallbackInfo ci) {
		IrisVibrisLifecycle.updateWorldSimulation();
		if (!IrisVibrisLifecycle.shouldBlockUserInput()) return;

		Minecraft minecraft = (Minecraft) (Object) this;
		minecraft.mouseHandler.releaseMouse();
		KeyMapping.releaseAll();
	}

	@Inject(method = "setWindowActive", at = @At("TAIL"))
	private void iris$vibrisWindowFocusChanged(boolean focused, CallbackInfo ci) {
		IrisVibrisLifecycle.windowFocusChanged(focused);
	}

	@Inject(method = "getDeltaTracker", at = @At("RETURN"), cancellable = true)
	private void iris$useDeterministicDeltaTracker(CallbackInfoReturnable<DeltaTracker> cir) {
		cir.setReturnValue(VibrisTime.deltaTracker(cir.getReturnValue()));
	}

	@WrapWithCondition(
		method = "tick()V",
		require = 1,
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;animateTick(III)V"
		)
	)
	private boolean iris$advanceAmbientParticles(ClientLevel level, int x, int y, int z) {
		return !IrisVibrisLifecycle.isWorldSimulationFrozen();
	}

	@WrapWithCondition(
		method = "tick()V",
		require = 1,
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/particle/ParticleEngine;tick()V"
		)
	)
	private boolean iris$advanceParticles(ParticleEngine particleEngine) {
		return !IrisVibrisLifecycle.isWorldSimulationFrozen();
	}

	@WrapWithCondition(
		method = "tick()V",
		require = 1,
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;tick()V"
		)
	)
	private boolean iris$advanceGameRenderer(GameRenderer gameRenderer) {
		return !IrisVibrisLifecycle.isWorldSimulationFrozen();
	}

	@WrapWithCondition(
		method = "tick()V",
		require = 1,
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickEntities()V"
		)
	)
	private boolean iris$tickEntities(ClientLevel level) {
		return !IrisVibrisLifecycle.isWorldSimulationFrozen();
	}

	@WrapWithCondition(
		method = "tick()V",
		require = 1,
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickBlockEntities()V"
		)
	)
	private boolean iris$tickBlockEntities(ClientLevel level) {
		return !IrisVibrisLifecycle.isWorldSimulationFrozen();
	}

	@WrapWithCondition(
		method = "tick()V",
		require = 1,
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V"
		)
	)
	private boolean iris$tickClientWorld(ClientLevel level, BooleanSupplier chunkSupplier) {
		return !IrisVibrisLifecycle.isWorldSimulationFrozen();
	}

	@Redirect(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/FramerateLimitTracker;getFramerateLimit()I")
	)
	private int iris$vibrisIdleFramerateLimit(FramerateLimitTracker tracker) {
		Minecraft minecraft = (Minecraft) (Object) this;
		return IrisVibrisLifecycle.idleFramerateLimit(tracker.getFramerateLimit(), minecraft.isWindowActive());
	}

	@Redirect(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;limitDisplayFPS(I)V")
	)
	private void iris$vibrisInterruptibleFrameLimit(int framerateLimit) {
		IrisVibrisLifecycle.limitDisplayFps(framerateLimit);
	}

	@Inject(
		method = "runTick",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay(Lcom/mojang/blaze3d/TracyFrameCapture;)V",
			shift = At.Shift.AFTER
		)
	)
	private void iris$vibrisYieldRenderLoop(boolean tick, CallbackInfo ci) {
		GL11C.glFinish();
		try {
			Thread.sleep(1);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}
}
