package dev.vibris.mod;

import dev.vibris.core.JobActivityObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runs on the Core execution worker. The local opt-in is bound to this game's directory. */
final class WindowsGpuScheduling implements JobActivityObserver {
	static final int IDLE = 0;
	static final int REALTIME = 5;
	private static final String CONFIG = "config/vibris/windows-gpu-scheduling.txt";
	private static final Logger LOGGER = LoggerFactory.getLogger("Vibris");

	enum Mode { MANAGED, BASELINE }

	interface Backend {
		Reading read();

		int set(int priority);
	}

	record Reading(int ntstatus, int priority) {
		boolean matches(int expected) { return ntstatus == 0 && priority == expected; }
	}

	record Evidence(String event, Execution job, Mode mode, int requested, Integer setStatus,
					Reading readback, Instant time, long monotonicNanos) {
		boolean applied() {
			return readback.matches(requested) && (setStatus == null || setStatus == 0);
		}
	}

	private final Backend backend;
	private final Supplier<Mode> config;
	private final Consumer<Boolean> activity;
	private final Consumer<Evidence> evidence;
	private final int original;
	private Mode mode;
	private boolean queueWasIdle = true;
	private Execution active;
	private Execution last;

	private WindowsGpuScheduling(Backend backend, Supplier<Mode> config, Consumer<Boolean> activity,
								 Consumer<Evidence> evidence, int original, Mode initialMode) {
		this.backend = backend;
		this.config = config;
		this.activity = activity;
		this.evidence = evidence;
		this.original = original;
		this.mode = initialMode;
	}

	static WindowsGpuScheduling using(Backend backend, Supplier<Mode> config, Consumer<Boolean> activity,
									 Consumer<Evidence> evidence, Mode initialMode) {
		Reading original = backend.read();
		if (original.ntstatus() != 0 || original.priority() < 0 || original.priority() > REALTIME) {
			throw new IllegalStateException("cannot read initial GPU class (NTSTATUS " + hex(original.ntstatus()) + ")");
		}
		return new WindowsGpuScheduling(backend, config, activity, evidence, original.priority(), initialMode);
	}

	static Binding bind(Path game, Consumer<Boolean> activity) {
		Path configuration = game.resolve(CONFIG);
		if (!System.getProperty("os.name", "").startsWith("Windows")) {
			return new Binding(activity::accept, null);
		}
		Mode initialMode = readConfig(game, configuration);
		if (initialMode == null) return new Binding(activity::accept, null);
		WindowsGpuSchedulingApi api = null;
		try {
			api = new WindowsGpuSchedulingApi();
			ProcessHandle process = ProcessHandle.current();
			String start = process.info().startInstant().map(Instant::toString).orElse("unavailable");
			JobActivityObserver observer = using(api, () -> readConfig(game, configuration), activity,
				event -> log(event, game, process.pid(), start), initialMode);
			return new Binding(observer, api);
		} catch (RuntimeException | LinkageError failure) {
			if (api != null) api.close();
			LOGGER.warn("Vibris GPU scheduling initialization failed for {}", game, failure);
			return new Binding(activity::accept, null);
		}
	}

	static Mode readConfig(Path game, Path configuration) {
		if (!Files.exists(configuration, LinkOption.NOFOLLOW_LINKS)) return null;
		try {
			if (!Files.isRegularFile(configuration, LinkOption.NOFOLLOW_LINKS) || Files.size(configuration) > 4096) {
				throw new IOException("expected an ordinary file containing a game directory and mode");
			}
			List<String> lines = Files.readAllLines(configuration);
			if (lines.size() != 2) throw new IOException("expected two lines");
			Path expected = Path.of(lines.getFirst());
			if (!expected.isAbsolute() || !Files.isSameFile(expected, game)) {
				throw new IOException("configured game directory does not match this process");
			}
			return switch (lines.getLast()) {
				case "managed" -> Mode.MANAGED;
				case "baseline" -> Mode.BASELINE;
				default -> throw new IOException("expected managed or baseline");
			};
		} catch (IOException | InvalidPathException failure) {
			LOGGER.warn("Vibris GPU scheduling config ignored at {}: {}", configuration, failure.getMessage());
			return null;
		}
	}

	@Override
	public void activityChanged(boolean active) {
		activity.accept(active);
	}

	@Override
	public void executionStarted(Execution job) {
		if (active != null) return;
		active = job;
		if (queueWasIdle) refreshMode();
		queueWasIdle = false;
		activityChanged(true);
		observe("job_start", job, mode == Mode.MANAGED ? REALTIME : original, true);
	}

	@Override
	public void executionFinished(Execution job) {
		if (!job.equals(active)) return;
		last = job;
		active = null;
		try {
			observe("job_end", job, mode == Mode.MANAGED ? REALTIME : original, false);
		} finally {
			activityChanged(false);
		}
	}

	@Override
	public void queueIdle() {
		if (active != null) return;
		queueWasIdle = true;
		refreshMode();
		observe("queue_idle", last, mode == Mode.MANAGED ? IDLE : original, true);
	}

	private void refreshMode() {
		Mode requested = config.get();
		if (requested != null) mode = requested;
	}

	private void observe(String event, Execution job, int expected, boolean change) {
		Reading readback = backend.read();
		Integer setStatus = null;
		if (change && !readback.matches(expected)) {
			setStatus = backend.set(expected);
			readback = backend.read();
		}
		evidence.accept(new Evidence(event, job, mode, expected, setStatus, readback, Instant.now(), System.nanoTime()));
	}

	private static void log(Evidence event, Path game, long pid, String start) {
		Execution job = event.job();
		String message = "Vibris GPU scheduling event={} pid={} process_start={} game={} job={} request={} workspace={} " +
			"group={} mode={} desired={} set_ntstatus={} get_ntstatus={} readback={} verified={} utc={} monotonic_ns={}";
		Object[] arguments = {
			event.event(), pid, start, game, job == null ? "-" : job.jobId(), job == null ? "-" : job.requestId(),
			job == null ? "-" : job.workspaceId(), job == null ? "-" : job.schedulingGroupId(), event.mode(),
			event.requested(), event.setStatus() == null ? "skipped" : hex(event.setStatus()),
			hex(event.readback().ntstatus()), event.readback().ntstatus() == 0 ? event.readback().priority() : "unknown",
			event.applied(), event.time(), event.monotonicNanos()
		};
		if (event.applied()) LOGGER.info(message, arguments);
		else LOGGER.warn(message, arguments);
	}

	private static String hex(int status) {
		return String.format(Locale.ROOT, "0x%08X", status);
	}

	record Binding(JobActivityObserver observer, WindowsGpuSchedulingApi api) implements AutoCloseable {
		@Override
		public void close() {
			if (api != null) api.close();
		}
	}
}
