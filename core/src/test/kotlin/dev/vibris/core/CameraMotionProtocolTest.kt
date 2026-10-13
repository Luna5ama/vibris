package dev.vibris.core

import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v2.Action
import dev.vibris.protocol.v2.ActionKind
import dev.vibris.protocol.v2.ActionSequence
import dev.vibris.protocol.v2.CameraKeyframe
import dev.vibris.protocol.v2.CameraMotion
import dev.vibris.protocol.v2.JobSpec
import dev.vibris.protocol.v2.RestorePolicy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CameraMotionProtocolTest {
    @Test fun xyzWireFieldsAndBoundsAreValidatedBeforeRuntimeUse() {
        val p=CameraMotion.newBuilder().setArtifactPrefix("move")
            .addKeyframes(CameraKeyframe.newBuilder().setFrameOffset(0))
            .addKeyframes(CameraKeyframe.newBuilder().setFrameOffset(4).setXDelta(2.0).setYDelta(1.0).setZDelta(-2.0))
        val plan=CameraMotionProtocol.toApi(p.build())
        assertEquals(dev.vibris.api.CameraMotionPlan.Position(1.0,.5,-1.0),plan.positionDeltaAt(2))
        for(bad in listOf(Double.NaN,17.0)){
            val invalid=p.clone().setKeyframes(1,p.getKeyframes(1).toBuilder().setXDelta(bad)).build()
            assertThrows(IllegalArgumentException::class.java){CameraMotionProtocol.toApi(invalid)}
        }
    }

    private fun action(last: Int = 2): Action = Action.newBuilder().setCameraMotion(
        CameraMotion.newBuilder().setArtifactPrefix("turn").setWarmupFrames(128)
            .addKeyframes(CameraKeyframe.newBuilder().setFrameOffset(0))
            .addKeyframes(CameraKeyframe.newBuilder().setFrameOffset(last).setYawDeltaDegrees(90.0)),
    ).build()

    private fun job(success: Boolean, error: Boolean, motion: Action = action()): CoreJob = CoreJob(
        JobSpec.newBuilder().setJobId("camera-test")
            .setRestoreState(RestorePolicy.newBuilder().setOnSuccess(success).setOnError(error))
            .setActionSequence(ActionSequence.newBuilder().addActions(motion)).build(),
        "camera-test", "workspace", "message", null,
    )

    @Test
    fun actionKeepsTypedKeyframesAndRequiresBothRestorationPaths() {
        val program = CaptureProgramBuilder(64)
        for ((success, error) in listOf(false to false, true to false, false to true)) {
            val failure = assertThrows(RuntimeJobExecutor.Failure::class.java) {
                program.actions(job(success, error), ResourceCatalog.empty())
            }
            assertTrue(failure.message!!.contains("restoration"))
        }
        val prepared = program.actions(job(true, true), ResourceCatalog.empty())
        assertEquals(CaptureProgramBuilder.ActionType.CAMERA_MOTION, prepared.steps.single().type)
        assertEquals(ActionKind.ACTION_KIND_CAMERA_MOTION, RuntimeActionProtocol.kind(action()))
        assertEquals(3, CameraMotionProtocol.toApi(action().cameraMotion).frameCount())
    }

    @Test
    fun oversizedUnsignedWireOffsetsAreRejectedBeforeRuntimeUse() {
        assertThrows(RuntimeJobExecutor.Failure::class.java) {
            CaptureProgramBuilder(64).actions(job(true, true, action(-1)), ResourceCatalog.empty())
        }
        assertThrows(IllegalArgumentException::class.java) { CameraMotionProtocol.toApi(action(240).cameraMotion) }
    }
}