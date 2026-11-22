package dev.vibris.core

/** Observes execution-lease activity. Queued jobs and read-only status calls are excluded. */
fun interface JobActivityObserver {
    fun activityChanged(active: Boolean)

    fun executionStarted(job: Execution) = activityChanged(true)

    fun executionFinished(job: Execution) = activityChanged(false)

    /** Called by the execution worker after the runnable queue and group continuation grace drain. */
    fun queueIdle() {}

    @JvmRecord
    data class Execution(
        val jobId: String,
        val requestId: String,
        val workspaceId: String,
        val schedulingGroupId: String,
    )

    companion object {
        @JvmStatic
        fun none(): JobActivityObserver = JobActivityObserver { }
    }
}
