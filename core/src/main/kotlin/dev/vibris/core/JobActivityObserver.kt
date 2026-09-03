package dev.vibris.core

/** Observes execution-lease activity. Queued jobs and read-only status calls are excluded. */
fun interface JobActivityObserver {
    fun activityChanged(active: Boolean)

    companion object {
        @JvmStatic
        fun none(): JobActivityObserver = JobActivityObserver { }
    }
}
