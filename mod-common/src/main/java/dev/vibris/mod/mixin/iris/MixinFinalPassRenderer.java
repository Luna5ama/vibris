package dev.vibris.mod.mixin.iris;

import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.mod.IrisVibrisPassCapture;
import dev.vibris.mod.VibrisPipeline;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.pipeline.FinalPassRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.pathways.CenterDepthSampler;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.function.Supplier;

@Mixin(value = FinalPassRenderer.class, remap = false)
public abstract class MixinFinalPassRenderer {
	@Shadow @Final private WorldRenderingPipeline pipeline;
	@Unique private IrisVibrisPassCapture.PassHandle vibris$handle;
	@Unique private ImmutableSet<Integer> vibris$flipsAfterPass = ImmutableSet.of();

	@Inject(method = "<init>", at = @At("RETURN"), require = 1, expect = 1)
	private void vibris$rememberFlips(WorldRenderingPipeline pipeline, ProgramSet pack, RenderTargets renderTargets,
		TextureAccess noiseTexture, ShaderStorageBufferHolder holder, FrameUpdateNotifier updateNotifier,
		ImmutableSet<Integer> flippedBuffers, CenterDepthSampler centerDepthSampler,
		Supplier<ShadowRenderTargets> shadowTargetsSupplier, Object2ObjectMap<String, TextureAccess> customTextureIds,
		Object2ObjectMap<String, TextureAccess> irisCustomTextures, Set<GlImage> customImages,
		ImmutableSet<Integer> flippedAtLeastOnce, CustomUniforms customUniforms, CallbackInfo ci) {
		vibris$flipsAfterPass = flippedBuffers;
	}

	@Redirect(method = "renderFinalPass", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/gl/GLDebug;popGroup()V"), require = 1, expect = 1)
	private void vibris$captureBoundary() {
		IrisVibrisPassCapture capture = ((VibrisPipeline) pipeline).vibris$getPassCapture();
		if (vibris$handle == null) {
			// The field's private nested type cannot be named in a Java @Shadow declaration.
			try {
				var field = FinalPassRenderer.class.getDeclaredField("finalPass");
				field.setAccessible(true);
				Object pass = field.get(this);
				vibris$handle = capture.register(ResourceCatalog.PassStage.FINAL, "final",
					pass == null ? java.util.List.of() : ((dev.vibris.mod.VibrisProgramInfo) pass).vibris$programs());
			} catch (ReflectiveOperationException failure) {
				throw new IllegalStateException("Cannot inspect the final render pass", failure);
			}
		}
		capture.captureBoundary(vibris$handle, vibris$flipsAfterPass);
		GLDebug.popGroup();
	}
}
