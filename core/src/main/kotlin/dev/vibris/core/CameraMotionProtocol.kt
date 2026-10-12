package dev.vibris.core

import dev.vibris.api.CameraMotionPlan
import dev.vibris.api.CameraMotionResult
import dev.vibris.api.CameraPose
import dev.vibris.api.CapturePlan
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v2.CameraMotion
import dev.vibris.protocol.v2.CameraMotionFrame
import dev.vibris.protocol.v2.CameraMotionReceipt
import dev.vibris.protocol.v2.JobResult

internal object CameraMotionProtocol {
    fun toApi(value: CameraMotion): CameraMotionPlan = CameraMotionPlan(
        value.keyframesList.map { CameraMotionPlan.Keyframe(it.frameOffset, it.yawDeltaDegrees, it.pitchDeltaDegrees) },
        value.warmupFrames,
        value.artifactPrefix,
    )

    fun plans(request: CameraMotionPlan, catalog: ResourceCatalog): List<CapturePlanBuilder.Plan> =
        (0 until request.frameCount()).map { offset ->
            val target = CapturePlanBuilder.screenshot(catalog, CapturePlan.ArtifactFormat.PNG, request.artifactName(offset))
            CapturePlanBuilder.plan(listOf(target), catalog)
        }

    fun receipt(
        request: CameraMotionPlan,
        motion: CameraMotionResult,
        plans: List<CapturePlan>,
        artifacts: JobResult,
        captures: CaptureJobExecutor,
    ): CameraMotionReceipt {
        check(motion.samples.size == request.frameCount() && motion.captures.size == plans.size)
        check(motion.samples.zipWithNext().all { (a, b) ->
            b.renderedFrameId == Math.incrementExact(a.renderedFrameId) && b.renderStartedNanos >= a.renderEndedNanos
        }) { "Camera motion returned a discontinuous frame sequence" }
        return CameraMotionReceipt.newBuilder().setWarmupFrames(request.warmupFrames).apply {
            motion.samples.forEachIndexed { index, sample ->
                val actual = sample.actualPose
                check(sample.frameOffset == index && motion.captures[index].frameId == sample.renderedFrameId)
                check(sample.renderEndedNanos >= sample.renderStartedNanos &&
                    sample.shaderTimeSeconds.isFinite() &&
                    kotlin.math.abs(sample.shaderTimeSeconds - (request.warmupFrames + index + 1) * (1.0F / 60.0F)) <= 0.00001F) {
                    "Camera motion returned an invalid frame clock"
                }
                addFrames(CameraMotionFrame.newBuilder()
                    .setFrameOffset(index).setRenderedFrameId(sample.renderedFrameId)
                    .setRenderStartedNanos(sample.renderStartedNanos).setRenderEndedNanos(sample.renderEndedNanos)
                    .setShaderTimeSeconds(sample.shaderTimeSeconds)
                    .setPlannedYawDegrees(sample.plannedRotation.yawDegrees)
                    .setPlannedPitchDegrees(sample.plannedRotation.pitchDegrees)
                    .setActualPose(dev.vibris.protocol.v2.CameraPose.newBuilder()
                        .setX(actual.x).setY(actual.y).setZ(actual.z)
                        .setYawDegrees(CameraPose.wrapYaw(actual.yawDegrees)).setPitchDegrees(actual.pitchDegrees))
                    .setCapture(captures.captureReceipt(plans[index], motion.captures[index], CaptureProgramBuilder.CaptureAction(0, 0, 0), artifacts, null)))
            }
        }.build()
    }
}