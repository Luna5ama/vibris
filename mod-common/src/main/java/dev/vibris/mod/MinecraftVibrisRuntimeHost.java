package dev.vibris.mod;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.luna5ama.vibris.capture.CaptureActionExecutor;
import dev.luna5ama.vibris.capture.VibrisPresetCatalog;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.CameraMotionPlan;
import dev.vibris.api.CameraMotionResult;
import dev.vibris.api.CameraPose;
import dev.vibris.api.ArtifactSink;
import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResourceNotFoundException;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.CompileCatalog;
import dev.vibris.api.ContextApplyResult;
import dev.vibris.api.ContextValidationResult;
import dev.vibris.api.DeterministicTemporalCaptureOutcome;
import dev.vibris.api.DeterministicTemporalCapturePlanner;
import dev.vibris.api.DeterministicTemporalCapturePlanning;
import dev.vibris.api.DeterministicTemporalCaptureReloaded;
import dev.vibris.api.DeterministicTemporalCaptureRequest;
import dev.vibris.api.RuntimeAction;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.api.RuntimeEnvironment;
import dev.vibris.api.RuntimeStatus;
import dev.vibris.api.SceneContext;
import dev.vibris.api.ScenePreset;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.core.ArtifactManager;
import dev.vibris.core.DeterministicTemporalCaptureScheduler;
import dev.vibris.core.RenderedFrameClock;
import dev.vibris.mod.mixin.minecraft.TimerAccess;
import dev.vibris.core.ShaderConfigFile;
import dev.vibris.core.VibrisRuntimeHost;
import net.irisshaders.iris.BuildConfig;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.Platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MinecraftVibrisRuntimeHost implements VibrisRuntimeHost {
	private final Minecraft minecraft;
	private final MinecraftContextController contexts;
	private final VibrisPresetCatalog presets;
	private final MinecraftVibrisCapture capture;
	private final CaptureActionExecutor actions;
	private final Path shaderLink;
	private final Path shaderConfigTarget;
	private volatile Path shaderConfigScratch;
	private volatile SceneContext activeContext;
	private volatile boolean closed;
	private boolean deterministicSequence;
	private CameraMotionController cameraMotion;
	private final DeterministicWorldSimulation worldSimulation = new DeterministicWorldSimulation();
	private boolean unfreezeWorld;

	public MinecraftVibrisRuntimeHost(Path gameDirectory) throws IOException {
		minecraft = Minecraft.getInstance();
		presets = VibrisPresetCatalog.load(gameDirectory.resolve("config/vibris/presets.json"));
		contexts = new MinecraftContextController(minecraft, presets);
		capture = new MinecraftVibrisCapture(minecraft);
		actions = new CaptureActionExecutor(gameDirectory, VibrisClient.captureManager(), VibrisClient.shaderDebugControl());
		shaderLink = gameDirectory.resolve("shaderpacks/vibris/shaders");
		shaderConfigTarget = gameDirectory.resolve("shaderpacks/vibris.txt");
		shaderConfigScratch = gameDirectory.resolve("vibris/config/vibris.txt");
	}

	void configureShaderConfigScratch(Path pendingRoot) {
		shaderConfigScratch = pendingRoot.resolveSibling("config").resolve("vibris.txt");
	}

	void beforeRenderedFrame(long frameId) {
		if (cameraMotion != null) cameraMotion.beforeFrame(frameId, System.nanoTime());
	}

	void renderedFrameTail(long frameId) {
		if (cameraMotion != null) cameraMotion.afterFrame(frameId, System.nanoTime());
		contexts.renderedFrameTail(frameId);
		if (!deterministicSequence) worldSimulation.renderedFrame(minecraft, contexts, frameId);
	}

	@Override
	public boolean supportsCameraMotion() { return true; }

	@Override
	public boolean supportsCameraTranslation() { return true; }

	@Override
	public boolean supportsWorldUnfreeze() { return true; }

	@Override
	public CompletionStage<CameraMotionResult> cameraMotion(CameraMotionPlan request, List<CapturePlan> plans,
		ArtifactSink sink, RenderedFrameClock frames, CancellationToken cancellation) {
		cancellation.throwIfCancellationRequested();
		if (!deterministicSequence || cameraMotion != null || minecraft.player == null || minecraft.level == null ||
			!minecraft.options.getCameraType().isFirstPerson()) {
			throw new IllegalStateException("Camera motion requires an exclusive first-person world sequence");
		}
		if (plans.size() != request.frameCount()) throw new IllegalArgumentException("Camera frame plans are incomplete");
		var player = minecraft.player;
		var level = minecraft.level;
		double x = player.getX(), y = player.getY(), z = player.getZ();
		double previousX = player.xo, previousY = player.yo, previousZ = player.zo;
		double previousRenderX = player.xOld, previousRenderY = player.yOld, previousRenderZ = player.zOld;
		for (var key : request.hasTranslation() ? request.keyframes() : List.<CameraMotionPlan.Keyframe>of()) {
			var target = net.minecraft.core.BlockPos.containing(x+key.xDelta(), y+key.yDelta(), z+key.zDelta());
			if (!level.hasChunkAt(target) || !level.getWorldBorder().isWithinBounds(target)) {
				throw new IllegalArgumentException("Camera translation requires loaded chunks within the world border");
			}
		}
		float yaw = player.getYRot(), pitch = player.getXRot();
		float previousYaw = player.yRotO, previousPitch = player.xRotO;
		int width = minecraft.getWindow().getWidth(), height = minecraft.getWindow().getHeight();
		request.validateAnchor(yaw, pitch);
		VibrisTime.Scope time = VibrisTime.begin(frames.currentFrame());
		try {
			TemporalResetResult reset = resetTemporal(cancellation);
			if (!reset.successful()) throw new IllegalStateException("Camera temporal reset failed");
		} catch (Throwable failure) { time.close(); throw failure; }
		CameraMotionController session;
		try {
			session = new CameraMotionController(request, yaw, pitch, frames.currentFrame(),
			System.nanoTime() + TimeUnit.SECONDS.toNanos(60), cancellation, new CameraMotionController.Driver() {
			private void requirePlayer() {
				if (minecraft.player != player || minecraft.level != level) {
					throw new IllegalStateException("Camera world changed");
				}
				if (minecraft.getWindow().getWidth() != width || minecraft.getWindow().getHeight() != height) {
					throw new IllegalStateException("Camera viewport changed");
				}
			}
			@Override public void translate(double dx, double dy, double dz) {
				requirePlayer();
				if (!request.hasTranslation()) return;
				var target = net.minecraft.core.BlockPos.containing(x+dx, y+dy, z+dz);
				if (!level.hasChunkAt(target)) throw new IllegalStateException("Camera translation left loaded chunks");
				player.snapTo(x+dx, y+dy, z+dz, player.getYRot(), player.getXRot());
			}
			@Override public void rotate(double y, double p) {
				requirePlayer();
				player.setYRot((float) y); player.setXRot((float) p);
				player.yRotO = (float) y; player.xRotO = (float) p;
			}
			@Override public CameraPose actualPose() {
				requirePlayer();
				var camera = minecraft.gameRenderer.getMainCamera();
				if (!camera.isInitialized() || camera.isDetached() || camera.entity() != player) {
					throw new IllegalStateException("Camera motion requires the local first-person camera");
				}
				var position = camera.position();
				return new CameraPose(position.x, position.y, position.z, camera.yRot(), camera.xRot());
			}
			@Override public float shaderTimeSeconds() {
				return ((TimerAccess) (Object) SystemTimeUniforms.TIMER).vibris$getFrameTimeCounter();
			}
			@Override public CaptureResult capture(int offset, long frameId) {
				return MinecraftVibrisRuntimeHost.this.capture(plans.get(offset), sink, frameId, cancellation);
			}
			@Override public void restore() {
				try {
					if (minecraft.player != player || minecraft.level != level) {
						throw new IllegalStateException("Camera world changed during restoration");
					}
					if (request.hasTranslation()) {
						player.snapTo(x, y, z, yaw, pitch);
						player.xo = previousX; player.yo = previousY; player.zo = previousZ;
						player.xOld = previousRenderX; player.yOld = previousRenderY; player.zOld = previousRenderZ;
					}
					player.setYRot(yaw); player.setXRot(pitch); player.yRotO = previousYaw; player.xRotO = previousPitch;
				} finally { time.close(); }
			}
			});
		} catch (Throwable failure) { time.close(); throw failure; }
		cameraMotion = session;
		session.result().whenComplete((ignored, failure) -> {
            if (cameraMotion == session) cameraMotion = null;
            if (failure != null) VibrisClient.LOGGER.warn("Vibris camera motion terminated with an error", failure);
        });
		pollCameraMotion(session);
		return session.result().minimalCompletionStage();
	}

	private void pollCameraMotion(CameraMotionController session) {
		if (session.result().isDone()) return;
		CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS).execute(() -> minecraft.execute(() -> {
			if (!session.result().isDone()) { session.check(System.nanoTime()); pollCameraMotion(session); }
		}));
	}

	@Override
	public List<ScenePreset> presets() {
		return presets.presets();
	}

	@Override
	public ContextValidationResult validateContext(SceneContext context) {
		return presets.validate(context);
	}

	String savePreset(String id) throws IOException {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.level == null || minecraft.player == null) {
			throw new IllegalStateException("A singleplayer world must be loaded");
		}
		String weather = minecraft.level.getThunderLevel(1.0f) > 0.0f ? "thunder" :
			minecraft.level.getRainLevel(1.0f) > 0.0f ? "rain" : "clear";
		String dimension = minecraft.level.dimension().identifier().toString();
		String save = MinecraftContextController.runningSave(server);
		return presets.save(new VibrisPresetCatalog.Preset(
			id,
			save,
			save,
			dimension,
			minecraft.player.getX(),
			minecraft.player.getY(),
			minecraft.player.getZ(),
			minecraft.player.getYRot(),
			minecraft.player.getXRot(),
			minecraft.options.fov().get(),
			minecraft.level.getDayTime(),
			weather,
			new SceneContext.Resolution(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight()),
			"default"
		));
	}

	@Override
	public boolean isClientThread() {
		return minecraft.isSameThread();
	}

	@Override
	public void executeOnClient(Runnable task) {
		minecraft.execute(task);
		GLFW.glfwPostEmptyEvent();
	}

	@Override
	public RuntimeEnvironment runtimeEnvironment() {
		if (!isClientThread()) {
			throw new IllegalStateException("Runtime environment must be queried on the Minecraft client thread");
		}

		GpuDevice device = RenderSystem.getDevice();
		String glVersion = device.getVersion();
		return runtimeEnvironment(
			SharedConstants.getCurrentVersion().name(),
			Iris.getVersion(),
			VibrisPlatform.getInstance().modVersion(),
			Runtime.version().toString(),
			Platform.get().getName() + " " + Platform.getArchitecture(),
			device.getVendor(),
			device.getRenderer(),
			glVersion,
			glVersion
		);
	}

	static RuntimeEnvironment runtimeEnvironment(
		String minecraftVersion,
		String irisVersion,
		String vibrisVersion,
		String javaVersion,
		String operatingSystem,
		String gpuVendor,
		String gpuRenderer,
		String openglVersion,
		String driverVersion
	) {
		return new RuntimeEnvironment(
			minecraftVersion,
			irisVersion,
			vibrisVersion,
			javaVersion,
			operatingSystem,
			gpuVendor,
			gpuRenderer,
			openglVersion,
			driverVersion
		);
	}

	@Override
	public RuntimeStatus status() {
		SceneContext context = activeContext;
		return new RuntimeStatus(
			!closed,
			context == null ? "" : context.saveId(),
			minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString(),
			"");
	}

	@Override
	public CompletionStage<String> executeAction(RuntimeAction action) {
		return actions.execute(action);
	}

	@Override
	public CompletionStage<ContextApplyResult> applyContext(SceneContext context, CancellationToken cancellation) {
		CompletableFuture<ContextApplyResult> result = new CompletableFuture<>();
		MinecraftContextController.ContextOperation operation = contexts.begin(context, cancellation);
		operation.preparation().whenComplete((prepared, failure) -> runOnClient(result, () -> {
			if (failure != null || !prepared.successful()) {
				contexts.release(operation);
				if (failure != null) result.completeExceptionally(unwrap(failure));
				else result.complete(prepared);
				return;
			}
			try {
				cancellation.throwIfCancellationRequested();
				contexts.awaitSceneReady(operation).whenComplete((ready, readyFailure) -> runOnClient(result, () -> {
					try {
						contexts.release(operation);
						if (readyFailure != null) result.completeExceptionally(unwrap(readyFailure));
						else {
							if (ready.successful()) {
								worldSimulation.sceneReady(minecraft, !unfreezeWorld);
								activeContext = ready.context();
								IrisVibrisAutomation.contextApplied(ready.context(), minecraft);
							}
							result.complete(ready);
						}
					} catch (Throwable caught) {
						result.completeExceptionally(caught);
					}
				}, () -> contexts.release(operation)));
			} catch (Throwable caught) {
				contexts.release(operation);
				result.completeExceptionally(caught);
			}
		}, () -> contexts.release(operation)));
		return result;
	}

	@Override
	public void beginDeterministicSequence(CancellationToken cancellation) {
		beginWorldSequence(cancellation, false);
	}

	@Override
	public void beginWorldSequence(CancellationToken cancellation, boolean unfreezeWorld) {
		cancellation.throwIfCancellationRequested();
		if (deterministicSequence) {
			throw new IllegalStateException("A deterministic runtime sequence is already active");
		}
		if (unfreezeWorld) worldSimulation.apply(minecraft, false);
		else worldSimulation.applyIfSettled(minecraft, true);
		this.unfreezeWorld = unfreezeWorld;
		deterministicSequence = true;
	}

	@Override
	public void endDeterministicSequence(CancellationToken cancellation) {
		cancellation.throwIfCancellationRequested();
		Throwable failure = cameraMotion == null ? null : closeCaptureScope(cameraMotion);
		try {
			if (unfreezeWorld) worldSimulation.applyIfSettled(minecraft, true);
			unfreezeWorld = false;
			deterministicSequence = false;
		} catch (Throwable cleanup) {
			if (failure != null) cleanup.addSuppressed(failure);
			if (cleanup instanceof RuntimeException runtime) throw runtime;
			if (cleanup instanceof Error error) throw error;
			throw new IllegalStateException("Failed to restore world simulation", cleanup);
		}
		if (failure instanceof RuntimeException runtime) throw runtime;
		if (failure instanceof Error error) throw error;
		if (failure != null) throw new IllegalStateException("Failed to restore camera motion", failure);
	}

	@Override
	public ReloadResult reload(Map<String, String> config, CancellationToken cancellation) {
		cancellation.throwIfCancellationRequested();
		minecraft.setScreen(null);
		minecraft.options.hideGui = true;
		if (config != null) {
			writeShaderConfig(config);
		}
		ReloadResult result = IrisShaderpackBridge.reload(config);
		IrisVibrisAutomation.shaderReloaded(
			result.successful(),
			shaderLink,
			Iris.getPipelineManager().getPipelineNullable(),
			IrisVibrisLifecycle.currentFrame());
		return result;
	}

	private void writeShaderConfig(Map<String, String> config) {
		ShaderConfigFile.write(shaderConfigTarget, shaderConfigScratch, config);
	}

	@Override
	public CompileCatalog compileCatalog(CancellationToken cancellation) {
		cancellation.throwIfCancellationRequested();
		return IrisVibrisCompileCatalog.current();
	}

	@Override
	public TemporalResetResult resetTemporal(CancellationToken cancellation) {
		cancellation.throwIfCancellationRequested();
		SystemTimeUniforms.COUNTER.reset();
		SystemTimeUniforms.TIMER.reset();
		CapturedRenderingState.INSTANCE.resetTextureReloadCount();
		if (!(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline)) {
			throw new IllegalStateException("The active Iris rendering pipeline is unavailable");
		}
		((VibrisPipeline) pipeline).vibris$resetTemporalState();
		IrisVibrisAutomation.temporalReset();
		return new TemporalResetResult(true);
	}

	@Override
	public CompletionStage<DeterministicTemporalCaptureOutcome> captureDeterministicTemporalPhase(
		DeterministicTemporalCaptureRequest request,
		DeterministicTemporalCapturePlanner planner,
		ArtifactSink sink,
		DeterministicTemporalCaptureScheduler scheduler,
		CancellationToken cancellation
	) {
		CompletableFuture<DeterministicTemporalCaptureOutcome> result = new CompletableFuture<>();
		MinecraftContextController.ContextOperation contextOperation;
		try {
			boolean retainedContext = deterministicSequence && request.context().equals(activeContext);
			contextOperation = retainedContext ?
				contexts.beginCurrent(request.context(), cancellation) :
				contexts.begin(request.context(), cancellation);
		} catch (Throwable failure) {
			result.complete(contextRejected(request, failure));
			return result;
		}

		contextOperation.preparation().whenComplete((context, contextFailure) -> runOnClient(result, () -> {
			if (result.isDone()) {
				contexts.release(contextOperation);
				return;
			}
			if (contextFailure != null) {
				contexts.release(contextOperation);
				result.complete(contextRejected(request, unwrap(contextFailure)));
				return;
			}
			if (!context.successful()) {
				contexts.release(contextOperation);
				result.complete(new DeterministicTemporalCaptureOutcome.ContextRejected(
					context,
					failure(DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED, context.message())
				));
				return;
			}
			primeDeterministicTemporalPhase(
				request, planner, sink, scheduler, cancellation, contextOperation, context, result);
		}, () -> contexts.release(contextOperation)));
		return result;
	}

	private void primeDeterministicTemporalPhase(
		DeterministicTemporalCaptureRequest request,
		DeterministicTemporalCapturePlanner planner,
		ArtifactSink sink,
		DeterministicTemporalCaptureScheduler scheduler,
		CancellationToken cancellation,
		MinecraftContextController.ContextOperation contextOperation,
		ContextApplyResult context,
		CompletableFuture<DeterministicTemporalCaptureOutcome> result
	) {
		if (result.isDone()) {
			contexts.release(contextOperation);
			return;
		}
		try {
			if (!deterministicSequence) {
				throw new IllegalStateException("A deterministic runtime sequence is required");
			}
			cancellation.throwIfCancellationRequested();
		} catch (Throwable failure) {
			contexts.release(contextOperation);
			result.complete(contextRejected(request, unwrap(failure)));
			return;
		}

		ReloadResult primeReload;
		try {
			primeReload = reload(request.preserveCurrentSettings() ? null : request.settings(), cancellation);
		} catch (Throwable failure) {
			contexts.release(contextOperation);
			Throwable cause = unwrap(failure);
			ReloadResult rejected = ReloadResult.failure(List.of(new ReloadResult.Diagnostic(
				ReloadResult.Severity.ERROR, "shaderpack", 0, failureMessage(cause))));
			result.complete(new DeterministicTemporalCaptureOutcome.ReloadRejected(
				context, rejected, failure(cause, false)));
			return;
		}
		if (!primeReload.successful()) {
			contexts.release(contextOperation);
			String message = primeReload.diagnostics().stream()
				.map(ReloadResult.Diagnostic::message)
				.filter(candidate -> candidate != null && !candidate.isBlank())
				.findFirst()
				.orElse("Shader reload was rejected");
			result.complete(new DeterministicTemporalCaptureOutcome.ReloadRejected(
				context,
				primeReload,
				failure(DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED, message)
			));
			return;
		}

		CompletionStage<ContextApplyResult> sceneReady;
		try {
			sceneReady = contexts.awaitSceneReady(contextOperation);
		} catch (Throwable failure) {
			contexts.release(contextOperation);
			result.complete(contextRejected(request, unwrap(failure)));
			return;
		}
		sceneReady.whenComplete((readyContext, sceneFailure) -> runOnClient(result, () -> {
			if (result.isDone()) {
				contexts.release(contextOperation);
				return;
			}
			if (sceneFailure != null) {
				contexts.release(contextOperation);
				result.complete(contextRejected(request, unwrap(sceneFailure)));
				return;
			}
			if (!readyContext.successful()) {
				contexts.release(contextOperation);
				result.complete(new DeterministicTemporalCaptureOutcome.ContextRejected(
					readyContext,
					failure(DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED, readyContext.message())
				));
				return;
			}
			finishDeterministicTemporalReload(
				request, planner, sink, scheduler, cancellation, contextOperation, readyContext, primeReload, result);
		}, () -> contexts.release(contextOperation)));
	}

	private void finishDeterministicTemporalReload(
		DeterministicTemporalCaptureRequest request,
		DeterministicTemporalCapturePlanner planner,
		ArtifactSink sink,
		DeterministicTemporalCaptureScheduler scheduler,
		CancellationToken cancellation,
		MinecraftContextController.ContextOperation contextOperation,
		ContextApplyResult context,
		ReloadResult primeReload,
		CompletableFuture<DeterministicTemporalCaptureOutcome> result
	) {
		ReloadResult reload;
		try {
			cancellation.throwIfCancellationRequested();
			reload = reload(request.preserveCurrentSettings() ? null : request.settings(), cancellation);
			cancellation.throwIfCancellationRequested();
		} catch (Throwable failure) {
			contexts.release(contextOperation);
			Throwable cause = unwrap(failure);
			ReloadResult rejected = ReloadResult.failure(List.of(new ReloadResult.Diagnostic(
				ReloadResult.Severity.ERROR, "shaderpack", 0, failureMessage(cause))));
			result.complete(new DeterministicTemporalCaptureOutcome.ReloadRejected(
				context, rejected, failure(cause, false)));
			return;
		}
		if (!reload.successful()) {
			contexts.release(contextOperation);
			String message = reload.diagnostics().stream()
				.map(ReloadResult.Diagnostic::message)
				.filter(candidate -> candidate != null && !candidate.isBlank())
				.findFirst()
				.orElse("Final shader reload was rejected");
			result.complete(new DeterministicTemporalCaptureOutcome.ReloadRejected(
				context,
				reload,
				failure(DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED, message)
			));
			return;
		}
		if (!primeReload.effectiveSettings().hasSameResolvedState(reload.effectiveSettings())) {
			contexts.release(contextOperation);
			ReloadResult rejected = ReloadResult.failure(List.of(new ReloadResult.Diagnostic(
				ReloadResult.Severity.ERROR,
				"shaderpack",
				0,
				"Effective shader settings changed between the prime and final reloads")));
			result.complete(new DeterministicTemporalCaptureOutcome.ReloadRejected(
				context,
				rejected,
				failure(
					DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED,
					"Effective shader settings changed between the prime and final reloads")
			));
			return;
		}

		contexts.release(contextOperation);
		activeContext = context.context();
		awaitFinalReloadSceneReady(
			request, planner, sink, scheduler, cancellation, context, reload, result);
	}

	private void awaitFinalReloadSceneReady(
		DeterministicTemporalCaptureRequest request,
		DeterministicTemporalCapturePlanner planner,
		ArtifactSink sink,
		DeterministicTemporalCaptureScheduler scheduler,
		CancellationToken cancellation,
		ContextApplyResult context,
		ReloadResult reload,
		CompletableFuture<DeterministicTemporalCaptureOutcome> result
	) {
		MinecraftContextController.ContextOperation finalSceneOperation;
		try {
			finalSceneOperation = contexts.beginCurrent(context.context(), cancellation);
		} catch (Throwable failure) {
			result.complete(contextRejected(request, unwrap(failure)));
			return;
		}

		finalSceneOperation.preparation().whenComplete((preparedContext, preparationFailure) ->
			runOnClient(result, () -> {
				if (result.isDone()) {
					contexts.release(finalSceneOperation);
					return;
				}
				if (preparationFailure != null) {
					contexts.release(finalSceneOperation);
					result.complete(contextRejected(request, unwrap(preparationFailure)));
					return;
				}
				if (!preparedContext.successful()) {
					contexts.release(finalSceneOperation);
					result.complete(new DeterministicTemporalCaptureOutcome.ContextRejected(
						preparedContext,
						failure(
							DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED,
							preparedContext.message())
					));
					return;
				}

				CompletionStage<ContextApplyResult> finalSceneReady;
				try {
					finalSceneReady = contexts.awaitSceneReady(finalSceneOperation);
				} catch (Throwable failure) {
					contexts.release(finalSceneOperation);
					result.complete(contextRejected(request, unwrap(failure)));
					return;
				}
				finalSceneReady.whenComplete((readyContext, sceneFailure) ->
					runOnClient(result, () -> {
						contexts.release(finalSceneOperation);
						if (result.isDone()) {
							return;
						}
						if (sceneFailure != null) {
							result.complete(contextRejected(request, unwrap(sceneFailure)));
							return;
						}
						if (!readyContext.successful()) {
							result.complete(new DeterministicTemporalCaptureOutcome.ContextRejected(
								readyContext,
								failure(
									DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED,
									readyContext.message())
							));
							return;
						}
						worldSimulation.sceneReady(minecraft, !unfreezeWorld);
						IrisVibrisAutomation.contextApplied(readyContext.context(), minecraft);
						startDeterministicTemporalPhaseAfterReload(
							request, planner, sink, scheduler, cancellation, readyContext, reload, result);
					}, () -> {
						contexts.release(finalSceneOperation);
					}));
			}, () -> {
				contexts.release(finalSceneOperation);
			}));
	}

	private void startDeterministicTemporalPhaseAfterReload(
		DeterministicTemporalCaptureRequest request,
		DeterministicTemporalCapturePlanner planner,
		ArtifactSink sink,
		DeterministicTemporalCaptureScheduler scheduler,
		CancellationToken cancellation,
		ContextApplyResult context,
		ReloadResult reload,
		CompletableFuture<DeterministicTemporalCaptureOutcome> result
	) {

		long reloadCompletedAtUnixMs = currentUnixMs();
		ResourceCatalog resources;
		CompileCatalog compile;
		try {
			cancellation.throwIfCancellationRequested();
			resources = resourceCatalog(scheduler.currentFrame());
			compile = compileCatalog(cancellation);
		} catch (Throwable failure) {
			result.completeExceptionally(unwrap(failure));
			return;
		}
		DeterministicTemporalCaptureReloaded reloaded = new DeterministicTemporalCaptureReloaded(
			context, reload, reloadCompletedAtUnixMs, resources, compile);
		DeterministicTemporalCapturePlanning planning;
		try {
			cancellation.throwIfCancellationRequested();
			planning = planner.plan(resources, compile);
		} catch (Throwable failure) {
			result.complete(new DeterministicTemporalCaptureOutcome.PlanningRejected(
				reloaded, failure(unwrap(failure), false)));
			return;
		}
		if (planning instanceof DeterministicTemporalCapturePlanning.Rejected rejected) {
			result.complete(new DeterministicTemporalCaptureOutcome.PlanningRejected(
				reloaded, rejected.failure()));
			return;
		}
		CapturePlan plan = ((DeterministicTemporalCapturePlanning.Planned) planning).plan();

		VibrisTime.Scope timeScope = null;
		TemporalResetResult reset;
		long originFrame = scheduler.currentFrame();
		try {
			timeScope = VibrisTime.begin(originFrame);
			reset = resetTemporal(cancellation);
		} catch (Throwable failure) {
			Throwable cleanupFailure = timeScope == null ? null : closeCaptureScope(timeScope);
			completeResetRejected(result, reloaded, plan, unwrap(failure), cleanupFailure);
			return;
		}
		if (!reset.successful()) {
			Throwable cleanupFailure = timeScope == null ? null : closeCaptureScope(timeScope);
			completeResetRejected(
				result,
				reloaded,
				plan,
				new IllegalStateException("Temporal reset was rejected"),
				cleanupFailure);
			return;
		}
		DeterministicParticleAnimation.Scope particleScope;
		try {
			DeterministicTextureAnimation.resetAll(minecraft);
			particleScope = DeterministicParticleAnimation.begin(minecraft);
		} catch (Throwable failure) {
			Throwable cleanupFailure = closeCaptureScope(timeScope);
			completeResetRejected(result, reloaded, plan, unwrap(failure), cleanupFailure);
			return;
		}
		CaptureScopes captureScopes = new CaptureScopes(particleScope, timeScope);
		long resetCompletedAtUnixMs = currentUnixMs();

		long anchorFrame;
		long warmupEndFrame;
		long captureFrame;
		try {
			anchorFrame = scheduler.currentFrame();
			warmupEndFrame = Math.addExact(anchorFrame, request.warmupFrames());
			captureFrame = Math.incrementExact(warmupEndFrame);
		} catch (Throwable failure) {
			Throwable cause = unwrap(failure);
			Throwable cleanupFailure = closeCaptureScope(captureScopes);
			if (cleanupFailure != null) cause.addSuppressed(cleanupFailure);
			result.completeExceptionally(cause);
			return;
		}

		DeterministicTemporalCaptureScheduler.ScheduledCapture scheduled;
		try {
			scheduled = scheduler.schedule(
				request.warmupFrames(),
				cancellation,
				frameId -> capture(plan, sink, frameId, cancellation));
		} catch (Throwable failure) {
			Throwable cleanupFailure = closeCaptureScope(captureScopes);
			completeRejectedBeforeSchedule(
				result, reloaded, plan, reset, resetCompletedAtUnixMs, request.warmupFrames(),
				anchorFrame, warmupEndFrame, captureFrame, unwrap(failure), cleanupFailure);
			return;
		}

		scheduled.capture().whenComplete((captured, operationFailure) -> {
			AtomicBoolean finishStarted = new AtomicBoolean();
			Throwable capturedFailure = operationFailure == null ? null : unwrap(operationFailure);
			Runnable finish = () -> completeScheduledCaptureOnce(
				finishStarted, result, reloaded, plan, reset, resetCompletedAtUnixMs, scheduled, captureScopes,
				captured, capturedFailure);
			try {
				if (isClientThread()) finish.run();
				else executeOnClient(finish);
			} catch (Throwable submissionFailure) {
				Throwable fallbackFailure = capturedFailure == null ? unwrap(submissionFailure) : capturedFailure;
				Runnable fallback = () -> completeScheduledCaptureOnce(
					finishStarted, result, reloaded, plan, reset, resetCompletedAtUnixMs, scheduled, captureScopes,
					captured, fallbackFailure);
				if (isClientThread()) fallback.run();
				else minecraft.execute(fallback);
			}
		});
	}

	private static void completeScheduledCaptureOnce(
		AtomicBoolean finishStarted,
		CompletableFuture<DeterministicTemporalCaptureOutcome> result,
		DeterministicTemporalCaptureReloaded reloaded,
		CapturePlan plan,
		TemporalResetResult reset,
		long resetCompletedAtUnixMs,
		DeterministicTemporalCaptureScheduler.ScheduledCapture scheduled,
		AutoCloseable captureScope,
		CaptureResult captured,
		Throwable operationFailure
	) {
		if (!finishStarted.compareAndSet(false, true)) {
			return;
		}
		completeScheduledCapture(
			result, reloaded, plan, reset, resetCompletedAtUnixMs, scheduled, captureScope,
			captured, operationFailure);
	}

	static void completeScheduledCapture(
		CompletableFuture<DeterministicTemporalCaptureOutcome> result,
		DeterministicTemporalCaptureReloaded reloaded,
		CapturePlan plan,
		TemporalResetResult reset,
		long resetCompletedAtUnixMs,
		DeterministicTemporalCaptureScheduler.ScheduledCapture scheduled,
		AutoCloseable captureScope,
		CaptureResult captured,
		Throwable operationFailure
	) {
		long terminalFrame;
		try {
			terminalFrame = scheduled.terminalFrame().toCompletableFuture().join();
		} catch (Throwable terminalFailure) {
			Throwable cause = unwrap(terminalFailure);
			Throwable cleanupFailure = closeCaptureScope(captureScope);
			if (cleanupFailure != null) cause.addSuppressed(cleanupFailure);
			result.completeExceptionally(cause);
			return;
		}
		Throwable cleanupFailure = closeCaptureScope(captureScope);
		if (operationFailure != null || cleanupFailure != null) {
			completeRejected(
				result, reloaded, plan, reset, resetCompletedAtUnixMs, scheduled.warmupFrames(),
				scheduled.anchorFrame(), scheduled.warmupEndFrame(), scheduled.captureFrame(), terminalFrame,
				operationFailure, cleanupFailure);
			return;
		}

		try {
			result.complete(new DeterministicTemporalCaptureOutcome.Captured(
				reloaded,
				plan,
				reset,
				resetCompletedAtUnixMs,
				scheduled.warmupFrames(),
				scheduled.anchorFrame(),
				scheduled.warmupEndFrame(),
				captured
			));
		} catch (Throwable invalidCapture) {
			completeRejected(
				result, reloaded, plan, reset, resetCompletedAtUnixMs, scheduled.warmupFrames(),
				scheduled.anchorFrame(), scheduled.warmupEndFrame(), scheduled.captureFrame(), terminalFrame,
				new InvalidDeterministicCaptureException(failureMessage(invalidCapture), invalidCapture), null);
		}
	}

	private static void completeRejectedBeforeSchedule(
		CompletableFuture<DeterministicTemporalCaptureOutcome> result,
		DeterministicTemporalCaptureReloaded reloaded,
		CapturePlan plan,
		TemporalResetResult reset,
		long resetCompletedAtUnixMs,
		int warmupFrames,
		long anchorFrame,
		long warmupEndFrame,
		long captureFrame,
		Throwable operationFailure,
		Throwable cleanupFailure
	) {
		completeRejected(
			result, reloaded, plan, reset, resetCompletedAtUnixMs, warmupFrames,
			anchorFrame, warmupEndFrame, captureFrame, anchorFrame, operationFailure, cleanupFailure);
	}

	static void completeResetRejected(
		CompletableFuture<DeterministicTemporalCaptureOutcome> result,
		DeterministicTemporalCaptureReloaded reloaded,
		CapturePlan plan,
		Throwable operationFailure,
		Throwable cleanupFailure
	) {
		if (cleanupFailure != null) operationFailure.addSuppressed(cleanupFailure);
		result.complete(new DeterministicTemporalCaptureOutcome.ResetRejected(
			reloaded,
			plan,
			new TemporalResetResult(false),
			failure(operationFailure, false)
		));
	}

	private static void completeRejected(
		CompletableFuture<DeterministicTemporalCaptureOutcome> result,
		DeterministicTemporalCaptureReloaded reloaded,
		CapturePlan plan,
		TemporalResetResult reset,
		long resetCompletedAtUnixMs,
		int warmupFrames,
		long anchorFrame,
		long warmupEndFrame,
		long captureFrame,
		long terminalFrame,
		Throwable operationFailure,
		Throwable cleanupFailure
	) {
		Throwable primary = operationFailure == null ? cleanupFailure : operationFailure;
		if (operationFailure != null && cleanupFailure != null) operationFailure.addSuppressed(cleanupFailure);
		DeterministicTemporalCaptureOutcome.Failure detail = failure(primary, operationFailure == null);
		if (warmupFrames > 0 && terminalFrame < warmupEndFrame) {
			result.complete(new DeterministicTemporalCaptureOutcome.WarmupRejected(
				reloaded,
				plan,
				reset,
				resetCompletedAtUnixMs,
				warmupFrames,
				anchorFrame,
				Math.toIntExact(terminalFrame - anchorFrame),
				terminalFrame,
				detail
			));
			return;
		}
		result.complete(new DeterministicTemporalCaptureOutcome.CaptureRejected(
			reloaded,
			plan,
			reset,
			resetCompletedAtUnixMs,
			warmupFrames,
			anchorFrame,
			warmupEndFrame,
			captureFrame,
			terminalFrame,
			detail
		));
	}

	private static DeterministicTemporalCaptureOutcome.ContextRejected contextRejected(
		DeterministicTemporalCaptureRequest request,
		Throwable failure
	) {
		Throwable cause = unwrap(failure);
		ContextApplyResult context = ContextApplyResult.failure(request.context(), failureMessage(cause));
		return new DeterministicTemporalCaptureOutcome.ContextRejected(context, failure(cause, false));
	}

	private void runOnClient(CompletableFuture<?> result, Runnable task) {
		runOnClient(result, task, () -> {
		});
	}

	private void runOnClient(CompletableFuture<?> result, Runnable task, Runnable submissionFailureCleanup) {
		try {
			if (isClientThread()) task.run();
			else executeOnClient(task);
		} catch (Throwable failure) {
			Throwable cause = unwrap(failure);
			try {
				submissionFailureCleanup.run();
			} catch (Throwable cleanupFailure) {
				cause.addSuppressed(unwrap(cleanupFailure));
			}
			result.completeExceptionally(cause);
		}
	}

	private static Throwable closeCaptureScope(AutoCloseable captureScope) {
		try {
			captureScope.close();
			return null;
		} catch (Throwable failure) {
			return unwrap(failure);
		}
	}


	private static DeterministicTemporalCaptureOutcome.Failure failure(
		Throwable failure,
		boolean cleanupOnly
	) {
		DeterministicTemporalCaptureOutcome.FailureKind kind = cleanupOnly ?
			DeterministicTemporalCaptureOutcome.FailureKind.CLEANUP_FAILED : failureKind(failure);
		return failure(kind, failureMessage(failure));
	}

	private static DeterministicTemporalCaptureOutcome.FailureKind failureKind(Throwable failure) {
		Throwable cause = failure;
		while (cause != null) {
			if (cause instanceof CancellationException) {
				return DeterministicTemporalCaptureOutcome.FailureKind.CANCELLED;
			}
			if (cause instanceof RenderedFrameClock.TargetMissedException ||
				cause instanceof RenderedFrameClock.AnchorMismatchException) {
				return DeterministicTemporalCaptureOutcome.FailureKind.MISSED_TARGET;
			}
			if (cause instanceof CaptureResourceNotFoundException) {
				return DeterministicTemporalCaptureOutcome.FailureKind.RESOURCE_NOT_FOUND;
			}
			if (cause instanceof ArtifactManager.JobTooLargeException || cause instanceof ArithmeticException) {
				return DeterministicTemporalCaptureOutcome.FailureKind.ARTIFACT_TOO_LARGE;
			}
			if (cause instanceof ArtifactManager.QuotaExceededException) {
				return DeterministicTemporalCaptureOutcome.FailureKind.ARTIFACT_QUOTA_EXCEEDED;
			}
			if (cause instanceof InvalidDeterministicCaptureException) {
				return DeterministicTemporalCaptureOutcome.FailureKind.INVALID_CAPTURE;
			}
			if (cause == cause.getCause()) break;
			cause = cause.getCause();
		}
		return DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED;
	}

	private record CaptureScopes(
		DeterministicParticleAnimation.Scope particles,
		VibrisTime.Scope time
	) implements AutoCloseable {
		@Override
		public void close() throws Exception {
			Throwable failure = null;
			try {
				particles.close();
			} catch (Throwable particleFailure) {
				failure = particleFailure;
			}
			try {
				time.close();
			} catch (Throwable timeFailure) {
				if (failure == null) failure = timeFailure;
				else failure.addSuppressed(timeFailure);
			}
			if (failure instanceof Exception exception) throw exception;
			if (failure instanceof Error error) throw error;
		}
	}

	private static DeterministicTemporalCaptureOutcome.Failure failure(
		DeterministicTemporalCaptureOutcome.FailureKind kind,
		String message
	) {
		String detail = message == null || message.isBlank() ? "Vibris operation failed" : message;
		return new DeterministicTemporalCaptureOutcome.Failure(kind, detail);
	}

	private static String failureMessage(Throwable failure) {
		StringBuilder message = new StringBuilder();
		String primary = failure.getMessage();
		message.append(primary == null || primary.isBlank() ? failure.getClass().getSimpleName() : primary);
		for (Throwable suppressed : failure.getSuppressed()) {
			message.append("; suppressed: ").append(
				suppressed.getMessage() == null ? suppressed.getClass().getSimpleName() : suppressed.getMessage());
		}
		return message.toString();
	}

	private static Throwable unwrap(Throwable failure) {
		Throwable current = failure;
		while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
		return current;
	}

	private static long currentUnixMs() {
		return Math.max(1L, System.currentTimeMillis());
	}

	private static final class InvalidDeterministicCaptureException extends IllegalStateException {
		private InvalidDeterministicCaptureException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	@Override
	public ResourceCatalog resourceCatalog(long frameId) {
		try {
			return capture.resourceCatalog(frameId);
		} catch (IllegalStateException unavailable) {
			throw unavailable;
		} catch (Throwable failure) {
			VibrisClient.LOGGER.error("Failed to build the Vibris runtime resource catalog.", failure);
			throw failure;
		}
	}

	@Override
	public CaptureResult capture(
		CapturePlan plan,
		ArtifactSink sink,
		long frameId,
		CancellationToken cancellation
	) {
		CaptureResult result = capture.capture(plan, sink, frameId, cancellation);
		IrisVibrisAutomation.captureComplete(plan, result.frameId());
		return result;
	}

	@Override
	public CompletionStage<CapturePlan.AfterPassReceipt> captureAfterPass(
		CapturePlan.AfterPassRequest request,
		ArtifactSink sink,
		CancellationToken cancellation
	) {
		return capture.captureAfterPass(request, sink, cancellation);
	}

	@Override
	public CompletionStage<dev.vibris.api.TextureAnalysisResult> analyzeTexture(
		dev.vibris.api.TextureAnalysisRequest request, ArtifactSink sink, CancellationToken cancellation) {
		return capture.analyzeTexture(request, sink, cancellation);
	}

	@Override
	public CompletionStage<CaptureResult> capturePatchedShaders(
		String artifactName,
		ArtifactSink sink,
		long frameId,
		CancellationToken cancellation
	) {
		return actions.capturePatchedShaders(artifactName, sink, frameId, cancellation);
	}

	@Override
	public void close() {
		if (cameraMotion != null) cameraMotion.close();
		closed = true;
		presets.close();
		contexts.close();
		if ("vibris".equals(Iris.getCurrentPackName()) &&
			Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline) {
			Iris.getPipelineManager().destroyPipeline();
		}
		activeContext = null;
	}
}
