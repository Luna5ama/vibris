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
	private double viewStart;
	private double viewSpan = 1.0;

	public void setToggleKey(KeyMapping toggleKey) {
		this.toggleKey = toggleKey;
	}

	public void setJobActive(boolean active) {
		jobActive = active;
	}

	public void handleKeyPress(int action, KeyEvent event) {
		if (action == 1 && toggleKey != null && toggleKey.matches(event)) requestedVisible = !requestedVisible;
	}

	public void handleScroll(double mouseX, double verticalScroll) {
		if (!effectiveVisible || verticalScroll == 0.0 || !Double.isFinite(verticalScroll)) return;
		Viewport viewport = zoomViewport(viewStart, viewSpan, mouseX, verticalScroll);
		viewStart = viewport.start();
		viewSpan = viewport.span();
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
		if (snapshot == null || snapshot.shaderNanoseconds() <= 0) return;
		Minecraft minecraft = Minecraft.getInstance();
		Window window = minecraft.getWindow();
		float inverseScale = 1.0F / window.getGuiScale();
		graphics.pose().pushMatrix();
		graphics.pose().scale(inverseScale, inverseScale);
		try {
			int width = window.getWidth();
			int height = window.getHeight();
			drawBox(graphics, minecraft.font, 0, height - BOX_HEIGHT, width, height,
				"GPU Frame", snapshot.frameNanoseconds(), "/GPU Frame", 0);
			drawBox(graphics, minecraft.font, 0, height - ROW_STRIDE - BOX_HEIGHT, width, height - ROW_STRIDE,
				"Iris Shader", snapshot.shaderNanoseconds(), "/GPU Frame/Iris Shader", 1);
			for (FlameGraphModel.Node node : snapshot.roots()) {
				drawNode(graphics, minecraft.font, node, snapshot.shaderNanoseconds(), width, 0, width, height, 2);
			}
		} finally {
			graphics.pose().popMatrix();
		}
	}

	public void reset() {
		model.clear();
		resetViewport();
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
		resetViewport();
		VibrisClient.shaderDebugControl().setRealtimeTimingEnabled(next);
	}

	private void drawNode(
		GuiGraphics graphics, Font font, FlameGraphModel.Node node, long total, int framebufferWidth,
		int parentLeft, int parentRight,
		int framebufferHeight, int depth
	) {
		if (node.durationNanoseconds() <= 0) return;
		Bounds bounds = layoutBounds(
			node.startNanoseconds(), node.durationNanoseconds(), total, framebufferWidth, parentLeft, parentRight,
			viewStart, viewSpan);
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
		return layoutBounds(start, duration, total, width, parentLeft, parentRight, 0.0, 1.0);
	}

	static Bounds layoutBounds(long start, long duration, long total, int width, int parentLeft, int parentRight,
		double viewStart, double viewSpan) {
		if (total <= 0 || duration <= 0) return new Bounds(parentLeft, parentLeft);
		int left = clamp((int) Math.floor((((double) start / total) - viewStart) / viewSpan * width), parentLeft, parentRight);
		int right = clamp((int) Math.ceil((((double) (start + duration) / total) - viewStart) / viewSpan * width), left, parentRight);
		return new Bounds(left, right);
	}

	static Viewport zoomViewport(double start, double span, double mouseX, double scroll) {
		double anchor = Math.max(0.0, Math.min(1.0, mouseX));
		double nextSpan = Math.max(MIN_VIEW_SPAN, Math.min(1.0, span / Math.pow(ZOOM_STEP, scroll)));
		double worldAnchor = start + anchor * span;
		double nextStart = Math.max(0.0, Math.min(1.0 - nextSpan, worldAnchor - anchor * nextSpan));
		return new Viewport(nextStart, nextSpan);
	}

	private void resetViewport() {
		viewStart = 0.0;
		viewSpan = 1.0;
	}

	record Bounds(int left, int right) {}
	record Viewport(double start, double span) {}

	private static final int BOX_HEIGHT = 11;
	private static final int ROW_STRIDE = 12;
	private static final int TEXT_PADDING = 2;
	private static final int BORDER_COLOR = 0xE020160F;
	private static final double ZOOM_STEP = 1.25;
	private static final double MIN_VIEW_SPAN = 1.0 / 64.0;
}
