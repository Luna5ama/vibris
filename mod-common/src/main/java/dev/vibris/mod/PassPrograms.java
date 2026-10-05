package dev.vibris.mod;

import dev.luna5ama.vibris.capture.GraphicsProgramRegistry;
import dev.vibris.api.ResourceCatalog;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.shaderpack.include.ShaderSourceMap;
import java.util.ArrayList;
import java.util.List;

public final class PassPrograms {
	private PassPrograms() {}

	public static List<ResourceCatalog.PassProgram> describe(Program graphics, ComputeProgram[] computes) {
		List<ResourceCatalog.PassProgram> result = new ArrayList<>();
		if (graphics != null) {
			var info = GraphicsProgramRegistry.INSTANCE.find(graphics.getProgramId());
			if (info != null) info.getSources().forEach((stage, source) ->
				result.add(describe(info.getPassName(), stage, source)));
		}
		if (computes != null) for (ComputeProgram compute : computes) {
			if (compute != null) result.addAll(((VibrisProgramInfo) (Object) compute).vibris$programs());
		}
		return List.copyOf(result);
	}

	public static ResourceCatalog.PassProgram describe(String name, String stage, String source) {
		String extension = switch (stage) {
			case "compute" -> ".csh";
			case "vertex" -> ".vsh";
			case "fragment" -> ".fsh";
			case "geometry" -> ".gsh";
			case "tesc" -> ".tcs";
			case "tese" -> ".tes";
			default -> "";
		};
		String fallback = name + extension;
		String entry = ShaderSourceMap.parse(source).sourcePaths().entrySet().stream()
			.map(java.util.Map.Entry::getValue)
			.filter(path -> path.substring(path.lastIndexOf('/') + 1).equals(fallback))
			.findFirst().orElse("/" + fallback);
		return new ResourceCatalog.PassProgram(name, stage, entry, ShaderMainSource.resolve(fallback, source));
	}
}
