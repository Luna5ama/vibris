package dev.vibris.mod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlameGraphDragInputTest {
	@Test
	void ctrlIsRequiredAtPressAndThroughoutDrag() {
		FlameGraphDragInput input = new FlameGraphDragInput();
		input.button(0, 1, 100, false);
		assertEquals(0, input.move(120, false));
		assertEquals(0, input.move(140, true));
		input.button(0, 1, 140, true);
		assertEquals(20, input.move(160, true));
		assertEquals(0, input.move(180, false));
		assertEquals(0, input.move(200, true));
	}

	@Test
	void rawPressAndReleaseWorkWithoutMinecraftInGameButtonState() {
		FlameGraphDragInput input = new FlameGraphDragInput();
		input.button(0, 1, 100, true);
		assertEquals(30, input.move(130, true));
		assertEquals(-20, input.move(110, true));
		input.button(1, 1, 110, true);
		assertEquals(10, input.move(120, true));
		input.button(0, 0, 120, true);
		assertEquals(0, input.move(180, true));
	}

	@Test
	void grabbingBlockingOrFocusLossCancelsUntilAnotherPress() {
		FlameGraphDragInput input = new FlameGraphDragInput();
		input.button(0, 1, 100, true);
		assertEquals(0, input.move(500, false));
		assertEquals(0, input.move(600, true));
		input.button(0, 1, 600, false);
		assertEquals(0, input.move(700, true));
		input.button(0, 1, 700, true);
		input.reset();
		assertEquals(0, input.move(800, true));
	}
}
