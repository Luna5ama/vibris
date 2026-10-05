package dev.vibris.integration;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolInteropTest {
    private static final Path ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();

    @Test
    void helloPingPong() throws Exception {
        assertScenario("hello-ping-pong", 55051, 3, 1, "protocol-c001");
    }

    @Test
    void rejectsMajorMismatch() throws Exception {
        assertScenario("reject-major-mismatch", 55052, 1, 65535, "protocol-c002");
    }

    private static void assertScenario(String scenario, int port, int protocolMajor, int capability, String pingId)
        throws Exception {
        ProcessHarness.Result result = ProcessHarness.run(
            Duration.ofSeconds(20),
            "pwsh.exe",
            "-NoLogo",
            "-NoProfile",
            "-File",
            ROOT.resolve("integration-tests/scripts/protocol-smoke.ps1").toString(),
            "-ServerJar",
            ROOT.resolve("test-runtime/build/libs/vibris-test-runtime.jar").toString(),
            "-Client",
            ROOT.resolve("mcp/out/build/Release/vibris-protocol-smoke.exe").toString(),
            "-Listen",
            "127.0.0.1:" + port,
            "-PingId",
            pingId,
            "-ProtocolMajor",
            Integer.toString(protocolMajor),
            "-ProtocolMinor",
            "0",
            "-Capability",
            Integer.toString(capability),
            "-Scenario",
            scenario,
            "-TimeoutSeconds",
            "10"
        );
        assertEquals(0, result.exitCode(), result.output());
    }
}
