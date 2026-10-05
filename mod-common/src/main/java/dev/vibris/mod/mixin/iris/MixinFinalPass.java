package dev.vibris.mod.mixin.iris;

import dev.vibris.mod.PassPrograms;
import dev.vibris.mod.VibrisProgramInfo;
import dev.vibris.api.ResourceCatalog;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.gl.program.ComputeProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import java.util.List;

@Mixin(targets = "net.irisshaders.iris.pipeline.FinalPassRenderer$Pass", remap = false)
abstract class MixinFinalPass implements VibrisProgramInfo {
	@Shadow Program program;
	@Shadow ComputeProgram[] computes;
	@Override public List<ResourceCatalog.PassProgram> vibris$programs() {
		return PassPrograms.describe(program, computes);
	}
}
