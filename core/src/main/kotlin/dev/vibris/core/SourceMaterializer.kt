package dev.vibris.core

import com.github.luben.zstd.ZstdInputStream
import dev.vibris.api.CancellationToken
import dev.vibris.protocol.v2.ErrorCode
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.utils.CountingInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException

internal class SourceMaterializer(private val trees: OwnedSourceTree) {
    @Throws(SourceRegistry.Failure::class)
    fun materialize(lease: SourceRegistry.Lease, cancellation: CancellationToken, deadline: Long): Path {
        synchronized(lease.materialization) {
            if (lease.materialization.ready) return lease.treeDirectory
            if (Files.exists(lease.treeDirectory)) OwnedSourceTree.delete(lease.treeDirectory)
            try {
                trees.requireSafeRoot()
                if (!trees.stillOwned(lease.ownedDirectory, lease.archive, lease.ownership)) changed()
                Files.createDirectory(lease.treeDirectory)
                val result = extract(lease, cancellation, deadline)
                if (result.fileCount != lease.reference.fileCount || result.totalBytes != lease.reference.totalBytes ||
                    result.sourceSha256 != lease.snapshotSha256 ||
                    !trees.stillOwned(lease.ownedDirectory, lease.archive, lease.ownership)
                ) changed()
                lease.materialization.ready = true
                return lease.treeDirectory
            } catch (failure: SourceRegistry.Failure) {
                OwnedSourceTree.delete(lease.treeDirectory)
                throw failure
            } catch (_: CancellationException) {
                OwnedSourceTree.delete(lease.treeDirectory)
                throw SourceRegistry.Failure(ErrorCode.ERROR_CODE_CANCELLED, "Source extraction was cancelled.")
            } catch (failure: IOException) {
                OwnedSourceTree.delete(lease.treeDirectory)
                throw SourceRegistry.Failure(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED,
                    failure.message ?: "Source extraction failed.")
            }
        }
    }

    fun matchesMaterialized(lease: SourceRegistry.Lease): Boolean {
        return try {
        val rootAttributes = Files.readAttributes(lease.treeDirectory, BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS)
        if (!rootAttributes.isDirectory || rootAttributes.isSymbolicLink || rootAttributes.isOther ||
            Files.isSymbolicLink(lease.treeDirectory)
        ) return false
        val seen = HashSet<String>()
        val files = ArrayList<FileDigest>()
        var count = 0L
        var bytes = 0L
        Files.walk(lease.treeDirectory).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (path == lease.treeDirectory) continue
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)
                if (Files.isSymbolicLink(path) || attributes.isSymbolicLink || attributes.isOther ||
                    !attributes.isDirectory && !attributes.isRegularFile
                ) return false
                val relative = lease.treeDirectory.relativize(path)
                val key = relative.toString().replace('\\', '/').lowercase(Locale.ROOT)
                if (!seen.add(key)) return false
                if (attributes.isDirectory) continue
                count++
                if (count > trees.maxFiles || attributes.size() > trees.maxBytes - bytes) return false
                val digest = MessageDigest.getInstance("SHA-256")
                var fileBytes = 0L
                BufferedInputStream(Files.newInputStream(path)).use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        digest.update(buffer, 0, read)
                        fileBytes += read
                    }
                }
                if (fileBytes != attributes.size()) return false
                bytes += fileBytes
                files.add(FileDigest(relative.toString().replace('\\', '/'), fileBytes, digest.digest()))
            }
        }
        count == lease.reference.fileCount && bytes == lease.reference.totalBytes &&
            aggregate(files) == lease.snapshotSha256
        } catch (_: IOException) {
            false
        }
    }

    private fun extract(lease: SourceRegistry.Lease, cancellation: CancellationToken, deadline: Long): Result {
        val seen = HashSet<String>()
        val files = ArrayList<FileDigest>()
        var count = 0L
        var bytes = 0L
        val maximumTarBytes = trees.maxBytes + trees.maxFiles.toLong() * 4096L + 1024L * 1024L
        Files.newInputStream(lease.archive).use { file ->
            CountingInputStream(BufferedInputStream(file)).use { compressed ->
                ZstdInputStream(compressed).use { zstd ->
                    CountingInputStream(zstd).use { counted ->
                        TarArchiveInputStream(counted).use { tar ->
                        while (true) {
                            check(cancellation, deadline)
                            val entry = tar.nextEntry ?: break
                            if (counted.bytesRead > maximumTarBytes) invalid("Source tar stream exceeds its limit.")
                            val relative = validate(entry, seen)
                            val target = lease.treeDirectory.resolve(relative).normalize()
                            if (!target.startsWith(lease.treeDirectory) || target == lease.treeDirectory) {
                                invalid("Source archive path escapes its tree.")
                            }
                            if (entry.isDirectory) {
                                createSafeDirectories(lease.treeDirectory, target)
                                continue
                            }
                            count++
                            if (count > trees.maxFiles) invalid("Source archive contains too many files.")
                            if (entry.size < 0 || entry.size > trees.maxBytes - bytes) {
                                invalid("Source archive exceeds its byte limit.")
                            }
                            createSafeDirectories(lease.treeDirectory, target.parent)
                            val digest = MessageDigest.getInstance("SHA-256")
                            var fileBytes = 0L
                            BufferedOutputStream(Files.newOutputStream(target, CREATE_NEW, WRITE)).use { output ->
                                val buffer = ByteArray(1024 * 1024)
                                while (true) {
                                    check(cancellation, deadline)
                                    val read = tar.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    if (read > trees.maxBytes - bytes - fileBytes) {
                                        invalid("Source archive exceeds its byte limit.")
                                    }
                                    output.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    fileBytes += read
                                }
                            }
                            if (fileBytes != entry.size) invalid("Source archive entry size changed while extracting.")
                            bytes += fileBytes
                            files.add(FileDigest(relative.toString().replace('\\', '/'), fileBytes, digest.digest()))
                        }
                            if (counted.bytesRead > maximumTarBytes) invalid("Source tar stream exceeds its limit.")
                        }
                    }
                }
                if (compressed.bytesRead != lease.reference.compressedBytes) {
                    invalid("Source archive contains trailing or unread compressed data.")
                }
            }
        }
        return Result(count, bytes, aggregate(files))
    }

    private fun createSafeDirectories(root: Path, target: Path) {
        var current = root
        for (component in root.relativize(target)) {
            current = current.resolve(component)
            try {
                Files.createDirectory(current)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                val attributes = Files.readAttributes(current, BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)
                if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.isOther ||
                    Files.isSymbolicLink(current)
                ) invalid("Source extraction encountered an unsafe directory.")
            }
        }
    }

    private fun validate(entry: TarArchiveEntry, seen: MutableSet<String>): Path {
        val archivedName = entry.name
        val name = if (entry.isDirectory) archivedName.removeSuffix("/") else archivedName
        if (name.isEmpty() || name.indexOf('\\') >= 0 || name.startsWith('/') || name.contains("//") ||
            name.length >= 2 && name[1] == ':' || entry.isSymbolicLink || entry.isLink || entry.isSparse ||
            !entry.isDirectory && !entry.isFile
        ) invalid("Source archive contains an unsafe entry.")
        for (component in name.split('/')) {
            val stem = component.substringBefore('.').uppercase(Locale.ROOT)
            if (component.isEmpty() || component == "." || component == ".." ||
                component.endsWith('.') || component.endsWith(' ') ||
                component.any { it.code < 32 || it in "<>:\"|?*" } ||
                stem == "CON" || stem == "PRN" || stem == "AUX" || stem == "NUL" ||
                stem.matches(Regex("COM[1-9¹²³]")) || stem.matches(Regex("LPT[1-9¹²³]"))
            ) invalid("Source archive contains an unsafe path component.")
        }
        val path = Path.of(name).normalize()
        if (path.isAbsolute || path.toString().isEmpty() || path.startsWith("..")) {
            invalid("Source archive contains an unsafe path.")
        }
        val key = path.toString().replace('\\', '/').lowercase(Locale.ROOT)
        if (!seen.add(key)) invalid("Source archive contains a duplicate path.")
        return path
    }

    private fun aggregate(files: List<FileDigest>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("vibris-source-tree-v2\u0000".toByteArray(Charsets.UTF_8))
        files.sortedWith { left, right -> compareUtf8(left.path, right.path) }.forEach { file ->
            val path = file.path.toByteArray(Charsets.UTF_8)
            digest.update('F'.code.toByte())
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(path.size).array())
            digest.update(path)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(file.size).array())
            digest.update(file.digest)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun compareUtf8(left: String, right: String): Int {
        val a = left.toByteArray(Charsets.UTF_8)
        val b = right.toByteArray(Charsets.UTF_8)
        for (index in 0 until minOf(a.size, b.size)) {
            val compared = (a[index].toInt() and 0xff).compareTo(b[index].toInt() and 0xff)
            if (compared != 0) return compared
        }
        return a.size.compareTo(b.size)
    }

    private fun check(cancellation: CancellationToken, deadline: Long) {
        cancellation.throwIfCancellationRequested()
        if (System.nanoTime() >= deadline) {
            throw SourceRegistry.Failure(ErrorCode.ERROR_CODE_EXECUTION_TIMEOUT, "Source extraction timed out.")
        }
    }

    private fun changed(): Nothing = throw SourceRegistry.Failure(
        ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, "Prepared source identity changed.")

    private fun invalid(message: String): Nothing = throw SourceRegistry.Failure(
        ErrorCode.ERROR_CODE_INVALID_SOURCE, message)

    internal class State { var ready: Boolean = false }
    private data class FileDigest(val path: String, val size: Long, val digest: ByteArray)
    private data class Result(val fileCount: Long, val totalBytes: Long, val sourceSha256: String)
}
