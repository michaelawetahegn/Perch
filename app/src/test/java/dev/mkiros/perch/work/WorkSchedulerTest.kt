package dev.mkiros.perch.work

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Scheduling, per SPEC.md §7. The point of the unique name is that changing the interval
 * in Settings must *move* the existing schedule, never lay a second one beside it — two
 * periodic refreshes would double every source's poll rate for the life of the install.
 */
@RunWith(RobolectricTestRunner::class)
class WorkSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `scheduling enqueues one periodic refresh that waits for a network`() {
        WorkScheduler.setInterval(context, RefreshInterval.Hourly)

        val info = scheduled().single()
        assertThat(info.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(info.periodicityInfo!!.repeatIntervalMillis)
            .isEqualTo(Duration.ofHours(1).toMillis())
    }

    @Test
    fun `changing the interval updates the existing work instead of adding another`() {
        WorkScheduler.setInterval(context, RefreshInterval.Hourly)
        val before = scheduled().single()

        WorkScheduler.setInterval(context, RefreshInterval.Every6Hours)

        val after = scheduled().single()
        assertThat(after.id).isEqualTo(before.id)
        assertThat(after.periodicityInfo!!.repeatIntervalMillis)
            .isEqualTo(Duration.ofHours(6).toMillis())
    }

    @Test
    fun `choosing manual cancels the periodic refresh`() {
        WorkScheduler.setInterval(context, RefreshInterval.Every15Minutes)

        WorkScheduler.setInterval(context, RefreshInterval.Manual)

        // The cancel lands on WorkManager's own task executor, which the configured
        // SynchronousExecutor does not cover, so read the state back in wall-clock time.
        assertThat(awaitCancelled()).isTrue()
    }

    @Test
    fun `startup scheduling leaves an interval the user already chose alone`() {
        WorkScheduler.setInterval(context, RefreshInterval.Every3Hours)

        WorkScheduler.ensureScheduled(context)

        val info = scheduled().single()
        assertThat(info.periodicityInfo!!.repeatIntervalMillis)
            .isEqualTo(Duration.ofHours(3).toMillis())
    }

    @Test
    fun `startup scheduling on a fresh install falls back to the hourly default`() {
        WorkScheduler.ensureScheduled(context)

        val info = scheduled().single()
        assertThat(info.periodicityInfo!!.repeatIntervalMillis)
            .isEqualTo(RefreshInterval.Default.duration!!.toMillis())
    }

    @Test
    fun `a failed pass backs off ten seconds and then exponentially`() {
        val request = WorkScheduler.request(RefreshInterval.Hourly)

        assertThat(request.workSpec.backoffPolicy)
            .isEqualTo(androidx.work.BackoffPolicy.EXPONENTIAL)
        assertThat(request.workSpec.backoffDelayDuration).isEqualTo(10_000L)
    }

    private fun scheduled(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(WorkScheduler.UNIQUE_NAME).get()

    /** Polls for up to five seconds; returns the final verdict either way so a stall fails. */
    private fun awaitCancelled(): Boolean {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            if (scheduled().none { !it.state.isFinished }) return true
            Thread.sleep(25)
        }
        return scheduled().none { !it.state.isFinished }
    }
}
