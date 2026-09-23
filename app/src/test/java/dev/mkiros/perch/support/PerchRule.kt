package dev.mkiros.perch.support

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.FolderEntity
import dev.mkiros.perch.data.document.DocumentStore
import dev.mkiros.perch.data.document.PageRasterizer
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.repo.DocumentOpener
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.model.BackfillRunner
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import java.io.File
import java.time.Clock
import java.time.Duration

/**
 * Opens an in-memory database, builds an [AppContainer] over it, and closes both again.
 *
 * Twenty-two tests used to carry the same fourteen-line `setUp`/`tearDown` pair (D10 /
 * issue #43); this is that pair, once. A test that needs a fixed [clock] or a settings
 * store of its own passes it here and changes nothing else:
 *
 * ```
 * @get:Rule(order = 1) val perch = PerchRule(clock = clock, settings = settings)
 * ```
 *
 * **`order = 1` is load-bearing wherever a Compose rule shares the class**, which is why
 * it is part of the idiom rather than a detail. JUnit applies the *highest* order first,
 * so a higher number is the *inner* rule: this one has to close the database inside the
 * Compose test environment, exactly where the hand-written `@After` closed it. Left to the
 * default the two rules tie, the container closes after the environment has gone, and a
 * coroutine a screen left running — a `viewModelScope` nothing cancels — reaches a closed
 * connection pool with no test scope left to bill it to. JUnit then reports it against
 * whichever test runs next (seen as `UncaughtExceptionsBeforeTest` in `BackfillOfferTest`).
 *
 * [close][AppContainer.close] rather than a bare `database.close()`, because the container
 * also owns the settings store's writer scope (issue #1).
 */
class PerchRule(
    private val clock: Clock = Clock.systemDefaultZone(),
    val settings: SettingsStore = SettingsStore.inMemory(),
    /** D22: the Feed's WorkManager seam is the container's, so a fake arrives the same way. */
    private val backfillRunner: BackfillRunner = BackfillRunner.NoOp,
    /** PLAN-13 G03: the rasterizer for tests (fixtures). */
    private val rasterizer: PageRasterizer = FixtureRasterizer(),
    /** PLAN-13 G10: opens URIs for document imports in tests. */
    private val documentOpener: DocumentOpener = DocumentOpener { null },
) : ExternalResource() {

    /** Valid from the rule's `before` — that is, from `@Before` onwards. */
    lateinit var container: AppContainer
        private set

    val database: PerchDatabase get() = container.database

    override fun before() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        container = AppContainer(
            database = PerchDatabase.inMemory(context),
            httpClient = PerchHttp.client(cacheDir = null),
            clock = clock,
            settings = settings,
            backfillRunner = backfillRunner,
            documents = DocumentStore(File(context.filesDir, "documents")),
            rasterizer = rasterizer,
            documentOpener = documentOpener,
        )
    }

    override fun after() {
        container.close()
    }

    // ---- seeding (F11 / #61) -------------------------------------------------------------
    //
    // [testFeed] and [testEntry] build a row; these insert one and hand back its id. Twelve
    // files used to carry a private copy of each, agreeing on everything below; the defaults
    // are theirs. The rule's [clock] is what dates a row, so a test that fixed its clock gets
    // entries that are two days old *by that clock* and fetched *now* — the shape a refreshed
    // feed has when the Feed opens on Today (U07). Pass a field only when the test is about it.

    /** A folder named [name]. Nothing orders by [sortIndex] (folders sort by name), so 0 is fine. */
    fun seedFolder(name: String, sortIndex: Int = 0): Long = runBlocking {
        database.folderDao().insert(FolderEntity(name = name, sortIndex = sortIndex, createdAt = 0L))
    }

    /** A feed, per [testFeed]'s defaults; the parameters are its. */
    fun seedFeed(
        title: String = "Example",
        feedUrl: String = "https://example.com/${title.hashCode()}/feed.xml",
        siteUrl: String? = "https://example.com",
        customTitle: String? = null,
        faviconUrl: String? = null,
        etag: String? = null,
        lastModified: String? = null,
        lastFetchedAt: Long? = null,
        lastSuccessAt: Long? = null,
        lastError: String? = null,
        consecutiveFailures: Int = 0,
        addedAt: Long = 0L,
        sortIndex: Int = 0,
        folderId: Long = FolderEntity.UNCATEGORIZED_ID,
        isSynthetic: Boolean = false,
    ): Long = runBlocking {
        database.feedDao().insert(
            testFeed(
                title = title,
                feedUrl = feedUrl,
                siteUrl = siteUrl,
                customTitle = customTitle,
                faviconUrl = faviconUrl,
                etag = etag,
                lastModified = lastModified,
                lastFetchedAt = lastFetchedAt,
                lastSuccessAt = lastSuccessAt,
                lastError = lastError,
                consecutiveFailures = consecutiveFailures,
                addedAt = addedAt,
                sortIndex = sortIndex,
                folderId = folderId,
                isSynthetic = isSynthetic,
            ),
        )
    }

    /**
     * An entry of [feedId], per [testEntry] — except that it has a summary, was published two
     * days ago and was fetched now, because that is what every copy said. The read/saved/liked
     * flags follow their timestamps, as [testEntry] derives them.
     */
    fun seedEntry(
        feedId: Long,
        title: String = "Untitled",
        guid: String = "guid-${title.hashCode()}",
        link: String? = "https://example.com/post",
        author: String? = null,
        publishedAt: Long = clock.millis() - Duration.ofDays(2).toMillis(),
        publishedIsEstimated: Boolean = false,
        summary: String? = "A short summary.",
        contentHtml: String? = summary?.let { "<p>$it</p>" },
        imageUrl: String? = null,
        readAt: Long? = null,
        savedAt: Long? = null,
        starredAt: Long? = null,
        bodyIsExcerpt: Boolean = false,
        fullTextAt: Long? = null,
        fetchedAt: Long = clock.millis(),
        documentPath: String? = null,
    ): Long = runBlocking {
        database.entryDao().insert(
            testEntry(
                feedId = feedId,
                title = title,
                guid = guid,
                link = link,
                author = author,
                publishedAt = publishedAt,
                publishedIsEstimated = publishedIsEstimated,
                summary = summary,
                contentHtml = contentHtml,
                imageUrl = imageUrl,
                readAt = readAt,
                savedAt = savedAt,
                starredAt = starredAt,
                bodyIsExcerpt = bodyIsExcerpt,
                fullTextAt = fullTextAt,
                fetchedAt = fetchedAt,
                documentPath = documentPath,
            ),
        )
    }
}
