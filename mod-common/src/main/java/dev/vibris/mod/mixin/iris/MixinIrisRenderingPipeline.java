package dev.vibris.mod.mixin.iris;

import dev.vibris.mod.IrisVibrisPassCapture;
import dev.vibris.mod.GlImageAccess;
import dev.vibris.mod.RenderTargetsAccess;
import dev.vibris.mod.ShaderStorageBufferHolderAccess;
import dev.vibris.mod.ShadowTemporalReset;
import dev.vibris.mod.VibrisClient;
import dev.vibris.mod.VibrisPipeline;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.mixinterface.RenderTargetInterface;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.targets.ClearPass;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL43C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IrisRenderingPipeline.class)
public abstract class MixinIrisRenderingPipeline implements VibrisPipeline {
	@Shadow @Final private RenderTargets renderTargets;
	@Shadow @Final private ComputeProgram[] setup;
	@Shadow @Final private java.util.Set<GlImage> customImages;
	@Shadow private ShaderStorageBufferHolder shaderStorageBufferHolder;
	@Shadow private ShadowRenderTargets shadowRenderTargets;
	@Shadow private ImmutableList<ClearPass> clearPassesFull;
	@Shadow private ImmutableList<ClearPass> shadowClearPassesFull;
	@Shadow private boolean destroyed;
	@Shadow private boolean isRenderingWorld;
	@Shadow private int terrainAtlasTexture;
	@Shadow private int terrainNormalTexture;
	@Shadow private int terrainSpecularTexture;
	@Unique
	private IrisVibrisPassCapture vibris$passCapture;

	@Inject(method = "<init>", at = @At(value = "INVOKE",
		target = "Lnet/irisshaders/iris/pipeline/transform/ShaderPrinter;resetPrintState()V",
		shift = At.Shift.AFTER), require = 1, expect = 1)
	private void vibris$initializePassCapture(CallbackInfo ci) {
		vibris$passCapture = new IrisVibrisPassCapture();
	}

	@Inject(method = "destroy", at = @At("HEAD"), require = 1, expect = 1)
	private void vibris$closePassCapture(CallbackInfo ci) {
		VibrisClient.flameGraph().reset();
		if (vibris$passCapture != null) vibris$passCapture.close();
	}

	@Inject(method = "beginLevelRendering", at = @At("HEAD"), require = 1, expect = 1)
	private void vibris$beginFrame(CallbackInfo ci) {
		VibrisClient.captureManager().startFrame();
	}

	@Inject(method = "finalizeLevelRendering", at = @At("TAIL"), require = 1, expect = 1)
	private void vibris$endFrame(CallbackInfo ci) {
		VibrisClient.captureManager().endFrame();
	}

	@Override
	public IrisVibrisPassCapture vibris$getPassCapture() {
		return vibris$passCapture;
	}

	@Override
	public void vibris$resetTemporalState() {
		RenderSystem.assertOnRenderThread();
		if (destroyed) throw new IllegalStateException("Tried to reset a destroyed world rendering pipeline");
		if (isRenderingWorld) throw new IllegalStateException("Cannot reset temporal state while rendering the world");
		customImages.forEach(image -> ((GlImageAccess) image).vibris$clearContents());
		if (shaderStorageBufferHolder != null) ((ShaderStorageBufferHolderAccess) shaderStorageBufferHolder).vibris$resetBuffers();
		Vector3d fog = CapturedRenderingState.INSTANCE.getFogColor();
		clearPassesFull.forEach(pass -> pass.execute(new Vector4f((float) fog.x, (float) fog.y, (float) fog.z, 1.0F)));
		renderTargets.onFullClear();
		((RenderTargetsAccess) renderTargets).vibris$resetDepthCopies();
		if (shadowRenderTargets != null) {
			((ShadowTemporalReset) shadowRenderTargets).vibris$resetTemporalState();
			shadowClearPassesFull.forEach(pass -> pass.execute(new Vector4f(1.0F)));
			shadowRenderTargets.onFullClear();
		}
		boolean ran = false;
		for (ComputeProgram program : setup) {
			if (program == null) continue;
			ran = true;
			program.use();
			program.dispatch(1, 1);
		}
		if (ran) ComputeProgram.unbind();
		IrisRenderSystem.memoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL43C.GL_TEXTURE_FETCH_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
		((RenderTargetInterface) Minecraft.getInstance().getMainRenderTarget()).iris$bindFramebuffer();
	}

	@Override public RenderTargets vibris$getRenderTargets() { return renderTargets; }
	@Override public ShadowRenderTargets vibris$getShadowRenderTargets() { return shadowRenderTargets; }
	@Override public java.util.Set<GlImage> vibris$getCustomImages() { return java.util.Set.copyOf(customImages); }
	@Override public java.util.List<net.irisshaders.iris.gl.buffer.ShaderStorageBuffer> vibris$getStorageBuffers() {
		return shaderStorageBufferHolder == null
			? java.util.List.of()
			: java.util.Arrays.stream(((ShaderStorageBufferHolderAccess) shaderStorageBufferHolder).vibris$buffers())
				.filter(java.util.Objects::nonNull)
				.toList();
	}
	@Override public int vibris$getTerrainAtlasTexture() { return terrainAtlasTexture; }
	@Override public int vibris$getTerrainNormalTexture() { return terrainNormalTexture; }
	@Override public int vibris$getTerrainSpecularTexture() { return terrainSpecularTexture; }
}
