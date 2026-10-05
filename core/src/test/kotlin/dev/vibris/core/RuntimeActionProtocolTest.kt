package dev.vibris.core

import dev.vibris.api.RuntimeAction
import dev.vibris.protocol.v2.Action
import dev.vibris.protocol.v2.DumpTexture
import dev.vibris.protocol.v2.GetGpuMetrics
import dev.vibris.protocol.v2.InspectShader
import dev.vibris.protocol.v2.NsightGpuTrace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeActionProtocolTest {
    @Test
    fun mapsTypedCommands() {
        val metrics = RuntimeActionProtocol.toApi(
            Action.newBuilder()
                .setGetGpuMetrics(GetGpuMetrics.newBuilder().setFrames(17))
                .build(),
        )
        assertEquals(RuntimeAction.GpuMetrics(17), metrics)
    }

    @Test
    fun convertsCanonicalGpuTimingJsonAndAppliesMetricFilter() {
        val action = Action.newBuilder()
            .setGetGpuMetrics(
                GetGpuMetrics.newBuilder()
                    .setFrames(3)
                    .addMetricIds("begin3_total")
                    .addMetricIds("begin3_a_compute"),
            )
            .build()

        val receipt = RuntimeActionProtocol.gpuMetricsReceipt(action, canonicalGpuMetrics())

        assertEquals("ns", receipt.timingUnit)
        assertEquals(3, receipt.sampledFrames)
        assertEquals(listOf("begin3_total", "begin3_a_compute"), receipt.metricsList.map { it.metricId })
        val aggregate = receipt.metricsList.first()
        assertEquals("", aggregate.programId)
        assertEquals("begin3", aggregate.passId)
        assertEquals(listOf(800L, 900L, 1000L), aggregate.samplesNsList)
        val program = receipt.metricsList.last()
        assertEquals("begin3_a", program.programId)
        assertEquals("begin3", program.passId)
        assertEquals(300, program.averageNs)
    }

    @Test
    fun rejectsEmptyMalformedAndOmittedGpuTimingOutput() {
        val unfiltered = metricsAction()
        assertInvalidMetrics(unfiltered, "{}")
        assertInvalidMetrics(
            unfiltered,
            canonicalGpuMetrics().replace("[800,900,1000]", "[]"),
        )
        val missing = RuntimeActionProtocol.gpuMetricsReceipt(
            Action.newBuilder()
                .setGetGpuMetrics(GetGpuMetrics.newBuilder().setFrames(3).addMetricIds("missing"))
                .build(),
            canonicalGpuMetrics(),
        )
        assertTrue(missing.metricsList.isEmpty())
        assertEquals("missing", missing.missingList.single().metricId)
        assertEquals(3, missing.missingList.single().suggestionsCount)
    }

    @Test
    fun rejectsIncompleteCommandsAtTheGrpcBoundary() {
        assertInvalid(Action.getDefaultInstance())
        assertInvalid(
            Action.newBuilder()
                .setGetGpuMetrics(GetGpuMetrics.newBuilder().setFrames(10_001))
                .build(),
        )
        assertInvalid(
            Action.newBuilder()
                .setGetGpuMetrics(GetGpuMetrics.getDefaultInstance())
                .build(),
        )
        assertInvalid(
            Action.newBuilder()
                .setGetGpuMetrics(
                    GetGpuMetrics.newBuilder()
                        .setFrames(3)
                        .addMetricIds("begin3_total")
                        .addMetricIds("begin3_total"),
                )
                .build(),
        )
        assertInvalid(
            Action.newBuilder()
                .setDumpTexture(DumpTexture.getDefaultInstance())
                .build(),
        )
        val inspection = Action.newBuilder().setInspectShader(InspectShader.getDefaultInstance()).build()
        assertFalse(RuntimeActionProtocol.isRuntime(inspection))
        assertInvalid(inspection)
        val nsight = Action.newBuilder().setNsightGpuTrace(
            NsightGpuTrace.newBuilder()
                .setPassId("composite/composite1")
                .setArtifactName("trace")
                .setReplayBackend("gl")
                .setArchitecture("Ada")
                .setMetricSetName("Throughput Metrics")
                .setReplayFrames(300)
                .setStartAfterMs(1_000)
                .setMaxDurationMs(1_000)
                .setTimeoutSeconds(300)
                .setGpuClocks("base"),
        ).build()
        assertFalse(RuntimeActionProtocol.isRuntime(nsight))
        assertInvalid(nsight)
    }

    private fun assertInvalid(action: Action) {
        assertThrows(IllegalArgumentException::class.java) { RuntimeActionProtocol.toApi(action) }
    }

    private fun assertInvalidMetrics(action: Action, json: String) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            RuntimeActionProtocol.gpuMetricsReceipt(action, json)
        }
        assertTrue(failure.message!!.isNotBlank())
    }

    private fun metricsAction() = Action.newBuilder()
        .setGetGpuMetrics(GetGpuMetrics.newBuilder().setFrames(3))
        .build()

    private fun canonicalGpuMetrics() = """
        {
          "timingUnit":"ns",
          "sampledFrames":3,
          "gpuTimings":{
            "begin3_total":{"avg":900,"p5":810,"p95":990,"p50":900,"samples":[800,900,1000]},
            "begin3_compute":{"avg":300,"p5":250,"p95":350,"p50":300,"samples":[250,300,350]}
          },
          "gpuTimingScopes":[
            {"metric":"begin3_total","kind":"framework_total","framework_pass":"begin3","stage":null},
            {"metric":"begin3_compute","kind":"compatibility_aggregate","framework_pass":"begin3","stage":"compute"}
          ],
          "gpuProgramTimings":[{
            "metric":"begin3_a_compute",
            "kind":"program",
            "program":"begin3_a",
            "stage":"compute",
            "source":"GenerateSkyViewLUT.comp.glsl",
            "defines":{"SKY_VIEW_SAMPLES":"32"},
            "dispatch":"direct:120x68x1",
            "framework_pass":"begin3",
            "compatibility_metric":"begin3_compute",
            "statistics":{"avg":300,"p5":250,"p95":350,"p50":300,"samples":[250,300,350]}
          }]
        }
    """.trimIndent()
}
