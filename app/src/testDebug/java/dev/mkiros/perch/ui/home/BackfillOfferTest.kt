package dev.mkiros.perch.ui.home

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.parse.FetchedPage
import dev.mkiros.perch.data.parse.PageFetcher
import dev.mkiros.perch.data.repo.BackfillRepository
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.screenshot.Screenshots
import dev.mkiros.perch.ui.source.AddSourceViewModel
import dev.mkiros.perch.ui.theme.PerchTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * PLAN-7 Z03/#21 — the backfill offer, its count, cancellation, its second entrance from
 * the drawer's selection bar, and §0.4's honest reach sentence.
 *
 * [FakeBackfillRunner] stands in for WorkManager throughout, the same way
 * [BackfillRepositoryTest]'s `FakeFetcher` stands in for the network — this suite never
 * touches either. [BackfillRepository] itself is real, over a stubbed [PageFetcher], so
 * "earned" is [BackfillRepository.plan]'s own call, not a fake answer this test hands back.
 */
@RunWith(RobolectricTestRunner::class)
class BackfillOfferTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer
    private lateinit var viewModel: HomeViewModel
    private lateinit var runner: FakeBackfillRunner
    private lateinit var drawerState: DrawerState
    private lateinit var selection: MutableState<DrawerSelection>

    private val now = Instant.parse("2026-08-24T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val settings = SettingsStore.inMemory().also {
        runBlocking { it.setTimeFilter(TimeFilter.AllTime) }
    }

    private lateinit var previousZone: TimeZone

    @Before
    fun setUp() {
        // §0.4's reach sentence reads `RelativeTime`'s default (system) zone — pinned so
        // the exact date it asserts cannot depend on which machine the suite runs on.
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = PerchDatabase.inMemory(context)
        container = AppContainer(
            database = database,
            httpClient = PerchHttp.client(cacheDir = null),
            clock = clock,
        )
        runner = FakeBackfillRunner()
    }

    @After
    fun tearDown() {
        database.close()
        TimeZone.setDefault(previousZone)
    }

    // ---- earned, and only earned -----------------------------------------------------

    @Test
    fun `the offer appears once a source is added and its archive holds materially more`() {
        val fetcher = FakeFetcher()
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1, POST_2, POST_3)
        val feedId = seedFeed(entryCount = 1)

        showHome(fetcher)
        viewModel.sourceAdded(feedId)
        awaitViewModel { viewModel.backfillOffer.value != null }

        compose.onNodeWithTag(BackfillTestTags.OFFER_DIALOG).assertIsDisplayed()
        val expected = ApplicationProvider.getApplicationContext<Context>().resources
            .getQuantityString(dev.mkiros.perch.R.plurals.backfill_offer_body, 3, 3)
        compose.onNodeWithTag(BackfillTestTags.OFFER_BODY).assertTextEquals(expected)
    }

    @Test
    fun `no offer for a handful of extra posts`() {
        val fetcher = FakeFetcher()
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1)
        val feedId = seedFeed(entryCount = 10)

        showHome(fetcher)
        viewModel.sourceAdded(feedId)
        // Nothing to wait out an emission for — settle the coroutine and check it stayed null.
        compose.waitForIdle()

        compose.onNodeWithTag(BackfillTestTags.OFFER_DIALOG).assertDoesNotExist()
        assertThat(viewModel.backfillOffer.value).isNull()
    }

    @Test
    fun `no offer at all for a source with nothing beyond what the feed already gave us`() {
        val fetcher = FakeFetcher()
        val feedId = seedFeed(entryCount = 1)

        showHome(fetcher)
        viewModel.sourceAdded(feedId)
        compose.waitForIdle()

        compose.onNodeWithTag(BackfillTestTags.OFFER_DIALOG).assertDoesNotExist()
    }

    @Test
    fun `when the archive holds more than the cap, the offer states both numbers`() {
        val fetcher = FakeFetcher()
        val many = (1..(BackfillRepository.MAX_PAGES + 5)).map { "https://example.com/2020/01/$it/post-$it" }
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(*many.toTypedArray())
        val feedId = seedFeed(entryCount = 1)

        showHome(fetcher)
        viewModel.sourceAdded(feedId)
        awaitViewModel { viewModel.backfillOffer.value != null }

        assertThat(viewModel.backfillOffer.value?.newPostCount).isEqualTo(many.size)
        assertThat(viewModel.backfillOffer.value?.pageCount).isEqualTo(BackfillRepository.MAX_PAGES)
        val expected = ApplicationProvider.getApplicationContext<Context>().resources.getQuantityString(
            dev.mkiros.perch.R.plurals.backfill_offer_body_capped,
            BackfillRepository.MAX_PAGES,
            many.size,
            BackfillRepository.MAX_PAGES,
        )
        compose.onNodeWithTag(BackfillTestTags.OFFER_BODY).assertTextEquals(expected)
    }

    // ---- accepting, progress, cancelling ----------------------------------------------

    @Test
    fun `accepting the offer enqueues the run and the strip tracks its progress`() {
        val fetcher = FakeFetcher()
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1, POST_2, POST_3)
        val feedId = seedFeed(entryCount = 1)

        showHome(fetcher)
        viewModel.sourceAdded(feedId)
        awaitViewModel { viewModel.backfillOffer.value != null }
        tap(BackfillTestTags.OFFER_ACCEPT)

        assertThat(runner.enqueued).containsExactly(feedId)
        compose.onNodeWithTag(BackfillTestTags.OFFER_DIALOG).assertDoesNotExist()
        compose.onNodeWithTag(BackfillTestTags.PROGRESS_STRIP).assertIsDisplayed()

        runner.push(feedId, BackfillProgress(1, 3, BackfillRunState.RUNNING))
        compose.waitForIdle()
        compose.onNodeWithText("Fetching older posts… 1/3").assertIsDisplayed()
    }

    @Test
    fun `stopping the strip cancels the run rather than clearing what already landed`() {
        val fetcher = FakeFetcher()
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1, POST_2, POST_3)
        val feedId = seedFeed(entryCount = 1)

        showHome(fetcher)
        viewModel.sourceAdded(feedId)
        awaitViewModel { viewModel.backfillOffer.value != null }
        tap(BackfillTestTags.OFFER_ACCEPT)
        runner.push(feedId, BackfillProgress(1, 3, BackfillRunState.RUNNING))
        compose.waitForIdle()

        tap(BackfillTestTags.PROGRESS_STOP)

        assertThat(runner.cancelled).containsExactly(feedId)
    }

    // ---- reachable again from the drawer -----------------------------------------------

    @Test
    fun `the drawer's selection bar offers it again for a reader who declined`() {
        val fetcher = FakeFetcher()
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1)
        val feedId = seedFeed(entryCount = 1, title = "GPUOpen")

        showHome(fetcher)
        // A declined offer leaves no trace behind it — this is a fresh ask, not a reopen.
        longPress("GPUOpen")
        tap(SelectionTestTags.BACKFILL)
        awaitViewModel { viewModel.backfillOffer.value?.feedId == feedId }

        compose.onNodeWithTag(BackfillTestTags.OFFER_DIALOG).assertIsDisplayed()
        // Selection mode is left behind, the way rename and move leave it (U09a).
        compose.onNodeWithTag(SelectionTestTags.BAR).assertDoesNotExist()
    }

    // ---- §0.4's honest reach ------------------------------------------------------------

    @Test
    fun `All Time scoped to one source states how far its stored history reaches`() {
        val fetcher = FakeFetcher()
        val feedId = seedFeed(title = "GPUOpen")
        seedEntry(feedId, "Old one", Instant.parse("2020-03-31T00:00:00Z"))

        showHome(fetcher)
        tapRow("GPUOpen")
        awaitViewModel { viewModel.sourceReach.value != null }

        compose.onNodeWithTag(BackfillTestTags.REACH_SENTENCE).assertTextEquals("Reaches back to 31 Mar 2020")
    }

    @Test
    fun `the reach sentence is absent from the unified inbox`() {
        val fetcher = FakeFetcher()
        seedFeed(title = "GPUOpen").also { seedEntry(it, "Old one", Instant.parse("2020-03-31T00:00:00Z")) }

        showHome(fetcher)

        compose.onNodeWithTag(BackfillTestTags.REACH_SENTENCE).assertDoesNotExist()
    }

    // ---- screenshots (Z03's Done-condition: opened and looked at) ---------------------

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `screenshot the backfill offer`() {
        // Issue #24: more candidates than MAX_PAGES, so the two counts the dialog states
        // actually differ — the shot this task's Done-condition asks be retaken and looked at.
        val fetcher = FakeFetcher()
        val many = (1..(BackfillRepository.MAX_PAGES + 5)).map { "https://example.com/2020/01/$it/post-$it" }
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(*many.toTypedArray())
        val feedId = seedFeed(entryCount = 1, title = "A blog")

        showHome(fetcher)
        viewModel.sourceAdded(feedId)
        awaitViewModel { viewModel.backfillOffer.value != null }

        Screenshots.capture(compose, compose.activity, Screenshots.dir(SCREENSHOT_DIR), "backfill-offer")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `screenshot a backfill in progress`() {
        val fetcher = FakeFetcher()
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1, POST_2, POST_3)
        val feedId = seedFeed(entryCount = 1, title = "A blog")

        showHome(fetcher)
        viewModel.sourceAdded(feedId)
        awaitViewModel { viewModel.backfillOffer.value != null }
        tap(BackfillTestTags.OFFER_ACCEPT)
        runner.push(feedId, BackfillProgress(4, 12, BackfillRunState.RUNNING))
        compose.waitForIdle()

        Screenshots.capture(compose, compose.activity, Screenshots.dir(SCREENSHOT_DIR), "backfill-progress")
    }

    // ---- harness -------------------------------------------------------------------------

    private fun awaitViewModel(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (predicate()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting on the view-model")
    }

    private fun openDrawer() {
        if (drawerState.isClosed) {
            compose.onNodeWithContentDescription("Open sources").performClick()
        }
        compose.waitForIdle()
    }

    private fun expandInDrawer(folderId: Long) {
        openDrawer()
        if (folderId !in viewModel.expandedFolders.value) {
            compose.onNodeWithTag(HomeTestTags.folderExpand(folderId))
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
        }
    }

    private fun row(label: String) =
        compose.onAllNodesWithText(label)
            .filterToOne(hasClickAction() and !hasTestTag(HomeTestTags.ENTRY))

    private fun longPress(label: String, folderId: Long = FolderEntity.UNCATEGORIZED_ID) {
        expandInDrawer(folderId)
        row(label).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
    }

    private fun tapRow(label: String, folderId: Long = FolderEntity.UNCATEGORIZED_ID) {
        expandInDrawer(folderId)
        row(label).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun tap(testTag: String) {
        compose.onNodeWithTag(testTag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun showHome(fetcher: PageFetcher) {
        val backfill = BackfillRepository(
            feedDao = database.feedDao(),
            entryDao = database.entryDao(),
            fetcher = fetcher,
            clock = clock,
        )
        viewModel = HomeViewModel(
            entries = container.entries,
            feeds = container.feeds,
            folders = container.folders,
            clock = clock,
            settings = settings,
            backfill = backfill,
            backfillRunner = runner,
        )
        val addSourceViewModel = AddSourceViewModel(container.feeds, container.folders)
        compose.setContent {
            drawerState = rememberDrawerState(DrawerValue.Closed)
            selection = rememberSaveable(stateSaver = DrawerSelection.Saver) {
                mutableStateOf<DrawerSelection>(DrawerSelection.None)
            }
            PerchTheme(dynamicColor = false) {
                HomeScreen(
                    viewModel = viewModel,
                    addSourceViewModel = addSourceViewModel,
                    onOpenEntry = {},
                    onOpenSettings = {},
                    drawerState = drawerState,
                    selection = selection,
                )
            }
        }
        compose.waitUntil(TIMEOUT_MS) { !viewModel.uiState.value.isLoading }
        compose.waitForIdle()
    }

    private fun seedFeed(entryCount: Int = 0, title: String = "A blog"): Long = runBlocking {
        val feedId = database.feedDao().insert(
            FeedEntity(
                feedUrl = SITE + "feed.xml",
                siteUrl = SITE.trimEnd('/'),
                title = title,
                customTitle = null,
                faviconUrl = null,
                etag = null,
                lastModified = null,
                lastFetchedAt = now.toEpochMilli(),
                lastSuccessAt = now.toEpochMilli(),
                lastError = null,
                addedAt = now.toEpochMilli(),
            ),
        )
        repeat(entryCount) { i -> seedEntry(feedId, "existing-$i", now) }
        feedId
    }

    private fun seedEntry(feedId: Long, title: String, published: Instant): Long = runBlocking {
        database.entryDao().insert(
            EntryEntity(
                feedId = feedId,
                guid = "guid-${title.hashCode()}",
                title = title,
                link = "https://example.com/$title",
                author = null,
                publishedAt = published.toEpochMilli(),
                publishedIsEstimated = false,
                summary = null,
                contentHtml = null,
                imageUrl = null,
                readAt = null,
                fetchedAt = now.toEpochMilli(),
            ),
        )
    }

    private class FakeFetcher(val pages: MutableMap<String, FetchedPage> = mutableMapOf()) : PageFetcher {
        override suspend fun fetch(url: String): FetchedPage? = pages[url]
    }

    /** No WorkManager anywhere: [enqueue] and [cancel] just record the call, and a test
     *  drives [push] itself to move the strip through its states. */
    private class FakeBackfillRunner : BackfillRunner {
        val enqueued = mutableListOf<Long>()
        val cancelled = mutableListOf<Long>()
        private val progress = mutableMapOf<Long, MutableStateFlow<BackfillProgress?>>()

        override fun enqueue(feedId: Long) {
            enqueued += feedId
            flowFor(feedId).value = BackfillProgress(0, 0, BackfillRunState.RUNNING)
        }

        override fun cancel(feedId: Long) {
            cancelled += feedId
            val current = flowFor(feedId).value
            flowFor(feedId).value = BackfillProgress(
                current?.done ?: 0,
                current?.total ?: 0,
                BackfillRunState.CANCELLED,
            )
        }

        override fun observe(feedId: Long): Flow<BackfillProgress?> = flowFor(feedId)

        fun push(feedId: Long, value: BackfillProgress) {
            flowFor(feedId).value = value
        }

        private fun flowFor(feedId: Long) = progress.getOrPut(feedId) { MutableStateFlow(null) }
    }

    private companion object {
        const val SITE = "https://example.com/"
        const val POST_1 = "https://example.com/2020/01/01/post-one"
        const val POST_2 = "https://example.com/2020/02/02/post-two"
        const val POST_3 = "https://example.com/2020/03/03/post-three"
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        const val SCREENSHOT_DIR = "build/perch-screenshots"

        fun sitemapOf(vararg urls: String) = FetchedPage(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              ${urls.joinToString("\n") { u -> "<url><loc>$u</loc></url>" }}
            </urlset>
            """.trimIndent().toByteArray(),
            "application/xml",
            SITE,
        )
    }
}
