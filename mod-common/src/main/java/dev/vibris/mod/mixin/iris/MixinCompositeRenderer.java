package dev.vibris.mod.mixin.iris;

import com.google.common.collect.ImmutableList;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.mod.IrisVibrisPassCapture;
import dev.vibris.mod.VibrisPipeline;
import dev.vibris.mod.VibrisPassInfo;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.pipeline.CompositePass;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = CompositeRenderer.class, remap = false)
public abstract class MixinCompositeRenderer {
	@Shadow @Final private ImmutableList<?> passes;
	@Shadow @Final private WorldRenderingPipeline pipeline;
	@Shadow @Final private CompositePass compositePass;
	@Unique private final List<IrisVibrisPassCapture.PassHandle> vibris$handles = new ArrayList<>();
	@Unique private int vibris$passIndex;

	@Inject(method = "<init>", at = @At("RETURN"), require = 1, expect = 1)
	private void vibris$registerPasses(CallbackInfo ci) {
		IrisVibrisPassCapture capture = ((VibrisPipeline) pipeline).vibris$getPassCapture();
		ResourceCatalog.PassStage stage = switch (compositePass) {
			case BEGIN -> ResourceCatalog.PassStage.BEGIN;
			case PREPARE -> ResourceCatalog.PassStage.PREPARE;
			case DEFERRED -> ResourceCatalog.PassStage.DEFERRED;
			case COMPOSITE -> ResourceCatalog.PassStage.COMPOSITE;
		};
		for (Object pass : passes) vibris$handles.add(capture.register(stage,
			((VibrisPassInfo) pass).vibris$name(), ((VibrisPassInfo) pass).vibris$programs()));
	}

	@Inject(method = "renderAll", at = @At("HEAD"), require = 1, expect = 1)
	private void vibris$beginPasses(CallbackInfo ci) { vibris$passIndex = 0; }

	@Redirect(method = "renderAll", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/gl/GLDebug;popGroup()V", ordinal = 0), require = 1, expect = 1)
	private void vibris$captureBoundary() {
		VibrisPassInfo pass = (VibrisPassInfo) passes.get(vibris$passIndex);
		((VibrisPipeline) pipeline).vibris$getPassCapture().captureBoundary(
			vibris$handles.get(vibris$passIndex++), pass.vibris$flipsAfterPass());
		GLDebug.popGroup();
	}
}
