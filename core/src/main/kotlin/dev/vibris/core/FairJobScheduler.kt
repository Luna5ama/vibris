package dev.vibris.core

import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

internal class FairJobScheduler(
    private val capacity: Int = CAPACITY,
    private val stateChanged: () -> Unit = {},
    private val maxConsecutiveGroupJobs: Int = MAX_CONSECUTIVE_GROUP_JOBS,
    private val maxGroupTurnDurationMs: Long = MAX_GROUP_TURN_DURATION_MS,
    private val continuationGraceMs: Long = CONTINUATION_GRACE_MS,
    private val nanoTime: () -> Long = System::nanoTime,
    private val queueIdle: () -> Unit = {},
) : AutoCloseable {
    private val queues = LinkedHashMap<String, ArrayDeque<Entry>>()
    private val workspaceRing = ArrayDeque<String>()
    private val worker: Thread
    private var active: ActiveJob? = null
    private var groupTurn: GroupTurn? = null
    private var queued = 0
    private var peakSize = 0
    private var closed = false

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(maxConsecutiveGroupJobs > 0) { "group job quantum must be positive" }
        require(maxGroupTurnDurationMs > 0) { "group turn duration must be positive" }
        require(continuationGraceMs >= 0) { "continuation grace must not be negative" }
        worker = Thread(::workLoop, "Vibris Core Scheduler").apply {
            isDaemon = true
            start()
        }
    }

    fun submit(metadata: JobMetadata, task: Runnable?): Submission {
        val requiredTask = requireNotNull(task) { "task" }
        val result = synchronized(this) {
            requireMetadata(metadata)
            if (closed || queued >= capacity) {
                return@synchronized Submission(false, 0)
            }
            queues.computeIfAbsent(metadata.workspaceId) { ArrayDeque() }
                .addLast(Entry(metadata, requiredTask))
            queued++
            peakSize = maxOf(peakSize, queued)
            if (metadata.workspaceId != active?.metadata?.workspaceId &&
                !workspaceRing.contains(metadata.workspaceId)
            ) {
                workspaceRing.addLast(metadata.workspaceId)
            }
            monitorNotifyAll()
            val position = orderedQueue().indexOfFirst { it.requestId == metadata.requestId } + 1
            Submission(true, position)
        }
        if (result.accepted) stateChanged()
        return result
    }

    fun cancel(requestId: String?): Boolean {
        val request = requireId(requestId, "request ID")
        val cancelled = synchronized(this) {
            val workspaces = queues.entries.iterator()
            while (workspaces.hasNext()) {
                val candidate = workspaces.next()
                val jobs = candidate.value.iterator()
                while (jobs.hasNext()) {
                    if (jobs.next().metadata.requestId != request) continue
                    jobs.remove()
                    queued--
                    if (candidate.value.isEmpty() && candidate.key != active?.metadata?.workspaceId) {
                        workspaces.remove()
                        workspaceRing.remove(candidate.key)
                    }
                    monitorNotifyAll()
                    return@synchronized true
                }
            }
            false
        }
        if (cancelled) stateChanged()
        return cancelled
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val ordered = orderedQueue()
        return Snapshot(
            active,
            ordered.mapIndexed { index, metadata -> QueuedJob(metadata, index + 1) },
            closed,
        )
    }

    @Synchronized
    fun size(): Int = queued

    @Synchronized
    fun canAccept(): Boolean = !closed && queued < capacity

    @Synchronized
    fun peakSize(): Int = peakSize

    @Synchronized
    fun isClosed(): Boolean = closed

    override fun close() {
        val changed = synchronized(this) {
            if (closed) {
                false
            } else {
                closed = true
                groupTurn = null
                monitorNotifyAll()
                true
            }
        }
        if (changed) stateChanged()
        if (Thread.currentThread() == worker) return

        var interrupted = false
        while (worker.isAlive) {
            try {
                worker.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun workLoop() {
        var reportIdle = true
        while (true) {
            val next = try {
                take(reportIdle)
            } catch (_: InterruptedException) {
                continue
            } ?: return
            if (next === Idle) {
                reportIdle = false
                queueIdle()
                continue
            }
            val job = next as Entry
            reportIdle = true

            stateChanged()
            try {
                job.task.run()
            } catch (failure: RuntimeException) {
                worker.uncaughtExceptionHandler.uncaughtException(worker, failure)
            } catch (failure: Error) {
                worker.uncaughtExceptionHandler.uncaughtException(worker, failure)
            } finally {
                finish(job.metadata)
                stateChanged()
            }
        }
    }

    @Synchronized
    @Throws(InterruptedException::class)
    private fun take(reportIdle: Boolean): Next? {
        while (true) {
            if (closed && queued == 0) return if (reportIdle) Idle else null
            val now = nanoTime()
            takeContinuation(now)?.let { return it }
            continuationWaitNanos(now).takeIf { it > 0 }?.let {
                monitorWaitNanos(it)
                continue
            }
            if (workspaceRing.isEmpty()) {
                if (reportIdle) return Idle
                monitorWait()
                continue
            }
            val workspaceId = workspaceRing.removeFirst()
            val workspaceQueue = queues[workspaceId]
            if (workspaceQueue == null || workspaceQueue.isEmpty()) {
                queues.remove(workspaceId)
                continue
            }
            val job = workspaceQueue.removeFirst()
            queued--
            beginGroupTurn(job.metadata, now)
            active = ActiveJob(job.metadata, System.currentTimeMillis())
            return job
        }
    }

    @Synchronized
    private fun finish(metadata: JobMetadata) {
        active = null
        val workspaceQueue = queues[metadata.workspaceId]
        if (workspaceQueue == null || workspaceQueue.isEmpty()) {
            queues.remove(metadata.workspaceId)
        } else if (!workspaceRing.contains(metadata.workspaceId)) {
            workspaceRing.addLast(metadata.workspaceId)
        }
        val now = nanoTime()
        val turn = groupTurn
        if (turn != null && turn.workspaceId == metadata.workspaceId &&
            turn.schedulingGroupId == metadata.schedulingGroupId && turnCanContinue(turn, now)
        ) {
            turn.continuationDeadlineNanos = saturatedAdd(
                now,
                TimeUnit.MILLISECONDS.toNanos(continuationGraceMs),
            )
        } else {
            groupTurn = null
        }
        monitorNotifyAll()
    }

    private fun takeContinuation(now: Long): Entry? {
        val turn = groupTurn ?: return null
        if (!turnCanContinue(turn, now) || closed) {
            groupTurn = null
            return null
        }
        val jobs = queues[turn.workspaceId]
        if (jobs != null && jobs.isNotEmpty()) {
            if (jobs.first().metadata.schedulingGroupId != turn.schedulingGroupId) {
                groupTurn = null
                return null
            }
            workspaceRing.remove(turn.workspaceId)
            val job = jobs.removeFirst()
            queued--
            turn.jobsStarted++
            turn.continuationDeadlineNanos = 0
            active = ActiveJob(job.metadata, System.currentTimeMillis())
            return job
        }
        if (turn.continuationDeadlineNanos == 0L || now >= turn.continuationDeadlineNanos) {
            groupTurn = null
        }
        return null
    }

    private fun continuationWaitNanos(now: Long): Long {
        val turn = groupTurn ?: return 0
        if (!turnCanContinue(turn, now) || closed) {
            groupTurn = null
            return 0
        }
        val jobs = queues[turn.workspaceId]
        if (jobs != null && jobs.isNotEmpty()) return 0
        return (turn.continuationDeadlineNanos - now).coerceAtLeast(0)
    }

    private fun beginGroupTurn(metadata: JobMetadata, now: Long) {
        groupTurn = metadata.schedulingGroupId.takeIf(String::isNotBlank)?.let {
            GroupTurn(metadata.workspaceId, it, now, 1, 0)
        }
    }

    private fun turnCanContinue(turn: GroupTurn, now: Long): Boolean =
        turn.jobsStarted < maxConsecutiveGroupJobs &&
            now - turn.startedAtNanos < TimeUnit.MILLISECONDS.toNanos(maxGroupTurnDurationMs)

    @Synchronized
    private fun orderedQueue(): List<JobMetadata> {
        if (queued == 0) return emptyList()
        val copies = LinkedHashMap<String, ArrayDeque<JobMetadata>>()
        queues.forEach { (workspace, jobs) ->
            if (jobs.isNotEmpty()) copies[workspace] = ArrayDeque(jobs.map(Entry::metadata))
        }
        val ring = ArrayDeque(workspaceRing)
        val ordered = ArrayList<JobMetadata>(queued)
        val now = nanoTime()
        groupTurn?.takeIf { turnCanContinue(it, now) }?.let { turn ->
            ring.remove(turn.workspaceId)
            val jobs = copies[turn.workspaceId]
            var remaining = maxConsecutiveGroupJobs - turn.jobsStarted
            while (jobs != null && jobs.isNotEmpty() && remaining > 0 &&
                jobs.first().schedulingGroupId == turn.schedulingGroupId
            ) {
                ordered.add(jobs.removeFirst())
                remaining--
            }
            if (jobs?.isNotEmpty() == true) ring.addLast(turn.workspaceId)
        }
        copies.keys.forEach { workspace ->
            if (copies[workspace]?.isNotEmpty() == true && !ring.contains(workspace)) ring.addLast(workspace)
        }
        while (ring.isNotEmpty()) {
            val workspace = ring.removeFirst()
            val jobs = copies[workspace] ?: continue
            if (jobs.isEmpty()) continue
            val first = jobs.removeFirst()
            ordered.add(first)
            var remaining = if (first.schedulingGroupId.isBlank()) 0 else maxConsecutiveGroupJobs - 1
            while (jobs.isNotEmpty() && remaining > 0 &&
                jobs.first().schedulingGroupId == first.schedulingGroupId
            ) {
                ordered.add(jobs.removeFirst())
                remaining--
            }
            if (jobs.isNotEmpty()) ring.addLast(workspace)
        }
        return ordered
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun monitorWait() {
        (this as java.lang.Object).wait()
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun monitorWaitNanos(durationNanos: Long) {
        val millis = durationNanos / 1_000_000
        val nanos = (durationNanos % 1_000_000).toInt()
        (this as java.lang.Object).wait(millis, nanos)
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun monitorNotifyAll() {
        (this as java.lang.Object).notifyAll()
    }

    @JvmRecord
    data class JobMetadata(
        val requestId: String,
        val workspaceId: String,
        val jobId: String,
        val worktreeRoot: String,
        val operation: String,
        val schedulingGroupId: String,
        val queuedAtUnixMs: Long,
    )

    @JvmRecord
    data class ActiveJob(val metadata: JobMetadata, val startedAtUnixMs: Long)

    @JvmRecord
    data class QueuedJob(val metadata: JobMetadata, val position: Int)

    @JvmRecord
    data class Snapshot(val active: ActiveJob?, val queued: List<QueuedJob>, val closed: Boolean)

    @JvmRecord
    data class Submission(val accepted: Boolean, val position: Int)

    private sealed interface Next

    private data object Idle : Next

    @JvmRecord
    private data class Entry(val metadata: JobMetadata, val task: Runnable) : Next

    private data class GroupTurn(
        val workspaceId: String,
        val schedulingGroupId: String,
        val startedAtNanos: Long,
        var jobsStarted: Int,
        var continuationDeadlineNanos: Long,
    )

    companion object {
        const val CAPACITY = 32
        const val MAX_CONSECUTIVE_GROUP_JOBS = 4
        const val MAX_GROUP_TURN_DURATION_MS = 120_000L
        const val CONTINUATION_GRACE_MS = 500L

        private fun requireMetadata(metadata: JobMetadata) {
            requireId(metadata.requestId, "request ID")
            requireId(metadata.workspaceId, "workspace ID")
            requireId(metadata.jobId, "job ID")
            requireId(metadata.operation, "operation")
            require(metadata.queuedAtUnixMs >= 0) { "queued timestamp must not be negative" }
        }

        private fun requireId(value: String?, name: String): String {
            if (value.isNullOrBlank()) throw IllegalArgumentException("$name must not be blank")
            return value
        }

        private fun saturatedAdd(left: Long, right: Long): Long =
            if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }
}
