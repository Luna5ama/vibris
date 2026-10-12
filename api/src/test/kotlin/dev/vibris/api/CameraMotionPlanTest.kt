package dev.vibris.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CameraMotionPlanTest {
    private fun key(frame: Int, yaw: Double = 0.0, pitch: Double = 0.0) = CameraMotionPlan.Keyframe(frame, yaw, pitch)

    @Test
    fun interpolatesInRenderedFramesWithoutTakingTheShortestWrappedArc() {
        val plan = CameraMotionPlan(listOf(key(0), key(4, 8.0), key(8)), 128, "turn")
        assertEquals(187.0, plan.rotationAt(4, 179.0, -14.0).yawDegrees)
        assertEquals(-173.0, CameraPose.wrapYaw(plan.rotationAt(4, 179.0, -14.0).yawDegrees))
        assertEquals(183.0, plan.rotationAt(2, 179.0, -14.0).yawDegrees)
        assertEquals(179.0, plan.rotationAt(8, 179.0, -14.0).yawDegrees)
        assertEquals(plan.rotationAt(6, 179.0, -14.0), plan.rotationAt(6, 179.0, -14.0))
        assertEquals("turn-0008", plan.artifactName(8))
    }

    @Test
    fun rejectsUnboundedNonfiniteAndAmbiguousPaths() {
        assertThrows(IllegalArgumentException::class.java) { key(240) }
        assertThrows(IllegalArgumentException::class.java) { key(-1) }
        assertThrows(IllegalArgumentException::class.java) { key(1, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { key(1, 721.0) }
        assertThrows(IllegalArgumentException::class.java) { key(1, pitch = Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { CameraMotionPlan(listOf(key(0), key(0)), 0, "turn") }
        assertThrows(IllegalArgumentException::class.java) { CameraMotionPlan(listOf(key(1), key(2)), 0, "turn") }
        assertThrows(IllegalArgumentException::class.java) { CameraMotionPlan(listOf(key(0), key(2)), 257, "turn") }
        assertThrows(IllegalArgumentException::class.java) { CameraMotionPlan(listOf(key(0), key(2)), 0, "../turn") }
        assertThrows(IllegalArgumentException::class.java) { CameraMotionPlan((0..16).map { key(it) }, 0, "turn") }
    }

    @Test
    fun validatesAbsolutePitchBeforeTheCameraIsChanged() {
        val plan = CameraMotionPlan(listOf(key(0), key(2, pitch = 20.0)), 0, "turn")
        plan.validateAnchor(393.0, 70.0)
        assertThrows(IllegalArgumentException::class.java) { plan.validateAnchor(393.0, 71.0) }
        assertThrows(IllegalArgumentException::class.java) { plan.rotationAt(3, 0.0, 0.0) }
    }
}