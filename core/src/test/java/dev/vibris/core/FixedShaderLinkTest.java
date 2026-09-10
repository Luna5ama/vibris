package dev.vibris.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class FixedShaderLinkTest {
    @TempDir
    Path temp;

    @Test
    void prepareUnlinksStaleSymlinkWithoutFollowingTarget() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending"));
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("sentinel.txt"), "outside");
        Files.createSymbolicLink(shaderpack.resolve("shaders"), outside);
        FixedShaderLink link = new FixedShaderLink(pending, shaderpack);

        // When
        link.prepare();

        // Then
        assertFalse(Files.exists(shaderpack.resolve("shaders"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(sentinel));
    }

    @Test
    void switchCreatesTrueDirectorySymlinkAndPreservesOldLinkOnOwnershipFailure() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending-switch"));
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack-switch"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        SourceRegistry.Lease sourceA = source(registry, pending, "A");
        SourceRegistry.Lease sourceB = source(registry, pending, "B");
        FixedShaderLink link = new FixedShaderLink(pending, shaderpack);
        link.prepare();
        link.switchTo(sourceA, () -> { });

        // When
        assertThrows(ShaderLink.Failure.class, () -> link.switchTo(sourceB, () -> {
            throw new ShaderLink.Failure("injected ownership failure", true);
        }));

        // Then
        Path active = shaderpack.resolve("shaders");
        assertTrue(Files.isSymbolicLink(active));
        assertEquals(sourceA.directory(), Files.readSymbolicLink(active));
        assertFalse(Files.exists(shaderpack.resolve("shaders.next." + sourceB.uuid()),
            java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void guardedReplacementHandlesUnavailableAtomicMove() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending-fallback"));
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack-fallback"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        SourceRegistry.Lease sourceA = source(registry, pending, "A");
        SourceRegistry.Lease sourceB = source(registry, pending, "B");
        FixedShaderLink link = new FixedShaderLink(pending, shaderpack, (source, target) -> {
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected");
        });
        link.prepare();
        link.switchTo(sourceA, () -> { });

        // When
        link.switchTo(sourceB, () -> { });

        // Then
        assertEquals(sourceB.directory(), link.currentTarget());
        assertTrue(Files.isDirectory(sourceB.directory()));
    }

    @Test
    void ordinaryDirectoryAtActivePathFailsClosed() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending-directory"));
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack-directory"));
        Path active = Files.createDirectory(shaderpack.resolve("shaders"));
        Path sentinel = Files.writeString(active.resolve("sentinel.txt"), "owned by user");
        FixedShaderLink link = new FixedShaderLink(pending, shaderpack);

        // When
        ShaderLink.Failure failure = assertThrows(ShaderLink.Failure.class, link::prepare);

        // Then
        assertTrue(failure.stable());
        assertTrue(Files.isRegularFile(sentinel));
    }

    @Test
    void replacedActiveSymlinkFailsSwitchWithoutTouchingReplacementTarget() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-replaced-link"));
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack-replaced-link"));
        Path outside = Files.createDirectory(temp.resolve("outside-replaced-link"));
        Path sentinel = Files.writeString(outside.resolve("sentinel.txt"), "outside");
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        SourceRegistry.Lease sourceA = source(registry, pending, "A");
        SourceRegistry.Lease sourceB = source(registry, pending, "B");
        FixedShaderLink link = new FixedShaderLink(pending, shaderpack);
        link.prepare();
        link.switchTo(sourceA, () -> { });
        Path active = shaderpack.resolve("shaders");
        Files.delete(active);
        Files.createSymbolicLink(active, outside);

        ShaderLink.Failure failure = assertThrows(
            ShaderLink.Failure.class, () -> link.switchTo(sourceB, () -> { }));

        assertFalse(failure.stable());
        assertEquals(outside, Files.readSymbolicLink(active));
        assertTrue(Files.isRegularFile(sentinel));
        assertFalse(Files.exists(shaderpack.resolve("shaders.next." + sourceB.uuid()),
            java.nio.file.LinkOption.NOFOLLOW_LINKS));

        ShaderLink.Failure detachFailure = assertThrows(ShaderLink.Failure.class, link::detach);
        assertFalse(detachFailure.stable());
        assertFalse(Files.exists(active, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(sentinel));
    }

    @Test
    void ordinaryReplacementFailsRetentionAndDetachWithoutDeletingIt() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-replaced-directory"));
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack-replaced-directory"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        SourceRegistry.Lease source = source(registry, pending, "A");
        FixedShaderLink link = new FixedShaderLink(pending, shaderpack);
        link.prepare();
        link.switchTo(source, () -> { });
        Path active = shaderpack.resolve("shaders");
        Files.delete(active);
        Files.createDirectory(active);
        Path sentinel = Files.writeString(active.resolve("sentinel.txt"), "user");

        ShaderLink.Failure retainFailure = assertThrows(ShaderLink.Failure.class, link::retainsActiveSource);
        ShaderLink.Failure detachFailure = assertThrows(ShaderLink.Failure.class, link::detach);

        assertFalse(retainFailure.stable());
        assertFalse(detachFailure.stable());
        assertTrue(Files.isRegularFile(sentinel));
    }

    private static SourceRegistry.Lease source(SourceRegistry registry, Path pending, String marker) throws Exception {
        String uuid = UUID.randomUUID().toString();
        var reference = SourceTestArchive.source(pending, uuid, marker,
            dev.vibris.protocol.v2.VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED, "main");
        List<SourceRegistry.Lease> leases = registry.reserve(registry.validate(List.of(reference)));
        registry.accept(leases);
        registry.materialize(leases.getFirst(), dev.vibris.api.CancellationToken.none(), Long.MAX_VALUE);
        return leases.getFirst();
    }
}
