package dev.vibris.mod.mixin.minecraft;

import dev.vibris.mod.IrisVibrisLifecycle;
import dev.vibris.mod.VibrisClient;
import dev.vibris.mod.VibrisTime;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GameRenderer.class, priority = 900)
public abstract class MixinGameRendererVibris {
	@Shadow @Final private Minecraft minecraft;
	@Shadow @Final private GuiRenderState guiRenderState;

	@Inject(method = "render", at = @At("HEAD"), require = 1, expect = 1)
	private void vibris$deterministicFrame(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
		VibrisClient.flameGraph().beginFrame();
		if (!VibrisTime.active()) return;
		long frame = VibrisTime.deterministicFrame(IrisVibrisLifecycle.currentFrame());
		((FrameCounterAccess) SystemTimeUniforms.COUNTER).vibris$setCount((int) Math.floorMod(frame, 720720L));
		((TimerAccess) (Object) SystemTimeUniforms.TIMER).vibris$setLastFrameTime(VibrisTime.frameSeconds());
		((TimerAccess) (Object) SystemTimeUniforms.TIMER).vibris$setFrameTimeCounter(
			Math.floorMod(Math.addExact(frame, 1L), 216000L) * VibrisTime.frameSeconds());
		CapturedRenderingState.INSTANCE.setRealTickDelta(1.0F);
	}

	@Inject(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"),
		require = 1, expect = 1)
	private void vibris$renderFlameGraph(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
		GuiGraphics graphics = new GuiGraphics(minecraft, guiRenderState, 0, 0);
		graphics.nextStratum();
		VibrisClient.flameGraph().render(graphics);
	}

	@Inject(method = "render", at = @At("TAIL"), require = 1, expect = 1)
	private void vibris$frameTail(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
		VibrisClient.shaderDebugControl().tickFrame();
		IrisVibrisLifecycle.clientFrameTail(Minecraft.getInstance().level != null);
	}
}
