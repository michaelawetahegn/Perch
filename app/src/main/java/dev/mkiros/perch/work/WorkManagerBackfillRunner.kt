package dev.mkiros.perch.work

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.mkiros.perch.ui.home.BackfillProgress
import dev.mkiros.perch.ui.home.BackfillRunState
import dev.mkiros.perch.ui.home.BackfillRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The real [BackfillRunner] (Z03) — [BackfillWorker] already owns the fetch, the throttling
 * and the skipping (Z02); this is only the translation from what WorkManager reports into
 * what a screen can observe, the same division [dev.mkiros.perch.ui.settings.RefreshScheduler]
 * draws for the periodic pass.
 */
class WorkManagerBackfillRunner(private val context: Context) : BackfillRunner {

    override fun enqueue(feedId: Long) = BackfillWorker.enqueue(context, feedId)

    override fun cancel(feedId: Long) = BackfillWorker.cancel(context, feedId)

    override fun observe(feedId: Long): Flow<BackfillProgress?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(BackfillWorker.uniqueName(feedId))
            .map { infos -> infos.firstOrNull()?.toProgress() }

    private fun WorkInfo.toProgress() = BackfillProgress(
        done = progress.getInt(BackfillWorker.KEY_DONE, 0),
        total = progress.getInt(BackfillWorker.KEY_TOTAL, 0),
        state = when (state) {
            WorkInfo.State.SUCCEEDED -> BackfillRunState.SUCCEEDED
            WorkInfo.State.FAILED -> BackfillRunState.FAILED
            WorkInfo.State.CANCELLED -> BackfillRunState.CANCELLED
            else -> BackfillRunState.RUNNING
        },
    )
}
