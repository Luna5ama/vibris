package dev.vibris.mod.mixin.iris;

import com.google.common.collect.ImmutableSet;
import dev.vibris.mod.VibrisPassInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer$Pass", remap = false)
abstract class MixinCompositePass implements VibrisPassInfo {
	@Shadow String name;
	@Shadow ImmutableSet<Integer> flipsAfterPass;
	@Shadow net.irisshaders.iris.gl.program.Program program;
	@Shadow net.irisshaders.iris.gl.program.ComputeProgram[] computes;

	@Override public String vibris$name() { return name; }
	@Override public ImmutableSet<Integer> vibris$flipsAfterPass() { return flipsAfterPass; }
	@Override public java.util.List<dev.vibris.api.ResourceCatalog.PassProgram> vibris$programs() {
		return dev.vibris.mod.PassPrograms.describe(program, computes);
	}
}
