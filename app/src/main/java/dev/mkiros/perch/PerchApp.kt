package dev.mkiros.perch

import android.app.Application
import androidx.work.Configuration
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.net.FeedFetcher
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.repo.FeedRepository
import dev.mkiros.perch.work.PerchWorkerFactory
import dev.mkiros.perch.work.WorkScheduler
import java.time.Clock

/**
 * Builds what the background refresh needs and starts it.
 *
 * The dependencies are `lazy` on purpose: WorkManager asks for this configuration during
 * startup, long before anything wants a database, and a process woken only to be told the
 * network came back should not open Room to hear it. T20 moves these three into
 * `di/AppContainer` when the UI needs them too.
 */
class PerchApp : Application(), Configuration.Provider {

    private val database by lazy { PerchDatabase.build(this) }

    private val feeds by lazy {
        FeedRepository(
            feedDao = database.feedDao(),
            entryDao = database.entryDao(),
            fetcher = FeedFetcher(PerchHttp.client(cacheDir)),
            clock = Clock.systemUTC(),
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(PerchWorkerFactory { feeds })
            .build()

    override fun onCreate() {
        super.onCreate()
        // KEEP, so a reader who chose a different interval keeps it across launches.
        WorkScheduler.ensureScheduled(this)
    }
}
