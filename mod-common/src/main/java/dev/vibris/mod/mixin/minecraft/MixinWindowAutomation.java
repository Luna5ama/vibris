package dev.vibris.mod.mixin.minecraft;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import dev.vibris.mod.VibrisPlatform;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Files;

@Mixin(Window.class)
public abstract class MixinWindowAutomation {
	@Shadow private int windowedX;
	@Shadow private int windowedY;

	@Shadow public abstract long handle();

	@Inject(method = "setWindowed", at = @At("HEAD"), require = 1, expect = 1)
	private void vibris$preserveWindowedPosition(int width, int height, CallbackInfo ci) {
		long window = handle();
		if (GLFW.glfwGetWindowMonitor(window) != 0L) return;

		// onMove updates x/y, but setWindowed restores the separately cached windowedX/Y.
		// Read GLFW immediately before resizing, even if a move callback has not arrived yet.
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var x = stack.mallocInt(1);
			var y = stack.mallocInt(1);
			GLFW.glfwGetWindowPos(window, x, y);
			windowedX = x.get(0);
			windowedY = y.get(0);
		}
	}
	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"), require = 1, expect = 1)
	private void vibris$configureAutomationFocus(WindowEventHandler handler, ScreenManager screenManager,
		DisplayData displayData, String title, String videoMode, CallbackInfo ci) {
		if (Files.isRegularFile(VibrisPlatform.getInstance().gameDirectory().resolve("config/vibris/server.json"))) {
			GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
			GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
		}
	}
}