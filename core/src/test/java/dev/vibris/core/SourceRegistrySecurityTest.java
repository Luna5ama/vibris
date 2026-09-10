package dev.vibris.core;

import dev.vibris.protocol.v2.ErrorCode;
import dev.vibris.protocol.v2.PreparedSourceRef;
import dev.vibris.protocol.v2.VcsCheckoutState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceRegistrySecurityTest {
    @TempDir
    Path temp;

    @Test
    void sourceFreeJobsAcceptAnEmptySourceSet() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-source-free"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());

        assertTrue(registry.validate(List.of(), 0).isEmpty());
    }

    @Test
    void multipleSourcesAreRejectedAtValidationBoundary() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-multiple"));
        PreparedSourceRef first = source(pending);
        PreparedSourceRef second = source(pending);
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());

        SourceRegistry.Failure failure = assertThrows(
            SourceRegistry.Failure.class, () -> registry.validate(List.of(first, second)));

        assertEquals(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, failure.code);
    }

    @Test
    void checkoutStateRequiresExactBranchShape() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-checkout-state"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        PreparedSourceRef attached = source(pending);

        assertEquals(1, registry.validate(List.of(attached)).size());
        assertEquals(1, registry.validate(List.of(attached.toBuilder()
            .setVcsCheckoutState(VcsCheckoutState.VCS_CHECKOUT_STATE_DETACHED)
            .clearBranch()
            .setStartHead("a".repeat(40))
            .build())).size());

        SourceRegistry.Failure unspecified = assertThrows(SourceRegistry.Failure.class, () ->
            registry.validate(List.of(attached.toBuilder()
                .setVcsCheckoutState(VcsCheckoutState.VCS_CHECKOUT_STATE_UNSPECIFIED)
                .build())));
        SourceRegistry.Failure attachedWithoutBranch = assertThrows(SourceRegistry.Failure.class, () ->
            registry.validate(List.of(attached.toBuilder().clearBranch().build())));
        SourceRegistry.Failure detachedWithBranch = assertThrows(SourceRegistry.Failure.class, () ->
            registry.validate(List.of(attached.toBuilder()
                .setVcsCheckoutState(VcsCheckoutState.VCS_CHECKOUT_STATE_DETACHED)
                .setStartHead("a".repeat(40))
                .build())));
        SourceRegistry.Failure detachedWithoutExactHead = assertThrows(SourceRegistry.Failure.class, () ->
            registry.validate(List.of(attached.toBuilder()
                .setVcsCheckoutState(VcsCheckoutState.VCS_CHECKOUT_STATE_DETACHED)
                .clearBranch()
                .setStartHead("HEAD")
                .build())));

        assertEquals(ErrorCode.ERROR_CODE_INVALID_SOURCE, unspecified.code);
        assertEquals(ErrorCode.ERROR_CODE_INVALID_SOURCE, attachedWithoutBranch.code);
        assertEquals(ErrorCode.ERROR_CODE_INVALID_SOURCE, detachedWithBranch.code);
        assertEquals(ErrorCode.ERROR_CODE_INVALID_SOURCE, detachedWithoutExactHead.code);
    }

    @Test
    void reparsePendingRootIsRejectedBeforeTraversal() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        String uuid = UUID.randomUUID().toString();
        PreparedSourceRef reference = SourceTestArchive.source(outside, uuid, "outside",
            VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED, "main");
        Path pending = Files.createSymbolicLink(temp.resolve("pending"), outside);
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());

        SourceRegistry.Failure failure = assertThrows(
            SourceRegistry.Failure.class, () -> registry.validate(List.of(reference)));
        assertEquals(ErrorCode.ERROR_CODE_SOURCE_CONTAINS_REPARSE_POINT, failure.code);
    }

    @Test
    void reservationRechecksExclusiveUuidOwnership() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-ordinary"));
        String uuid = UUID.randomUUID().toString();
        PreparedSourceRef reference = SourceTestArchive.source(pending, uuid, "ordinary",
            VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED, "main");
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        List<SourceRegistry.Candidate> first = registry.validate(List.of(reference));
        List<SourceRegistry.Candidate> second = registry.validate(List.of(reference));
        List<SourceRegistry.Lease> reservation = registry.reserve(first);

        SourceRegistry.Failure failure = assertThrows(
            SourceRegistry.Failure.class, () -> registry.reserve(second));
        assertEquals(ErrorCode.ERROR_CODE_INVALID_SOURCE, failure.code);
        registry.reject(reservation);
    }

    @Test
    void contentValidationIsDeferredUntilMaterialization() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-content-mutation"));
        PreparedSourceRef reference = source(pending);
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        List<SourceRegistry.Candidate> candidates = registry.validate(List.of(reference));
        Path archive = pending.resolve(reference.getSourceUuid()).resolve(OwnedSourceTree.ARCHIVE_NAME);
        byte[] corrupted = Files.readAllBytes(archive);
        corrupted[corrupted.length / 2] ^= 0x55;
        Files.write(archive, corrupted);
        SourceRegistry.Lease lease = registry.reserve(candidates).getFirst();
        registry.accept(List.of(lease));

        SourceRegistry.Failure failure = assertThrows(SourceRegistry.Failure.class, () ->
            registry.materialize(lease, dev.vibris.api.CancellationToken.none(), Long.MAX_VALUE));
        assertEquals(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, failure.code);
        assertFalse(Files.exists(lease.directory()));
    }

    @Test
    void cleanupDoesNotFollowPendingRootReplacedAfterReservation() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-reserved"));
        String uuid = UUID.randomUUID().toString();
        PreparedSourceRef reference = SourceTestArchive.source(pending, uuid, "reserved",
            VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED, "main");
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        List<SourceRegistry.Lease> reservation = registry.reserve(registry.validate(List.of(reference)));
        registry.accept(reservation);

        Files.move(pending, temp.resolve("original-pending"));
        Path outside = Files.createDirectory(temp.resolve("outside-reserved"));
        Path outsideSource = Files.createDirectory(outside.resolve(uuid));
        Path sentinel = Files.writeString(outsideSource.resolve("sentinel.txt"), "outside");
        Files.createSymbolicLink(pending, outside);

        registry.cleanup(reservation);

        assertTrue(Files.exists(sentinel), "cleanup followed a replaced pending-root ancestor");
        assertEquals(0, registry.size(), "unsafe cleanup must not wedge source capacity");
    }

    @Test
    void deletingActiveSourceDoesNotPoisonNextActivation() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-active-delete"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        SourceRegistry.Lease first = registry.reserve(registry.validate(List.of(source(pending)))).getFirst();
        registry.accept(List.of(first));
        registry.materialize(first, dev.vibris.api.CancellationToken.none(), Long.MAX_VALUE);
        registry.commitActivation(registry.beginActivation(first));

        Files.delete(first.directory().resolve("main.glsl"));
        Files.delete(first.directory());
        registry.release(List.of(first), true);

        assertEquals("", registry.activeUuid());
        assertEquals(0, registry.size());

        SourceRegistry.Lease second = registry.reserve(registry.validate(List.of(source(pending)))).getFirst();
        registry.accept(List.of(second));
        registry.materialize(second, dev.vibris.api.CancellationToken.none(), Long.MAX_VALUE);
        registry.commitActivation(registry.beginActivation(second));

        assertEquals(second.uuid(), registry.activeUuid());
        registry.release(List.of(second), false);
        assertFalse(Files.exists(second.directory()));
    }

    @Test
    void activeContentMutationInvalidatesSnapshotReceipt() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-active-mutation"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        SourceRegistry.Lease lease = registry.reserve(registry.validate(List.of(source(pending)))).getFirst();
        registry.accept(List.of(lease));
        registry.materialize(lease, dev.vibris.api.CancellationToken.none(), Long.MAX_VALUE);
        registry.commitActivation(registry.beginActivation(lease));
        Files.writeString(lease.directory().resolve("main.glsl"), "x".repeat(36));

        SourceRegistry.Failure failure = assertThrows(SourceRegistry.Failure.class, registry::requireActiveOwned);

        assertEquals(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, failure.code);
    }

    private static PreparedSourceRef source(Path pending) throws Exception {
        String uuid = UUID.randomUUID().toString();
        return SourceTestArchive.source(pending, uuid, uuid,
            VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED, "main");
    }
}
