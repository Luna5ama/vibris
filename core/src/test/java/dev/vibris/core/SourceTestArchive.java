package dev.vibris.core;

import com.github.luben.zstd.ZstdOutputStream;
import dev.vibris.protocol.v2.PreparedSourceRef;
import dev.vibris.protocol.v2.SourceArchiveFormat;
import dev.vibris.protocol.v2.VcsCheckoutState;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.BufferedOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class SourceTestArchive {
    private SourceTestArchive() {
    }

    static PreparedSourceRef source(Path pending, String marker) throws Exception {
        return source(pending, UUID.randomUUID().toString(), marker,
            VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED, "main");
    }

    static PreparedSourceRef source(
        Path pending,
        String uuid,
        String marker,
        VcsCheckoutState checkoutState,
        String branch
    ) throws Exception {
        byte[] content = marker.getBytes(StandardCharsets.UTF_8);
        return source(pending, uuid, Map.of("main.glsl", content), List.of(), checkoutState, branch);
    }

    static PreparedSourceRef source(
        Path pending,
        String uuid,
        Map<String, byte[]> files,
        List<String> directories,
        VcsCheckoutState checkoutState,
        String branch
    ) throws Exception {
        Path directory = Files.createDirectory(pending.resolve(uuid));
        Path archive = directory.resolve(OwnedSourceTree.ARCHIVE_NAME);
        write(archive, tar -> {
            for (String name : directories) directory(tar, name);
            for (var entry : files.entrySet()) file(tar, entry.getKey(), entry.getValue());
        });
        long totalBytes = files.values().stream().mapToLong(value -> value.length).sum();
        return PreparedSourceRef.newBuilder()
            .setSourceUuid(uuid)
            .setVcsCheckoutState(checkoutState)
            .setBranch(branch)
            .setFileCount(files.size())
            .setTotalBytes(totalBytes)
            .setSnapshotSha256(sourceHash(files.entrySet().stream()
                .map(entry -> new FileDigest(entry.getKey(), entry.getValue())).toList()))
            .setArchiveFormat(SourceArchiveFormat.SOURCE_ARCHIVE_FORMAT_TAR_ZSTD)
            .setCompressedBytes(Files.size(archive))
            .build();
    }

    static PreparedSourceRef replaceArchive(Path pending, PreparedSourceRef reference, TarWriter writer)
        throws Exception {
        Path archive = pending.resolve(reference.getSourceUuid()).resolve(OwnedSourceTree.ARCHIVE_NAME);
        Files.delete(archive);
        write(archive, writer);
        return reference.toBuilder().setCompressedBytes(Files.size(archive)).build();
    }

    static void file(TarArchiveOutputStream tar, String name, byte[] content) throws Exception {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(content.length);
        entry.setMode(0644);
        tar.putArchiveEntry(entry);
        tar.write(content);
        tar.closeArchiveEntry();
    }

    static void directory(TarArchiveOutputStream tar, String name) throws Exception {
        TarArchiveEntry entry = new TarArchiveEntry(name.endsWith("/") ? name : name + "/");
        entry.setSize(0);
        entry.setMode(0755);
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
    }

    private static void write(Path archive, TarWriter writer) throws Exception {
        try (var output = new BufferedOutputStream(Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW));
             var zstd = new ZstdOutputStream(output, 3).setChecksum(true);
             var tar = new TarArchiveOutputStream(zstd)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            writer.write(tar);
            tar.finish();
        }
    }

    private static String sourceHash(List<FileDigest> files) throws Exception {
        MessageDigest aggregate = MessageDigest.getInstance("SHA-256");
        aggregate.update("vibris-source-tree-v2\0".getBytes(StandardCharsets.UTF_8));
        files.stream().sorted((left, right) -> compareUtf8(left.path(), right.path())).forEach(file -> {
            aggregate.update((byte) 'F');
            byte[] path = file.path().getBytes(StandardCharsets.UTF_8);
            aggregate.update(ByteBuffer.allocate(Integer.BYTES).putInt(path.length).array());
            aggregate.update(path);
            aggregate.update(ByteBuffer.allocate(Long.BYTES).putLong(file.content().length).array());
            try {
                aggregate.update(MessageDigest.getInstance("SHA-256").digest(file.content()));
            } catch (java.security.NoSuchAlgorithmException failure) {
                throw new AssertionError(failure);
            }
        });
        return HexFormat.of().formatHex(aggregate.digest());
    }

    private static int compareUtf8(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < Math.min(a.length, b.length); index++) {
            int compared = Integer.compare(Byte.toUnsignedInt(a[index]), Byte.toUnsignedInt(b[index]));
            if (compared != 0) return compared;
        }
        return Integer.compare(a.length, b.length);
    }

    private record FileDigest(String path, byte[] content) {
    }

    @FunctionalInterface
    interface TarWriter {
        void write(TarArchiveOutputStream tar) throws Exception;
    }
}
