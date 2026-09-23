package dev.mkiros.perch.di

import android.content.Context
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.document.DocumentStore
import dev.mkiros.perch.data.document.PageRasterizer
import dev.mkiros.perch.data.document.PdfRendererRasterizer
import dev.mkiros.perch.data.net.ConnectivityMonitor
import dev.mkiros.perch.data.net.FeedFetcher
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.repo.ArticleTextRepository
import dev.mkiros.perch.data.repo.BackfillRepository
import dev.mkiros.perch.data.repo.DocumentOpener
import dev.mkiros.perch.data.repo.EntryRepository
import dev.mkiros.perch.data.repo.FeedRepository
import dev.mkiros.perch.data.repo.FolderRepository
import dev.mkiros.perch.data.repo.OpmlRepository
import dev.mkiros.perch.data.repo.ProfileRepository
import dev.mkiros.perch.data.repo.SavedLinkRepository
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.model.BackfillRunner
import dev.mkiros.perch.model.Incoming
import dev.mkiros.perch.model.RefreshScheduler
import dev.mkiros.perch.work.WorkManagerBackfillRunner
import dev.mkiros.perch.work.WorkScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.File
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
    /**
     * The **device's zoned** clock, not `systemUTC()` (issue #9 / PLAN-3 §0).
     *
     * A `Clock` carries a zone as well as an instant, and everything downstream that
     * computes a *calendar* answer reads it. That used to include the reading list's own
     * window: U07 opened Today at midnight in `clock.zone`, and with `systemUTC()` that
     * midnight was Greenwich's, so west of Greenwich the Feed emptied every evening once
     * local time passed UTC midnight (19:00 CDT / 18:00 CST). W02/#15 made the window a
     * rolling twenty-four hours, which has no midnight to get wrong — but the zone stays,
     * because every date a reader *reads* is still a calendar answer (`RelativeTime` past
     * a week, the article byline). The zone is resolved once, when the process builds its
     * container; a reader who crosses a zone sees the new one from the next launch.
     */
    val clock: Clock = Clock.systemDefaultZone(),
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
    /**
     * WorkManager, as the Feed sees it. A seam on the container rather than something a
     * ViewModel factory builds for itself, so that no factory needs a `Context` (D22 /
     * issue #55) — the container is the one place that knows how this app is assembled.
     *
     * Defaults to [BackfillRunner.NoOp] for the same reason [connectivity] defaults to
     * "online": a test about anything else should not have to own a WorkManager.
     */
    val backfillRunner: BackfillRunner = BackfillRunner.NoOp,
    /** WorkManager as Settings sees it; [backfillRunner]'s reasoning, periodic side. */
    val refreshScheduler: RefreshScheduler = RefreshScheduler { },
    /** Stored PDF documents (PLAN-13 G01). Constructed over `filesDir/documents`. */
    val documents: DocumentStore,
    /** Renders document pages to bitmaps (PLAN-13 G03). */
    val rasterizer: PageRasterizer = PdfRendererRasterizer(),
    /** Shares and document imports offered to Perch (PLAN-13 §0.8), oldest first. */
    val intake: MutableStateFlow<List<Incoming>> = MutableStateFlow(emptyList()),
    /**
     * Opens content URIs for document imports (PLAN-13 §0.8). [create] wires the content
     * resolver; the default opens nothing, for the same reason [connectivity] defaults.
     */
    val documentOpener: DocumentOpener = DocumentOpener { null },
) : Closeable {

    /**
     * Ends everything the container owns. Issue #1: the settings store's writer scope had
     * no owner, so a write still in flight when a test's data directory went away threw
     * into nothing and was billed to whichever test ran next. `PerchApp.onTerminate` calls
     * this, and Robolectric calls that at the end of every test.
     */
    override fun close() {
        settings.close()
        database.close()
    }

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

    /** Z02: fills a subscribed source's history in behind its feed (PLAN-7 §0.3, issue #21). */
    val backfill: BackfillRepository by lazy {
        BackfillRepository(
            feedDao = database.feedDao(),
            entryDao = database.entryDao(),
            archivePostDao = database.archivePostDao(),
            fetcher = fetcher,
            clock = clock,
        )
    }

    /** Y03: a pasted link, saved without ever subscribing to its site (PLAN-6 §0.3/§0.4). */
    val savedLinks: SavedLinkRepository by lazy {
        SavedLinkRepository(
            feedDao = database.feedDao(),
            entryDao = database.entryDao(),
            fetcher = fetcher,
            clock = clock,
            documents = documents,
            rasterizer = rasterizer,
            documentOpener = documentOpener,
        )
    }

    val folders: FolderRepository by lazy {
        FolderRepository(folderDao = database.folderDao(), clock = clock)
    }

    val entries: EntryRepository by lazy {
        EntryRepository(entryDao = database.entryDao(), clock = clock, documents = documents)
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
                backfillRunner = WorkManagerBackfillRunner(app),
                refreshScheduler = { interval -> WorkScheduler.setInterval(app, interval) },
                documents = DocumentStore(File(app.filesDir, "documents")),
                documentOpener = DocumentOpener { uri ->
                    try {
                        app.contentResolver.openInputStream(uri)
                    } catch (e: Exception) {
                        null
                    }
                },
            )
        }
    }
}
