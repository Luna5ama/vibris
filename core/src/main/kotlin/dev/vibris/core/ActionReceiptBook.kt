package dev.vibris.core

import dev.vibris.protocol.v2.Action
import dev.vibris.protocol.v2.ActionKind
import dev.vibris.protocol.v2.ActionReceipt
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobResult
import dev.vibris.protocol.v2.ReceiptStatus

internal class ActionReceiptBook(actions: List<Action>) {
    private val entries: List<Entry>
    private val receipts: Array<ActionReceipt?>

    init {
        var actionIndex = 0
        var preludeIndex = 0
        entries = actions.mapIndexed { wireIndex, action ->
            val prelude = action.prelude
            Entry(
                wireIndex,
                runCatching { RuntimeActionProtocol.kind(action) }.getOrDefault(ActionKind.ACTION_KIND_UNSPECIFIED),
                if (prelude) preludeIndex++ else actionIndex++,
                prelude,
            )
        }
        receipts = arrayOfNulls(entries.size)
    }

    fun success(wireIndex: Int): ActionReceipt.Builder = builder(wireIndex, ReceiptStatus.RECEIPT_STATUS_OK)

    fun put(wireIndex: Int, receipt: ActionReceipt) {
        check(receipts[wireIndex] == null) { "Action receipt was already completed." }
        receipts[wireIndex] = receipt
    }

    fun replace(wireIndex: Int, receipt: ActionReceipt) {
        check(receipts[wireIndex] != null) { "Action receipt placeholder is missing." }
        receipts[wireIndex] = receipt
    }

    fun complete(): ReceiptSet {
        check(receipts.none { it == null }) { "Every input action must have a terminal receipt." }
        return split()
    }

    fun fail(failure: RuntimeJobExecutor.Failure, activeWireIndices: Collection<Int>): ReceiptSet {
        val affected = activeWireIndices.toMutableSet()
        if (affected.isEmpty()) {
            entries.firstOrNull { receipts[it.wireIndex] == null }?.let { affected.add(it.wireIndex) }
        }
        entries.forEach { entry ->
            when {
                entry.wireIndex in affected -> receipts[entry.wireIndex] = failureReceipt(entry, failure)
                receipts[entry.wireIndex] == null -> receipts[entry.wireIndex] = cancelledReceipt(entry, failure)
            }
        }
        return split()
    }

    private fun failureReceipt(entry: Entry, failure: RuntimeJobExecutor.Failure): ActionReceipt =
        (receipts[entry.wireIndex]?.toBuilder() ?: builder(
            entry,
            ReceiptStatus.RECEIPT_STATUS_UNSPECIFIED,
        ))
            .setStatus(
                if (failure.code == ErrorCode.ERROR_CODE_CANCELLED) {
                    ReceiptStatus.RECEIPT_STATUS_CANCELLED
                } else {
                    ReceiptStatus.RECEIPT_STATUS_FAILED
                },
            )
            .setError(ProtocolMessages.error(failure.code, failure.message ?: "Action execution failed.").putAllDetails(failure.details))
            .build()

    private fun cancelledReceipt(entry: Entry, failure: RuntimeJobExecutor.Failure): ActionReceipt =
        builder(entry, ReceiptStatus.RECEIPT_STATUS_CANCELLED)
            .setError(
                ProtocolMessages.error(
                    ErrorCode.ERROR_CODE_CANCELLED,
                    "Action was not executed because an earlier action failed: " +
                        (failure.message ?: "unknown failure"),
                ),
            )
            .build()

    private fun builder(wireIndex: Int, status: ReceiptStatus): ActionReceipt.Builder =
        builder(entries[wireIndex], status)

    private fun builder(entry: Entry, status: ReceiptStatus): ActionReceipt.Builder = ActionReceipt.newBuilder()
        .setActionIndex(entry.outputIndex)
        .setKind(entry.kind)
        .setStatus(status)

    private fun split(): ReceiptSet {
        val actions = ArrayList<ActionReceipt>()
        val preludes = ArrayList<ActionReceipt>()
        entries.forEach { entry ->
            val receipt = checkNotNull(receipts[entry.wireIndex])
            if (entry.prelude) preludes.add(receipt) else actions.add(receipt)
        }
        return ReceiptSet(java.util.List.copyOf(actions), java.util.List.copyOf(preludes))
    }

    private data class Entry(
        val wireIndex: Int,
        val kind: ActionKind,
        val outputIndex: Int,
        val prelude: Boolean,
    )

    data class ReceiptSet(
        val actions: List<ActionReceipt>,
        val preludes: List<ActionReceipt>,
    ) {
        fun addTo(result: JobResult.Builder): JobResult.Builder = result
            .addAllActionReceipts(actions)
            .addAllPreludeReceipts(preludes)
    }
}
