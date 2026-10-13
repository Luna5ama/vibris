package dev.vibris.api

@JvmRecord
data class CameraMotionPlan(
    @field:DefensiveSnapshot val keyframes: List<Keyframe>,
    val warmupFrames: Int,
    val artifactPrefix: String,
) {
    init {
        require(keyframes.size in 2..16) { "Camera motion requires 2 to 16 keyframes" }
        require(keyframes.first().frameOffset == 0) { "The first camera keyframe must be frame zero" }
        require(keyframes.first().yawDeltaDegrees == 0.0 && keyframes.first().pitchDeltaDegrees == 0.0) {
            "Camera motion must start at the current rotation"
        }
        require(keyframes.first().let { it.xDelta == 0.0 && it.yDelta == 0.0 && it.zDelta == 0.0 }) {
            "Camera motion must start at the current position"
        }
        require(keyframes.zipWithNext().all { (a, b) ->
            val frames = (b.frameOffset - a.frameOffset).toDouble()
            frames > 0 && listOf(b.xDelta-a.xDelta, b.yDelta-a.yDelta, b.zDelta-a.zDelta).all {
                kotlin.math.abs(it) <= frames
            }
        }) { "Camera translation is limited to one block per rendered frame on each axis" }
        require(keyframes.zipWithNext().all { (a, b) -> a.frameOffset < b.frameOffset }) {
            "Camera keyframe offsets must be strictly increasing"
        }
        require(warmupFrames in 0..256) { "Camera warmup must be between 0 and 256 frames" }
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,47}").matches(artifactPrefix)) {
            "Camera artifact prefix must be a safe name of at most 48 characters"
        }
    }

    fun frameCount(): Int = keyframes.last().frameOffset + 1

    fun validateAnchor(yaw: Double, pitch: Double) {
        require(yaw.isFinite() && pitch.isFinite() && pitch in -90.0..90.0) { "Camera anchor is invalid" }
        require(keyframes.all { pitch + it.pitchDeltaDegrees in -90.0..90.0 }) {
            "Camera motion exceeds the absolute pitch limits [-90, 90] degrees"
        }
    }

    fun rotationAt(frameOffset: Int, yaw: Double, pitch: Double): Rotation {
        require(frameOffset in 0 until frameCount()) { "Camera frame offset is out of bounds" }
        val end = keyframes.indexOfFirst { it.frameOffset >= frameOffset }.coerceAtLeast(0)
        val b = keyframes[end]
        val a = keyframes[(end - 1).coerceAtLeast(0)]
        val fraction = if (a.frameOffset == b.frameOffset) 0.0 else
            (frameOffset - a.frameOffset).toDouble() / (b.frameOffset - a.frameOffset)
        return Rotation(
            yaw + a.yawDeltaDegrees + (b.yawDeltaDegrees - a.yawDeltaDegrees) * fraction,
            pitch + a.pitchDeltaDegrees + (b.pitchDeltaDegrees - a.pitchDeltaDegrees) * fraction,
        )
    }

    fun positionDeltaAt(frameOffset: Int): Position {
        require(frameOffset in 0 until frameCount()) { "Camera frame offset is out of bounds" }
        val end = keyframes.indexOfFirst { it.frameOffset >= frameOffset }.coerceAtLeast(0)
        val b = keyframes[end]
        val a = keyframes[(end - 1).coerceAtLeast(0)]
        val t = if (a.frameOffset == b.frameOffset) 0.0 else
            (frameOffset - a.frameOffset).toDouble() / (b.frameOffset - a.frameOffset)
        return Position(a.xDelta+(b.xDelta-a.xDelta)*t, a.yDelta+(b.yDelta-a.yDelta)*t,
            a.zDelta+(b.zDelta-a.zDelta)*t)
    }

    fun hasTranslation(): Boolean = keyframes.any { it.xDelta != 0.0 || it.yDelta != 0.0 || it.zDelta != 0.0 }

    @JvmRecord
    data class Position(val x: Double, val y: Double, val z: Double)

    fun artifactName(frameOffset: Int): String = "$artifactPrefix-${frameOffset.toString().padStart(4, '0')}"

    @JvmRecord
    data class Keyframe @JvmOverloads constructor(
        val frameOffset: Int, val yawDeltaDegrees: Double, val pitchDeltaDegrees: Double,
        val xDelta: Double = 0.0, val yDelta: Double = 0.0, val zDelta: Double = 0.0,
    ) {
        init {
            require(listOf(xDelta, yDelta, zDelta).all { it.isFinite() && it in -16.0..16.0 }) {
                "Camera XYZ offsets must be finite and within [-16, 16] blocks"
            }
            require(frameOffset in 0..239) { "Camera motion is limited to 240 trajectory frames" }
            require(yawDeltaDegrees.isFinite() && yawDeltaDegrees in -720.0..720.0) {
                "Camera yaw delta must be finite and within [-720, 720] degrees"
            }
            require(pitchDeltaDegrees.isFinite() && pitchDeltaDegrees in -180.0..180.0) {
                "Camera pitch delta must be finite and within [-180, 180] degrees"
            }
        }
    }

    @JvmRecord
    data class Rotation(val yawDegrees: Double, val pitchDegrees: Double)
}

@JvmRecord
data class CameraPose(val x: Double, val y: Double, val z: Double, val yawDegrees: Double, val pitchDegrees: Double) {
    init {
        require(listOf(x, y, z, yawDegrees, pitchDegrees).all(Double::isFinite)) { "Camera pose must be finite" }
        require(pitchDegrees in -90.0..90.0) { "Camera pitch is outside [-90, 90] degrees" }
    }

    companion object {
        @JvmStatic
        fun wrapYaw(value: Double): Double {
            require(value.isFinite()) { "Camera yaw must be finite" }
            return ((value % 360.0 + 540.0) % 360.0) - 180.0
        }
    }
}

@JvmRecord
data class CameraMotionSample @JvmOverloads constructor(
    val frameOffset: Int,
    val renderedFrameId: Long,
    val renderStartedNanos: Long,
    val renderEndedNanos: Long,
    val shaderTimeSeconds: Float,
    val plannedRotation: CameraMotionPlan.Rotation,
    val actualPose: CameraPose,
    val plannedPose: CameraPose = CameraPose(actualPose.x, actualPose.y, actualPose.z,
        plannedRotation.yawDegrees, plannedRotation.pitchDegrees),
)

@JvmRecord
data class CameraMotionResult(
    @field:DefensiveSnapshot val samples: List<CameraMotionSample>,
    @field:DefensiveSnapshot val captures: List<CaptureResult>,
)