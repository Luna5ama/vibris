package dev.vibris.mod;

import dev.luna5ama.vibris.capture.GpuTimingProgram;
import net.irisshaders.iris.shaderpack.include.ShaderSourceMap;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ComputeProgramTimingTest {
	@Test
	void programEntryIsTheWrapperRatherThanTheFirstInclude() {
		String source = ShaderSourceMap.appendMetadata("#line 2 2\nvoid main() {}", Map.of(
			0, "/Base.glsl", 1, "/world0/composite19.csh", 2, "/pass/GIPathGuidePrepare.comp.glsl"));
		var program = PassPrograms.describe("composite19", "compute", source);
		assertEquals("/world0/composite19.csh", program.entryFile());
		assertEquals("/pass/GIPathGuidePrepare.comp.glsl", program.mainSource());
	}

	@Test
	void preservesGroupedProgramNamesAndResolvesTheMainSource() {
		List<String> groupedPrograms = List.of(
			"begin1_a", "begin1_b", "begin2_a", "begin2_b", "begin3_a", "composite13_a", "composite34");
		for (String program : groupedPrograms) {
			String implementation = program.equals("begin3_a")
				? "/techniques/atmospherics/air/lut/GenerateSkyViewLUT.comp.glsl"
				: "/pass/" + program + ".comp.glsl";
			GpuTimingProgram timing = new ComputeProgramTiming(program, mappedSource(program, implementation))
				.direct(new Vector3i(120, 68, 1));

			assertEquals(program, timing.getProgram());
			assertEquals(implementation, timing.getSourceFile());
			assertEquals("compute", timing.getStage());
			assertEquals("direct:120x68x1", timing.getDispatch());
		}
	}

	@Test
	void distinguishesSkyViewAndDirectLightingFromTheirWrapperPrograms() {
		GpuTimingProgram skyView = new ComputeProgramTiming(
			"begin3_a",
			mappedSource("begin3_a", "/techniques/atmospherics/air/lut/GenerateSkyViewLUT.comp.glsl")
		).direct(new Vector3i(120, 68, 1));
		GpuTimingProgram directLighting = new ComputeProgramTiming(
			"composite13_a",
			mappedSource("composite13_a", "/pass/composite/DirectLighting.glsl")
		).direct(new Vector3i(240, 135, 1));

		assertEquals("/techniques/atmospherics/air/lut/GenerateSkyViewLUT.comp.glsl", skyView.getSourceFile());
		assertEquals("/pass/composite/DirectLighting.glsl", directLighting.getSourceFile());
		assertEquals("begin3_a", skyView.getProgram());
		assertEquals("composite13_a", directLighting.getProgram());
	}

	@Test
	void cachesDispatchMetadataAndFallsBackToTheTopLevelSource() {
		ComputeProgramTiming timing = new ComputeProgramTiming("begin3_a", "#version 460\nvoid main() {}\n");
		GpuTimingProgram direct = timing.direct(new Vector3i(8, 4, 1));

		assertSame(direct, timing.direct(new Vector3i(8, 4, 1)));
		assertEquals("begin3_a.csh", direct.getSourceFile());
		assertEquals("indirect:offset=64", timing.indirect(64).getDispatch());
	}

	@Test
	void scansCommentsAndLineDirectivesWithoutCopyingTheSource() {
		String source = """
			// #line 1 1
			#line 1 1
			/* void main() {}
			#line 99 1
			*/
			#line 20 2
			#line 21
			void/* comment */ main /* comment */() {}
			""";
		String mapped = ShaderSourceMap.appendMetadata(source, Map.of(
			1, "/wrapper.csh",
			2, "/implementation.comp.glsl"
		));

		GpuTimingProgram timing = new ComputeProgramTiming("compute", mapped).direct(new Vector3i(1));

		assertEquals("/implementation.comp.glsl", timing.getSourceFile());
	}

	private static String mappedSource(String program, String implementation) {
		String source = """
			#line 1 1
			#version 460 compatibility
			// void main() must not select the wrapper source.
			#line 20 2
			void main() {}
			""";
		return ShaderSourceMap.appendMetadata(source, Map.of(
			1, "/" + program + ".csh",
			2, implementation
		));
	}

	private static String fileName(String path) {
		return path.substring(path.lastIndexOf('/') + 1);
	}
}
