package dev.mkiros.perch

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.work.PerchWorkerFactory
import dev.mkiros.perch.work.WorkScheduler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds the process-wide object graph and starts background refresh.
 *
 * [container] is `lazy` on purpose: WorkManager asks for this configuration during startup,
 * long before anything wants a database, and a process woken only to be told the network
 * came back should not open Room to hear it.
 */
class PerchApp : Application(), Configuration.Provider {

    private val containerDelegate = lazy { AppContainer.create(this) }

    val container: AppContainer by containerDelegate

    /**
     * Owns everything started at process start.
     *
     * Issue #1: this used to be an anonymous `CoroutineScope(...)` built at the `launch`
     * site, so nothing could cancel it and nothing handled what it threw. A failure went to
     * the global uncaught-exception handler, from which `runTest` billed it to whichever
     * test ran *next* — which is why the flake's victim moved with the test ordering. The
     * handler keeps a startup failure out of that channel; [onTerminate] gives the scope an
     * end. Startup scheduling is best-effort by nature: the next launch tries again.
     */
    internal val startupScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            CoroutineExceptionHandler { _, error -> Log.w(TAG, "startup work failed", error) },
    )

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(PerchWorkerFactory(feeds = { container.feeds }, backfill = { container.backfill }))
            .build()

    override fun onCreate() {
        super.onCreate()
        // KEEP, so a reader who chose a different interval keeps it across launches — and
        // seeded from the persisted choice rather than from the default, because "Manual"
        // schedules nothing at all: enqueueing the default here would quietly put a
        // reader who turned background refresh off back on it at the next cold launch.
        startupScope.launch {
            WorkScheduler.ensureScheduled(this@PerchApp, container.settings.current().refreshInterval)
        }
    }

    /**
     * Android never calls this on a device — a real process is killed, not retired. It is
     * called by Robolectric's `tearDownApplication` at the end of every test, which is
     * exactly the boundary issue #1's leaked startup work used to cross.
     */
    override fun onTerminate() {
        startupScope.cancel()
        if (containerDelegate.isInitialized()) container.close()
        super.onTerminate()
    }

    private companion object {
        const val TAG = "PerchApp"
    }
}
