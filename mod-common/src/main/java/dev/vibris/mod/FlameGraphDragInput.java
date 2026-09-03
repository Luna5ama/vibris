package dev.vibris.mod;

/** Tracks raw left-button events independently of Minecraft's in-game attack-button state. */
public final class FlameGraphDragInput {
	private boolean pressed;
	private double previousX;

	public void button(int button, int action, double x, boolean available) {
		if (!available) {
			reset();
		} else if (button == 0) {
			pressed = action == 1 && Double.isFinite(x);
			previousX = x;
		}
	}

	public double move(double x, boolean available) {
		if (!available || !Double.isFinite(x)) {
			reset();
			return 0.0;
		}
		if (!pressed) return 0.0;
		double delta = x - previousX;
		previousX = x;
		return delta;
	}

	public void reset() {
		pressed = false;
	}
}
