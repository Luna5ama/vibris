package dev.vibris.core

import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v2.Action
import dev.vibris.protocol.v2.ActionSequence
import dev.vibris.protocol.v2.DumpBuffer
import dev.vibris.protocol.v2.DumpBufferAfterPass
import dev.vibris.protocol.v2.GetGpuMetrics
import dev.vibris.protocol.v2.InspectShader
import dev.vibris.protocol.v2.JobSpec
import dev.vibris.protocol.v2.LoadShader
import dev.vibris.protocol.v2.NsightGpuTrace
import dev.vibris.protocol.v2.ResetTemporalState
import dev.vibris.protocol.v2.ResourceSelector
import dev.vibris.protocol.v2.ResultArtifactOptions
import dev.vibris.protocol.v2.WaitFrames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CaptureProgramBuilderTest {
    @Test
    fun `strict v2 action sequence accepts result artifacts`() {
        val submission = JobSpec.newBuilder()
            .setJobId("paired-request")
            .setResultArtifacts(
                ResultArtifactOptions.newBuilder()
                    .setWriteJson(true),
            )
            .setActionSequence(
                ActionSequence.newBuilder()
                    .addActions(
                        Action.newBuilder().setLoadShader(
                            LoadShader.newBuilder()
                                .setSourceUuid("33333333-3333-4333-8333-333333333333")
                                .setSourceId("baseline")
                                .setConfigId("config"),
                        ),
                    )
                    .addActions(
                        Action.newBuilder().setGetGpuMetrics(
                            GetGpuMetrics.newBuilder().setFrames(4),
                        ),
                    ),
            )
            .build()
        val job = CoreJob(
            submission,
            "paired-request",
            "11111111-1111-4111-8111-111111111111",
            "message",
            null,
        )

        val program = CaptureProgramBuilder().actions(job, ResourceCatalog.empty())

        assertEquals(2, program.steps.size)
        assertEquals(CaptureProgramBuilder.ActionType.LOAD, program.steps[0].type)
        assertEquals(CaptureProgramBuilder.ActionType.RUNTIME, program.steps[1].type)
    }

    @Test
    fun `shader inspection is a typed query step`() {
        val submission = JobSpec.newBuilder()
            .setJobId("inspect-request")
            .setActionSequence(
                ActionSequence.newBuilder().addActions(
                    Action.newBuilder().setInspectShader(InspectShader.getDefaultInstance()),
                ),
            )
            .build()
        val job = CoreJob(
            submission,
            "inspect-request",
            "11111111-1111-4111-8111-111111111111",
            "message",
            null,
        )

        val program = CaptureProgramBuilder().actions(job, ResourceCatalog.empty())

        assertEquals(CaptureProgramBuilder.ActionType.INSPECT, program.steps.single().type)
    }

    @Test
    fun `deterministic prelude is one deferred step without reading the catalog`() {
        val actions = listOf(
            load(),
            reset(),
            waitFrames(3),
            dumpBuffer("first"),
            dumpBuffer("second"),
        )

        val program = CaptureProgramBuilder().actions(job(actions), ResourceCatalog.empty())

        assertTrue(CaptureProgramBuilder.startsWithDeterministicBlock(actions))
        assertEquals(1, program.steps.size)
        assertEquals(CaptureProgramBuilder.ActionType.DETERMINISTIC, program.steps.single().type)
        val block = program.steps.single().deterministic!!
        assertEquals(0, block.loadActionIndex)
        assertEquals(1, block.resetActionIndex)
        assertEquals(2, block.waitActionIndex)
        assertEquals(3, block.warmupFrames)
        assertEquals(listOf(3, 4), block.captures.map { it.actionIndex })
        assertEquals(0L, program.estimatedBytes)
        assertTrue(program.planningSession.hasDeferredCaptures)
        assertEquals(setOf(3, 4), program.planningSession.directCaptureActionIndices)
    }

    @Test
    fun `deterministic prelude supports zero warmup without a wait action`() {
        val actions = listOf(load(), reset(), dumpBuffer("capture"))

        val program = CaptureProgramBuilder().actions(job(actions), ResourceCatalog.empty())

        val block = program.steps.single().deterministic!!
        assertEquals(null, block.waitActionIndex)
        assertEquals(0, block.warmupFrames)
        assertEquals(listOf(2), block.captures.map { it.actionIndex })
    }

    @Test
    fun `deterministic matcher rejects nonpositive wait without changing granular validation`() {
        val actions = listOf(load(), reset(), waitFrames(0), dumpBuffer("capture"))

        assertFalse(CaptureProgramBuilder.startsWithDeterministicBlock(actions))
        assertThrows(RuntimeJobExecutor.Failure::class.java) {
            CaptureProgramBuilder().actions(job(actions), bufferCatalog())
        }
    }

    @Test
    fun `incorrect prelude flags keep actions on the granular path`() {
        val loadNotPrelude = listOf(load(prelude = false), reset(), dumpBuffer("capture"))
        val resetPrelude = listOf(load(), reset(prelude = true), dumpBuffer("capture"))
        val waitPrelude = listOf(load(), reset(), waitFrames(1, prelude = true), dumpBuffer("capture"))
        val capturePrelude = listOf(load(), reset(), dumpBuffer("capture", prelude = true))

        listOf(loadNotPrelude, resetPrelude, capturePrelude).forEach { actions ->
            assertFalse(CaptureProgramBuilder.startsWithDeterministicBlock(actions))
            val program = CaptureProgramBuilder().actions(job(actions), bufferCatalog())
            assertEquals(
                listOf(
                    CaptureProgramBuilder.ActionType.LOAD,
                    CaptureProgramBuilder.ActionType.RESET,
                    CaptureProgramBuilder.ActionType.DEFERRED_CAPTURE,
                ),
                program.steps.map { it.type },
            )
        }
        assertFalse(CaptureProgramBuilder.startsWithDeterministicBlock(waitPrelude))
        assertEquals(
            listOf(
                CaptureProgramBuilder.ActionType.LOAD,
                CaptureProgramBuilder.ActionType.RESET,
                CaptureProgramBuilder.ActionType.WAIT,
                CaptureProgramBuilder.ActionType.DEFERRED_CAPTURE,
            ),
            CaptureProgramBuilder().actions(job(waitPrelude), bufferCatalog()).steps.map { it.type },
        )
    }

    @Test
    fun `deferred planning retries cleanly and preserves raw capture indices`() {
        val actions = listOf(
            load(),
            reset(),
            waitFrames(2),
            dumpBuffer("baseline"),
            dumpBuffer("candidate"),
        )
        val program = CaptureProgramBuilder().actions(job(actions), ResourceCatalog.empty())
        val deterministic = program.steps[0]
        var registrations = 0

        assertThrows(IllegalStateException::class.java) {
            program.planningSession.resolveDeferred(deterministic, bufferCatalog()) { _, _ ->
                registrations++
                throw IllegalStateException("registration failed")
            }
        }
        assertEquals(1, registrations)

        var registeredBytes = -1L
        val resolved = program.planningSession.resolveDeferred(deterministic, bufferCatalog()) { plan, bytes ->
            registrations++
            assertEquals(2, plan.targets.size)
            registeredBytes = bytes
        }
        assertEquals(2, registrations)
        assertEquals(registeredBytes, resolved.estimatedBytes)
        assertEquals(listOf(3, 4), resolved.captureActions.map { it.actionIndex })
        val second = program.planningSession.resolveDeferred(deterministic, bufferCatalog()) { _, _ ->
            throw AssertionError("a resolved group must not register twice")
        }
        assertSame(resolved, second)
    }

    @Test
    fun `deferred outputs remain globally unique across planning points ignoring case`() {
        val actions = listOf(
            load(),
            reset(),
            dumpBuffer("Shared"),
            Action.newBuilder().setInspectShader(InspectShader.getDefaultInstance()).build(),
            dumpBuffer("shared"),
        )
        val program = CaptureProgramBuilder().actions(job(actions), bufferCatalog())
        program.planningSession.resolveDeferred(program.steps[0], bufferCatalog()) { _, _ -> }
        var registered = false

        assertThrows(RuntimeJobExecutor.Failure::class.java) {
            program.planningSession.resolveDeferredCapture(program.steps[2], bufferCatalog()) { _, _ ->
                registered = true
            }
        }
        assertFalse(registered)
    }

    @Test
    fun `catalog actions after the first deterministic load stay raw until their execution point`() {
        val actions = listOf(
            load(),
            reset(),
            dumpBuffer("first", "first_buffer"),
            Action.newBuilder().setInspectShader(InspectShader.getDefaultInstance()).build(),
            dumpBuffer("later", "later_buffer"),
            dumpBuffer("later-two", "later_buffer"),
        )
        val program = CaptureProgramBuilder().actions(job(actions), ResourceCatalog.empty())

        assertEquals(
            listOf(
                CaptureProgramBuilder.ActionType.DETERMINISTIC,
                CaptureProgramBuilder.ActionType.INSPECT,
                CaptureProgramBuilder.ActionType.DEFERRED_CAPTURE,
            ),
            program.steps.map { it.type },
        )
        program.planningSession.resolveDeferred(program.steps[0], bufferCatalog("first_buffer")) { _, _ -> }
        var registrations = 0
        assertThrows(RuntimeJobExecutor.Failure::class.java) {
            program.planningSession.resolveDeferredCapture(
                program.steps[2],
                ResourceCatalog.empty(),
            ) { _, _ -> registrations++ }
        }
        assertEquals(0, registrations)
        assertThrows(IllegalStateException::class.java) {
            program.planningSession.resolveDeferredCapture(
                program.steps[2],
                bufferCatalog("later_buffer", 256),
            ) { _, _ ->
                registrations++
                throw IllegalStateException("registration failed")
            }
        }
        val resolved = program.planningSession.resolveDeferredCapture(
            program.steps[2],
            bufferCatalog("later_buffer", 256),
        ) { plan, bytes ->
            registrations++
            assertTrue(plan.targets.all { it.resource.logicalName == "later_buffer" })
            assertEquals(8_704L, bytes)
        }
        assertEquals(2, registrations)
        assertEquals(listOf(4, 5), resolved.captureActions.map { it.actionIndex })
        assertSame(
            resolved,
            program.planningSession.resolveDeferredCapture(
                program.steps[2],
                ResourceCatalog.empty(),
            ) { _, _ -> throw AssertionError("a resolved capture must not register twice") },
        )
    }

    @Test
    fun `after-pass groups after deterministic load resolve atomically from the current catalog`() {
        val passId = "composite/composite21"
        val actions = listOf(
            load(),
            reset(),
            dumpBuffer("first"),
            Action.newBuilder().setInspectShader(InspectShader.getDefaultInstance()).build(),
            dumpBufferAfterPass("after-a", passId),
            dumpBufferAfterPass("after-b", passId),
        )
        val program = CaptureProgramBuilder().actions(job(actions), ResourceCatalog.empty())
        val deferred = program.steps.last()

        assertEquals(CaptureProgramBuilder.ActionType.DEFERRED_AFTER_PASS, deferred.type)
        var registrations = 0
        val catalog = bufferCatalog(withPass = true)
        assertThrows(IllegalStateException::class.java) {
            program.planningSession.resolveDeferredAfterPass(deferred, catalog) { _, _ ->
                registrations++
                throw IllegalStateException("registration failed")
            }
        }
        val resolved = program.planningSession.resolveDeferredAfterPass(deferred, catalog) { plan, bytes ->
            registrations++
            assertEquals(2, plan.targets.size)
            assertTrue(bytes > 0)
        }
        assertEquals(2, registrations)
        assertEquals(listOf(4, 5), resolved.afterPassActions.map { it.actionIndex })
        assertTrue(resolved.afterPassActions.all { it.request.mappingSha256 == catalog.mappingSha256 })
        assertSame(
            resolved,
            program.planningSession.resolveDeferredAfterPass(deferred, ResourceCatalog.empty()) { _, _ ->
                throw AssertionError("a resolved after-pass group must not register twice")
            },
        )
    }

    @Test
    fun `multiple deterministic blocks resolve against their own final catalogs`() {
        val actions = listOf(
            load(),
            reset(),
            dumpBuffer("a", "buffer_a"),
            Action.newBuilder().setInspectShader(InspectShader.getDefaultInstance()).build(),
            load(),
            reset(),
            dumpBuffer("b", "buffer_b"),
        )
        val program = CaptureProgramBuilder().actions(job(actions), ResourceCatalog.empty())
        val blocks = program.steps.filter { it.type == CaptureProgramBuilder.ActionType.DETERMINISTIC }

        val first = program.planningSession.resolveDeferred(blocks[0], bufferCatalog("buffer_a")) { _, _ -> }
        val second = program.planningSession.resolveDeferred(blocks[1], bufferCatalog("buffer_b")) { _, _ -> }

        assertEquals("buffer_a", first.capture.targets.single().resource.logicalName)
        assertEquals("buffer_b", second.capture.targets.single().resource.logicalName)
        assertEquals(listOf(2), first.captureActions.map { it.actionIndex })
        assertEquals(listOf(6), second.captureActions.map { it.actionIndex })
    }

    @Test
    fun `catalog planning without a prior source mutation remains eager`() {
        val actions = listOf(dumpBuffer("eager"))

        assertThrows(RuntimeJobExecutor.Failure::class.java) {
            CaptureProgramBuilder().actions(job(actions), ResourceCatalog.empty())
        }
        val program = CaptureProgramBuilder().actions(job(actions), bufferCatalog())
        assertEquals(CaptureProgramBuilder.ActionType.CAPTURE, program.steps.single().type)
    }

    @Test
    fun `atomic Nsight action owns single and multi replay capture planning`() {
        val single = nsight("single-trace") {
            passId = "composite/composite1"
        }
        val multi = nsight("multi-trace") {
            captureType = "composite"
        }

        val program = CaptureProgramBuilder().actions(job(listOf(single, multi)), ResourceCatalog.empty())

        assertEquals(
            listOf(CaptureProgramBuilder.ActionType.NSIGHT, CaptureProgramBuilder.ActionType.NSIGHT),
            program.steps.map { it.type },
        )
        assertEquals(listOf(0, 1), program.steps.map { it.actionIndex })
        assertThrows(RuntimeJobExecutor.Failure::class.java) {
            CaptureProgramBuilder().actions(job(listOf(single, single)), ResourceCatalog.empty())
        }
    }

    @Test
    fun `capture after a non-strict load resolves from the post-load catalog`() {
        val actions = listOf(
            load(prelude = false),
            dumpBuffer("post-load", "post_load_buffer"),
        )
        val program = CaptureProgramBuilder().actions(job(actions), ResourceCatalog.empty())

        assertEquals(
            listOf(
                CaptureProgramBuilder.ActionType.LOAD,
                CaptureProgramBuilder.ActionType.DEFERRED_CAPTURE,
            ),
            program.steps.map { it.type },
        )
        val resolved = program.planningSession.resolveDeferredCapture(
            program.steps[1],
            bufferCatalog("post_load_buffer", 512),
        ) { _, bytes -> assertEquals(4_608L, bytes) }
        assertEquals("post_load_buffer", resolved.capture.targets.single().resource.logicalName)
    }

    @Test
    fun `deterministic matcher shares offset behavior with the parser matcher`() {
        val actions = listOf(
            Action.newBuilder().setInspectShader(InspectShader.getDefaultInstance()).build(),
            load(),
            reset(),
            dumpBuffer("capture"),
        )

        assertFalse(CaptureProgramBuilder.startsWithDeterministicBlock(actions))
        assertTrue(CaptureProgramBuilder.startsWithDeterministicBlock(actions, 1))
    }

    private fun job(actions: List<Action>): CoreJob {
        val submission = JobSpec.newBuilder()
            .setJobId("capture-program")
            .setActionSequence(ActionSequence.newBuilder().addAllActions(actions))
            .build()
        return CoreJob(
            submission,
            "capture-program",
            "11111111-1111-4111-8111-111111111111",
            "message",
            null,
        )
    }

    private fun load(prelude: Boolean = true): Action = Action.newBuilder()
        .setPrelude(prelude)
        .setLoadShader(
            LoadShader.newBuilder()
                .setSourceUuid("33333333-3333-4333-8333-333333333333")
                .setSourceId("source")
                .setConfigId("config"),
        ).build()

    private fun reset(prelude: Boolean = false): Action = Action.newBuilder()
        .setPrelude(prelude)
        .setResetTemporalState(ResetTemporalState.getDefaultInstance())
        .build()

    private fun waitFrames(frames: Int, prelude: Boolean = false): Action = Action.newBuilder()
        .setPrelude(prelude)
        .setWaitFrames(WaitFrames.newBuilder().setFrameCount(frames))
        .build()

    private fun dumpBuffer(
        artifactName: String,
        resourceName: String = "buffer",
        prelude: Boolean = false,
    ): Action = Action.newBuilder()
        .setPrelude(prelude)
        .setDumpBuffer(
            DumpBuffer.newBuilder()
                .setResource(ResourceSelector.newBuilder().setLogicalName(resourceName))
                .setArtifactName(artifactName),
        ).build()

    private fun dumpBufferAfterPass(artifactName: String, passId: String): Action = Action.newBuilder()
        .setDumpBufferAfterPass(
            DumpBufferAfterPass.newBuilder()
                .setPassId(passId)
                .setResource(ResourceSelector.newBuilder().setLogicalName("buffer"))
                .setArtifactName(artifactName),
        ).build()


    private fun nsight(
        artifactName: String,
        capture: NsightGpuTrace.Builder.() -> Unit,
    ): Action = Action.newBuilder().setNsightGpuTrace(
        NsightGpuTrace.newBuilder()
            .apply(capture)
            .setArtifactName(artifactName)
            .setReplayBackend("gl")
            .setArchitecture("Ada")
            .setMetricSetName("Throughput Metrics")
            .setReplayFrames(300)
            .setStartAfterMs(1_000)
            .setMaxDurationMs(1_000)
            .setTimeoutSeconds(300)
            .setTimeEveryAction(true)
            .setGpuClocks("base"),
    ).build()

    private fun bufferCatalog(
        name: String = "buffer",
        byteSize: Long = 64,
        withPass: Boolean = false,
    ): ResourceCatalog {
        val resource = ResourceCatalog.ResourceDescriptor.of(
            name,
            ResourceCatalog.ResourceKind.BUFFER,
            emptyList(),
            0,
            0,
            0,
            0,
            0,
            "binary",
            0,
            ResourceCatalog.ScalarType.UINT8,
            byteSize,
            1,
            name,
            "shader_storage",
            "",
            "",
            "",
            0,
            "",
            "",
        )
        val passes = if (withPass) {
            listOf(
                ResourceCatalog.PassDescriptor.of(
                    ResourceCatalog.PassStage.COMPOSITE,
                    "composite21",
                    0,
                    listOf(name),
                ),
            )
        } else {
            emptyList()
        }
        return ResourceCatalog.of(listOf(resource), passes)
    }
}
