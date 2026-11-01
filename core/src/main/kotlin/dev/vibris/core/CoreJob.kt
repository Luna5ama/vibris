package dev.vibris.core

import dev.vibris.api.CancellationToken
import dev.vibris.protocol.v2.JobSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture

internal class CoreJob(
    @JvmField val submission: JobSpec,
    @JvmField val requestId: String,
    @JvmField val workspaceId: String,
    @JvmField val messageId: String,
    @Volatile @JvmField var session: ControlSession?,
) {
    @JvmField
    val acceptedNanos: Long = System.nanoTime()

    @JvmField
    val acceptedAtUnixMs: Long = System.currentTimeMillis()

    @JvmField
    val cancellation: CancellationToken.Source = CancellationToken.source()

    private val spans = ArrayList<dev.vibris.protocol.v2.ExecutionSpan>()

    fun recordSpan(operation: String, startedNanos: Long) =
        recordMeasuredSpan(operation, startedNanos, System.nanoTime() - startedNanos)

    @Synchronized
    fun recordMeasuredSpan(operation: String, startedNanos: Long, durationNanos: Long) {
        spans.add(dev.vibris.protocol.v2.ExecutionSpan.newBuilder()
            .setOperation(operation)
            .setStartOffsetNs((startedNanos - acceptedNanos).coerceAtLeast(0))
            .setDurationNs(durationNanos.coerceAtLeast(0))
            .build())
    }

    fun <T> measure(operation: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            recordSpan(operation, start)
        }
    }

    @Synchronized
    fun executionSpans(): List<dev.vibris.protocol.v2.ExecutionSpan> = spans.toList()

    private val ready = CountDownLatch(1)

    @Volatile
    @JvmField
    var sources: List<SourceRegistry.Lease> = emptyList()

    @Volatile
    @JvmField
    var disconnectCancellation: ScheduledFuture<*>? = null

    fun initialize(acceptedSources: List<SourceRegistry.Lease>) {
        sources = java.util.List.copyOf(acceptedSources)
        ready.countDown()
    }

    @Throws(InterruptedException::class)
    fun awaitReady() {
        ready.await()
    }

    @Synchronized
    fun bind(replacement: ControlSession?) {
        session = replacement
        disconnectCancellation?.cancel(false)
        disconnectCancellation = null
    }

    @Synchronized
    fun scheduleDisconnectCancellation(future: ScheduledFuture<*>?) {
        disconnectCancellation?.cancel(false)
        disconnectCancellation = future
    }

    @Synchronized
    fun stillOwnedBy(candidate: ControlSession?): Boolean = session === candidate
}
