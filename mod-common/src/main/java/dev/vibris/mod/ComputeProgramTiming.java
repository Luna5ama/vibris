package dev.vibris.mod;

import dev.luna5ama.vibris.capture.GpuTimingProgram;
import net.irisshaders.iris.shaderpack.include.ShaderSourceMap;
import org.joml.Vector3i;

import java.util.Map;

public final class ComputeProgramTiming {
	private final String program;
	private final String sourceFile;
	private int directX = Integer.MIN_VALUE;
	private int directY = Integer.MIN_VALUE;
	private int directZ = Integer.MIN_VALUE;
	private GpuTimingProgram directTiming;
	private long indirectOffset = Long.MIN_VALUE;
	private GpuTimingProgram indirectTiming;

	public ComputeProgramTiming(String program, String transformedSource) {
		this.program = program;
		this.sourceFile = ShaderMainSource.resolve(program + ".csh", transformedSource);
	}

	public GpuTimingProgram direct(Vector3i workGroups) {
		if (directTiming == null || directX != workGroups.x || directY != workGroups.y || directZ != workGroups.z) {
			directX = workGroups.x;
			directY = workGroups.y;
			directZ = workGroups.z;
			directTiming = timing("direct:" + directX + "x" + directY + "x" + directZ);
		}
		return directTiming;
	}

	public GpuTimingProgram indirect(long offset) {
		if (indirectTiming == null || indirectOffset != offset) {
			indirectOffset = offset;
			indirectTiming = timing("indirect:offset=" + offset);
		}
		return indirectTiming;
	}

	private GpuTimingProgram timing(String dispatch) {
		return GpuTimingProgram.compute(program, sourceFile, Map.of(), dispatch);
	}

}
