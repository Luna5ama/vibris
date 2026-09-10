package dev.vibris.core;

import dev.vibris.core.source.SourceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceActivatorTest {
    @TempDir
    Path temp;

    @Test
    void releasedActiveIsRetainedUntilSuccessfulSuccessorCommit() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        RecordingShaderLink link = new RecordingShaderLink();
        SourceActivator activator = new SourceActivator(registry, link);
        SourceRegistry.Lease sourceA = source(registry, pending, "A");
        SourceRegistry.Lease sourceB = source(registry, pending, "B");
        SourceRegistry.Lease sourceC = source(registry, pending, "C");

        SourceActivator.Activation activationA = activator.begin(sourceA);
        activator.commit(activationA);
        activator.release(List.of(sourceA));

        // When
        SourceActivator.Activation activationB = activator.begin(sourceB);
        assertTrue(activator.rollback(activationB));
        activator.fail(activationB);
        activator.release(List.of(sourceB));

        // Then
        assertEquals(SourceState.RELEASED_ACTIVE, sourceA.record().state());
        assertTrue(Files.isDirectory(sourceA.directory()));
        assertFalse(Files.exists(sourceB.directory()));
        assertEquals(sourceA.uuid(), registry.activeUuid());
        assertEquals(List.of(sourceA.uuid(), sourceB.uuid(), sourceA.uuid()), link.switches);

        SourceActivator.Activation activationC = activator.begin(sourceC);
        activator.commit(activationC);
        assertFalse(Files.exists(sourceA.directory()));
        assertEquals(sourceC.uuid(), registry.activeUuid());
    }

    @Test
    void rollbackFailureKeepsPreviousSourceAndMarksActivatorNotReady() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending-failed"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        RecordingShaderLink link = new RecordingShaderLink();
        SourceActivator activator = new SourceActivator(registry, link);
        SourceRegistry.Lease sourceA = source(registry, pending, "A");
        SourceRegistry.Lease sourceB = source(registry, pending, "B");
        SourceActivator.Activation activationA = activator.begin(sourceA);
        activator.commit(activationA);
        activator.release(List.of(sourceA));
        SourceActivator.Activation activationB = activator.begin(sourceB);
        link.failSource = sourceA.uuid();

        // When
        boolean restored = activator.rollback(activationB);
        activator.fail(activationB);
        activator.release(List.of(sourceB));

        // Then
        assertFalse(restored);
        assertFalse(activator.ready());
        assertEquals(SourceState.RELEASED_ACTIVE, sourceA.record().state());
        assertTrue(Files.isDirectory(sourceA.directory()));
        assertFalse(Files.exists(sourceB.directory()));
    }

    @Test
    void readinessDoesNotWaitForActiveSourceVerification() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending-readiness"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        RecordingShaderLink link = new RecordingShaderLink();
        SourceActivator activator = new SourceActivator(registry, link);
        SourceRegistry.Lease source = source(registry, pending, "active");
        SourceActivator.Activation activation = activator.begin(source);
        activator.commit(activation);
        link.blockRetainsActiveSource = true;

        CompletableFuture<SourceRegistry.Lease> verification = CompletableFuture.supplyAsync(() -> {
            try {
                return activator.verifyActiveSource();
            } catch (SourceActivator.Failure failure) {
                throw new RuntimeException(failure);
            }
        });
        assertTrue(link.retainsActiveSourceEntered.await(5, TimeUnit.SECONDS));

        // When / Then
        CompletableFuture<Boolean> readiness = CompletableFuture.supplyAsync(activator::ready);
        try {
            assertTrue(readiness.get(1, TimeUnit.SECONDS));
        } finally {
            link.releaseRetainsActiveSource.countDown();
        }
        assertEquals(source, verification.get(5, TimeUnit.SECONDS));
    }

    private static SourceRegistry.Lease source(SourceRegistry registry, Path pending, String marker) throws Exception {
        String uuid = UUID.randomUUID().toString();
        var reference = SourceTestArchive.source(pending, uuid, marker,
            dev.vibris.protocol.v2.VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED, "main");
        List<SourceRegistry.Lease> leases = registry.reserve(registry.validate(List.of(reference)));
        registry.accept(leases);
        return leases.getFirst();
    }

    private static final class RecordingShaderLink implements ShaderLink {
        private final List<String> switches = new ArrayList<>();
        private final CountDownLatch retainsActiveSourceEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRetainsActiveSource = new CountDownLatch(1);
        private String failSource;
        private boolean blockRetainsActiveSource;

        @Override
        public void switchTo(SourceRegistry.Lease source, OwnershipCheck ownership) throws Failure {
            ownership.verify();
            switches.add(source.uuid());
            if (source.uuid().equals(failSource)) {
                throw new Failure("injected switch failure", true);
            }
        }

        @Override
        public void detach() {
        }

        @Override
        public boolean retainsActiveSource() throws Failure {
            if (blockRetainsActiveSource) {
                retainsActiveSourceEntered.countDown();
                try {
                    releaseRetainsActiveSource.await();
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw new Failure("interrupted active-source verification", true, interruption);
                }
            }
            return true;
        }
    }
}
