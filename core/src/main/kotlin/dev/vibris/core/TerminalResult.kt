package dev.vibris.core

import dev.vibris.protocol.v2.JobCompleted
import dev.vibris.protocol.v2.JobFailed
import dev.vibris.protocol.v2.ProtocolVersion
import dev.vibris.protocol.v2.ServerMessage

@JvmRecord
internal data class TerminalResult(
    val completed: JobCompleted?,
    val failed: JobFailed?,
) {
    fun successful(): Boolean = completed != null

    fun message(messageId: String, requestId: String, workspaceId: String): ServerMessage {
        val result = ServerMessage.newBuilder()
            .setProtocolVersion(V2)
            .setMessageId(messageId)
            .setRequestId(requestId)
            .setWorkspaceId(workspaceId)
        return if (successful()) {
            result.setJobCompleted(completed).build()
        } else {
            result.setJobFailed(failed).build()
        }
    }

    companion object {
        private val V2 = ProtocolVersion.newBuilder().setMajor(3).setMinor(0).build()

        @JvmStatic
        fun completed(value: JobCompleted): TerminalResult = TerminalResult(value, null)

        @JvmStatic
        fun failed(value: JobFailed): TerminalResult = TerminalResult(null, value)
    }
}
