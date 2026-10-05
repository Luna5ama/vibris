package dev.vibris.mod;

import com.google.common.collect.ImmutableSet;
import dev.luna5ama.vibris.capture.GlArtifactCapture;
import dev.luna5ama.vibris.capture.GlCaptureMetadata;
import dev.luna5ama.vibris.capture.StorageBufferInfo;
import dev.luna5ama.vibris.capture.TextureInfo;
import dev.vibris.api.ArtifactSink;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.mod.IrisShaderDebugHost;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Pipeline-owned registry and one-shot executor for exact named render-pass captures. */
public final class IrisVibrisPassCapture implements AutoCloseable {
	private static final int CAPTURE_BARRIER_BITS = GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT |
		GL42C.GL_BUFFER_UPDATE_BARRIER_BIT | GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT |
		GL42C.GL_TEXTURE_FETCH_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT |
		GL42C.GL_FRAMEBUFFER_BARRIER_BIT;
	private static final long MAX_CAPTURE_BYTES = Integer.MAX_VALUE;

	private final Backend backend;
	private final TextureAnalysisJobs analyses = new TextureAnalysisJobs();
	private final List<PassHandle> passes = new ArrayList<>();
	private final Map<String, List<PendingCapture>> pendingByPass = new HashMap<>();
	private final Map<String, Integer> occurrences = new HashMap<>();
	private ImmutableSet<Integer> latestMainFlips = ImmutableSet.of();
	private ImmutableSet<Integer> latestShadowFlips = ImmutableSet.of();
	private boolean closed;

	public IrisVibrisPassCapture() {
		this(new GlBackend());
	}

	IrisVibrisPassCapture(Backend backend) {
		this.backend = backend;
	}

	public synchronized PassHandle register(ResourceCatalog.PassStage stage, String programId) {
		return register(stage, programId, List.of());
	}

	public synchronized PassHandle register(ResourceCatalog.PassStage stage, String programId,
		List<ResourceCatalog.PassProgram> programs) {
		ensureOpen();
		PassHandle handle = new PassHandle(stage, programId, passes.size(), programs);
		if (passes.stream().anyMatch(existing -> existing.passId().equals(handle.passId()))) {
			throw new IllegalStateException("Duplicate Vibris pass identifier: " + handle.passId());
		}
		passes.add(handle);
		return handle;
	}

	public synchronized void setMainFlips(ImmutableSet<Integer> flips) {
		ensureOpen();
		latestMainFlips = flips;
	}

	public synchronized void setShadowFlips(ImmutableSet<Integer> flips) {
		ensureOpen();
		latestShadowFlips = flips;
	}

	public ResourceCatalog resourceCatalog(long frameId) {
		List<ResourceCatalog.ResourceDescriptor> resources = backend.resources(frameId);
		return resourceCatalog(resources);
	}

	ResourceCatalog resourceCatalog(Collection<ResourceCatalog.ResourceDescriptor> resources) {
		Set<String> logicalNames = new HashSet<>();
		for (ResourceCatalog.ResourceDescriptor resource : resources) {
			if (!logicalNames.add(resource.logicalName())) {
				throw new IllegalStateException("Duplicate Vibris resource logical name: " + resource.logicalName());
			}
		}
		List<String> readable = resources.stream()
			.filter(resource -> resource.kind() == ResourceCatalog.ResourceKind.TEXTURE ||
				resource.kind() == ResourceCatalog.ResourceKind.BUFFER)
			.map(ResourceCatalog.ResourceDescriptor::logicalName)
			.sorted()
			.toList();
		List<ResourceCatalog.PassDescriptor> descriptors;
		synchronized (this) {
			descriptors = passes.stream()
				.map(pass -> ResourceCatalog.PassDescriptor.of(pass.stage(), pass.programId(), pass.order(), readable, pass.programs))
				.toList();
		}
		return ResourceCatalog.of(resources, descriptors, IrisVibrisCompileCatalog.current().shaderGeneration());
	}

	public CompletionStage<CapturePlan.AfterPassReceipt> schedule(
		CapturePlan.AfterPassRequest request,
		ArtifactSink sink,
		CancellationToken cancellation,
		ResourceCatalog catalog
	) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(sink, "sink");
		Objects.requireNonNull(cancellation, "cancellation");
		Objects.requireNonNull(catalog, "catalog");
		try {
			cancellation.throwIfCancellationRequested();
			validateRequest(request, catalog);
		} catch (Throwable throwable) {
			return CompletableFuture.failedFuture(throwable);
		}

		PendingFuture future = new PendingFuture(this);
		PendingCapture pending = new PendingCapture(request, sink, cancellation, future);
		future.pending = pending;
		synchronized (this) {
			if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Pass capture registry is closed"));
			pendingByPass.computeIfAbsent(request.pass().passId(), ignored -> new ArrayList<>()).add(pending);
		}
		return future;
	}

	public void captureBoundary(PassHandle pass, ImmutableSet<Integer> flipsAfterPass) {
		List<PendingCapture> captures;
		int occurrence;
		synchronized (this) {
			if (closed) return;
			verifyHandle(pass);
			if (pass.stage() == ResourceCatalog.PassStage.SHADOW_COMPOSITE) {
				latestShadowFlips = flipsAfterPass;
			} else {
				latestMainFlips = flipsAfterPass;
			}
			occurrence = occurrences.merge(pass.passId(), 1, Integer::sum);
			captures = pendingByPass.remove(pass.passId());
		}
		analyses.boundary(pass.passId(), IrisVibrisLifecycle.currentFrame() + 1);
		if (captures == null || captures.isEmpty()) return;

		List<PendingCapture> active = new ArrayList<>();
		for (PendingCapture pending : captures) {
			if (pending.future.isDone() || pending.cancellation.isCancellationRequested()) {
				if (!pending.future.isDone()) {
					pending.future.completeExceptionally(new CancellationException("Vibris operation was cancelled"));
				}
			} else {
				active.add(pending);
			}
		}
		if (active.isEmpty()) return;

		backend.prepareBoundary();
		long frameId = IrisVibrisLifecycle.currentFrame() + 1;
		for (PendingCapture pending : active) {
			try {
				OwnedSnapshot snapshot = backend.snapshot(
					pending.request.target().resource(), latestMainFlips, latestShadowFlips);
				CompletableFuture.supplyAsync(() -> {
					try (snapshot) {
						pending.cancellation.throwIfCancellationRequested();
						CaptureResult result = snapshot.write(
							pending.request.target(), pending.sink, frameId, pending.cancellation);
						return new CapturePlan.AfterPassReceipt(
							pending.request, occurrence, snapshot.physicalName(), result);
					}
				}).whenComplete((receipt, failure) -> {
					if (failure == null) pending.future.complete(receipt);
					else pending.future.completeExceptionally(unwrap(failure));
				});
			} catch (Throwable throwable) {
				pending.future.completeExceptionally(throwable);
			}
		}
	}

	public Integer resolveCurrent(CapturePlan.Target target) {
		synchronized (this) {
			ensureOpen();
			return backend.resolve(target.resource(), latestMainFlips, latestShadowFlips).glId();
		}
	}

	public CompletionStage<dev.vibris.api.TextureAnalysisResult> analyzeTexture(
		dev.vibris.api.TextureAnalysisRequest request, ArtifactSink sink,
		CancellationToken cancellation, java.util.function.IntSupplier texture) {
		return analyses.schedule(request, sink, cancellation, texture);
	}

	public void endFrame() {
		analyses.boundary(null, IrisVibrisLifecycle.currentFrame() + 1);
	}

	private static void validateRequest(CapturePlan.AfterPassRequest request, ResourceCatalog catalog) {
		if (!catalog.mappingSha256().equals(request.mappingSha256())) {
			throw new IllegalArgumentException("Pass/resource mapping changed");
		}
		ResourceCatalog.PassDescriptor pass = catalog.passes().stream()
			.filter(candidate -> candidate.passId().equals(request.pass().passId()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unknown render pass: " + request.pass().passId()));
		if (!pass.equals(request.pass())) {
			throw new IllegalArgumentException("Render pass descriptor does not match the active pipeline");
		}
		CapturePlan.ResourceSelector selector = request.target().resource();
		ResourceCatalog.ResourceDescriptor resource = catalog.resources().stream()
			.filter(candidate -> candidate.logicalName().equals(selector.logicalName()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unknown capture resource: " + selector.logicalName()));
		if (resource.kind() != selector.kind()) {
			throw new IllegalArgumentException("Capture resource kind does not match the active pipeline");
		}
		if (selector.kind() == ResourceCatalog.ResourceKind.TEXTURE) {
			if (!resource.availableViews().contains(selector.textureView())) {
				throw new IllegalArgumentException("Texture view is unavailable: " + selector.textureView());
			}
			if (selector.mipLevel() >= resource.mipLevels() || selector.layer() >= resource.layers()) {
				throw new IllegalArgumentException("Texture subresource is out of range");
			}
		}
	}

	private synchronized void remove(PendingCapture pending) {
		List<PendingCapture> captures = pendingByPass.get(pending.request.pass().passId());
		if (captures == null) return;
		captures.remove(pending);
		if (captures.isEmpty()) pendingByPass.remove(pending.request.pass().passId());
	}

	private void verifyHandle(PassHandle pass) {
		if (pass.order() < 0 || pass.order() >= passes.size() || passes.get(pass.order()) != pass) {
			throw new IllegalArgumentException("Pass handle does not belong to this pipeline");
		}
	}

	private void ensureOpen() {
		if (closed) throw new IllegalStateException("Pass capture registry is closed");
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		analyses.close();
		CancellationException cancellation = new CancellationException("Iris pipeline was destroyed");
		pendingByPass.values().stream().flatMap(Collection::stream)
			.forEach(pending -> pending.future.completeExceptionally(cancellation));
		pendingByPass.clear();
	}

	private static Throwable unwrap(Throwable failure) {
		return failure.getCause() == null ? failure : failure.getCause();
	}

	public static final class PassHandle {
		private final ResourceCatalog.PassStage stage;
		private final String programId;
		private final int order;
		private final String passId;
		private final List<ResourceCatalog.PassProgram> programs;

		public PassHandle(ResourceCatalog.PassStage stage, String programId, int order) {
			this(stage, programId, order, List.of());
		}

		public PassHandle(ResourceCatalog.PassStage stage, String programId, int order,
			List<ResourceCatalog.PassProgram> programs) {
			this.programs = List.copyOf(programs);
			this.stage = Objects.requireNonNull(stage, "stage");
			this.programId = Objects.requireNonNull(programId, "programId");
			this.order = order;
			this.passId = stage.getId() + "/" + programId;
		}

		public ResourceCatalog.PassStage stage() {
			return stage;
		}

		public String programId() {
			return programId;
		}

		public int order() {
			return order;
		}

		public String passId() {
			return passId;
		}
	}

	interface Backend {
		default void prepareBoundary() {
		}

		List<ResourceCatalog.ResourceDescriptor> resources(long frameId);

		OwnedSnapshot snapshot(
			CapturePlan.ResourceSelector selector,
			Set<Integer> mainFlips,
			Set<Integer> shadowFlips
		);

		ResolvedResource resolve(
			CapturePlan.ResourceSelector selector,
			Set<Integer> mainFlips,
			Set<Integer> shadowFlips
		);
	}

	interface OwnedSnapshot extends AutoCloseable {
		String physicalName();

		CaptureResult write(
			CapturePlan.Target target,
			ArtifactSink sink,
			long frameId,
			CancellationToken cancellation
		);

		@Override
		void close();
	}

	record ResolvedResource(String physicalName, int glId) {
	}

	private record PendingCapture(
		CapturePlan.AfterPassRequest request,
		ArtifactSink sink,
		CancellationToken cancellation,
		PendingFuture future
	) {
	}

	private static final class PendingFuture extends CompletableFuture<CapturePlan.AfterPassReceipt> {
		private final IrisVibrisPassCapture owner;
		private PendingCapture pending;

		private PendingFuture(IrisVibrisPassCapture owner) {
			this.owner = owner;
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			boolean cancelled = super.cancel(mayInterruptIfRunning);
			if (cancelled && pending != null) owner.remove(pending);
			return cancelled;
		}

		@Override
		public boolean completeExceptionally(Throwable exception) {
			boolean completed = super.completeExceptionally(exception);
			if (completed && pending != null) owner.remove(pending);
			return completed;
		}
	}

	private static final class GlBackend implements Backend {
		private final IrisShaderDebugHost shaderDebug = new IrisShaderDebugHost();

		@Override
		public void prepareBoundary() {
			GL42C.glMemoryBarrier(CAPTURE_BARRIER_BITS);
		}

		@Override
		public List<ResourceCatalog.ResourceDescriptor> resources(long frameId) {
			Map<String, TextureInfo> textures = new LinkedHashMap<>();
			Map<String, EnumSet<ResourceCatalog.TextureView>> views = new LinkedHashMap<>();
			for (TextureInfo texture : shaderDebug.textureCatalog().getTextures()) {
				String name = texture.getName();
				if (name.endsWith(".alt")) continue;
				boolean paired = name.endsWith(".main");
				String logicalName = paired ? name.substring(0, name.length() - ".main".length()) : name;
				textures.put(logicalName, texture);
				views.put(logicalName, paired
					? EnumSet.allOf(ResourceCatalog.TextureView.class)
					: EnumSet.of(ResourceCatalog.TextureView.CURRENT, ResourceCatalog.TextureView.MAIN));
			}

			List<ResourceCatalog.ResourceDescriptor> resources = new ArrayList<>();
			int beauty = ((GpuTextureInterface) net.minecraft.client.Minecraft.getInstance().getMainRenderTarget()
				.getColorTexture()).iris$getGlId();
			ResourceCatalog.ResourceDescriptor beautyDescriptor = textureDescriptor(
				"beauty", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, List.of(), beauty, frameId, "screenshot");
			if (beautyDescriptor != null) resources.add(beautyDescriptor);
			textures.forEach((name, texture) -> {
				ResourceCatalog.ResourceDescriptor descriptor = textureDescriptor(
					name, ResourceCatalog.ResourceKind.TEXTURE, views.get(name), texture.getTextureId(), frameId,
					texture.getCategory());
				if (descriptor != null) resources.add(descriptor);
			});
			for (StorageBufferInfo buffer : shaderDebug.storageBuffers()) {
				resources.add(ResourceCatalog.ResourceDescriptor.of(
					buffer.getName(), ResourceCatalog.ResourceKind.BUFFER, List.of(),
					0, 0, 0, 0, 0, "binary", 0, ResourceCatalog.ScalarType.UINT8,
					buffer.getSizeBytes(), frameId, buffer.getName(), buffer.getCategory(),
					"", "", "", 0, "", ""));
			}
			return resources;
		}

		@Override
		public OwnedSnapshot snapshot(
			CapturePlan.ResourceSelector selector,
			Set<Integer> mainFlips,
			Set<Integer> shadowFlips
		) {
			ResolvedResource resource = resolve(selector, mainFlips, shadowFlips);
			if (selector.kind() == ResourceCatalog.ResourceKind.TEXTURE) {
				return new TextureSnapshot(resource.physicalName(),
					GlArtifactCapture.readTexture(resource.glId(), selector.mipLevel(), selector.region()));
			}
			if (selector.kind() == ResourceCatalog.ResourceKind.BUFFER) {
				GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
				long size = GL45C.glGetNamedBufferParameteri64(resource.glId(), GL15C.GL_BUFFER_SIZE);
				if (size < 0 || size > MAX_CAPTURE_BYTES) {
					throw new IllegalArgumentException("Buffer capture is too large: " + size + " bytes");
				}
				ByteBuffer direct = MemoryUtil.memAlloc((int) size);
				try {
					GL45C.glGetNamedBufferSubData(resource.glId(), 0, direct);
					byte[] bytes = new byte[(int) size];
					direct.get(bytes);
					return new BufferSnapshot(resource.physicalName(), bytes);
				} finally {
					MemoryUtil.memFree(direct);
				}
			}
			throw new IllegalArgumentException("After-pass capture supports only textures and buffers");
		}

		@Override
		public ResolvedResource resolve(
			CapturePlan.ResourceSelector selector,
			Set<Integer> mainFlips,
			Set<Integer> shadowFlips
		) {
			if (selector.kind() == ResourceCatalog.ResourceKind.BUFFER) {
				StorageBufferInfo buffer = shaderDebug.storageBuffers().stream()
					.filter(candidate -> candidate.getName().equals(selector.logicalName()))
					.findFirst()
					.orElseThrow(() -> new IllegalArgumentException(
						"Unknown shader storage buffer: " + selector.logicalName()));
				return new ResolvedResource(buffer.getName(), buffer.getGlId());
			}
			if (selector.kind() != ResourceCatalog.ResourceKind.TEXTURE) {
				throw new IllegalArgumentException("Resource is not a texture or buffer: " + selector.logicalName());
			}

			Integer main = shaderDebug.resolveTexture(selector.logicalName() + ".main");
			Integer alt = shaderDebug.resolveTexture(selector.logicalName() + ".alt");
			if (main == null || alt == null) {
				if (selector.textureView() != ResourceCatalog.TextureView.CURRENT &&
					selector.textureView() != ResourceCatalog.TextureView.MAIN) {
					throw new IllegalArgumentException("Texture has no alternate physical view: " + selector.logicalName());
				}
				Integer texture = shaderDebug.resolveTexture(selector.logicalName());
				if (texture == null) throw new IllegalArgumentException("Unknown texture: " + selector.logicalName());
				return new ResolvedResource(selector.logicalName(), texture);
			}

			boolean currentIsAlt = currentIsAlt(selector.logicalName(), mainFlips, shadowFlips);
			boolean selectAlt = selectAlt(selector.textureView(), currentIsAlt);
			return new ResolvedResource(selector.logicalName() + (selectAlt ? ".alt" : ".main"),
				selectAlt ? alt : main);
		}

		private static ResourceCatalog.ResourceDescriptor textureDescriptor(
			String name,
			ResourceCatalog.ResourceKind kind,
			Collection<ResourceCatalog.TextureView> views,
			int textureId,
			long frameId,
			String category
		) {
			GlCaptureMetadata metadata = GlArtifactCapture.describeTextureOrNull(textureId, 0);
			if (metadata == null) return null;
			return descriptor(name, kind, views, metadata, frameId, category);
		}
	}

	static boolean currentIsAlt(
		String logicalName,
		Set<Integer> mainFlips,
		Set<Integer> shadowFlips
	) {
		if (logicalName.startsWith("colortex")) {
			return mainFlips.contains(parseIndex(logicalName, "colortex"));
		}
		if (logicalName.startsWith("shadowcolor")) {
			return shadowFlips.contains(parseIndex(logicalName, "shadowcolor"));
		}
		throw new IllegalArgumentException("Texture is not a paired render target: " + logicalName);
	}

	static boolean selectAlt(ResourceCatalog.TextureView view, boolean currentIsAlt) {
		return switch (view) {
			case CURRENT -> currentIsAlt;
			case ALTERNATE -> !currentIsAlt;
			case MAIN -> false;
			case ALT -> true;
		};
	}

	private static int parseIndex(String value, String prefix) {
		try {
			return Integer.parseInt(value.substring(prefix.length()));
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Invalid render target name: " + value, exception);
		}
	}

	private abstract static class AbstractSnapshot implements OwnedSnapshot {
		private final String physicalName;

		private AbstractSnapshot(String physicalName) {
			this.physicalName = physicalName;
		}

		@Override
		public String physicalName() {
			return physicalName;
		}

		@Override
		public CaptureResult write(
			CapturePlan.Target target,
			ArtifactSink sink,
			long frameId,
			CancellationToken cancellation
		) {
			List<CaptureResult.CapturedArtifact> artifacts = new ArrayList<>();
			for (CapturePlan.ArtifactOutputSpec output : target.outputs()) {
				if (output.role() == CapturePlan.ArtifactRole.METADATA) continue;
				cancellation.throwIfCancellationRequested();
				IrisVibrisPassCapture.write(sink, output.fileName(),
					stream -> writePayload(target, output, stream));
				artifacts.add(captured(output));
			}
			ResourceCatalog.ResourceDescriptor resource = descriptor(
				target.resource().logicalName(), target.resource().kind(),
				target.resource().textureView() == null ? List.of() : List.of(target.resource().textureView()),
				metadata(), frameId, category(target.resource().logicalName(), target.resource().kind()));
			for (CapturePlan.ArtifactOutputSpec output : target.outputs()) {
				if (output.role() != CapturePlan.ArtifactRole.METADATA) continue;
				cancellation.throwIfCancellationRequested();
				IrisVibrisPassCapture.write(sink, output.fileName(), stream -> writeMetadata(stream, resource,
					target.outputs().stream().anyMatch(spec -> spec.format() == CapturePlan.ArtifactFormat.PNG)));
				artifacts.add(captured(output));
			}
			return new CaptureResult(frameId, List.of(
				new CaptureResult.ArtifactGroup(target.artifactName(), resource, artifacts)));
		}

		abstract GlCaptureMetadata metadata();

		abstract void writePayload(
			CapturePlan.Target target,
			CapturePlan.ArtifactOutputSpec output,
			OutputStream stream
		) throws IOException;
	}

	private static final class TextureSnapshot extends AbstractSnapshot {
		private final GlArtifactCapture.TextureReadback readback;

		private TextureSnapshot(String physicalName, GlArtifactCapture.TextureReadback readback) {
			super(physicalName);
			this.readback = readback;
		}

		@Override
		GlCaptureMetadata metadata() {
			return readback.getMetadata();
		}

		@Override
		void writePayload(CapturePlan.Target target, CapturePlan.ArtifactOutputSpec output, OutputStream stream) {
			switch (output.format()) {
				case BIN -> readback.writeBin(stream);
				case PNG -> readback.writePng(
					output.subresourceIndex() == null ? target.resource().layer() : output.subresourceIndex(), stream);
				default -> throw new IllegalArgumentException("Unsupported texture format: " + output.format());
			}
		}

		@Override
		public void close() {
			readback.close();
		}
	}

	private static final class BufferSnapshot extends AbstractSnapshot {
		private final byte[] bytes;
		private final GlCaptureMetadata metadata;

		private BufferSnapshot(String physicalName, byte[] bytes) {
			super(physicalName);
			this.bytes = bytes;
			this.metadata = new GlCaptureMetadata(
				0, 0, 0, "binary", 0, ResourceCatalog.ScalarType.UINT8, bytes.length,
				"", "", "", 0, "", "", 0);
		}

		@Override
		GlCaptureMetadata metadata() {
			return metadata;
		}

		@Override
		void writePayload(CapturePlan.Target target, CapturePlan.ArtifactOutputSpec output, OutputStream stream)
			throws IOException {
			if (output.format() != CapturePlan.ArtifactFormat.BIN) {
				throw new IllegalArgumentException("Buffer captures require BIN output");
			}
			stream.write(bytes);
		}

		@Override
		public void close() {
		}
	}

	private static ResourceCatalog.ResourceDescriptor descriptor(
		String name,
		ResourceCatalog.ResourceKind kind,
		Collection<ResourceCatalog.TextureView> views,
		GlCaptureMetadata metadata,
		long frameId,
		String category
	) {
		return ResourceCatalog.ResourceDescriptor.of(
			name, kind, views, metadata.getWidth(), metadata.getHeight(), metadata.getDepth(),
			metadata.getMipLevels(), kind == ResourceCatalog.ResourceKind.TEXTURE ? metadata.getDepth() : 0,
			metadata.getInternalFormat(), metadata.getChannelCount(), metadata.getScalarType(),
			metadata.getByteSize(), frameId, name, category, metadata.getTextureTarget(),
			metadata.getChannelLayout(), metadata.getNumericClass(), metadata.getComponentBits(),
			metadata.getReadbackFormat(), metadata.getReadbackType(), metadata.getRegion(),
			metadata.getSourceWidth(), metadata.getSourceHeight());
	}

	private static CaptureResult.CapturedArtifact captured(CapturePlan.ArtifactOutputSpec output) {
		return new CaptureResult.CapturedArtifact(
			output.fileName(), output.format(), output.role(), output.subresourceIndex());
	}

	private static String category(String name, ResourceCatalog.ResourceKind kind) {
		if (kind == ResourceCatalog.ResourceKind.BUFFER) return "iris_ssbo";
		if (name.startsWith("colortex")) return "colortex";
		if (name.startsWith("depthtex")) return "depthtex";
		if (name.startsWith("shadowtex")) return "shadowtex";
		if (name.startsWith("shadowcolor")) return "shadowcolor";
		if (name.equals("noisetex")) return "noise_texture";
		if (name.startsWith("custom_texture.")) return "custom_texture";
		if (name.startsWith("iris_custom_texture.")) return "iris_custom_texture";
		if (name.startsWith("iris_custom_image.")) return "iris_custom_image";
		if (name.startsWith("gbuffers_terrain.")) return "terrain_atlas";
		return "texture";
	}

	private static void write(ArtifactSink sink, String fileName, IoConsumer action) {
		try (OutputStream output = sink.open(fileName)) {
			action.accept(output);
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	private static void writeMetadata(
		OutputStream output,
		ResourceCatalog.ResourceDescriptor resource,
		boolean yFlipped
	) throws IOException {
		String json = "{\"logical_name\":\"" + escape(resource.logicalName()) + "\"" +
			(resource.region() == null ? "" : ",\"region\":{\"x\":" + resource.region().x() +
				",\"y\":" + resource.region().y() + ",\"width\":" + resource.region().width() +
				",\"height\":" + resource.region().height() + "}") +
			",\"source_width\":" + resource.sourceWidth() + ",\"source_height\":" + resource.sourceHeight() +
			",\"kind\":\"" + resource.kind() + "\"" +
			",\"width\":" + resource.width() +
			",\"height\":" + resource.height() +
			",\"depth\":" + resource.depth() +
			",\"internal_format\":\"" + escape(resource.internalFormat()) + "\"" +
			",\"channel_count\":" + resource.channelCount() +
			",\"scalar_type\":\"" + resource.scalarType() + "\"" +
			",\"category\":\"" + escape(resource.category()) + "\"" +
			",\"target\":\"" + escape(resource.textureTarget()) + "\"" +
			",\"mip_levels\":" + resource.mipLevels() +
			",\"channel_layout\":\"" + escape(resource.channelLayout()) + "\"" +
			",\"numeric_class\":\"" + escape(resource.numericClass()) + "\"" +
			",\"component_bits\":" + resource.componentBits() +
			",\"readback_format\":\"" + escape(resource.readbackFormat()) + "\"" +
			",\"readback_type\":\"" + escape(resource.readbackType()) + "\"" +
			",\"endianness\":\"native\"" +
			",\"packing\":{\"alignment\":1,\"row_length\":0,\"image_height\":0,\"skip_pixels\":0,\"skip_rows\":0,\"skip_images\":0,\"swap_bytes\":false}" +
			",\"axis_order\":\"X,Y,Z\"" +
			",\"y_flipped\":" + yFlipped +
			",\"byte_size\":" + resource.byteSize() +
			",\"frame_id\":" + resource.frameId() + "}";
		output.write(json.getBytes(StandardCharsets.UTF_8));
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	@FunctionalInterface
	private interface IoConsumer {
		void accept(OutputStream output) throws IOException;
	}
}
