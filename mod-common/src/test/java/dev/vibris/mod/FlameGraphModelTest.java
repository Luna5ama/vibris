package dev.vibris.mod;

import dev.luna5ama.vibris.capture.GpuFrameTiming;
import dev.luna5ama.vibris.capture.GpuScopeTiming;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlameGraphModelTest {
	@Test
	void accumulatesActualSamplesThenCapsEmaWindowAtSixtyFourFrames() {
		FlameGraphModel model = new FlameGraphModel();
		model.accept(frame(100, scope("first", 10, 40)));
		assertEquals(100, model.snapshot().totalNanoseconds());

		model.accept(frame(200, scope("first", 20, 80)));
		assertEquals(150, model.snapshot().totalNanoseconds());
		assertEquals(15, model.snapshot().roots().getFirst().startNanoseconds());
		assertEquals(60, model.snapshot().roots().getFirst().durationNanoseconds());

		FlameGraphModel capped = new FlameGraphModel();
		for (int frame = 0; frame < 64; frame++) capped.accept(frame(64));
		capped.accept(frame(128));
		assertEquals(65, capped.snapshot().totalNanoseconds());
	}

	@Test
	void absentScopesAreNotDrawnAndExpireAfterTwentyFrames() {
		FlameGraphModel model = new FlameGraphModel();
		model.accept(frame(1_000, scope("transient", 10, 100)));
		for (int index = 0; index < 20; index++) model.accept(frame(1_000));
		assertTrue(model.snapshot().roots().isEmpty());
		model.accept(frame(1_000, scope("transient", 30, 300)));
		assertEquals(30, model.snapshot().roots().getFirst().startNanoseconds());
		assertEquals(300, model.snapshot().roots().getFirst().durationNanoseconds());
	}

	@Test
	void preservesLatestHierarchyAndDuplicateOrder() {
		FlameGraphModel model = new FlameGraphModel();
		GpuScopeTiming child = new GpuScopeTiming("child", 20, 30, List.of());
		model.accept(frame(100, new GpuScopeTiming("parent", 10, 80, List.of(child))));
		assertEquals("child", model.snapshot().roots().getFirst().children().getFirst().name());
	}

	@Test
	void labelFallsBackThroughFullTailTimeAndBlank() {
		assertEquals("composite13(3.12ms)", label(19));
		assertEquals("...site13(3.12ms)", label(17));
		assertEquals("3.12ms", label(6));
		assertEquals("", label(5));
	}

	@Test
	void layoutUsesFramebufferPixelsAndClipsToParent() {
		assertEquals(new VibrisFlameGraph.Bounds(250, 500),
			VibrisFlameGraph.layoutBounds(250, 250, 1_000, 1_000, 0, 1_000));
		assertEquals(new VibrisFlameGraph.Bounds(300, 600),
			VibrisFlameGraph.layoutBounds(100, 800, 1_000, 1_000, 300, 600));
	}

	private static String label(int width) {
		return VibrisFlameGraph.fitLabel("composite13", 3_120_000, width, String::length);
	}

	private static GpuFrameTiming frame(long total, GpuScopeTiming... scopes) {
		return new GpuFrameTiming(total, List.of(scopes));
	}

	private static GpuScopeTiming scope(String name, long start, long duration) {
		return new GpuScopeTiming(name, start, duration, List.of());
	}
}
