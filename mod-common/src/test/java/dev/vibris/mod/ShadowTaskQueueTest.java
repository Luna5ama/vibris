package dev.vibris.mod;

import net.irisshaders.iris.compat.sodium.ShadowTaskQueue;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ShadowTaskQueueTest {
	@Test void mirrorsOnlyActiveTasksWithoutDroppingRetainedWork() {
		Object regular = new Object(), shadow = new Object(), stale = new Object();
		var destination = new ArrayDeque<>(List.of(regular));
		var source = new ArrayDeque<>(List.of(regular, shadow, shadow, stale));
		ShadowTaskQueue.appendMissing(destination, source, task -> task != stale);
		ShadowTaskQueue.appendMissing(destination, source, task -> task != stale);
		assertEquals(List.of(regular, shadow), List.copyOf(destination));
		assertEquals(4, source.size());
		// A replaced regular queue must rediscover unsubmitted shadow work.
		destination.clear();
		ShadowTaskQueue.appendMissing(destination, source, task -> task == shadow);
		assertEquals(1, destination.size());
		assertSame(shadow, destination.getFirst());
	}
}
