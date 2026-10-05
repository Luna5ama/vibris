package dev.vibris.mod;

import dev.luna5ama.vibris.capture.GlArtifactCapture;
import dev.luna5ama.vibris.capture.TextureStatistics;
import dev.luna5ama.vibris.capture.GpuTextureAccumulator;
import dev.luna5ama.vibris.capture.TextureAccumulationOutput;
import dev.vibris.api.ArtifactSink;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.TextureAnalysisRequest;
import dev.vibris.api.TextureAnalysisResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.IntSupplier;

/** All sampling and GPU resource lifetime operations run on the pipeline's render thread. */
final class TextureAnalysisJobs implements AutoCloseable {
	private final List<Pending> pending = new ArrayList<>();
	private boolean closed;

	synchronized CompletionStage<TextureAnalysisResult> schedule(TextureAnalysisRequest request,
		ArtifactSink sink, CancellationToken cancellation, IntSupplier texture) {
		if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Pipeline is closed"));
		Pending work = new Pending(request, sink, cancellation, texture);
		pending.add(work);
		return work.result;
	}

	synchronized void boundary(String passId, long frameId) {
		for (var iterator = pending.iterator(); iterator.hasNext();) {
			Pending work = iterator.next();
			if (work.result.isDone() || work.cancellation.isCancellationRequested()) {
				if (work.accumulator != null) work.accumulator.close();
				work.result.completeExceptionally(new CancellationException("Texture analysis cancelled"));
				iterator.remove();
				continue;
			}
			if (!Objects.equals(passId, work.request.passId())) continue;
			try {
				var selector = work.request.resource();
				if (work.request.accumulation() != null) {
					if (work.accumulator == null) work.accumulator = new GpuTextureAccumulator(
						work.request.accumulation(), selector.region(), work.texture.getAsInt(), selector.mipLevel());
					if (!work.accumulator.sample(work.texture.getAsInt(), frameId)) continue;
					var snapshot = TextureAccumulationOutput.snapshot(work.request, work.accumulator);
					work.accumulator.close();
					work.accumulator = null;
					completeOffThread(work, () -> TextureAccumulationOutput.write(work.request, snapshot,
						Objects.requireNonNull(work.sink), work.cancellation));
				} else {
					try (var readback = GlArtifactCapture.readTexture(work.texture.getAsInt(), selector.mipLevel(), selector.region())) {
						int channels = readback.getMetadata().getChannelCount();
						var values = readback.values(selector.layer());
						completeOffThread(work, () -> new TextureAnalysisResult(frameId,
							TextureStatistics.compute(channels, values), "{}", null));
					}
				}
			} catch (Throwable failure) {
				work.result.completeExceptionally(failure);
			}
			iterator.remove();
			if (work.accumulator != null) work.accumulator.close();
		}
	}

	private static void completeOffThread(Pending work, java.util.function.Supplier<TextureAnalysisResult> operation) {
		Thread thread = new Thread(() -> {
			try {
				work.cancellation.throwIfCancellationRequested();
				work.result.complete(operation.get());
			} catch (Throwable failure) {
				work.result.completeExceptionally(failure);
			}
		}, "vibris-texture-analysis-output");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}

	@Override public synchronized void close() {
		closed = true;
		pending.forEach(work -> {
			if (work.accumulator != null) work.accumulator.close();
			work.result.completeExceptionally(new CancellationException("Pipeline destroyed"));
		});
		pending.clear();
	}

	private static final class Pending {
		final TextureAnalysisRequest request;
		final ArtifactSink sink;
		final CancellationToken cancellation;
		final IntSupplier texture;
		final CompletableFuture<TextureAnalysisResult> result = new CompletableFuture<>();
		GpuTextureAccumulator accumulator;
		Pending(TextureAnalysisRequest request, ArtifactSink sink, CancellationToken cancellation, IntSupplier texture) {
			this.request = request; this.sink = sink; this.cancellation = cancellation; this.texture = texture;
		}
	}
}
