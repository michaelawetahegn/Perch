package dev.mkiros.perch

import android.app.Application
import androidx.work.Configuration
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.work.PerchWorkerFactory
import dev.mkiros.perch.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Holds the process-wide object graph and starts background refresh.
 *
 * [container] is `lazy` on purpose: WorkManager asks for this configuration during startup,
 * long before anything wants a database, and a process woken only to be told the network
 * came back should not open Room to hear it.
 */
class PerchApp : Application(), Configuration.Provider {

    val container: AppContainer by lazy { AppContainer.create(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(PerchWorkerFactory { container.feeds })
            .build()

    override fun onCreate() {
        super.onCreate()
        // KEEP, so a reader who chose a different interval keeps it across launches — and
        // seeded from the persisted choice rather than from the default, because "Manual"
        // schedules nothing at all: enqueueing the default here would quietly put a
        // reader who turned background refresh off back on it at the next cold launch.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            WorkScheduler.ensureScheduled(this@PerchApp, container.settings.current().refreshInterval)
        }
    }
}
