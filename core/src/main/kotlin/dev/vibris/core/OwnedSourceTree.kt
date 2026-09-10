package dev.vibris.core

import dev.vibris.protocol.v2.ErrorCode
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal class OwnedSourceTree(
    pendingRoot: Path,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val maxFiles: Int = DEFAULT_MAX_FILES,
) {
    private val pendingRoot = pendingRoot.toAbsolutePath().normalize()
    private var pendingRootIdentity: OwnedPathIdentity? = null

    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(maxFiles > 0) { "maxFiles must be positive" }
    }

    @Throws(SourceRegistry.Failure::class)
    fun inspect(uuid: String): Inspection {
        requireSafePendingRoot()
        val directory = pendingRoot.resolve(uuid).normalize()
        if (pendingRoot != directory.parent) {
            throw SourceRegistry.Failure(ErrorCode.ERROR_CODE_INVALID_SOURCE, "Source UUID escapes the pending root.")
        }
        val archive = directory.resolve(ARCHIVE_NAME)
        try {
            val directoryAttributes = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val archiveAttributes = Files.readAttributes(archive, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            requireOrdinaryDirectory(directory, directoryAttributes)
            requireOrdinaryFile(archive, archiveAttributes)
            return Inspection(directory, archive, archiveAttributes.size())
        } catch (_: IOException) {
            throw SourceRegistry.Failure(ErrorCode.ERROR_CODE_INVALID_SOURCE, "Prepared source archive is missing or unsafe.")
        }
    }

    @Synchronized
    @Throws(SourceRegistry.Failure::class)
    fun reserve(directory: Path, archive: Path, compressedBytes: Long): Reservation {
        requireSafePendingRoot()
        val inspection = inspect(directory.fileName.toString())
        if (inspection.directory != directory || inspection.archive != archive ||
            inspection.compressedBytes != compressedBytes
        ) {
            throw SourceRegistry.Failure(ErrorCode.ERROR_CODE_INVALID_SOURCE,
                "Source archive changed before ownership transfer.")
        }
        return try {
            Reservation(
                Ownership(
                    checkNotNull(pendingRootIdentity),
                    OwnedPathIdentity.captureDirectory(directory),
                    FileIdentity.capture(archive),
                ),
            )
        } catch (_: IOException) {
            throw SourceRegistry.Failure(ErrorCode.ERROR_CODE_SOURCE_CONTAINS_REPARSE_POINT,
                "Source archive changed before ownership transfer.")
        }
    }

    fun stillOwned(directory: Path, archive: Path, ownership: Ownership): Boolean =
        ownership.rootIdentity.matchesDirectory(pendingRoot) &&
            ownership.directoryIdentity.matchesDirectory(directory) && ownership.archiveIdentity.matches(archive)

    @Synchronized
    @Throws(SourceRegistry.Failure::class)
    fun requireSafeRoot() = requireSafePendingRoot()

    @Synchronized
    @Throws(SourceRegistry.Failure::class)
    private fun requireSafePendingRoot() {
        var current: Path? = pendingRoot
        try {
            val identity = OwnedPathIdentity.captureDirectory(pendingRoot)
            if (pendingRootIdentity == null) pendingRootIdentity = identity
            if (!checkNotNull(pendingRootIdentity).matchesDirectory(pendingRoot)) {
                throw SourceRegistry.Failure(ErrorCode.ERROR_CODE_SOURCE_CONTAINS_REPARSE_POINT,
                    "Pending source root identity changed.")
            }
            while (current != null) {
                OwnedPathIdentity.captureDirectory(current)
                current = current.parent
            }
        } catch (_: IOException) {
            val code = if (Files.exists(pendingRoot, NOFOLLOW_LINKS)) {
                ErrorCode.ERROR_CODE_SOURCE_CONTAINS_REPARSE_POINT
            } else ErrorCode.ERROR_CODE_INVALID_SOURCE
            throw SourceRegistry.Failure(code, "Pending source root is missing or unsafe.")
        }
    }

    data class Inspection(val directory: Path, val archive: Path, val compressedBytes: Long)
    data class Reservation(val ownership: Ownership)
    data class Ownership(
        val rootIdentity: OwnedPathIdentity,
        val directoryIdentity: OwnedPathIdentity,
        val archiveIdentity: FileIdentity,
    )

    data class FileIdentity(val fileKey: Any?, val creationTime: FileTime, val size: Long) {
        fun matches(path: Path): Boolean = try {
            val current = capture(path)
            val sameFile = if (fileKey != null || current.fileKey != null) {
                fileKey != null && fileKey == current.fileKey
            } else creationTime == current.creationTime
            sameFile && size == current.size
        } catch (_: IOException) {
            false
        }

        companion object {
            @Throws(IOException::class)
            fun capture(path: Path): FileIdentity {
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                requireOrdinaryFile(path, attributes)
                return FileIdentity(attributes.fileKey(), attributes.creationTime(), attributes.size())
            }
        }
    }

    companion object {
        const val ARCHIVE_NAME = "source.tar.zst"
        const val TREE_NAME = "tree"
        private const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024
        private const val DEFAULT_MAX_FILES = 100_000

        @JvmStatic
        fun delete(root: Path): Boolean = try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    requireOrdinaryDirectory(directory, attributes)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    requireOrdinaryFile(file, attributes)
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                    if (failure != null) throw failure
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            })
            true
        } catch (_: IOException) {
            false
        }

        @Throws(IOException::class)
        private fun requireOrdinaryDirectory(path: Path, attributes: BasicFileAttributes) {
            if (!attributes.isDirectory || Files.isSymbolicLink(path) || attributes.isSymbolicLink || attributes.isOther) {
                throw IOException("source directory is not ordinary")
            }
        }

        @Throws(IOException::class)
        private fun requireOrdinaryFile(path: Path, attributes: BasicFileAttributes) {
            if (!attributes.isRegularFile || Files.isSymbolicLink(path) ||
                attributes.isSymbolicLink || attributes.isOther
            ) {
                throw IOException("source archive is not an ordinary file")
            }
        }
    }
}
