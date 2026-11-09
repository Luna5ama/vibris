package dev.vibris.mod;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisVibrisLifecycleTest {
	private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(15);

	@Test
	void doesNotThrottleBeforeIdleTimeout() {
		long idleSince = 1_000L;
		long beforeTimeout = idleSince + TIMEOUT - 1L;

		assertFalse(IrisVibrisLifecycle.shouldThrottleIdle(false, true, true, idleSince, beforeTimeout));
	}

	@Test
	void throttlesUnfocusedWindowAfterIdleTimeout() {
		long idleSince = 1_000L;
		long timeoutElapsed = idleSince + TIMEOUT;

		assertTrue(IrisVibrisLifecycle.shouldThrottleIdle(false, true, true, idleSince, timeoutElapsed));
	}

	@Test
	void focusOnlyBypassesThrottleAfterTimeout() {
		long idleSince = 1_000L;
		long timeoutElapsed = idleSince + TIMEOUT;

		assertFalse(IrisVibrisLifecycle.shouldThrottleIdle(true, true, true, idleSince, timeoutElapsed));
		assertTrue(IrisVibrisLifecycle.shouldThrottleIdle(false, true, true, idleSince, timeoutElapsed));
	}

	@Test
	void activeRuntimeNeverThrottles() {
		assertFalse(IrisVibrisLifecycle.shouldThrottleIdle(false, true, false, Long.MAX_VALUE, Long.MAX_VALUE));
	}
}