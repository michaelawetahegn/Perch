package dev.mkiros.perch.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.mkiros.perch.data.repo.BackfillRepository
import dev.mkiros.perch.data.repo.FeedRepository
import kotlinx.coroutines.CancellationException

/**
 * The background refresh pass (SPEC.md §7). One unit of work = every source that is due,
 * four at a time, failures isolated per feed — all of which [FeedRepository.refreshDue]
 * already guarantees, so this class is only the translation from that pass's report into
 * the three answers WorkManager understands.
 *
 * That translation is the whole design decision here. A pass in which *some* source came
 * back is a success even if others failed: their `lastError` is already stored and the
 * drawer will show it, and retrying the whole pass would re-poll the healthy sources for
 * nothing. Only a pass in which *everything* failed is worth a retry, because that shape
 * means the phone, not the feeds — no route, captive portal, DNS down — and the
 * ten-second exponential backoff in [WorkScheduler] is the right response to it. We stop
 * asking after [MAX_ATTEMPTS] so a genuinely offline device settles back onto its normal
 * period instead of climbing the backoff curve all day.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
    private val feeds: FeedRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val report = feeds.refreshDue()
        val attempted = report.outcomes.size
        if (attempted > 0 && report.failed == attempted) giveUpOrRetry() else Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Nothing per-feed reaches here; this is the database or the process itself
        // being unhappy, which is exactly what a backed-off retry is for.
        giveUpOrRetry()
    }

    private fun giveUpOrRetry(): Result =
        if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()

    private companion object {
        /** Retries per period before waiting for the next one instead. */
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * Hands each worker the one dependency it cannot get from a [Context].
 *
 * Both repositories are supplied as lambdas rather than instances because WorkManager
 * builds this factory during app startup, before the database is worth opening — a
 * process woken purely to deliver a broadcast should not pay for Room.
 */
class PerchWorkerFactory(
    private val backfill: () -> BackfillRepository = { error("BackfillWorker needs a BackfillRepository") },
    private val feeds: () -> FeedRepository,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        RefreshWorker::class.java.name -> RefreshWorker(appContext, workerParameters, feeds())
        BackfillWorker::class.java.name -> BackfillWorker(appContext, workerParameters, backfill())
        else -> null // Anything else falls through to WorkManager's default factory.
    }
}
