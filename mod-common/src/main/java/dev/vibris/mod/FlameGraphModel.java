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

	void clear() {
		frameTotals.clear();
		states.clear();
		snapshot = null;
	}

	void accept(GpuFrameTiming frame) {
		frameTotals.add(frame.getTotalNanoseconds());
		Set<String> present = new HashSet<>();
		List<RawNode> roots = collect(frame.getScopes(), "", present);
		states.entrySet().removeIf(entry -> {
			if (present.contains(entry.getKey())) return false;
			ScopeState state = entry.getValue();
			state.start.add(0L);
			state.duration.add(0L);
			return ++state.absentFrames >= WINDOW;
		});
		snapshot = new Snapshot(frameTotals.average(), smooth(roots));
	}

	Snapshot snapshot() {
		return snapshot;
	}

	private List<RawNode> collect(List<GpuScopeTiming> scopes, String parent, Set<String> present) {
		Map<String, Integer> occurrences = new LinkedHashMap<>();
		List<RawNode> result = new ArrayList<>(scopes.size());
		for (GpuScopeTiming scope : scopes) {
			int occurrence = occurrences.merge(scope.getName(), 1, Integer::sum) - 1;
			String key = parent + "/" + scope.getName() + "#" + occurrence;
			present.add(key);
			ScopeState state = states.computeIfAbsent(key, ignored -> new ScopeState());
			state.absentFrames = 0;
			state.start.add(scope.getStartNanoseconds());
			state.duration.add(scope.getDurationNanoseconds());
			result.add(new RawNode(key, scope.getName(), collect(scope.getChildren(), key, present)));
		}
		return result;
	}

	private List<Node> smooth(List<RawNode> nodes) {
		return nodes.stream().map(node -> {
			ScopeState state = states.get(node.key);
			return new Node(node.key, node.name, state.start.average(), state.duration.average(), smooth(node.children));
		}).toList();
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
	record Snapshot(long totalNanoseconds, List<Node> roots) {}

	private static final int WINDOW = 20;
	private static final int MAX_EMA_FRAMES = 64;
}
