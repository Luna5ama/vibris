package dev.vibris.mod;

import dev.luna5ama.vibris.capture.GpuFrameTiming;
import dev.luna5ama.vibris.capture.GpuScopeTiming;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FlameGraphModel {
	private final SampleSeries frameTotals = new SampleSeries();
	private final Map<String, ScopeState> states = new HashMap<>();
	private Snapshot snapshot;
	private boolean paused;

	boolean isPaused() { return paused; }
	void setPaused(boolean paused) { this.paused = paused; }

	void clear() {
		paused = false;
		frameTotals.clear();
		states.clear();
		snapshot = null;
	}

	void accept(GpuFrameTiming frame) {
		if (paused) return;
		frameTotals.add(frame.getTotalNanoseconds());
		Set<String> present = new HashSet<>();
		List<RawNode> roots = collect(frame.getScopes(), "", 0L, present);
		states.entrySet().removeIf(entry -> {
			if (present.contains(entry.getKey())) return false;
			ScopeState state = entry.getValue();
			state.start.add(0L);
			state.duration.add(0L);
			return ++state.absentFrames >= WINDOW;
		});
		List<Node> smoothed = new ArrayList<>(roots.size());
		long shaderTotal = 0L;
		for (RawNode root : roots) {
			Node node = smooth(root, shaderTotal);
			smoothed.add(node);
			shaderTotal = saturatedAdd(shaderTotal, node.durationNanoseconds());
		}
		snapshot = new Snapshot(frameTotals.average(), shaderTotal, List.copyOf(smoothed));
	}

	Snapshot snapshot() {
		return snapshot;
	}

	private List<RawNode> collect(List<GpuScopeTiming> scopes, String parent, long parentStart, Set<String> present) {
		Map<String, Integer> occurrences = new LinkedHashMap<>();
		List<RawNode> result = new ArrayList<>(scopes.size());
		for (GpuScopeTiming scope : scopes) {
			int occurrence = occurrences.merge(scope.getName(), 1, Integer::sum) - 1;
			String key = parent + "/" + scope.getName() + "#" + occurrence;
			present.add(key);
			ScopeState state = states.computeIfAbsent(key, ignored -> new ScopeState());
			state.absentFrames = 0;
			state.start.add(parent.isEmpty() ? 0L : Math.max(0L, scope.getStartNanoseconds() - parentStart));
			state.duration.add(scope.getDurationNanoseconds());
			result.add(new RawNode(key, scope.getName(),
				collect(scope.getChildren(), key, scope.getStartNanoseconds(), present)));
		}
		return result;
	}

	private Node smooth(RawNode node, long parentStart) {
		ScopeState state = states.get(node.key);
		long start = saturatedAdd(parentStart, state.start.average());
		return new Node(node.key, node.name, start, state.duration.average(),
			node.children.stream().map(child -> smooth(child, start)).toList());
	}

	private static long saturatedAdd(long left, long right) {
		if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
		return left + right;
	}

	private static final class SampleSeries {
		private double average;
		private int frameCount = 1;

		void add(long value) {
			average += (value - average) * (1.0 / Math.min(frameCount, MAX_EMA_FRAMES));
			if (frameCount < MAX_EMA_FRAMES) frameCount++;
		}

		long average() {
			return Math.round(average);
		}

		void clear() {
			average = 0.0;
			frameCount = 1;
		}
	}

	private static final class ScopeState {
		final SampleSeries start = new SampleSeries();
		final SampleSeries duration = new SampleSeries();
		int absentFrames;
	}

	private record RawNode(String key, String name, List<RawNode> children) {}
	record Node(String key, String name, long startNanoseconds, long durationNanoseconds, List<Node> children) {}
	record Snapshot(long frameNanoseconds, long shaderNanoseconds, List<Node> roots) {}

	private static final int WINDOW = 20;
	private static final int MAX_EMA_FRAMES = 64;
}
