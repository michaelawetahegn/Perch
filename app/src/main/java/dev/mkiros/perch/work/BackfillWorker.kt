package dev.mkiros.perch.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.mkiros.perch.data.repo.BackfillRepository
import kotlinx.coroutines.CancellationException

/**
 * One source's backfill, run as work (PLAN-7 §0.3) so it survives the reader leaving the
 * screen — [BackfillRepository.run] already does the fetching, throttling and skipping;
 * this is only the translation into what WorkManager understands, the same division
 * [RefreshWorker] draws for the periodic pass.
 *
 * Cancelling the work (the reader asking Perch to stop, or [WorkManager.cancelUniqueWork])
 * flips [isStopped], which [BackfillRepository.run] polls between pages — whatever already
 * landed stays, per §0.3. A retried or re-enqueued run costs nothing extra: `run` always
 * starts from a fresh [BackfillRepository.plan], so a post already stored is skipped, not
 * refetched.
 */
class BackfillWorker(
    context: Context,
    params: WorkerParameters,
    private val backfill: BackfillRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val feedId = inputData.getLong(KEY_FEED_ID, -1L)
        if (feedId < 0) return Result.failure()
        return try {
            backfill.run(
                feedId = feedId,
                isCancelled = { isStopped },
                onProgress = { done, total -> setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total)) },
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Whatever fetchAndStore already wrote is already committed (§0.3); a failure
            // this broad is the database or the process itself, the same call RefreshWorker
            // makes, and there is nothing here worth retrying blindly.
            Result.failure()
        }
    }

    companion object {
        const val KEY_FEED_ID = "feedId"

        /** Read by a progress observer (Z03) off [androidx.work.WorkInfo.progress]. */
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"

        /** One backfill in flight per source at a time — a second tap joins the first. */
        fun uniqueName(feedId: Long) = "perch-backfill-$feedId"

        fun request(feedId: Long) = OneTimeWorkRequestBuilder<BackfillWorker>()
            .setInputData(workDataOf(KEY_FEED_ID to feedId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        fun enqueue(context: Context, feedId: Long) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName(feedId), ExistingWorkPolicy.KEEP, request(feedId))
        }

        fun cancel(context: Context, feedId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(feedId))
        }
    }
}
