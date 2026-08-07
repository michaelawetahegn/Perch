package dev.mkiros.perch.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * How often the background pass runs, per SPEC.md §7. The values Settings offers.
 *
 * Fifteen minutes is WorkManager's own floor for periodic work, so it is also ours.
 */
enum class RefreshInterval(
    /** null means "never in the background" — [Manual] is refresh-when-I-pull. */
    val duration: Duration?,
) {
    Manual(null),
    Every15Minutes(Duration.ofMinutes(15)),
    Hourly(Duration.ofHours(1)),
    Every3Hours(Duration.ofHours(3)),
    Every6Hours(Duration.ofHours(6)),
    ;

    companion object {
        /** What a fresh install polls at until the reader says otherwise. */
        val Default = Hourly
    }
}

/**
 * Owns the one periodic refresh, and the fact that there is only ever one of it.
 *
 * Both entry points enqueue under the same [UNIQUE_NAME], and the difference between them
 * is the whole point: [setInterval] is the reader changing their mind, so it `UPDATE`s the
 * schedule in place (same work id, new period, no lost backoff state); [ensureScheduled]
 * is app startup, so it `KEEP`s whatever is already there. Startup running `UPDATE` would
 * reset the reader's chosen interval to the default on every cold launch, and enqueueing
 * without a unique name at all would leave a second periodic refresh behind on every
 * launch until the install polled every source continuously.
 */
object WorkScheduler {

    /** The unique name that makes "one periodic refresh" enforceable. */
    const val UNIQUE_NAME = "perch-periodic-refresh"

    /** Applies a reader-chosen [interval], replacing whatever is scheduled now. */
    fun setInterval(context: Context, interval: RefreshInterval) {
        val manager = WorkManager.getInstance(context)
        val duration = interval.duration
        if (duration == null) {
            manager.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        manager.enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request(interval),
        )
    }

    /** Schedules the [fallback] period at startup, without disturbing an existing schedule. */
    fun ensureScheduled(context: Context, fallback: RefreshInterval = RefreshInterval.Default) {
        val duration = fallback.duration ?: return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request(fallback),
        )
    }

    /** The request both entry points enqueue. Visible so a test can read its backoff. */
    internal fun request(interval: RefreshInterval): PeriodicWorkRequest {
        val period = requireNotNull(interval.duration) { "$interval schedules no work" }
        return PeriodicWorkRequestBuilder<RefreshWorker>(period)
            .setConstraints(
                Constraints.Builder()
                    // No point waking up to fail: every feed needs the network.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** SPEC.md §7: first retry after ten seconds, doubling from there. */
    private const val BACKOFF_SECONDS = 10L
}
