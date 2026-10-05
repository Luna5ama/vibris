package dev.vibris.mod.mixin.iris;

import dev.luna5ama.vibris.capture.GpuTimingProgram;
import dev.vibris.mod.ComputeProgramTiming;
import dev.vibris.mod.VibrisClient;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.joml.Vector3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumMap;

@Mixin(value = ComputeProgram.class, remap = false)
public abstract class MixinComputeProgram implements dev.vibris.mod.VibrisProgramInfo {
	@Unique private ComputeProgramTiming vibris$timing;
	@Unique private java.util.List<dev.vibris.api.ResourceCatalog.PassProgram> vibris$programs = java.util.List.of();

	@Override public java.util.List<dev.vibris.api.ResourceCatalog.PassProgram> vibris$programs() {
		return vibris$programs;
	}

	@Inject(method = "<init>", at = @At("RETURN"), require = 1, expect = 1)
	private void vibris$initializeTiming(String name, int program, ProgramUniforms uniforms,
		ProgramSamplers samplers, ProgramImages images, EnumMap<ShaderType, String> sources, CallbackInfo ci) {
		vibris$timing = new ComputeProgramTiming(name, sources.get(ShaderType.COMPUTE));
		vibris$programs = java.util.List.of(dev.vibris.mod.PassPrograms.describe(name, "compute", sources.get(ShaderType.COMPUTE)));
	}

	@Redirect(method = "dispatch", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/gl/IrisRenderSystem;dispatchCompute(Ljava/util/EnumMap;Ljava/lang/String;Lorg/joml/Vector3i;)V"), require = 1, expect = 1)
	private void vibris$dispatchTimed(EnumMap<ShaderType, String> shaderSources, String passName, Vector3i groups) {
		GpuTimingProgram timing = vibris$timing.direct(groups);
		VibrisClient.shaderDebugControl().beginCompute(timing);
		try {
			if (!VibrisClient.captureManager().dispatchCompute(
				shaderSources.get(ShaderType.COMPUTE), timing.getProgram(), groups.x, groups.y, groups.z)) {
				IrisRenderSystem.dispatchCompute(groups);
			}
		} finally {
			VibrisClient.shaderDebugControl().endCompute();
		}
	}

	@Redirect(method = "dispatch", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/gl/IrisRenderSystem;dispatchComputeIndirect(Ljava/util/EnumMap;Ljava/lang/String;J)V"), require = 1, expect = 1)
	private void vibris$dispatchIndirectTimed(EnumMap<ShaderType, String> shaderSources, String passName, long offset) {
		GpuTimingProgram timing = vibris$timing.indirect(offset);
		VibrisClient.shaderDebugControl().beginCompute(timing);
		try {
			if (!VibrisClient.captureManager().dispatchComputeIndirect(
				shaderSources.get(ShaderType.COMPUTE), timing.getProgram(), offset)) {
				IrisRenderSystem.dispatchComputeIndirect(offset);
			}
		} finally {
			VibrisClient.shaderDebugControl().endCompute();
		}
	}
}
