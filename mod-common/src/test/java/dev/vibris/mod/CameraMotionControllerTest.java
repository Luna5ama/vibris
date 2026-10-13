package dev.vibris.mod;

import dev.vibris.api.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CameraMotionControllerTest {
    static CameraMotionController translating(Driver d, AtomicBoolean cancelled) {
        var p=new CameraMotionPlan(List.of(new CameraMotionPlan.Keyframe(0,0,0),
            new CameraMotionPlan.Keyframe(4,0,0,2,1,-2),new CameraMotionPlan.Keyframe(8,0,0)),0,"move");
        return new CameraMotionController(p,179,-14,100,1000,cancelled::get,d);
    }
    @Test void xyzMovementExportsActualAndPlannedPositionsAndRestoresOnSuccess() {
        Driver d=new Driver();var c=translating(d,new AtomicBoolean());
        for(int i=0;i<9;i++)frame(c,101+i,10*i);
        var samples=c.result().join().samples();assertEquals(9,samples.size());
        assertEquals(3,samples.get(4).actualPose().x());
        assertEquals(3,samples.get(4).actualPose().y());
        assertEquals(1,samples.get(4).actualPose().z());
        for(var s:samples){assertEquals(s.plannedPose().x(),s.actualPose().x());
            assertEquals(s.plannedPose().y(),s.actualPose().y());assertEquals(s.plannedPose().z(),s.actualPose().z());}
        restored(d);
    }
    @Test void wrongPositionStopsBeforeReadbackAndRestores() {
        Driver d=new Driver();d.ignoreTranslation=true;var c=translating(d,new AtomicBoolean());
        frame(c,101,0);frame(c,102,10);
        assertThrows(CompletionException.class,()->c.result().join());assertEquals(1,d.captures);restored(d);
    }
    @Test void movedPositionRestoresAfterCancellationTimeoutAndReadbackError() {
        for(int reason=0;reason<3;reason++){
            Driver d=new Driver();var cancelled=new AtomicBoolean();var c=translating(d,cancelled);
            frame(c,101,0);frame(c,102,10);assertTrue(d.x>1);
            if(reason==0){cancelled.set(true);c.check(30);assertThrows(CancellationException.class,()->c.result().join());}
            else {if(reason==1)c.check(1000);else{d.captureFailure=true;frame(c,103,20);}
                assertThrows(CompletionException.class,()->c.result().join());}
            restored(d);
        }
    }

    static class Driver implements CameraMotionController.Driver {
        double yaw = 179.0, pitch = -14.0;
        double x=1, y=2, z=3;
        boolean ignoreTranslation;
        public void translate(double dx,double dy,double dz) { if(!ignoreTranslation){x=1+dx;y=2+dy;z=3+dz;} }
        int restores, captures, rotations;
        boolean wrongFrame, wrongPose, wrongClock, captureFailure, restoreFailure;
        public void rotate(double y, double p) { yaw = y; pitch = p; rotations++; }
        public float shaderTimeSeconds() { return wrongClock ? Float.NaN : rotations * (1.0F / 60.0F); }
        public CameraPose actualPose() { return new CameraPose(x, y, z, yaw + (wrongPose ? 1 : 0), pitch); }
        public CaptureResult capture(int offset, long frame) {
            captures++;
            if (captureFailure) throw new IllegalStateException("readback failed");
            return new CaptureResult(frame + (wrongFrame ? 1 : 0), List.of());
        }
        public void restore() {
            restores++; yaw = 179.0; pitch = -14.0; x=1;y=2;z=3;
            if (restoreFailure) throw new IllegalStateException("restore failed");
        }
    }
    static CameraMotionPlan plan(int warmup) {
        return new CameraMotionPlan(List.of(new CameraMotionPlan.Keyframe(0, 0, 0),
            new CameraMotionPlan.Keyframe(2, 8, 4)), warmup, "turn");
    }
    static CameraMotionController controller(Driver driver, AtomicBoolean cancelled, int warmup) {
        return new CameraMotionController(plan(warmup), 179, -14, 100, 1000, cancelled::get, driver);
    }
    static void frame(CameraMotionController controller, long id, long now) {
        controller.beforeFrame(id, now); controller.afterFrame(id, now + 1);
    }
    static void restored(Driver driver) {
        assertEquals(1,driver.x);assertEquals(2,driver.y);assertEquals(3,driver.z);
        assertEquals(1, driver.restores); assertEquals(179.0, driver.yaw); assertEquals(-14.0, driver.pitch);
    }

    @Test void capturesEveryTrajectoryFrameAfterWarmupWithAlignedPoseAndTime() {
        Driver driver = new Driver();
        CameraMotionController c = controller(driver, new AtomicBoolean(), 1);
        for (int i = 0; i < 4; i++) frame(c, 101 + i, i * 10);
        CameraMotionResult result = c.result().join();
        assertEquals(3, result.samples().size()); assertEquals(3, driver.captures);
        for (int i = 0; i < 3; i++) {
            var sample = result.samples().get(i);
            assertEquals(i, sample.frameOffset()); assertEquals(102 + i, sample.renderedFrameId());
            assertEquals(sample.renderedFrameId(), result.captures().get(i).frameId());
            assertEquals((i + 2) * (1.0F / 60.0F), sample.shaderTimeSeconds());
            assertEquals(sample.renderStartedNanos() + 1, sample.renderEndedNanos());
            assertEquals(179 + i * 4.0, sample.actualPose().yawDegrees());
        }
        c.close(); restored(driver);
    }

    @Test void cancellationRestoresBeforePublishingTerminalFailure() {
        Driver driver = new Driver(); AtomicBoolean cancelled = new AtomicBoolean();
        CameraMotionController c = controller(driver, cancelled, 0);
        frame(c, 101, 0); cancelled.set(true); c.check(5);
        assertThrows(CancellationException.class, () -> c.result().join()); restored(driver);
        frame(c, 102, 10); c.close(); assertEquals(1, driver.captures); restored(driver);
    }

    @Test void timeoutWithoutFurtherRenderedFramesRestores() {
        Driver driver = new Driver(); CameraMotionController c = controller(driver, new AtomicBoolean(), 0);
        c.beforeFrame(101, 0); c.check(1000);
        assertThrows(CompletionException.class, () -> c.result().join()); restored(driver);
    }

    @Test void skippedFrameIsRejectedRatherThanSilentlySparseSampling() {
        Driver driver = new Driver(); CameraMotionController c = controller(driver, new AtomicBoolean(), 0);
        frame(c, 101, 0); c.beforeFrame(103, 10);
        assertThrows(CompletionException.class, () -> c.result().join()); restored(driver);
    }

    @Test void wrongCaptureFrameAndWrongActualPoseAreRejected() {
        for (boolean wrongFrame : List.of(false, true)) {
            Driver driver = new Driver(); driver.wrongFrame = wrongFrame; driver.wrongPose = !wrongFrame;
            CameraMotionController c = controller(driver, new AtomicBoolean(), 0); frame(c, 101, 0);
            assertThrows(CompletionException.class, () -> c.result().join()); restored(driver);
        }
    }

    @Test void readbackAndCleanupErrorsDoNotPublishSuccess() {
        for (boolean cleanup : List.of(false, true)) {
            Driver driver = new Driver(); driver.captureFailure = !cleanup; driver.restoreFailure = cleanup;
            CameraMotionController c = controller(driver, new AtomicBoolean(), 0);
            for (int i = 0; i < 3; i++) frame(c, 101 + i, 10 * i);
            assertThrows(CompletionException.class, () -> c.result().join()); restored(driver);
        }
    }

    @Test void invalidShaderClockCannotProduceAcceptedImages() {
        Driver driver = new Driver(); driver.wrongClock = true;
        CameraMotionController c = controller(driver, new AtomicBoolean(), 0); frame(c, 101, 0);
        assertThrows(CompletionException.class, () -> c.result().join());
        assertEquals(0, driver.captures); restored(driver);
    }

    @Test void cancellationBetweenBeforeAndAfterDoesNotCaptureAnUnownedFrame() {
        Driver driver = new Driver(); AtomicBoolean cancelled = new AtomicBoolean();
        CameraMotionController c = controller(driver, cancelled, 0);
        c.beforeFrame(101, 0); cancelled.set(true); c.afterFrame(101, 1);
        assertThrows(CancellationException.class, () -> c.result().join());
        assertEquals(0, driver.captures); restored(driver);
    }
}