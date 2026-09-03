package dev.vibris.mod.mixin.minecraft;

import dev.vibris.mod.IrisVibrisLifecycle;
import dev.vibris.mod.VibrisClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MixinMouseHandler_VibrisInputBlock {
	@Shadow
	private boolean isLeftPressed;

	@Shadow
	private boolean isMiddlePressed;

	@Shadow
	private boolean isRightPressed;

	@Shadow
	private MouseButtonInfo activeButton;

	@Shadow
	private double mousePressedTime;

	@Shadow
	private double accumulatedDX;

	@Shadow
	private double accumulatedDY;

	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void iris$blockMouseButton(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
		if (iris$discardBlockedInput()) ci.cancel();
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void iris$blockMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
		MouseHandler handler = (MouseHandler) (Object) this;
		if (!handler.isMouseGrabbed()) {
			double width = Minecraft.getInstance().getWindow().getScreenWidth();
			if (width > 0.0) VibrisClient.flameGraph().handleScroll(handler.xpos() / width, vertical);
		}
		if (iris$discardBlockedInput()) ci.cancel();
	}

	@Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
	private void iris$blockMouseMovement(long window, double x, double y, CallbackInfo ci) {
		if (iris$discardBlockedInput()) ci.cancel();
	}

	@Inject(method = "handleAccumulatedMovement", at = @At("HEAD"), cancellable = true)
	private void iris$blockAccumulatedMouseMovement(CallbackInfo ci) {
		if (iris$discardBlockedInput()) ci.cancel();
	}

	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void iris$blockMouseGrab(CallbackInfo ci) {
		if (IrisVibrisLifecycle.shouldBlockUserInput()) ci.cancel();
	}

	private boolean iris$discardBlockedInput() {
		if (!IrisVibrisLifecycle.shouldBlockUserInput()) return false;

		isLeftPressed = false;
		isMiddlePressed = false;
		isRightPressed = false;
		activeButton = null;
		mousePressedTime = 0.0;
		accumulatedDX = 0.0;
		accumulatedDY = 0.0;
		((MouseHandler) (Object) this).setIgnoreFirstMove();
		return true;
	}
}
