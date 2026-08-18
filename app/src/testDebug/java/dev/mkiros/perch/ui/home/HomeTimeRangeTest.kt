package dev.mkiros.perch.ui.home

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.source.AddSourceViewModel
import dev.mkiros.perch.ui.theme.PerchTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * U08a: the time range is a **dropdown**, not a row of chips.
 *
 * Every behaviour U07 established still binds — persistence across process death,
 * Feed-only scope, the widen affordance — so this is a test of the *control*, not a
 * second test of the window. `HomeTimeFilterTest` still owns which entries survive.
 *
 * The point of the change is that the screen shows the range the reader is in and
 * nothing else: five always-visible chips spend a horizontal band restating the four
 * options they are not choosing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class HomeTimeRangeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer
    private lateinit var settings: SettingsStore
    private lateinit var viewModel: HomeViewModel

    private val clock = Clock.fixed(Instant.parse("2026-08-07T12:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = PerchDatabase.inMemory(context)
        settings = SettingsStore.inMemory()
        container = AppContainer(
            database = database,
            httpClient = PerchHttp.client(cacheDir = null),
            clock = clock,
            settings = settings,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `the closed control names the active range and none of the other four`() {
        seedEntry(seedFeed("Source One"), "This morning")

        showHome()

        rangeLabel().assertTextEquals("Past 24 Hours")
        listOf("Past Week", "Past Month", "Past Year", "All Time").forEach { absent ->
            compose.onNodeWithText(absent).assertDoesNotExist()
        }
    }

    @Test
    fun `the open menu offers all five ranges with exactly one marked selected`() {
        seedEntry(seedFeed("Source One"), "This morning")

        showHome()
        openMenu()

        assertThat(menuItemCount()).isEqualTo(TimeFilter.entries.size)
        assertThat(selectedItemCount()).isEqualTo(1)
        compose.onNodeWithTag(HomeTestTags.rangeItem(TimeFilter.Today)).assertIsSelected()
    }

    @Test
    fun `choosing a range from the menu re-queries the list and closes the menu`() {
        val feedId = seedFeed("Source One")
        seedEntry(feedId, "This morning", at = "2026-08-07T09:00:00Z")
        seedEntry(feedId, "Last month", at = "2026-07-20T09:00:00Z")

        showHome()
        chooseRange(TimeFilter.PastMonth)

        compose.onNodeWithText("Last month").assertIsDisplayed()
        compose.onNodeWithTag(HomeTestTags.RANGE_MENU).assertDoesNotExist()
        rangeLabel().assertTextEquals("Past Month")

        // And the selection moved with it — one marked item, the new one.
        openMenu()
        assertThat(selectedItemCount()).isEqualTo(1)
        compose.onNodeWithTag(HomeTestTags.rangeItem(TimeFilter.PastMonth)).assertIsSelected()
    }

    @Test
    fun `the chosen range comes back out of DataStore on the next launch`() {
        val feedId = seedFeed("Source One")
        seedEntry(feedId, "Last month", at = "2026-07-20T09:00:00Z")

        showHome()
        chooseRange(TimeFilter.PastMonth)

        // A second view model over the same settings — process death, in miniature.
        val relaunched = HomeViewModel(
            entries = container.entries,
            feeds = container.feeds,
            folders = container.folders,
            clock = clock,
            settings = settings,
        )
        val seen = mutableListOf<HomeUiState>()
        val job = MainScope().launch { relaunched.uiState.collect { seen += it } }
        try {
            await({ "the relaunched view model never loaded" }) {
                seen.lastOrNull()?.isLoading == false
            }
            assertThat(seen.last().timeFilter).isEqualTo(TimeFilter.PastMonth)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `the widen affordance moves the dropdown's own selection`() {
        seedEntry(seedFeed("Source One"), "Three days ago", at = "2026-08-04T09:00:00Z")

        showHome()
        // Today is empty, so the way out is the empty state's button — and afterwards the
        // control and the empty state have to agree about which window the reader is in.
        tap(HomeTestTags.EMPTY_WIDEN)
        awaitState { it.timeFilter == TimeFilter.PastWeek }

        rangeLabel().assertTextEquals("Past Week")
        openMenu()
        assertThat(selectedItemCount()).isEqualTo(1)
        compose.onNodeWithTag(HomeTestTags.rangeItem(TimeFilter.PastWeek)).assertIsSelected()
    }

    /**
     * Real font metrics, not Robolectric's stand-in: under the default graphics mode
     * `measureText` returns about a pixel per character, so every string "fits" and a
     * clipping assertion would pass on a control that clips on a phone.
     */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun `the longest label still fits at font scale 1_3`() {
        // The longest of the five, which W02/#15 moved: "Past 24 Hours" is two characters
        // longer than "Past Month" was. Re-pin this when a label changes — the assertion
        // is only worth anything against whichever one is widest.
        seedEntry(seedFeed("Source One"), "This morning")
        runBlocking { settings.setTimeFilter(TimeFilter.Today) }

        showHome(fontScale = 1.3f)

        val node = rangeLabel().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        // The label got every pixel the glyphs asked for, on one line: no ellipsis, no
        // wrap, nothing squeezed. (`hasVisualOverflow` is not the assertion to make here —
        // it also goes true when the paragraph's fractional *height* rounds past the
        // integer layout height, which says nothing about clipping.)
        val layout = layouts.first()
        assertThat(layout.lineCount).isEqualTo(1)
        assertThat(layout.size.width.toFloat()).isAtLeast(layout.multiParagraph.maxIntrinsicWidth)

        // …and the whole control still sits inside the screen rather than off its edge.
        val bounds = compose.onNodeWithTag(HomeTestTags.TIME_RANGE).fetchSemanticsNode()
            .boundsInRoot
        assertThat(bounds.right).isAtMost(compose.activity.window.decorView.width.toFloat())
    }

    // ---- harness -----------------------------------------------------------------

    /**
     * The label inside the control. The button merges its descendants — the chevron's
     * content description lands on the same node as the text — so the text node is only
     * addressable in the unmerged tree.
     */
    private fun rangeLabel() =
        compose.onNodeWithTag(HomeTestTags.TIME_RANGE_LABEL, useUnmergedTree = true)

    private fun openMenu() {
        tap(HomeTestTags.TIME_RANGE)
        compose.onNodeWithTag(HomeTestTags.RANGE_MENU).assertExists()
    }

    private fun chooseRange(filter: TimeFilter) {
        openMenu()
        tap(HomeTestTags.rangeItem(filter))
        awaitState { it.timeFilter == filter }
    }

    private fun menuItemCount(): Int = compose
        .onAllNodes(hasAnyAncestor(hasTestTag(HomeTestTags.RANGE_MENU)) and hasClickAction())
        .fetchSemanticsNodes().size

    private fun selectedItemCount(): Int = compose
        .onAllNodes(hasAnyAncestor(hasTestTag(HomeTestTags.RANGE_MENU)) and isSelected())
        .fetchSemanticsNodes().size

    private fun tap(testTag: String) {
        compose.onNodeWithTag(testTag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun awaitState(predicate: (HomeUiState) -> Boolean) =
        await({ "timed out; last state was ${viewModel.uiState.value}" }) {
            predicate(viewModel.uiState.value)
        }

    /** Polls in wall-clock time: Room's executor is a real thread. */
    private fun await(message: () -> String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (predicate()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError(message())
    }

    private fun showHome(fontScale: Float = 1f) {
        viewModel = HomeViewModel(
            entries = container.entries,
            feeds = container.feeds,
            folders = container.folders,
            clock = clock,
            settings = settings,
        )
        val addSourceViewModel = AddSourceViewModel(container.feeds, container.folders)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                PerchTheme(dynamicColor = false) {
                    HomeScreen(
                        viewModel = viewModel,
                        addSourceViewModel = addSourceViewModel,
                        onOpenEntry = {},
                        onOpenSettings = {},
                    )
                }
            }
        }
        compose.waitUntil(TIMEOUT_MS) { !viewModel.uiState.value.isLoading }
        compose.waitForIdle()
    }

    private fun seedFeed(title: String): Long = runBlocking {
        database.feedDao().insert(
            FeedEntity(
                feedUrl = "https://example.com/${title.hashCode()}/feed.xml",
                siteUrl = "https://example.com",
                title = title,
                customTitle = null,
                faviconUrl = null,
                etag = null,
                lastModified = null,
                lastFetchedAt = null,
                lastSuccessAt = null,
                lastError = null,
                addedAt = 0L,
                folderId = FolderEntity.UNCATEGORIZED_ID,
            ),
        )
    }

    private fun seedEntry(
        feedId: Long,
        title: String,
        at: String = "2026-08-07T09:00:00Z",
    ): Long = runBlocking {
        val published = Instant.parse(at).toEpochMilli()
        database.entryDao().insert(
            EntryEntity(
                feedId = feedId,
                guid = "guid-${title.hashCode()}",
                title = title,
                link = "https://example.com/post",
                author = null,
                publishedAt = published,
                publishedIsEstimated = false,
                summary = null,
                contentHtml = null,
                imageUrl = null,
                isRead = false,
                readAt = null,
                fetchedAt = published,
            ),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
