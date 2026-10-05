package dev.vibris.mod;

import dev.luna5ama.vibris.capture.GlCapturePlanExecutor;
import dev.vibris.api.ArtifactSink;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.ResourceCatalog;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletionStage;

final class MinecraftVibrisCapture {
	private final Minecraft minecraft;

	MinecraftVibrisCapture(Minecraft minecraft) {
		this.minecraft = minecraft;
	}

	ResourceCatalog resourceCatalog(long frameId) {
		return passCapture().resourceCatalog(frameId);
	}

	CaptureResult capture(
		CapturePlan plan,
		ArtifactSink sink,
		long frameId,
		CancellationToken cancellation
	) {
		int finalFramebuffer = ((GpuTextureInterface) minecraft.getMainRenderTarget().getColorTexture()).iris$getGlId();
		return GlCapturePlanExecutor.capture(
			plan,
			sink,
			frameId,
			cancellation,
			target -> resolve(target, finalFramebuffer));
	}

	CompletionStage<CapturePlan.AfterPassReceipt> captureAfterPass(
		CapturePlan.AfterPassRequest request,
		ArtifactSink sink,
		CancellationToken cancellation
	) {
		IrisVibrisPassCapture passCapture = passCapture();
		return passCapture.schedule(request, sink, cancellation,
			passCapture.resourceCatalog(IrisVibrisLifecycle.currentFrame()));
	}

	private Integer resolve(
		CapturePlan.Target target,
		int finalFramebuffer
	) {
		return switch (target.resource().kind()) {
			case FINAL_FRAMEBUFFER -> finalFramebuffer;
			case TEXTURE, BUFFER -> passCapture().resolveCurrent(target);
			case PATCHED_SHADERS -> throw new IllegalArgumentException(
				"Patched shaders require directory artifact capture");
		};
	}

	CompletionStage<dev.vibris.api.TextureAnalysisResult> analyzeTexture(
		dev.vibris.api.TextureAnalysisRequest request, ArtifactSink sink, CancellationToken cancellation) {
		IrisVibrisPassCapture pipeline = passCapture();
		CapturePlan.Target target = new CapturePlan.Target(request.resource(), CapturePlan.ArtifactFormat.BIN,
			"texture-analysis", java.util.List.of());
		return pipeline.analyzeTexture(request, sink, cancellation, () -> {
			int beauty = ((GpuTextureInterface) minecraft.getMainRenderTarget().getColorTexture()).iris$getGlId();
			return resolve(target, beauty);
		});
	}

	private static IrisVibrisPassCapture passCapture() {
		if (Iris.getPipelineManager().getPipelineNullable() instanceof VibrisPipeline pipeline) {
			return pipeline.vibris$getPassCapture();
		}
		throw new IllegalStateException("No Iris shader pipeline is active");
	}
}
