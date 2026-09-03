package dev.vibris.mod.mixin.minecraft;

import dev.vibris.mod.IrisVibrisLifecycle;
import dev.vibris.mod.VibrisClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class MixinKeyboardHandler_VibrisInputBlock {
	@Shadow
	private long debugCrashKeyTime;

	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void iris$blockKeyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
		VibrisClient.flameGraph().handleKeyPress(action, event);
		if (!IrisVibrisLifecycle.shouldBlockUserInput()) return;

		KeyMapping.releaseAll();
		ci.cancel();
	}

	@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
	private void iris$blockCharacterInput(long window, CharacterEvent event, CallbackInfo ci) {
		if (IrisVibrisLifecycle.shouldBlockUserInput()) ci.cancel();
	}

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void iris$blockLatchedKeyboardInput(CallbackInfo ci) {
		if (!IrisVibrisLifecycle.shouldBlockUserInput()) return;

		debugCrashKeyTime = -1L;
		KeyMapping.releaseAll();
		ci.cancel();
	}
}
