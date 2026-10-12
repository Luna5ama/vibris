package dev.vibris.mod;

import dev.vibris.api.CameraMotionPlan;
import dev.vibris.api.CameraMotionResult;
import dev.vibris.api.CameraMotionSample;
import dev.vibris.api.CameraPose;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.CaptureResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** One client-thread sequence; every admitted frame has exactly one before/after pair. */
final class CameraMotionController implements AutoCloseable {
    interface Driver {
        void rotate(double yaw, double pitch);
        CameraPose actualPose();
        float shaderTimeSeconds();
        CaptureResult capture(int offset, long frameId) throws Exception;
        void restore() throws Exception;
    }

    private final CameraMotionPlan plan;
    private final double anchorYaw;
    private final double anchorPitch;
    private final Driver driver;
    private final CancellationToken cancellation;
    private final long deadline;
    private final CompletableFuture<CameraMotionResult> result = new CompletableFuture<>();
    private final List<CameraMotionSample> samples = new ArrayList<>();
    private final List<CaptureResult> captures = new ArrayList<>();
    private long expectedFrame;
    private long pendingFrame = -1;
    private long startedNanos;
    private long lastStartedNanos = Long.MIN_VALUE;
    private int elapsedFrames;
    private CameraMotionPlan.Rotation rotation;
    private boolean finished;

    CameraMotionController(CameraMotionPlan plan, double yaw, double pitch, long anchorFrame,
        long deadline, CancellationToken cancellation, Driver driver) {
        plan.validateAnchor(yaw, pitch);
        this.plan = plan;
        this.anchorYaw = yaw;
        this.anchorPitch = pitch;
        this.expectedFrame = Math.incrementExact(anchorFrame);
        this.deadline = deadline;
        this.cancellation = cancellation;
        this.driver = driver;
    }

    CompletableFuture<CameraMotionResult> result() { return result; }

    void check(long now) {
        if (finished) return;
        if (cancellation.isCancellationRequested()) {
            finish(new CancellationException("Camera motion was cancelled"));
        } else if (now - deadline >= 0) {
            finish(new IllegalStateException("Camera motion timed out"));
        }
    }

    void beforeFrame(long frameId, long now) {
        check(now);
        if (finished) return;
        try {
            if (pendingFrame != -1 || frameId != expectedFrame || now < lastStartedNanos) {
                throw new IllegalStateException("Camera render frame alignment was lost");
            }
            int offset = Math.max(0, elapsedFrames - plan.warmupFrames());
            rotation = plan.rotationAt(offset, anchorYaw, anchorPitch);
            driver.rotate(rotation.yawDegrees(), rotation.pitchDegrees());
            pendingFrame = frameId;
            startedNanos = now;
            lastStartedNanos = now;
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    void afterFrame(long frameId, long now) {
        check(now);
        if (finished) return;
        try {
            if (pendingFrame != frameId || frameId != expectedFrame || now < startedNanos) {
                throw new IllegalStateException("Camera capture does not match the rendered frame");
            }
            if (elapsedFrames >= plan.warmupFrames()) {
                int offset = elapsedFrames - plan.warmupFrames();
                CameraPose actual = driver.actualPose();
                if (Math.abs(CameraPose.wrapYaw(actual.yawDegrees() - rotation.yawDegrees())) > 0.001 ||
                    Math.abs(actual.pitchDegrees() - rotation.pitchDegrees()) > 0.001) {
                    throw new IllegalStateException("Rendered camera did not follow the requested rotation");
                }
                float shaderTime = driver.shaderTimeSeconds();
                float expectedTime = (elapsedFrames + 1L) * (1.0F / 60.0F);
                if (!Float.isFinite(shaderTime) || Math.abs(shaderTime - expectedTime) > 0.00001F) {
                    throw new IllegalStateException("Camera shader clock alignment was lost");
                }
                CaptureResult captured = driver.capture(offset, frameId);
                if (captured.frameId() != frameId) throw new IllegalStateException("Camera capture frame mismatch");
                samples.add(new CameraMotionSample(offset, frameId, startedNanos, now,
                    shaderTime, rotation, actual));
                captures.add(captured);
            }
            pendingFrame = -1;
            expectedFrame = Math.incrementExact(expectedFrame);
            elapsedFrames++;
            if (elapsedFrames == plan.warmupFrames() + plan.frameCount()) finish(null);
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void finish(Throwable failure) {
        if (finished) return;
        finished = true;
        try {
            driver.restore();
        } catch (Throwable cleanup) {
            if (failure != null) cleanup.addSuppressed(failure);
            failure = cleanup;
        }
        if (failure == null) result.complete(new CameraMotionResult(List.copyOf(samples), List.copyOf(captures)));
        else result.completeExceptionally(failure);
    }

    @Override public void close() { finish(new CancellationException("Camera motion was closed")); }
}