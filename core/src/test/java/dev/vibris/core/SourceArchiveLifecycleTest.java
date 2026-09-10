package dev.vibris.core;

import dev.vibris.api.CancellationToken;
import dev.vibris.protocol.v2.ErrorCode;
import dev.vibris.protocol.v2.PreparedSourceRef;
import dev.vibris.protocol.v2.VcsCheckoutState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceArchiveLifecycleTest {
    private static final String CROSS_LANGUAGE_HASH =
        "090d0522c1a199a333ac1d81ba7463c48f51004780ba893b0da40a414c0fd14b";
    @TempDir
    Path temp;

    @Test
    void firstActivationMaterializesUnicodeBinaryAndEmptyEntriesOnce() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending"));
        String uuid = UUID.randomUUID().toString();
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("lib/中文/" + "long-name-".repeat(16) + ".bin", new byte[]{0, 1, -1, 42});
        files.put("empty.glsl", new byte[0]);
        PreparedSourceRef reference = SourceTestArchive.source(pending, uuid, files, List.of("empty-dir"),
            VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED, "main");
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        SourceRegistry.Lease lease = accept(registry, reference);
        SourceActivator activator = new SourceActivator(registry, new NoOpLink());

        assertTrue(Files.isRegularFile(lease.archive()));
        assertFalse(Files.exists(lease.directory()));
        SourceActivator.Activation activation = activator.begin(lease);
        activator.commit(activation);
        Path binary = lease.directory().resolve(files.keySet().iterator().next());
        assertArrayEquals(new byte[]{0, 1, -1, 42}, Files.readAllBytes(binary));
        assertTrue(Files.isDirectory(lease.directory().resolve("empty-dir")));
        FileTime marker = FileTime.fromMillis(1_234_000);
        Files.setLastModifiedTime(binary, marker);

        registry.materialize(lease, CancellationToken.none(), Long.MAX_VALUE);

        assertEquals(marker, Files.getLastModifiedTime(binary));
    }

    @Test
    void cancelledMaterializationDeletesTreeAndRetriesFromArchive() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("cancel-pending"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        SourceRegistry.Lease lease = accept(registry, SourceTestArchive.source(pending, "retry"));
        CancellationToken.Source cancellation = CancellationToken.source();
        cancellation.cancel();

        SourceRegistry.Failure failure = assertThrows(SourceRegistry.Failure.class,
            () -> registry.materialize(lease, cancellation.token(), Long.MAX_VALUE));

        assertEquals(ErrorCode.ERROR_CODE_CANCELLED, failure.code);
        assertFalse(Files.exists(lease.directory()));
        assertTrue(Files.isRegularFile(lease.archive()));
        registry.materialize(lease, CancellationToken.none(), Long.MAX_VALUE);
        assertTrue(Files.isRegularFile(lease.directory().resolve("main.glsl")));
    }

    @Test
    void unsafeOrMismatchedArchivesNeverReplaceTheActiveSource() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("invalid-pending"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe(), 1024, 32);
        SourceActivator activator = new SourceActivator(registry, new NoOpLink());
        SourceRegistry.Lease active = accept(registry, SourceTestArchive.source(pending, "active"));
        SourceActivator.Activation first = activator.begin(active);
        activator.commit(first);
        activator.release(List.of(active));

        PreparedSourceRef traversal = SourceTestArchive.source(pending, "safe");
        traversal = SourceTestArchive.replaceArchive(pending, traversal,
            tar -> SourceTestArchive.file(tar, "../escape.glsl", new byte[]{1}));
        assertRejectedWithoutSwitch(registry, activator, traversal, active);

        String duplicateUuid = UUID.randomUUID().toString();
        PreparedSourceRef duplicate = SourceTestArchive.source(pending, duplicateUuid,
            Map.of("A.glsl", new byte[]{1}, "a.glsl", new byte[]{2}), List.of(),
            VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED, "main");
        assertRejectedWithoutSwitch(registry, activator, duplicate, active);

        PreparedSourceRef wrongHash = SourceTestArchive.source(pending, "hash").toBuilder()
            .setSnapshotSha256("0".repeat(64)).build();
        assertRejectedWithoutSwitch(registry, activator, wrongHash, active);

        PreparedSourceRef oversized = SourceTestArchive.source(pending, "12345678").toBuilder()
            .setTotalBytes(4).build();
        assertRejectedWithoutSwitch(registry, activator, oversized, active);

        PreparedSourceRef corrupted = SourceTestArchive.source(pending, "checksum");
        Path corruptedArchive = pending.resolve(corrupted.getSourceUuid()).resolve(OwnedSourceTree.ARCHIVE_NAME);
        byte[] compressed = Files.readAllBytes(corruptedArchive);
        compressed[compressed.length - 1] ^= 0x5a;
        Files.write(corruptedArchive, compressed);
        assertRejectedWithoutSwitch(registry, activator, corrupted, active);
    }

    @Test
    void emptyDirectoryOnlyArchiveHasStableEmptySourceHash() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("empty-pending"));
        String uuid = UUID.randomUUID().toString();
        PreparedSourceRef reference = SourceTestArchive.source(pending, uuid, Map.of(), List.of("only-dir"),
            VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED, "main");
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        SourceRegistry.Lease lease = accept(registry, reference);

        registry.materialize(lease, CancellationToken.none(), Long.MAX_VALUE);

        assertTrue(Files.isDirectory(lease.directory().resolve("only-dir")));
        assertEquals(0, reference.getFileCount());
        assertEquals(64, reference.getSnapshotSha256().length());
    }

    @Test
    void sourceHashMatchesTheCrossLanguageUtf8Vector() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("hash-pending"));
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("empty.glsl", new byte[0]);
        files.put("lib/中文/data.bin", new byte[]{0, 1, -1, 42});
        files.put("supplementary/🚀.glsl", "rocket".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PreparedSourceRef reference = SourceTestArchive.source(pending, UUID.randomUUID().toString(), files,
            List.of(), VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED, "main");
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        SourceRegistry.Lease lease = accept(registry, reference);

        registry.materialize(lease, CancellationToken.none(), Long.MAX_VALUE);

        assertEquals(CROSS_LANGUAGE_HASH, reference.getSnapshotSha256());
        assertEquals(CROSS_LANGUAGE_HASH, lease.snapshotSha256());
    }

    private static SourceRegistry.Lease accept(SourceRegistry registry, PreparedSourceRef reference) throws Exception {
        List<SourceRegistry.Lease> leases = registry.reserve(registry.validate(List.of(reference)));
        registry.accept(leases);
        return leases.getFirst();
    }

    private static void assertRejectedWithoutSwitch(
        SourceRegistry registry,
        SourceActivator activator,
        PreparedSourceRef reference,
        SourceRegistry.Lease active
    ) throws Exception {
        SourceRegistry.Lease candidate = accept(registry, reference);
        assertThrows(SourceActivator.Failure.class, () -> activator.begin(candidate));
        assertEquals(active.uuid(), registry.activeUuid());
        assertFalse(Files.exists(candidate.directory()));
        assertTrue(Files.isRegularFile(candidate.archive()));
        activator.release(List.of(candidate));
    }

    private static final class NoOpLink implements ShaderLink {
        @Override
        public void switchTo(SourceRegistry.Lease source, OwnershipCheck ownership) throws Failure {
            ownership.verify();
        }

        @Override
        public void detach() {
        }

        @Override
        public boolean retainsActiveSource() {
            return true;
        }
    }
}
