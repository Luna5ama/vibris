package dev.vibris.mod;

import com.mojang.blaze3d.platform.Window;
import dev.luna5ama.vibris.capture.GpuFrameTiming;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;

import java.awt.Color;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

public final class VibrisFlameGraph {
	private final FlameGraphModel model = new FlameGraphModel();
	private volatile boolean jobActive;
	private boolean requestedVisible;
	private boolean effectiveVisible;
	private KeyMapping toggleKey;

	public void setToggleKey(KeyMapping toggleKey) {
		this.toggleKey = toggleKey;
	}

	public void setJobActive(boolean active) {
		jobActive = active;
	}

	public void handleKeyPress(int action, KeyEvent event) {
		if (action == 1 && toggleKey != null && toggleKey.matches(event)) requestedVisible = !requestedVisible;
	}

	public void beginFrame() {
		if (toggleKey != null) {
			while (toggleKey.consumeClick()) {
				// KeyboardHandler toggles directly so the binding also works while a Screen is open.
			}
		}
		synchronizeEffectiveState();
		if (effectiveVisible) {
			acceptCompleted();
			VibrisClient.shaderDebugControl().beginFrame();
		}
	}

	public void render(GuiGraphics graphics) {
		synchronizeEffectiveState();
		if (!effectiveVisible) return;
		acceptCompleted();
		FlameGraphModel.Snapshot snapshot = model.snapshot();
		if (snapshot == null || snapshot.totalNanoseconds() <= 0) return;
		Minecraft minecraft = Minecraft.getInstance();
		Window window = minecraft.getWindow();
		float inverseScale = 1.0F / window.getGuiScale();
		graphics.pose().pushMatrix();
		graphics.pose().scale(inverseScale, inverseScale);
		try {
			int width = window.getWidth();
			int height = window.getHeight();
			drawBox(graphics, minecraft.font, 0, height - BOX_HEIGHT, width, height,
				"GPU Frame", snapshot.totalNanoseconds(), "/GPU Frame", 0);
			for (FlameGraphModel.Node node : snapshot.roots()) {
				drawNode(graphics, minecraft.font, node, snapshot.totalNanoseconds(), width, 0, width, height, 1);
			}
		} finally {
			graphics.pose().popMatrix();
		}
	}

	public void reset() {
		model.clear();
		VibrisClient.shaderDebugControl().setRealtimeTimingEnabled(false);
		effectiveVisible = false;
	}

	private void acceptCompleted() {
		for (GpuFrameTiming frame : VibrisClient.shaderDebugControl().drainRealtimeTimings()) model.accept(frame);
	}

	private void synchronizeEffectiveState() {
		boolean next = requestedVisible && !jobActive;
		if (next == effectiveVisible) return;
		effectiveVisible = next;
		model.clear();
		VibrisClient.shaderDebugControl().setRealtimeTimingEnabled(next);
	}

	private void drawNode(
		GuiGraphics graphics, Font font, FlameGraphModel.Node node, long total, int framebufferWidth,
		int parentLeft, int parentRight,
		int framebufferHeight, int depth
	) {
		if (node.durationNanoseconds() <= 0) return;
		Bounds bounds = layoutBounds(node.startNanoseconds(), node.durationNanoseconds(), total, framebufferWidth, parentLeft, parentRight);
		int left = bounds.left();
		int right = bounds.right();
		if (right <= left) return;
		int bottom = framebufferHeight - depth * ROW_STRIDE;
		int top = bottom - BOX_HEIGHT;
		drawBox(graphics, font, left, top, right, bottom, node.name(), node.durationNanoseconds(), node.key(), depth);
		for (FlameGraphModel.Node child : node.children()) {
			drawNode(graphics, font, child, total, framebufferWidth, left, right, framebufferHeight, depth + 1);
		}
	}

	private void drawBox(GuiGraphics graphics, Font font, int left, int top, int right, int bottom,
		String name, long nanoseconds, String path, int depth) {
		graphics.fill(left, top, right, bottom, BORDER_COLOR);
		if (right - left > 2 && bottom - top > 2) graphics.fill(left + 1, top + 1, right - 1, bottom - 1, color(path, depth));
		String label = fitLabel(font, name, nanoseconds, right - left - TEXT_PADDING * 2);
		if (!label.isEmpty()) graphics.drawString(font, label, left + TEXT_PADDING, top + 1, textColor(color(path, depth)), false);
	}

	static String fitLabel(Font font, String name, long nanoseconds, int width) {
		return fitLabel(name, nanoseconds, width, font::width);
	}

	static String fitLabel(String name, long nanoseconds, int width, ToIntFunction<String> measure) {
		String time = String.format(Locale.ROOT, "%.2fms", nanoseconds / 1_000_000.0);
		if (measure.applyAsInt(time) > width) return "";
		String full = name + "(" + time + ")";
		if (measure.applyAsInt(full) <= width) return full;
		String suffix = "(" + time + ")";
		for (int start = 0; start < name.length(); start++) {
			String candidate = "..." + name.substring(start) + suffix;
			if (measure.applyAsInt(candidate) <= width) return candidate;
		}
		return time;
	}

	private static int color(String path, int depth) {
		float hue = Math.floorMod(path.hashCode(), 51) / 360.0F;
		float brightness = Math.max(0.62F, 0.94F - depth * 0.035F);
		return 0xE0000000 | (Color.HSBtoRGB(hue, 0.72F, brightness) & 0x00FFFFFF);
	}

	private static int textColor(int color) {
		int red = color >> 16 & 255;
		int green = color >> 8 & 255;
		int blue = color & 255;
		return red * 299 + green * 587 + blue * 114 >= 150_000 ? 0xFF000000 : 0xFFFFFFFF;
	}

	private static int clamp(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	static Bounds layoutBounds(long start, long duration, long total, int width, int parentLeft, int parentRight) {
		if (total <= 0 || duration <= 0) return new Bounds(parentLeft, parentLeft);
		int left = clamp((int) Math.floor((double) start / total * width), parentLeft, parentRight);
		int right = clamp((int) Math.ceil((double) (start + duration) / total * width), left, parentRight);
		return new Bounds(left, right);
	}

	record Bounds(int left, int right) {}

	private static final int BOX_HEIGHT = 11;
	private static final int ROW_STRIDE = 12;
	private static final int TEXT_PADDING = 2;
	private static final int BORDER_COLOR = 0xE020160F;
}
