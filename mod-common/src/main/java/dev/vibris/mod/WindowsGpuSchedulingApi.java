package dev.vibris.mod;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.Kernel32;
import org.lwjgl.system.windows.WindowsLibrary;

import java.nio.IntBuffer;
import java.nio.file.Path;

import static org.lwjgl.system.JNI.invokePI;
import static org.lwjgl.system.JNI.invokePPI;
import static org.lwjgl.system.MemoryUtil.memAddress;

/** Calls WDDM for this process only. No CPU scheduler APIs or foreign process handles are used. */
final class WindowsGpuSchedulingApi implements WindowsGpuScheduling.Backend, AutoCloseable {
	private final WindowsLibrary gdi32;
	private final long get;
	private final long set;

	WindowsGpuSchedulingApi() {
		gdi32 = new WindowsLibrary(Path.of(System.getenv("SystemRoot"), "System32", "gdi32.dll").toString());
		get = gdi32.getFunctionAddress("D3DKMTGetProcessSchedulingPriorityClass");
		set = gdi32.getFunctionAddress("D3DKMTSetProcessSchedulingPriorityClass");
		if (get == 0 || set == 0) {
			gdi32.free();
			throw new UnsupportedOperationException("D3DKMT process scheduling entry points are unavailable");
		}
	}

	@Override
	public WindowsGpuScheduling.Reading read() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer result = stack.ints(-1);
			int status = invokePPI(Kernel32.GetCurrentProcess(), memAddress(result), get);
			return new WindowsGpuScheduling.Reading(status, result.get(0));
		}
	}

	@Override
	public int set(int priority) {
		return invokePI(Kernel32.GetCurrentProcess(), priority, set);
	}

	@Override
	public void close() {
		gdi32.free();
	}
}
