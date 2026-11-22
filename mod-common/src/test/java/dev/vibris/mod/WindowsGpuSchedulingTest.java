package dev.vibris.mod;

import dev.vibris.core.JobActivityObserver.Execution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static dev.vibris.mod.WindowsGpuScheduling.Mode.BASELINE;
import static dev.vibris.mod.WindowsGpuScheduling.Mode.MANAGED;
import static org.junit.jupiter.api.Assertions.*;

class WindowsGpuSchedulingTest {
	private static final Execution FIRST = new Execution("a", "request-a", "workspace", "group");
	private static final Execution SECOND = new Execution("b", "request-b", "workspace", "group");

	@Test
	void keepsQueuedJobsAtRealtimeAndIgnoresDuplicateOrStaleCallbacks() {
		FakeBackend nativeApi = new FakeBackend();
		List<Boolean> activity = new ArrayList<>();
		List<WindowsGpuScheduling.Evidence> evidence = new ArrayList<>();
		var scheduler = WindowsGpuScheduling.using(nativeApi, () -> MANAGED, activity::add, evidence::add, MANAGED);

		scheduler.queueIdle();
		scheduler.executionStarted(FIRST);
		scheduler.executionStarted(FIRST);
		scheduler.executionFinished(SECOND);
		scheduler.executionFinished(FIRST);
		scheduler.executionStarted(SECOND);
		scheduler.executionFinished(FIRST);
		scheduler.executionFinished(SECOND);
		scheduler.queueIdle();

		assertEquals(List.of(0, 5, 0), nativeApi.sets);
		assertEquals(List.of(true, false, true, false), activity);
		assertEquals(List.of("queue_idle", "job_start", "job_end", "job_start", "job_end", "queue_idle"),
			evidence.stream().map(WindowsGpuScheduling.Evidence::event).toList());
		assertEquals(SECOND, evidence.getLast().job());
		assertTrue(evidence.stream().allMatch(WindowsGpuScheduling.Evidence::applied));
	}

	@Test
	void reportsDeniedAndClampedRealtimeAndStillReturnsToIdle() {
		FakeBackend nativeApi = new FakeBackend();
		List<WindowsGpuScheduling.Evidence> evidence = new ArrayList<>();
		var scheduler = WindowsGpuScheduling.using(nativeApi, () -> MANAGED, ignored -> {}, evidence::add, MANAGED);
		scheduler.queueIdle();
		nativeApi.denyRealtime = true;
		scheduler.executionStarted(FIRST);
		scheduler.executionFinished(FIRST);
		nativeApi.denyRealtime = false;
		nativeApi.clamp = true;
		scheduler.executionStarted(SECOND);
		scheduler.executionFinished(SECOND);
		scheduler.queueIdle();

		assertFalse(evidence.get(1).applied());
		assertEquals(0xc0000061, evidence.get(1).setStatus());
		assertEquals(0, evidence.get(1).readback().priority());
		assertFalse(evidence.get(3).applied());
		assertEquals(0, evidence.get(3).setStatus());
		assertEquals(4, evidence.get(3).readback().priority());
		assertTrue(evidence.getLast().applied());
		assertEquals(0, nativeApi.priority);
	}

	@Test
	void baselineChangeWaitsForAQueueBoundary() {
		FakeBackend nativeApi = new FakeBackend();
		AtomicReference<WindowsGpuScheduling.Mode> config = new AtomicReference<>(MANAGED);
		List<WindowsGpuScheduling.Evidence> evidence = new ArrayList<>();
		var scheduler = WindowsGpuScheduling.using(nativeApi, config::get, ignored -> {}, evidence::add, MANAGED);
		scheduler.queueIdle();
		scheduler.executionStarted(FIRST);
		config.set(BASELINE);
		scheduler.executionFinished(FIRST);
		scheduler.executionStarted(SECOND);
		assertEquals(5, nativeApi.priority);
		scheduler.executionFinished(SECOND);
		scheduler.queueIdle();
		assertEquals(2, nativeApi.priority);
		scheduler.executionStarted(FIRST);
		assertEquals(BASELINE, evidence.getLast().mode());
		assertEquals(List.of(0, 5, 2), nativeApi.sets);
	}

	@Test
	void optInRequiresTheSameGameDirectory(@TempDir Path temp) throws Exception {
		Path config = temp.resolve("windows-gpu-scheduling.txt");
		Path game = Files.createDirectory(temp.resolve("target"));
		Path other = Files.createDirectory(temp.resolve("other"));
		assertNull(WindowsGpuScheduling.readConfig(game, config));
		Files.writeString(config, other + "\nmanaged\n");
		assertNull(WindowsGpuScheduling.readConfig(game, config));
		Files.writeString(config, game + "\nmanaged\n");
		assertEquals(MANAGED, WindowsGpuScheduling.readConfig(game, config));
	}

	private static final class FakeBackend implements WindowsGpuScheduling.Backend {
		final List<Integer> sets = new ArrayList<>();
		int priority = 2;
		boolean denyRealtime;
		boolean clamp;

		@Override
		public WindowsGpuScheduling.Reading read() {
			return new WindowsGpuScheduling.Reading(0, priority);
		}

		@Override
		public int set(int value) {
			sets.add(value);
			if (value == 5 && denyRealtime) return 0xc0000061;
			priority = value == 5 && clamp ? 4 : value;
			return 0;
		}
	}
}
