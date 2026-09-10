package dev.vibris.core

import dev.vibris.core.source.SourceRecord
import dev.vibris.core.source.SourceState
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.PreparedSourceRef
import dev.vibris.protocol.v2.SourceArchiveFormat
import dev.vibris.protocol.v2.VcsCheckoutState
import dev.vibris.api.CancellationToken
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.UUID

internal class SourceRegistry @JvmOverloads constructor(
    pendingRoot: Path,
    private val probe: CoreProbe,
    private val maxSourceBytes: Long = ServerConfiguration.DEFAULT_MAX_SOURCE_BYTES,
    private val maxSourceFiles: Int = ServerConfiguration.DEFAULT_MAX_SOURCE_FILES,
) {
    private val trees = OwnedSourceTree(pendingRoot, maxSourceBytes, maxSourceFiles)
    private val materializer = SourceMaterializer(trees)
    private val sources = HashMap<String, Lease>()
    private var activeSource: Lease? = null

    @Throws(Failure::class)
    fun validate(references: List<PreparedSourceRef>): List<Candidate> = validate(references, 1)

    @Throws(Failure::class)
    fun validate(references: List<PreparedSourceRef>, expectedCount: Int): List<Candidate> {
        if (references.isEmpty()) {
            if (expectedCount == 0) return emptyList()
            throw Failure(ErrorCode.ERROR_CODE_INVALID_SOURCE, "A source is required.")
        }
        if (references.size != expectedCount) {
            throw Failure(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, "Prepared source count does not match the job.")
        }
        val unique = HashSet<String>()
        val candidates = ArrayList<Candidate>(references.size)
        for (reference in references) {
            validateCheckout(reference)
            if (reference.archiveFormat != SourceArchiveFormat.SOURCE_ARCHIVE_FORMAT_TAR_ZSTD ||
                reference.compressedBytes <= 0 || reference.compressedBytes > maximumArchiveBytes() ||
                reference.fileCount < 0 || reference.totalBytes < 0 || reference.totalBytes > maxSourceBytes ||
                reference.fileCount > maxSourceFiles
            ) {
                throw Failure(ErrorCode.ERROR_CODE_INVALID_SOURCE, "Prepared source archive metadata is invalid.")
            }
            val uuid = requireUuid(reference.sourceUuid)
            if (!unique.add(uuid)) {
                throw Failure(ErrorCode.ERROR_CODE_INVALID_SOURCE, "Source UUID is repeated.")
            }
            val inspection = trees.inspect(uuid)
            if (inspection.compressedBytes != reference.compressedBytes) {
                throw Failure(
                    ErrorCode.ERROR_CODE_INVALID_SOURCE,
                    "Source archive size does not match its metadata.",
                )
            }
            candidates.add(Candidate(
                uuid,
                inspection.directory,
                inspection.archive,
                reference.fileCount,
                reference.totalBytes,
                reference.compressedBytes,
                reference,
            ))
        }
        return candidates
    }

    private fun validateCheckout(reference: PreparedSourceRef) {
        when (reference.vcsCheckoutState) {
            VcsCheckoutState.VCS_CHECKOUT_STATE_ATTACHED -> {
                if (reference.branch.isBlank()) {
                    throw Failure(ErrorCode.ERROR_CODE_INVALID_SOURCE, "Attached source branch is required.")
                }
            }
            VcsCheckoutState.VCS_CHECKOUT_STATE_DETACHED -> {
                if (reference.branch.isNotEmpty()) {
                    throw Failure(ErrorCode.ERROR_CODE_INVALID_SOURCE, "Detached source branch must be empty.")
                }
                if (!isFullCommit(reference.startHead)) {
                    throw Failure(ErrorCode.ERROR_CODE_INVALID_SOURCE, "Detached source HEAD must be an exact commit.")
                }
            }
            else -> throw Failure(ErrorCode.ERROR_CODE_INVALID_SOURCE, "Source checkout state is required.")
        }
    }

    private fun isFullCommit(value: String): Boolean = value.length == 40 && value.all { character ->
        character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
    }

    @Synchronized
    @Throws(Failure::class)
    fun reserve(candidates: List<Candidate>): List<Lease> {
        if (sources.size + candidates.size > CAPACITY) {
            throw Failure(ErrorCode.ERROR_CODE_QUEUE_FULL, "The source registry is full.")
        }
        val reservations = ArrayList<OwnedSourceTree.Reservation>(candidates.size)
        for (candidate in candidates) {
            if (sources.containsKey(candidate.uuid)) {
                throw Failure(ErrorCode.ERROR_CODE_INVALID_SOURCE, "Source UUID is already owned.")
            }
            reservations.add(trees.reserve(
                candidate.directory,
                candidate.archive,
                candidate.compressedBytes,
            ))
        }
        val reserved = ArrayList<Lease>(candidates.size)
        for (index in candidates.indices) {
            val candidate = candidates[index]
            val lease = Lease(
                candidate.uuid,
                candidate.directory,
                candidate.archive,
                candidate.directory.resolve(OwnedSourceTree.TREE_NAME),
                reservations[index].ownership,
                SourceRecord(candidate.uuid, 1),
                candidate.reference.snapshotSha256,
                candidate.reference,
                SourceMaterializer.State(),
            )
            sources[candidate.uuid] = lease
            reserved.add(lease)
        }
        return reserved
    }

    @Synchronized
    fun accept(reserved: List<Lease>) {
        for (lease in reserved) {
            transition(lease, "", SourceState.VALIDATED, Runnable { lease.record.state() })
            transition(lease, SourceState.VALIDATED, SourceState.QUEUED, Runnable { lease.record.queue() })
        }
    }

    @Synchronized
    fun reject(reserved: List<Lease>) {
        for (lease in reserved) {
            sources.remove(lease.uuid, lease)
        }
    }

    @Synchronized
    @Throws(Failure::class)
    fun beginActivation(lease: Lease): Activation {
        requireOwned(lease)
        transition(
            lease,
            SourceState.QUEUED,
            SourceState.ACTIVATING,
            Runnable { lease.record.beginActivation() },
        )
        return Activation(lease, activeSource)
    }

    @Throws(Failure::class)
    fun materialize(lease: Lease, cancellation: CancellationToken, deadline: Long): Path {
        requireOwned(lease)
        return materializer.materialize(lease, cancellation, deadline)
    }

    @Synchronized
    fun commitActivation(activation: Activation) {
        if (activeSource !== activation.previous) {
            throw IllegalStateException("active source changed")
        }
        val next = activation.next
        transition(next, SourceState.ACTIVATING, SourceState.ACTIVE, Runnable { next.record.activated() })
        activeSource = next
        val previous = activation.previous
        if (previous != null) {
            val before = previous.record.state()
            previous.record.deactivate()
            record(previous, before, previous.record.state())
            deleteIfEligible(previous)
        }
    }

    @Synchronized
    fun failActivation(activation: Activation) {
        failActivation(activation.next)
    }

    @Synchronized
    fun retryActivation(activation: Activation) {
        retryActivation(activation.next)
    }

    @Synchronized
    fun retryActivation(lease: Lease) {
        val before = lease.record.state()
        lease.record.retryActivation()
        record(lease, before, lease.record.state())
    }

    @Synchronized
    fun failActivation(lease: Lease) {
        val before = lease.record.state()
        lease.record.failed()
        record(lease, before, lease.record.state())
    }

    @Synchronized
    @Throws(Failure::class)
    fun requireOwned(lease: Lease) {
        if (!sources.containsKey(lease.uuid) ||
            !trees.stillOwned(lease.ownedDirectory, lease.archive, lease.ownership)
        ) {
            throw Failure(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, "Prepared source identity changed.")
        }
    }

    @Synchronized
    @Throws(Failure::class)
    fun requireMaterializedOwned(lease: Lease) {
        requireOwned(lease)
        if (!lease.materialization.ready || !Files.isDirectory(lease.treeDirectory, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(lease.treeDirectory) || !materializer.matchesMaterialized(lease)
        ) {
            throw Failure(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, "Prepared source content changed.")
        }
    }

    @Synchronized
    @Throws(Failure::class)
    fun requireActiveOwned(): Lease? {
        val source = activeSource ?: return null
        requireMaterializedOwned(source)
        return source
    }

    @Synchronized
    fun activeUuid(): String = activeSource?.uuid ?: ""

    @Synchronized
    @Throws(Failure::class)
    fun activeSnapshot(): Lease? = requireActiveOwned()

    private fun maximumArchiveBytes(): Long {
        val overhead = maxSourceFiles.toLong() * 4096L + 1024L * 1024L
        return if (maxSourceBytes > Long.MAX_VALUE - overhead) Long.MAX_VALUE else maxSourceBytes + overhead
    }

    @Synchronized
    fun detachActive() {
        val active = activeSource ?: return
        val before = active.record.state()
        active.record.deactivate()
        record(active, before, active.record.state())
        activeSource = null
        deleteIfEligible(active)
    }

    @Synchronized
    @Throws(Failure::class)
    fun retainActive(): Lease? {
        val active = activeSource ?: return null
        requireOwned(active)
        val before = active.record.state()
        active.record.retain()
        record(active, before, active.record.state())
        return active
    }

    @Synchronized
    fun releaseRetained(lease: Lease) {
        release(lease, true)
    }

    @Synchronized
    fun isActive(lease: Lease): Boolean = activeSource === lease && lease.record.active()

    fun cleanup(leases: List<Lease>) {
        release(leases, false)
    }

    fun release(leases: List<Lease>, retainActive: Boolean) {
        for (lease in leases) {
            release(lease, retainActive)
        }
    }

    @Synchronized
    fun size(): Int = sources.size

    @Synchronized
    private fun release(lease: Lease, retainActive: Boolean) {
        if (!sources.containsKey(lease.uuid)) {
            return
        }
        if (!trees.stillOwned(lease.ownedDirectory, lease.archive, lease.ownership) ||
            lease.record.active() && (!lease.materialization.ready || !materializer.matchesMaterialized(lease))
        ) {
            abandonUnsafe(lease)
            return
        }
        var before = lease.record.state()
        lease.record.release()
        record(lease, before, lease.record.state())
        if (lease.record.active() && !retainActive) {
            before = lease.record.state()
            lease.record.deactivate()
            record(lease, before, lease.record.state())
            if (activeSource === lease) {
                activeSource = null
            }
        }
        deleteIfEligible(lease)
    }

    private fun abandonUnsafe(lease: Lease) {
        var before = lease.record.state()
        if (before == SourceState.VALIDATED || before == SourceState.QUEUED || before == SourceState.ACTIVATING) {
            lease.record.failed()
            record(lease, before, lease.record.state())
        }
        before = lease.record.state()
        lease.record.release()
        record(lease, before, lease.record.state())
        if (lease.record.active()) {
            before = lease.record.state()
            lease.record.deactivate()
            record(lease, before, lease.record.state())
        }
        if (activeSource === lease) {
            activeSource = null
        }
        sources.remove(lease.uuid, lease)
    }

    @Synchronized
    fun close() {
        val active = activeSource
        if (active != null && active.record.active()) {
            val before = active.record.state()
            active.record.deactivate()
            record(active, before, active.record.state())
        }
        activeSource = null
        ArrayList(sources.values).forEach(::deleteIfEligible)
    }

    private fun deleteIfEligible(lease: Lease) {
        if (!lease.record.deletionEligible()) {
            return
        }
        var before = lease.record.state()
        lease.record.beginDeleting()
        record(lease, before, lease.record.state())
        if (!OwnedSourceTree.delete(lease.ownedDirectory)) {
            sources.remove(lease.uuid, lease)
            return
        }
        before = lease.record.state()
        lease.record.deleted()
        record(lease, before, lease.record.state())
        sources.remove(lease.uuid, lease)
    }

    private fun transition(lease: Lease, from: SourceState, to: SourceState, action: Runnable) {
        action.run()
        record(lease, from, to)
    }

    private fun transition(lease: Lease, from: String, to: SourceState, action: Runnable) {
        action.run()
        probe.sourceTransition(lease.uuid, from, to.name)
    }

    private fun record(lease: Lease, from: SourceState, to: SourceState) {
        probe.sourceTransition(lease.uuid, from.name, to.name)
    }

    data class Candidate(
        val uuid: String,
        val directory: Path,
        val archive: Path,
        val fileCount: Long,
        val totalBytes: Long,
        val compressedBytes: Long,
        val reference: PreparedSourceRef,
    ) {
        fun uuid(): String = uuid

        fun directory(): Path = directory

        fun archive(): Path = archive

        fun fileCount(): Long = fileCount

        fun totalBytes(): Long = totalBytes

        fun compressedBytes(): Long = compressedBytes

        fun reference(): PreparedSourceRef = reference
    }

    data class Lease(
        val uuid: String,
        val ownedDirectory: Path,
        val archive: Path,
        val treeDirectory: Path,
        val ownership: OwnedSourceTree.Ownership,
        val record: SourceRecord,
        val snapshotSha256: String,
        val reference: PreparedSourceRef,
        val materialization: SourceMaterializer.State,
    ) {
        fun uuid(): String = uuid

        val directory: Path get() = treeDirectory

        fun directory(): Path = treeDirectory

        fun ownedDirectory(): Path = ownedDirectory

        fun archive(): Path = archive

        fun ownership(): OwnedSourceTree.Ownership = ownership

        fun record(): SourceRecord = record

        fun snapshotSha256(): String = snapshotSha256

        fun reference(): PreparedSourceRef = reference
    }

    data class Activation(
        val next: Lease,
        val previous: Lease?,
    ) {
        fun next(): Lease = next

        fun previous(): Lease? = previous
    }

    class Failure(
        @JvmField val code: ErrorCode,
        message: String,
    ) : Exception(message)

    companion object {
        const val CAPACITY = 128

        @Throws(Failure::class)
        private fun requireUuid(value: String): String {
            try {
                val uuid = UUID.fromString(value)
                if (!uuid.toString().equals(value, ignoreCase = true)) {
                    throw IllegalArgumentException()
                }
                return uuid.toString()
            } catch (_: IllegalArgumentException) {
                throw Failure(ErrorCode.ERROR_CODE_INVALID_SOURCE, "Source UUID is invalid.")
            }
        }
    }
}
