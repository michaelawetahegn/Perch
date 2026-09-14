package dev.mkiros.perch.ui.home

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.MutableState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.entity.FolderEntity
import dev.mkiros.perch.data.parse.FetchedPage
import dev.mkiros.perch.data.parse.PageFetcher
import dev.mkiros.perch.data.repo.BackfillRepository
import dev.mkiros.perch.data.repo.PerchPaging
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.model.BackfillProgress
import dev.mkiros.perch.model.BackfillRunState
import dev.mkiros.perch.model.BackfillRunner
import dev.mkiros.perch.model.TimeFilter
import dev.mkiros.perch.support.MapPageFetcher
import dev.mkiros.perch.support.PerchRule
import dev.mkiros.perch.ui.screenshot.Screenshots
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
import dev.mkiros.perch.ui.screenshot.showHome as showHomeScreen
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
 * [MapPageFetcher] stands in for the network — this suite never
 * touches either. [BackfillRepository] itself is real, over a stubbed [PageFetcher], so
 * "earned" is [BackfillRepository.plan]'s own call, not a fake answer this test hands back.
 */
@RunWith(RobolectricTestRunner::class)
class BackfillOfferTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: HomeViewModel
    private lateinit var backfill: BackfillRepository
    private val runner = FakeBackfillRunner()
    private lateinit var drawerState: DrawerState
    private lateinit var selection: MutableState<DrawerSelection>
    private lateinit var homeScope: MutableState<HomeScope>

    private val now = Instant.parse("2026-08-24T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val settings = SettingsStore.inMemory().also {
        runBlocking { it.setTimeFilter(TimeFilter.AllTime) }
    }

    private lateinit var previousZone: TimeZone

    @get:Rule(order = 1)
    val perch = PerchRule(clock = clock, backfillRunner = runner)

    @Before
    fun setUp() {
        // §0.4's reach sentence reads `RelativeTime`'s default (system) zone — pinned so
        // the exact date it asserts cannot depend on which machine the suite runs on.
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(previousZone)
    }

    // ---- earned, and only earned -----------------------------------------------------

    @Test
    fun `the offer appears once a source is added and its archive holds materially more`() {
        val fetcher = MapPageFetcher()
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
        val fetcher = MapPageFetcher()
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
        val fetcher = MapPageFetcher()
        val feedId = seedFeed(entryCount = 1)

        showHome(fetcher)
        viewModel.sourceAdded(feedId)
        compose.waitForIdle()

        compose.onNodeWithTag(BackfillTestTags.OFFER_DIALOG).assertDoesNotExist()
    }

    @Test
    fun `when the archive holds more than the cap, the offer states both numbers`() {
        val fetcher = MapPageFetcher()
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
        val fetcher = MapPageFetcher()
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
        val fetcher = MapPageFetcher()
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

    /**
     * D14/#47. Declining is the offer's other exit and the one a reader takes most often;
     * it must cost nothing — no run enqueued, and no trace that would make the dialog come
     * back on its own (the drawer's *Fetch older posts* is how it is asked for again).
     */
    @Test
    fun `declining the offer closes it and fetches nothing`() {
        val fetcher = MapPageFetcher()
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1, POST_2, POST_3)
        val feedId = seedFeed(entryCount = 1)

        showHome(fetcher)
        viewModel.sourceAdded(feedId)
        awaitViewModel { viewModel.backfillOffer.value != null }

        tap(BackfillTestTags.OFFER_DECLINE)

        compose.onNodeWithTag(BackfillTestTags.OFFER_DIALOG).assertDoesNotExist()
        assertThat(viewModel.backfillOffer.value).isNull()
        assertThat(runner.enqueued).isEmpty()
        compose.onNodeWithTag(BackfillTestTags.PROGRESS_STRIP).assertDoesNotExist()
    }

    /**
     * D14/#47. Once the run has finished the strip's `×` is a *dismiss*, not a stop: there
     * is nothing left to cancel, and the reader saying "yes, I saw it" must not read as
     * them taking the run back.
     */
    @Test
    fun `dismissing the finished strip clears it without cancelling anything`() {
        val fetcher = MapPageFetcher()
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1, POST_2, POST_3)
        val feedId = seedFeed(entryCount = 1)

        showHome(fetcher)
        viewModel.sourceAdded(feedId)
        awaitViewModel { viewModel.backfillOffer.value != null }
        tap(BackfillTestTags.OFFER_ACCEPT)
        runner.push(feedId, BackfillProgress(3, 3, BackfillRunState.SUCCEEDED))
        compose.waitForIdle()

        // A finished run offers the dismiss, not the stop — they are different buttons.
        compose.onNodeWithTag(BackfillTestTags.PROGRESS_STOP).assertDoesNotExist()
        tap(BackfillTestTags.PROGRESS_DISMISS)

        compose.onNodeWithTag(BackfillTestTags.PROGRESS_STRIP).assertDoesNotExist()
        assertThat(runner.cancelled).isEmpty()
    }

    // ---- reachable again from the drawer -----------------------------------------------

    @Test
    fun `the drawer's selection bar offers it again for a reader who declined`() {
        val fetcher = MapPageFetcher()
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
        val fetcher = MapPageFetcher()
        val feedId = seedFeed(title = "GPUOpen")
        perch.seedEntry(feedId, "Old one", publishedAt = at("2020-03-31T00:00:00Z"))

        showHome(fetcher)
        tapRow("GPUOpen")
        awaitViewModel { viewModel.sourceReach.value != null }

        compose.onNodeWithTag(BackfillTestTags.REACH_SENTENCE).assertTextEquals("Reaches back to 31 Mar 2020")
    }

    /**
     * S07/#25. The oldest row Perch holds may be one whose date Perch invented, and a
     * sentence built on `MIN(publishedAt)` would then state the day of the fetch as if
     * the source had published it. It states the oldest date the source published for
     * itself instead — the guess is older here, and must not win.
     */
    @Test
    fun `the reach sentence states the oldest date the source published for itself`() {
        val fetcher = MapPageFetcher()
        val feedId = seedFeed(title = "GPUOpen")
        perch.seedEntry(feedId, "Undated one", publishedAt = at("2019-01-05T00:00:00Z"), publishedIsEstimated = true)
        perch.seedEntry(feedId, "Old one", publishedAt = at("2020-03-31T00:00:00Z"))

        showHome(fetcher)
        tapRow("GPUOpen")
        awaitViewModel { viewModel.sourceReach.value != null }

        compose.onNodeWithTag(BackfillTestTags.REACH_SENTENCE).assertTextEquals("Reaches back to 31 Mar 2020")
    }

    /** With no known date anywhere, the sentence still says something — as a guess. */
    @Test
    fun `a source whose every date was guessed reaches back to a guess`() {
        val fetcher = MapPageFetcher()
        val feedId = seedFeed(title = "GPUOpen")
        perch.seedEntry(feedId, "Undated one", publishedAt = at("2020-03-31T00:00:00Z"), publishedIsEstimated = true)

        showHome(fetcher)
        tapRow("GPUOpen")
        awaitViewModel { viewModel.sourceReach.value != null }

        compose.onNodeWithTag(BackfillTestTags.REACH_SENTENCE).assertTextEquals("Reaches back to ~31 Mar 2020")
    }

    @Test
    fun `the reach sentence is absent from the unified inbox`() {
        val fetcher = MapPageFetcher()
        seedFeed(title = "GPUOpen").also { perch.seedEntry(it, "Old one", publishedAt = at("2020-03-31T00:00:00Z")) }

        showHome(fetcher)

        compose.onNodeWithTag(BackfillTestTags.REACH_SENTENCE).assertDoesNotExist()
    }

    // ---- §0.4's footer: the end of All Time offers the next forty (F08/#68) -------------

    /**
     * PLAN-12 F08/#68 — the reader: "if you keep scrolling down into the older history, then
     * it should start retrieving posts in batches". The sitemap lists 100 posts, ten of which
     * the feed already gave us, so the footer names 90 and offers the next 40 — and after
     * that batch has really run (the repository over the stubbed network, not a fake answer)
     * it names 50.
     */
    @Test
    fun `the bottom of a source's All Time list names the archive and loads forty more`() {
        val fetcher = MapPageFetcher()
        val feedId = seedFeed(entryCount = 10, title = "GPUOpen")
        val archive = archivePages(90)
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(*(existingLinks(10) + archive.keys).toTypedArray())
        fetcher.pages += archive

        showHome(fetcher)
        tapRow("GPUOpen")
        scrollToArchiveFooter()

        compose.onNodeWithTag(BackfillTestTags.ARCHIVE_REMAINING).assertTextEquals(remaining(90))
        compose.onNodeWithTag(BackfillTestTags.ARCHIVE_LOAD).assertTextEquals(loadLabel(BackfillRepository.MAX_PAGES))
        tap(BackfillTestTags.ARCHIVE_LOAD)
        assertThat(runner.enqueued).containsExactly(feedId)

        // The fake runner only records the enqueue; the batch itself is the real run.
        runBlocking { backfill.run(feedId) }
        runner.push(feedId, BackfillProgress(BackfillRepository.MAX_PAGES, BackfillRepository.MAX_PAGES, BackfillRunState.SUCCEEDED))
        awaitViewModel { viewModel.sourceReach.value?.entryCount == 10 + BackfillRepository.MAX_PAGES }

        scrollToArchiveFooter()
        compose.onNodeWithTag(BackfillTestTags.ARCHIVE_REMAINING).assertTextEquals(remaining(50))
    }

    /**
     * PLAN-12 F09/#68 — the reader's follow-up: "hitting the bottom and then dragging up
     * should do the same thing that reloading does on other apps". At the end of the list a
     * drag past the threshold is the button: the same batch runs, and forty more rows land.
     */
    @Test
    fun `dragging up past the end of All Time loads the next batch`() {
        val fetcher = MapPageFetcher()
        val feedId = seedFeed(entryCount = 10, title = "GPUOpen")
        val archive = archivePages(90)
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(*(existingLinks(10) + archive.keys).toTypedArray())
        fetcher.pages += archive

        showHome(fetcher)
        tapRow("GPUOpen")
        scrollToArchiveFooter()

        compose.onNodeWithTag(HomeTestTags.ENTRY_LIST).performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertThat(runner.enqueued).containsExactly(feedId)
        runBlocking { backfill.run(feedId) }
        runner.push(feedId, BackfillProgress(BackfillRepository.MAX_PAGES, BackfillRepository.MAX_PAGES, BackfillRunState.SUCCEEDED))
        awaitViewModel { viewModel.sourceReach.value?.entryCount == 10 + BackfillRepository.MAX_PAGES }

        scrollToArchiveFooter()
        compose.onNodeWithTag(BackfillTestTags.ARCHIVE_REMAINING).assertTextEquals(remaining(50))
    }

    /** WorkManager's KEEP policy would let a second tap join the same run silently — so there is no second tap. */
    @Test
    fun `the footer is disabled while a batch runs`() {
        val fetcher = MapPageFetcher()
        val feedId = seedFeed(entryCount = 10, title = "GPUOpen")
        val archive = archivePages(90)
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(*(existingLinks(10) + archive.keys).toTypedArray())
        fetcher.pages += archive

        showHome(fetcher)
        tapRow("GPUOpen")
        scrollToArchiveFooter()
        tap(BackfillTestTags.ARCHIVE_LOAD)

        assertThat(runner.enqueued).containsExactly(feedId)
        compose.onNodeWithTag(BackfillTestTags.ARCHIVE_LOAD)
            .assertIsNotEnabled()
            .assertTextEquals(string(dev.mkiros.perch.R.string.archive_footer_fetching))
        compose.onNodeWithTag(BackfillTestTags.PROGRESS_STRIP).assertIsDisplayed()
    }

    /** §0.4: scoped to one source only, and only under All Time — the reach sentence's own guard. */
    @Test
    fun `the footer is absent in the All-sources scope, in a folder scope, and outside All Time`() {
        val fetcher = MapPageFetcher()
        seedFeed(entryCount = 10, title = "GPUOpen")
        val archive = archivePages(90)
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(*(existingLinks(10) + archive.keys).toTypedArray())
        fetcher.pages += archive

        showHome(fetcher)
        tapRow("GPUOpen")
        scrollToArchiveFooter()

        setScope(HomeScope.All)
        compose.onNodeWithTag(BackfillTestTags.ARCHIVE_FOOTER).assertDoesNotExist()
        setScope(HomeScope.Folder(FolderEntity.UNCATEGORIZED_ID))
        compose.onNodeWithTag(BackfillTestTags.ARCHIVE_FOOTER).assertDoesNotExist()

        tapRow("GPUOpen")
        scrollToArchiveFooter()
        viewModel.selectTimeFilter(TimeFilter.PastYear)
        awaitViewModel { viewModel.uiState.value.timeFilter == TimeFilter.PastYear }
        compose.onNodeWithTag(BackfillTestTags.ARCHIVE_FOOTER).assertDoesNotExist()
    }

    /** With nothing left unfetched the slot is empty and `pagedFooter`'s own marker stands. */
    @Test
    fun `the footer is absent when nothing is left`() {
        val fetcher = MapPageFetcher()
        val count = PerchPaging.PAGE_SIZE + 5
        seedFeed(entryCount = count, title = "GPUOpen")
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(*existingLinks(count).toTypedArray())

        showHome(fetcher)
        tapRow("GPUOpen")
        // The plan has looked at the archive — and found nothing the feed had not covered.
        awaitViewModel { SITE + "sitemap.xml" in fetcher.requested }
        scrollToEnd()

        compose.onNodeWithTag(PagedListTestTags.END).assertIsDisplayed()
        compose.onNodeWithTag(BackfillTestTags.ARCHIVE_FOOTER).assertDoesNotExist()
    }

    /** §0.4: not through `sourceAdded` — its `isWorthwhile` gate would refuse a second batch. */
    @Test
    fun `loadOlder enqueues the runner and marks the feed running without an offer`() {
        val fetcher = MapPageFetcher()
        val feedId = seedFeed(entryCount = 10, title = "GPUOpen")

        showHome(fetcher)
        viewModel.loadOlder(feedId)
        compose.waitForIdle()

        assertThat(runner.enqueued).containsExactly(feedId)
        assertThat(viewModel.backfillOffer.value).isNull()
        compose.onNodeWithTag(BackfillTestTags.OFFER_DIALOG).assertDoesNotExist()
        compose.onNodeWithTag(BackfillTestTags.PROGRESS_STRIP).assertIsDisplayed()
    }

    // ---- screenshots (Z03's Done-condition: opened and looked at) ---------------------

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `screenshot the backfill offer`() {
        // Issue #24: more candidates than MAX_PAGES, so the two counts the dialog states
        // actually differ — the shot this task's Done-condition asks be retaken and looked at.
        val fetcher = MapPageFetcher()
        val many = (1..(BackfillRepository.MAX_PAGES + 5)).map { "https://example.com/2020/01/$it/post-$it" }
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(*many.toTypedArray())
        val feedId = seedFeed(entryCount = 1, title = "A blog")

        showHome(fetcher)
        viewModel.sourceAdded(feedId)
        awaitViewModel { viewModel.backfillOffer.value != null }

        Screenshots.capture(compose, compose.activity, Screenshots.dir(Screenshots.DIR), "backfill-offer")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `screenshot a backfill in progress`() {
        val fetcher = MapPageFetcher()
        fetcher.pages[SITE + "sitemap.xml"] = sitemapOf(POST_1, POST_2, POST_3)
        val feedId = seedFeed(entryCount = 1, title = "A blog")

        showHome(fetcher)
        viewModel.sourceAdded(feedId)
        awaitViewModel { viewModel.backfillOffer.value != null }
        tap(BackfillTestTags.OFFER_ACCEPT)
        runner.push(feedId, BackfillProgress(4, 12, BackfillRunState.RUNNING))
        compose.waitForIdle()

        Screenshots.capture(compose, compose.activity, Screenshots.dir(Screenshots.DIR), "backfill-progress")
    }

    // ---- harness -------------------------------------------------------------------------

    private fun awaitViewModel(predicate: () -> Boolean) =
        compose.awaitInRealTime(
            "the view model to satisfy the test's predicate",
            predicate = predicate,
        )

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

    private fun setScope(scope: HomeScope) {
        compose.runOnUiThread { homeScope.value = scope }
        compose.waitForIdle()
    }

    private fun exists(tag: String): Boolean =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /**
     * The footer sits after every loaded page, so reaching it is the same walk
     * `PagedFeedTest` makes: scroll to it, let the next page land, scroll again. Until the
     * plan has answered there is no footer to scroll to, and the attempt simply retries.
     */
    private fun scrollToArchiveFooter() {
        compose.awaitInRealTime("the archive footer") {
            runCatching {
                compose.onNodeWithTag(HomeTestTags.ENTRY_LIST)
                    .performScrollToNode(hasTestTag(BackfillTestTags.ARCHIVE_FOOTER))
            }
            compose.waitForIdle()
            exists(BackfillTestTags.ARCHIVE_FOOTER)
        }
    }

    private fun scrollToEnd() {
        var row = 0
        compose.awaitInRealTime("the end of the list") {
            val moved = runCatching {
                compose.onNodeWithTag(HomeTestTags.ENTRY_LIST).performScrollToIndex(row)
            }.isSuccess
            if (moved) row++
            compose.waitForIdle()
            exists(PagedListTestTags.END)
        }
    }

    private fun string(id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    private fun remaining(count: Int): String = ApplicationProvider.getApplicationContext<Context>()
        .resources.getQuantityString(dev.mkiros.perch.R.plurals.archive_footer_remaining, count, count)

    private fun loadLabel(count: Int): String = string(dev.mkiros.perch.R.string.archive_footer_load, count)

    private fun showHome(fetcher: PageFetcher) {
        backfill = BackfillRepository(
            feedDao = perch.database.feedDao(),
            entryDao = perch.database.entryDao(),
            archivePostDao = perch.database.archivePostDao(),
            fetcher = fetcher,
            clock = clock,
            // F08 runs a real forty-page batch on the test thread: no politeness pause.
            delay = {},
        )
        val home = showHomeScreen(perch, compose, clock, settings, backfill = backfill)
        viewModel = home.viewModel
        drawerState = home.drawerState
        selection = home.selection
        homeScope = home.homeScope
    }

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /** [entryCount] entries whose links [existingLinks] can name, on a feed fetched now. */
    private fun seedFeed(entryCount: Int = 0, title: String = "A blog"): Long {
        val feedId = perch.seedFeed(
            title = title,
            feedUrl = SITE + "feed.xml",
            siteUrl = SITE.trimEnd('/'),
            lastFetchedAt = now.toEpochMilli(),
            lastSuccessAt = now.toEpochMilli(),
            addedAt = now.toEpochMilli(),
        )
        repeat(entryCount) { i ->
            perch.seedEntry(
                feedId,
                "existing-$i",
                link = "https://example.com/existing-$i",
                publishedAt = now.toEpochMilli(),
            )
        }
        return feedId
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

        /** The links [seedFeed]'s entries carry — a sitemap that lists them is one the feed already covered. */
        fun existingLinks(count: Int): List<String> = (0 until count).map { "https://example.com/existing-$it" }

        /** [count] archive posts, each with a page the extractor will store as an entry. */
        fun archivePages(count: Int): Map<String, FetchedPage> = (1..count).associate { n ->
            val url = "https://example.com/2020/01/$n/post-$n"
            url to FetchedPage(
                """
                <html><head><meta property="og:title" content="Post $n"></head>
                <body><article><p>${"Real prose, with commas and length. ".repeat(30)}</p></article></body></html>
                """.trimIndent().toByteArray(),
                "text/html",
                url,
            )
        }

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
