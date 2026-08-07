package dev.mkiros.perch

import android.app.Application
import androidx.work.Configuration
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.work.PerchWorkerFactory
import dev.mkiros.perch.work.WorkScheduler

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
        // KEEP, so a reader who chose a different interval keeps it across launches.
        WorkScheduler.ensureScheduled(this)
    }
}
