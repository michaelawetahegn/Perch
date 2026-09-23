package dev.mkiros.perch.ui.article

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.parse.ArticleBlock
import dev.mkiros.perch.data.parse.FetchedPage
import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.document.PageRasterizer
import dev.mkiros.perch.data.document.PageSource
import dev.mkiros.perch.data.repo.ArticleTextRepository
import dev.mkiros.perch.data.repo.EntryRepository
import dev.mkiros.perch.support.LaunchedJobs
import dev.mkiros.perch.support.MapPageFetcher
import dev.mkiros.perch.support.PerchRule
import dev.mkiros.perch.support.awaitInRealTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import java.util.concurrent.Executors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * D14/#47 — the article screen's three interactive actions, which had no test of their own.
 *
 * The screen loads once by id and has no flow behind it, so every one of these actions has
 * to keep two things in step by hand: the row in the database, and the copy of it on
 * screen. That is the whole risk here, and it is why each test asserts *both* — a toggle
 * that only wrote would leave the icon showing the state the reader just left, and one that
 * only echoed would lose the tap on the next open.
 *
 * The network is [MapPageFetcher]: `loadFullArticle` is a fetch, and this suite never makes
 * one. Everything else is real — the container's own repositories over an in-memory
 * database, so a write here is the write the app performs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ArticleViewModelTest {

    private val now = Instant.parse("2026-09-08T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @get:Rule
    val perch = PerchRule(clock = clock)

    private val fetcher = MapPageFetcher()

    @Before
    fun setUp() {
        // `viewModelScope` is Main. Unconfined so an action's `launch` starts on the
        // calling thread — but the suspension points inside it are Room's and the
        // fetcher's own executors, so every assertion still waits in wall-clock time
        // (NOTES.md: waiting on Room is not waiting on the screen).
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- Read later, and Liked (U09) --------------------------------------------------

    @Test
    fun `Read later writes the flag and shows it without waiting for a reload`() {
        val id = seedEntry()
        val viewModel = articleViewModel(id)

        viewModel.toggleSaved()

        assertThat(loaded(viewModel).isSaved).isTrue()
        assertThat(stored(id) { it.isSaved }.isSaved).isTrue()
    }

    @Test
    fun `tapping Read later a second time takes the entry back off the queue`() {
        val id = seedEntry(savedAt = now.toEpochMilli())
        val viewModel = articleViewModel(id)
        assertThat(loaded(viewModel).isSaved).isTrue()

        viewModel.toggleSaved()

        assertThat(loaded(viewModel).isSaved).isFalse()
        assertThat(stored(id) { !it.isSaved }.isSaved).isFalse()
    }

    @Test
    fun `Like writes the flag and shows it, and leaves Read later alone`() {
        val id = seedEntry(savedAt = now.toEpochMilli())
        val viewModel = articleViewModel(id)

        viewModel.toggleLiked()

        assertThat(loaded(viewModel).isLiked).isTrue()
        assertThat(loaded(viewModel).isSaved).isTrue()
        assertThat(stored(id) { it.isStarred }.isStarred).isTrue()
        assertThat(stored(id) { it.isSaved }.isSaved).isTrue()
    }

    @Test
    fun `tapping Like a second time unlikes it`() {
        val id = seedEntry(starredAt = now.toEpochMilli())
        val viewModel = articleViewModel(id)

        viewModel.toggleLiked()

        assertThat(loaded(viewModel).isLiked).isFalse()
        assertThat(stored(id) { !it.isStarred }.isStarred).isFalse()
    }

    // ---- Load full article (U10) -------------------------------------------------------

    @Test
    fun `loading the full article replaces the feed's body with the page's`() {
        val id = seedEntry()
        servePage(ARTICLE_HTML)
        val viewModel = articleViewModel(id)
        assertThat(loaded(viewModel).canLoadFullText).isTrue()

        loadFullArticle(viewModel)

        assertThat(blockText(viewModel)).contains(BODY_SENTENCE)
        assertThat(loaded(viewModel).isFetchingFullText).isFalse()
        // Offered once, and only once: the body now came from an extraction.
        assertThat(loaded(viewModel).canLoadFullText).isFalse()
        assertThat(stored(id).fullTextAt).isEqualTo(now.toEpochMilli())
    }

    /**
     * F10/#60. A load the caller stopped is not "an extraction no better than the body":
     * the coroutine must unwind at the cancellation, not carry on to write that the fetch
     * is over. The seam is [MapPageFetcher.cancelOn] — the `CancellationException` comes
     * up out of the fetch while the ViewModel is still alive to record something.
     */
    @Test
    fun `a full-text load cancelled at the fetch does not write that the fetch ended`() {
        val id = seedEntry()
        fetcher.cancelOn = LINK
        val viewModel = articleViewModel(id)

        val launched = LaunchedJobs(viewModel.viewModelScope)
        viewModel.loadFullArticle()
        runBlocking { launched.current.joinAll() }

        assertThat(loaded(viewModel).isFetchingFullText).isTrue()
        assertThat(loaded(viewModel).canLoadFullText).isTrue()
    }

    /**
     * The rule that makes the action safe to offer: an extraction that loses changes
     * nothing, so a reader who taps it on a page Perch cannot read keeps what they had.
     */
    @Test
    fun `a page that yields nothing better leaves the body the reader already had`() {
        val id = seedEntry()
        servePage("<html><body><article><p>A stub.</p></article></body></html>")
        val viewModel = articleViewModel(id)

        loadFullArticle(viewModel)

        assertThat(blockText(viewModel)).contains("The feed shipped the whole article, at length.")
        assertThat(blockText(viewModel)).doesNotContain("A stub.")
        assertThat(loaded(viewModel).isFetchingFullText).isFalse()
        // Still on offer — nothing was extracted, so the reader is not stuck with the stub.
        assertThat(loaded(viewModel).canLoadFullText).isTrue()
    }

    /** An unreachable page is the same outcome as a thin one: no crash, no spinner left on. */
    @Test
    fun `an unreachable page stops the spinner and leaves the article readable`() {
        val id = seedEntry()
        val viewModel = articleViewModel(id)

        loadFullArticle(viewModel)

        assertThat(loaded(viewModel).isFetchingFullText).isFalse()
        assertThat(blockText(viewModel)).contains("The feed shipped the whole article, at length.")
    }

    /**
     * D03/#36 at the screen's level: the flags the reader tapped while the page was in
     * flight are on the row that comes back, so the state the fetch publishes carries them
     * rather than the ones it started with.
     */
    @Test
    fun `a Read later tapped during the fetch survives the article arriving`() {
        val id = seedEntry()
        servePage(ARTICLE_HTML)
        val viewModel = articleViewModel(id)

        viewModel.toggleSaved()
        loadFullArticle(viewModel)

        assertThat(blockText(viewModel)).contains(BODY_SENTENCE)
        assertThat(loaded(viewModel).isSaved).isTrue()
        assertThat(stored(id) { it.isSaved }.isSaved).isTrue()
    }

    /**
     * The other half of U10: a body the feed only teased is fetched on open, without the
     * reader asking. The action above is the way out of a *missed* teaser; this is the
     * case the heuristic is supposed to catch on its own.
     */
    @Test
    fun `an entry the feed only teased is fetched on open without being asked`() {
        val id = seedEntry(contentHtml = TEASER)
        servePage(ARTICLE_HTML)

        val viewModel = articleViewModel(id)
        awaitInRealTime("the article the feed withheld") { blockText(viewModel).contains(BODY_SENTENCE) }

        assertThat(fetcher.requested).containsExactly(LINK)
        assertThat(blockText(viewModel)).doesNotContain(TEASER_SENTENCE)
    }

    /** Nothing to load twice: a second tap while one fetch is running is not a second fetch. */
    @Test
    fun `the action does nothing once the body has come from an extraction`() {
        val id = seedEntry()
        servePage(ARTICLE_HTML)
        val viewModel = articleViewModel(id)
        loadFullArticle(viewModel)
        val after = fetcher.requested.size

        loadFullArticle(viewModel)

        assertThat(fetcher.requested).hasSize(after)
    }

    // ---- the reading position (E01, #65) -------------------------------------------------

    @Test
    fun `where the reader stopped is written through the repository`() {
        val id = seedEntry()
        val viewModel = articleViewModel(id)

        viewModel.saveScrollPosition(1_234)

        assertThat(stored(id) { it.scrollPosition == 1_234 }.scrollPosition).isEqualTo(1_234)
    }

    @Test
    fun `an article opens carrying the position it was left at`() {
        val id = seedEntry()
        runBlocking { perch.database.entryDao().setScrollPosition(id, 640) }

        val viewModel = articleViewModel(id)

        assertThat(loaded(viewModel).scrollPosition).isEqualTo(640)
    }

    // ---- every settled scroll at the same offset writes once (F06, #71) ------------------

    @Test
    fun `two settles at the same offset reach the repository once`() {
        val id = seedEntry()
        val viewModel = articleViewModel(id)

        viewModel.saveScrollPosition(400)
        viewModel.saveScrollPosition(400)

        assertThat(stored(id) { it.scrollPosition == 400 }.scrollPosition).isEqualTo(400)
        assertThat(entries.scrollWrites).isEqualTo(1)
    }

    @Test
    fun `a settle at a new offset reaches the repository again`() {
        val id = seedEntry()
        val viewModel = articleViewModel(id)

        viewModel.saveScrollPosition(400)
        viewModel.saveScrollPosition(401)

        assertThat(stored(id) { it.scrollPosition == 401 }.scrollPosition).isEqualTo(401)
        assertThat(entries.scrollWrites).isEqualTo(2)
    }

    @Test
    fun `the first settle after open at the stored position writes nothing`() {
        val id = seedEntry()
        runBlocking { perch.database.entryDao().setScrollPosition(id, 640) }
        val viewModel = articleViewModel(id)

        viewModel.saveScrollPosition(640)

        assertThat(entries.scrollWrites).isEqualTo(0)
    }

    // ---- documents (PLAN-13 G05) --------------------------------------------------------

    @Test
    fun `a document entry loads with its page count and aspects and no blocks`() {
        val docPath = copyFixtureTo("mixed-sizes")
        val id = seedEntry(
            title = "A PDF",
            contentHtml = null,
            documentPath = docPath,
        )
        val viewModel = articleViewModel(id)
        val state = loaded(viewModel)

        assertThat(state.document).isNotNull()
        assertThat(state.document!!.pageCount).isEqualTo(3)
        assertThat(state.document!!.aspects[1]).isWithin(0.01f).of(1.5f)
        assertThat(state.blocks).isEmpty()
        assertThat(state.standfirst).isNull()
        assertThat(state.canLoadFullText).isFalse()
    }

    @Test
    fun `a document never triggers the automatic full-text fetch`() {
        val docPath = copyFixtureTo("mixed-sizes")
        val id = seedEntry(
            title = "A PDF",
            contentHtml = null,
            link = LINK,
            documentPath = docPath,
        )
        val viewModel = articleViewModel(id)

        assertThat(fetcher.requested).isEmpty()
    }

    @Test
    fun `a document whose file is gone says so`() {
        val docPath = copyFixtureTo("mixed-sizes")
        val id = seedEntry(
            title = "A PDF",
            contentHtml = null,
            documentPath = docPath,
        )
        val file = perch.container.documents.resolve(docPath)
        file?.delete()

        val viewModel = articleViewModel(id)
        val state = loaded(viewModel)

        assertThat(state.document).isNull()
        assertThat(state.documentGone).isTrue()
    }

    @Test
    fun `Load full article is not offered for a document`() {
        val docPath = copyFixtureTo("mixed-sizes")
        val id = seedEntry(
            title = "A PDF",
            contentHtml = null,
            documentPath = docPath,
        )
        val viewModel = articleViewModel(id)

        assertThat(loaded(viewModel).canLoadFullText).isFalse()
    }

    @Test
    fun `the page a document stopped on is written through saveScrollPosition`() {
        val docPath = copyFixtureTo("mixed-sizes")
        val id = seedEntry(
            title = "A PDF",
            contentHtml = null,
            documentPath = docPath,
        )
        val viewModel = articleViewModel(id)

        viewModel.saveScrollPosition(3)
        viewModel.saveScrollPosition(3)
        viewModel.saveScrollPosition(4)

        assertThat(stored(id) { it.scrollPosition == 4 }.scrollPosition).isEqualTo(4)
        assertThat(entries.scrollWrites).isEqualTo(2)
    }

    /**
     * Measuring means opening every page in turn — a hundred page opens on a long paper — and
     * `viewModelScope` is the main thread. Main is a named thread here, because the test's own
     * Unconfined stand-in resumes on Room's executor and would hide the difference.
     */
    @Test
    fun `a document's pages are measured off the main thread`() {
        val docPath = copyFixtureTo("mixed-sizes")
        val id = seedEntry(title = "A PDF", contentHtml = null, documentPath = docPath)
        val main = Executors.newSingleThreadExecutor { Thread(it, MAIN_THREAD) }
        Dispatchers.setMain(main.asCoroutineDispatcher())
        val openedOn = mutableListOf<String>()
        val recording = object : PageRasterizer {
            override fun open(file: java.io.File): PageSource? {
                openedOn += Thread.currentThread().name
                return perch.container.rasterizer.open(file)
            }
        }

        try {
            val viewModel = newViewModel(id, rasterizer = recording)
            awaitInRealTime("the article to load") { viewModel.state.value is ArticleUiState.Loaded }

            assertThat(loaded(viewModel).document!!.pageCount).isEqualTo(3)
            assertThat(openedOn).hasSize(1)
            // Coroutine debug mode suffixes a thread's name while a coroutine runs on it.
            assertThat(openedOn.filter { it.startsWith(MAIN_THREAD) }).isEmpty()
            viewModel.viewModelScope.cancel()
        } finally {
            main.shutdown()
        }
    }

    // ---- harness -------------------------------------------------------------------------

    /** Constructed and *loaded* — the `init` read is Room's, so it is waited out here once. */
    private fun articleViewModel(entryId: Long): ArticleViewModel =
        newViewModel(entryId).also { viewModel ->
            awaitInRealTime("the article to load") { viewModel.state.value is ArticleUiState.Loaded }
        }

    /**
     * The container's own repository, counting one thing (F06, #71): the count moves on the
     * calling thread, before Room is reached, so it is exact the moment an action returns.
     */
    private val entries: CountingEntries by lazy {
        CountingEntries(perch.database.entryDao(), clock, perch.container.documents)
    }

    private class CountingEntries(entryDao: EntryDao, clock: Clock, documents: dev.mkiros.perch.data.document.DocumentStore) : EntryRepository(entryDao, clock, documents) {
        var scrollWrites = 0
            private set

        override suspend fun setScrollPosition(entryId: Long, scrollPosition: Int) {
            scrollWrites++
            super.setScrollPosition(entryId, scrollPosition)
        }
    }

    private fun newViewModel(
        entryId: Long,
        rasterizer: PageRasterizer = perch.container.rasterizer,
    ) = ArticleViewModel(
        entries = entries,
        feeds = perch.container.feeds,
        articleText = ArticleTextRepository(
            entryDao = perch.database.entryDao(),
            fetcher = fetcher,
            clock = clock,
        ),
        entryId = entryId,
        zone = ZoneOffset.UTC,
        rasterizer = rasterizer,
        documents = perch.container.documents,
    )

    private fun loaded(viewModel: ArticleViewModel): ArticleUiState.Loaded =
        viewModel.state.value as ArticleUiState.Loaded

    /** The body as flat prose — what the reader ends up looking at, not how it is boxed. */
    private fun blockText(viewModel: ArticleViewModel): String =
        loaded(viewModel).blocks.joinToString(" ") { block ->
            when (block) {
                is ArticleBlock.Paragraph -> block.text.text
                is ArticleBlock.Heading -> block.text.text
                is ArticleBlock.Code -> block.text
                else -> ""
            }
        }

    /** The row as the database really holds it, once [predicate] holds of it. */
    private fun stored(entryId: Long, predicate: (EntryEntity) -> Boolean = { true }): EntryEntity {
        awaitInRealTime("the entry row to be written") { predicate(row(entryId)) }
        return row(entryId)
    }

    private fun row(entryId: Long) = runBlocking { perch.database.entryDao().findById(entryId)!! }

    /** Runs *Load full article* and waits out the fetch, however it ended. */
    private fun loadFullArticle(viewModel: ArticleViewModel) {
        viewModel.loadFullArticle()
        awaitInRealTime("the full-text fetch to settle") { !loaded(viewModel).isFetchingFullText }
    }

    private fun servePage(html: String) {
        fetcher.pages[LINK] = FetchedPage(html.toByteArray(), "text/html", LINK)
    }

    private fun seedEntry(
        contentHtml: String? = FEED_BODY,
        savedAt: Long? = null,
        starredAt: Long? = null,
        documentPath: String? = null,
        title: String = "A post",
        link: String? = LINK,
        author: String? = "A writer",
        publishedAt: Long = now.toEpochMilli(),
    ): Long = perch.seedEntry(
        perch.seedFeed(title = "A blog"),
        title = title,
        link = link,
        author = author,
        publishedAt = publishedAt,
        contentHtml = contentHtml,
        savedAt = savedAt,
        starredAt = starredAt,
        documentPath = documentPath,
    )

    private fun copyFixtureTo(slug: String): String {
        val fixture = dev.mkiros.perch.data.document.DocumentFixtures.manifest()
            .find { it.slug == slug }
            ?: throw IllegalArgumentException("Unknown fixture: $slug")

        val destFile = perch.container.documents.newDocument()
        fixture.file.copyTo(destFile, overwrite = true)
        return perch.container.documents.relativize(destFile)
            ?: throw IllegalStateException("Could not relativize $destFile")
    }

    private companion object {
        const val LINK = "https://example.com/post"
        const val MAIN_THREAD = "test-main"
        const val BODY_SENTENCE = "This is the article the feed did not ship."
        const val TEASER_SENTENCE = "A one-line teaser."

        /**
         * A body long enough that `FullText.needsExtraction` says no, so opening the
         * article does *not* start a fetch of its own — every test below that fetches
         * does so because it asked to, and the toggles race nothing.
         */
        val FEED_BODY = "<p>${"The feed shipped the whole article, at length. ".repeat(40)}</p>"

        /** The other shape: a paragraph standing in for the article (U10 §0). */
        val TEASER = "<p>$TEASER_SENTENCE</p>"

        /** A page with an article on it, so the extractor has something to beat a teaser with. */
        val ARTICLE_HTML = """
            <html><head><title>A post</title></head><body>
              <article>
                <p>$BODY_SENTENCE ${"It runs on for a good while so the extraction wins. ".repeat(60)}</p>
                <p>${"A second paragraph, equally long-winded, for good measure. ".repeat(60)}</p>
              </article>
            </body></html>
        """.trimIndent()
    }
}
