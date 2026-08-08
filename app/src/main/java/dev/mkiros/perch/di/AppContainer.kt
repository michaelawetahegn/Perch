package dev.mkiros.perch.di

import android.content.Context
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.net.ConnectivityMonitor
import dev.mkiros.perch.data.net.FeedFetcher
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.repo.ArticleTextRepository
import dev.mkiros.perch.data.repo.EntryRepository
import dev.mkiros.perch.data.repo.FeedRepository
import dev.mkiros.perch.data.repo.FolderRepository
import dev.mkiros.perch.data.repo.OpmlRepository
import dev.mkiros.perch.data.repo.ProfileRepository
import dev.mkiros.perch.data.settings.SettingsStore
import okhttp3.OkHttpClient
import java.time.Clock

/**
 * The object graph, assembled by hand (SPEC.md §2 — no DI framework for an app this size).
 *
 * One container per process, held by `PerchApp`; screens reach it through their ViewModel
 * factories rather than by looking it up, so a Robolectric test can hand a screen a
 * container built over an in-memory database and a `MockWebServer` client.
 *
 * Everything is `lazy` for the same reason the worker configuration was: a process woken
 * only to run background refresh should not open Room, and a process showing the reading
 * list should not build an HTTP stack until something asks to fetch.
 */
class AppContainer(
    val database: PerchDatabase,
    val httpClient: OkHttpClient,
    val clock: Clock = Clock.systemUTC(),
    /**
     * Defaults to "online" so every test that is not *about* the offline banner can build
     * a container without a shadow network. [create] supplies the real one.
     */
    val connectivity: ConnectivityMonitor = ConnectivityMonitor.AlwaysOnline,
    /**
     * Defaults to a store that keeps nothing, for the same reason [connectivity] defaults
     * to "online": a test about the reading list should not have to own a settings file.
     */
    val settings: SettingsStore = SettingsStore.inMemory(),
) {

    /** One fetcher for feeds, discovery and article pages — one client, one set of limits. */
    private val fetcher: FeedFetcher by lazy { FeedFetcher(httpClient) }

    val feeds: FeedRepository by lazy {
        FeedRepository(
            feedDao = database.feedDao(),
            entryDao = database.entryDao(),
            fetcher = fetcher,
            clock = clock,
        )
    }

    /** U10: the article screen's way of getting text a feed did not ship. */
    val articleText: ArticleTextRepository by lazy {
        ArticleTextRepository(entryDao = database.entryDao(), fetcher = fetcher, clock = clock)
    }

    val folders: FolderRepository by lazy {
        FolderRepository(folderDao = database.folderDao(), clock = clock)
    }

    val entries: EntryRepository by lazy {
        EntryRepository(entryDao = database.entryDao(), clock = clock)
    }

    val opml: OpmlRepository by lazy {
        OpmlRepository(feedDao = database.feedDao(), folders = folders, clock = clock)
    }

    /** U14: the whole reading identity, out to one file and back. */
    val profile: ProfileRepository by lazy {
        ProfileRepository(
            feedDao = database.feedDao(),
            entryDao = database.entryDao(),
            folders = folders,
            clock = clock,
        )
    }

    companion object {
        fun create(context: Context): AppContainer {
            val app = context.applicationContext
            return AppContainer(
                database = PerchDatabase.build(app),
                httpClient = PerchHttp.client(app.cacheDir),
                connectivity = ConnectivityMonitor.system(app),
                settings = SettingsStore.create(app),
            )
        }
    }
}
