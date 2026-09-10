package dev.vibris.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.Objects

internal class FixedShaderLink(
    pendingRoot: Path,
    shaderpackRoot: Path,
    atomicMover: AtomicMover,
) : ShaderLink {
    private val pendingRoot = pendingRoot.toAbsolutePath().normalize()
    private val shaderpackRoot = shaderpackRoot.toAbsolutePath().normalize()
    private val activeLink = this.shaderpackRoot.resolve(ACTIVE_NAME)
    private val atomicMover = Objects.requireNonNull(atomicMover, "atomicMover")
    private var activeIdentity: LinkIdentity? = null

    constructor(pendingRoot: Path, shaderpackRoot: Path) : this(
        pendingRoot,
        shaderpackRoot,
        AtomicMover { source, target ->
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            Unit
        },
    )

    @Synchronized
    @Throws(ShaderLink.Failure::class)
    fun prepare() {
        requireOrdinaryRoots()
        try {
            deleteLink(activeLink)
            activeIdentity = null
        } catch (exception: IOException) {
            throw ShaderLink.Failure("Failed to detach the stale shader link.", true, exception)
        }
        try {
            Files.list(shaderpackRoot).use { children ->
                for (child in children.filter { path -> path.fileName.toString().startsWith(NEXT_PREFIX) }.toList()) {
                    deleteLink(child)
                }
            }
        } catch (exception: IOException) {
            throw ShaderLink.Failure("Failed to clean temporary shader links.", true, exception)
        }
    }

    @Synchronized
    @Throws(ShaderLink.Failure::class)
    override fun switchTo(source: SourceRegistry.Lease, ownership: ShaderLink.OwnershipCheck) {
        requireOrdinaryRoots()
        verifyActiveLink()
        val target = source.directory.toAbsolutePath().normalize()
        if (pendingRoot != target.parent?.parent || target.fileName.toString() != OwnedSourceTree.TREE_NAME) {
            throw ShaderLink.Failure("Shader source is outside the pending root.", true)
        }
        requireLinkOrMissing(activeLink)
        val temporary = shaderpackRoot.resolve(NEXT_PREFIX + source.uuid)
        if (Files.exists(temporary, NOFOLLOW_LINKS)) {
            throw ShaderLink.Failure("Temporary shader link already exists.", true)
        }

        var identity: LinkIdentity? = null
        try {
            Files.createSymbolicLink(temporary, target)
            identity = LinkIdentity.capture(temporary)
            ownership.verify()
            if (!identity.matches(temporary) || target != Files.readSymbolicLink(temporary)) {
                throw ShaderLink.Failure("Temporary shader link identity changed.", true)
            }
            replace(temporary, target)
        } catch (failure: ShaderLink.Failure) {
            deleteIfOwned(temporary, identity)
            throw failure
        } catch (exception: IOException) {
            deleteIfOwned(temporary, identity)
            throw ShaderLink.Failure("Failed to create the directory shader link.", true, exception)
        } catch (exception: UnsupportedOperationException) {
            deleteIfOwned(temporary, identity)
            throw ShaderLink.Failure("Failed to create the directory shader link.", true, exception)
        } catch (exception: SecurityException) {
            deleteIfOwned(temporary, identity)
            throw ShaderLink.Failure("Failed to create the directory shader link.", true, exception)
        }
    }

    @Synchronized
    @Throws(ShaderLink.Failure::class)
    override fun detach() {
        try {
            verifyActiveLink()
        } catch (failure: ShaderLink.Failure) {
            if (Files.isSymbolicLink(activeLink)) {
                try {
                    Files.delete(activeLink)
                    activeIdentity = null
                } catch (cleanupFailure: IOException) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
        if (activeIdentity == null) {
            return
        }
        try {
            Files.delete(activeLink)
            activeIdentity = null
        } catch (exception: IOException) {
            throw ShaderLink.Failure("Failed to detach the active shader link.", true, exception)
        }
    }

    @Synchronized
    @Throws(ShaderLink.Failure::class)
    override fun retainsActiveSource(): Boolean {
        verifyActiveLink()
        return activeIdentity != null
    }

    @Synchronized
    @Throws(ShaderLink.Failure::class)
    fun currentTarget(): Path? {
        verifyActiveLink()
        return activeIdentity?.target
    }

    @Throws(ShaderLink.Failure::class)
    private fun replace(temporary: Path, target: Path) {
        val previous = currentTarget()
        try {
            atomicMover.move(temporary, activeLink)
        } catch (atomicFailure: IOException) {
            replaceGuarded(temporary, previous, atomicFailure)
        }
        try {
            val replacement = LinkIdentity.capture(activeLink)
            if (target != replacement.target) {
                throw IOException("active shader link target mismatch")
            }
            activeIdentity = replacement
        } catch (verificationFailure: IOException) {
            val restored = restore(previous)
            throw ShaderLink.Failure("Active shader link verification failed.", restored, verificationFailure)
        }
    }

    @Throws(ShaderLink.Failure::class)
    private fun replaceGuarded(temporary: Path, previous: Path?, atomicFailure: IOException) {
        verifyActiveLink()
        try {
            if (Files.exists(activeLink, NOFOLLOW_LINKS)) {
                Files.delete(activeLink)
            }
            Files.move(temporary, activeLink)
        } catch (fallbackFailure: IOException) {
            fallbackFailure.addSuppressed(atomicFailure)
            val restored = restore(previous)
            throw ShaderLink.Failure("Failed to replace the active shader link.", restored, fallbackFailure)
        }
    }

    private fun restore(previous: Path?): Boolean {
        return try {
            if (Files.exists(activeLink, NOFOLLOW_LINKS)) {
                deleteLink(activeLink)
            }
            if (previous != null) {
                Files.createSymbolicLink(activeLink, previous)
            }
            activeIdentity = if (previous == null) null else LinkIdentity.capture(activeLink)
            previous == null || previous == checkNotNull(activeIdentity).target
        } catch (_: IOException) {
            activeIdentity = null
            false
        } catch (_: ShaderLink.Failure) {
            activeIdentity = null
            false
        }
    }

    @Throws(ShaderLink.Failure::class)
    private fun verifyActiveLink() {
        val identity = activeIdentity
        if (identity == null) {
            if (Files.exists(activeLink, NOFOLLOW_LINKS)) {
                throw ShaderLink.Failure("Active shader link was created outside Vibris.", false)
            }
            return
        }
        if (!identity.matches(activeLink)) {
            throw ShaderLink.Failure("Active shader link identity or target changed.", false)
        }
    }

    @Throws(ShaderLink.Failure::class)
    private fun requireOrdinaryRoots() {
        try {
            requireOrdinaryAncestors(pendingRoot)
            requireOrdinaryAncestors(shaderpackRoot)
        } catch (exception: IOException) {
            throw ShaderLink.Failure("Shader link roots are missing or unsafe.", true, exception)
        }
    }

    fun interface AtomicMover {
        @Throws(IOException::class)
        fun move(source: Path, target: Path)
    }

    private data class LinkIdentity(
        val fileKey: Any?,
        val creationTime: FileTime,
        val target: Path,
    ) {
        fun matches(path: Path): Boolean {
            return try {
                val current = capture(path)
                val sameFile = if (fileKey != null || current.fileKey != null) {
                    fileKey != null && fileKey == current.fileKey
                } else {
                    creationTime == current.creationTime
                }
                sameFile && target == current.target
            } catch (_: IOException) {
                false
            }
        }

        companion object {
            @Throws(IOException::class)
            fun capture(path: Path): LinkIdentity {
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!attributes.isSymbolicLink || !Files.isSymbolicLink(path)) {
                    throw IOException("path is not a symbolic link")
                }
                return LinkIdentity(attributes.fileKey(), attributes.creationTime(), Files.readSymbolicLink(path))
            }
        }
    }

    companion object {
        private const val ACTIVE_NAME = "shaders"
        private const val NEXT_PREFIX = "shaders.next."

        @Throws(IOException::class)
        private fun requireOrdinaryAncestors(path: Path) {
            var current: Path? = path
            while (current != null) {
                OwnedPathIdentity.captureDirectory(current)
                current = current.parent
            }
        }

        @Throws(ShaderLink.Failure::class)
        private fun requireLinkOrMissing(path: Path) {
            if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                throw ShaderLink.Failure("Shader link path is not a symbolic link.", true)
            }
        }

        @Throws(IOException::class, ShaderLink.Failure::class)
        private fun deleteLink(path: Path) {
            requireLinkOrMissing(path)
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                Files.delete(path)
            }
        }

        private fun deleteIfOwned(path: Path, identity: LinkIdentity?) {
            if (identity == null || !identity.matches(path)) {
                return
            }
            try {
                Files.delete(path)
            } catch (_: IOException) {
            }
        }
    }
}
