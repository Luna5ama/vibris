package dev.vibris.mod;

import dev.luna5ama.vibris.capture.GpuFrameTiming;
import dev.luna5ama.vibris.capture.GpuScopeTiming;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlameGraphModelTest {
	@Test
	void pausePreservesSnapshotAndDoesNotAccumulateSkippedSamples() {
		FlameGraphModel model = new FlameGraphModel();
		model.accept(frame(100, scope("pass", 0, 40)));
		FlameGraphModel.Snapshot frozen = model.snapshot();
		model.setPaused(true);
		for (int i = 0; i < 100; i++) model.accept(frame(10_000));
		assertEquals(frozen, model.snapshot());
		model.setPaused(false);
		model.accept(frame(200, scope("pass", 0, 80)));
		assertEquals(150, model.snapshot().frameNanoseconds());
		assertEquals(60, model.snapshot().shaderNanoseconds());
		model.setPaused(true);
		model.clear();
		assertTrue(!model.isPaused());
	}

	@Test
	void horizontalDragTracksViewportScaleAndClampsAtEdges() {
		assertEquals(new VibrisFlameGraph.Viewport(0.25, 0.5), VibrisFlameGraph.panViewport(0.3, 0.5, 0.1));
		assertEquals(new VibrisFlameGraph.Viewport(0.35, 0.5), VibrisFlameGraph.panViewport(0.3, 0.5, -0.1));
		assertEquals(new VibrisFlameGraph.Viewport(0.0, 0.5), VibrisFlameGraph.panViewport(0.3, 0.5, 10.0));
		assertEquals(new VibrisFlameGraph.Viewport(0.5, 0.5), VibrisFlameGraph.panViewport(0.3, 0.5, -10.0));
		assertEquals(new VibrisFlameGraph.Viewport(0.0, 1.0), VibrisFlameGraph.panViewport(0.0, 1.0, -1.0));
	}

	@Test
	void accumulatesActualPassSamplesThenCapsEmaWindowAtSixtyFourFrames() {
		FlameGraphModel model = new FlameGraphModel();
		model.accept(frame(100, scope("first", 10, 40)));
		assertEquals(100, model.snapshot().frameNanoseconds());
		assertEquals(40, model.snapshot().shaderNanoseconds());

		model.accept(frame(200, scope("first", 20, 80)));
		assertEquals(150, model.snapshot().frameNanoseconds());
		assertEquals(60, model.snapshot().shaderNanoseconds());
		assertEquals(0, model.snapshot().roots().getFirst().startNanoseconds());
		assertEquals(60, model.snapshot().roots().getFirst().durationNanoseconds());

		FlameGraphModel capped = new FlameGraphModel();
		for (int frame = 0; frame < 64; frame++) capped.accept(frame(1_000, scope("first", 0, 64)));
		capped.accept(frame(1_000, scope("first", 0, 128)));
		assertEquals(65, capped.snapshot().shaderNanoseconds());
	}

	@Test
	void compactsTopLevelLabelsAndKeepsChildrenRelativeToTheirPass() {
		FlameGraphModel model = new FlameGraphModel();
		GpuScopeTiming child = new GpuScopeTiming("child", 1_120, 20, List.of());
		model.accept(frame(10_000,
			new GpuScopeTiming("first", 1_000, 100, List.of(child)),
			scope("second", 9_000, 200)));

		assertEquals(300, model.snapshot().shaderNanoseconds());
		assertEquals(0, model.snapshot().roots().get(0).startNanoseconds());
		assertEquals(120, model.snapshot().roots().get(0).children().getFirst().startNanoseconds());
		assertEquals(100, model.snapshot().roots().get(1).startNanoseconds());
	}

	@Test
	void absentScopesAreNotDrawnAndExpireAfterTwentyFrames() {
		FlameGraphModel model = new FlameGraphModel();
		model.accept(frame(1_000, scope("transient", 10, 100)));
		for (int index = 0; index < 20; index++) model.accept(frame(1_000));
		assertTrue(model.snapshot().roots().isEmpty());
		model.accept(frame(1_000, scope("transient", 30, 300)));
		assertEquals(0, model.snapshot().roots().getFirst().startNanoseconds());
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

	@Test
	void horizontalZoomKeepsTheMousePositionAnchored() {
		VibrisFlameGraph.Viewport centered = VibrisFlameGraph.zoomViewport(0.0, 1.0, 0.5, 1.0);
		assertEquals(0.1, centered.start(), 0.000_001);
		assertEquals(0.8, centered.span(), 0.000_001);
		assertEquals(new VibrisFlameGraph.Bounds(0, 500),
			VibrisFlameGraph.layoutBounds(100, 400, 1_000, 1_000, 0, 1_000, centered.start(), centered.span()));

		VibrisFlameGraph.Viewport leftEdge = VibrisFlameGraph.zoomViewport(0.0, 1.0, 0.0, 1.0);
		assertEquals(0.0, leftEdge.start());
		VibrisFlameGraph.Viewport reset = VibrisFlameGraph.zoomViewport(centered.start(), centered.span(), 0.5, -100.0);
		assertEquals(0.0, reset.start());
		assertEquals(1.0, reset.span());
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
