package dev.mkiros.perch.ui.home

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Terminal or in-flight state of one source's backfill, read off WorkManager (PLAN-7 §0.3). */
enum class BackfillRunState { RUNNING, SUCCEEDED, FAILED, CANCELLED }

/** What Z03's progress strip renders — a page count and which of [BackfillRunState] it is in. */
data class BackfillProgress(val done: Int, val total: Int, val state: BackfillRunState) {
    val isRunning: Boolean get() = state == BackfillRunState.RUNNING
}

/**
 * How [HomeViewModel] drives one source's backfill without holding WorkManager itself —
 * mirrors [dev.mkiros.perch.ui.settings.RefreshScheduler]'s seam. Production wires this to
 * [dev.mkiros.perch.work.BackfillWorker] (`WorkManagerBackfillRunner`); a test fakes it and
 * asserts `enqueue`/`cancel` calls directly, driving progress by hand with no WorkManager
 * in the loop at all.
 */
interface BackfillRunner {
    fun enqueue(feedId: Long)
    fun cancel(feedId: Long)
    fun observe(feedId: Long): Flow<BackfillProgress?>

    companion object {
        /** Every test not about backfill can build a [HomeViewModel] without one. */
        val NoOp: BackfillRunner = object : BackfillRunner {
            override fun enqueue(feedId: Long) = Unit
            override fun cancel(feedId: Long) = Unit
            override fun observe(feedId: Long): Flow<BackfillProgress?> = flowOf(null)
        }
    }
}
