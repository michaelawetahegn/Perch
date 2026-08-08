package dev.mkiros.perch.ui.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.map.Mapper
import coil.request.Options
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.debug.DebugSeeder
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.article.ArticleScreen
import dev.mkiros.perch.ui.collection.CollectionTestTags
import dev.mkiros.perch.ui.nav.NavTestTags
import dev.mkiros.perch.ui.nav.PerchNavHost
import dev.mkiros.perch.ui.nav.PerchTab
import dev.mkiros.perch.ui.article.ArticleUiState
import dev.mkiros.perch.ui.article.ArticleViewModel
import dev.mkiros.perch.ui.home.HomeScreen
import dev.mkiros.perch.ui.home.HomeTestTags
import dev.mkiros.perch.ui.home.HomeViewModel
import dev.mkiros.perch.ui.home.TimeFilter
import dev.mkiros.perch.ui.source.AddSourceViewModel
import dev.mkiros.perch.ui.theme.PerchTheme
import dev.mkiros.perch.ui.theme.ThemeMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
 * The six screenshots T29's polish pass is a critique of, rendered as real pixels on the
 * JVM — `@GraphicsMode(NATIVE)` and no emulator, seconds per capture.
 *
 * Two things make them worth looking at. The content is the **T28 seed**, so every screen
 * shows real headlines from real feeds that went in through `FeedRepository.add` — nothing
 * here flatters the renderer with markup a live feed would never have produced. And the
 * theme is pinned to the `#3F6E5A` fallback scheme (`dynamicColor = false`), so the pixels
 * are the same on any machine and a re-capture after a fix is a real comparison.
 *
 * Each test also asserts the file landed and is not a blank slab, so this stays a gate
 * rather than a side effect: a screen that silently stops composing fails here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class DesignScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer
    private lateinit var homeViewModel: HomeViewModel

    private val now = Instant.parse("2026-08-07T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /**
     * These tests are about the list, the drawer and the row — not about U07's window,
     * which [dev.mkiros.perch.ui.home.HomeTimeFilterTest] owns. Home opens on Today, so
     * without this every entry seeded a day or two back would be filtered out and the
     * assertions would be about an empty screen.
     */
    private val settings = SettingsStore.inMemory().also {
        runBlocking { it.setTimeFilter(TimeFilter.AllTime) }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = PerchDatabase.inMemory(context)
        container = AppContainer(
            database = database,
            httpClient = PerchHttp.client(cacheDir = null),
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        Coil.reset()
        database.close()
    }

    @Test
    fun `the unread list in dark`() {
        seed()
        showHome(ThemeMode.Dark)

        capture("home-dark")
    }

    @Test
    fun `the unread list in light`() {
        seed()
        showHome(ThemeMode.Light)

        capture("home-light")
    }

    /**
     * U08a's time range, closed and open. The closed shot is the one that matters most:
     * the whole point of the change is how little of the screen the control spends when
     * the reader is not using it.
     */
    @Test
    fun `the time range control over the list`() {
        seed()
        showHome(ThemeMode.Dark)

        capture("time-range-closed")
    }

    @Test
    fun `the time range menu, open`() {
        seed()
        showHome(ThemeMode.Dark)
        compose.onNodeWithTag(HomeTestTags.TIME_RANGE)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        capture("time-range-open")
    }

    @Test
    fun `the source drawer over the list`() {
        seed()
        sortIntoFolders()
        showHome(ThemeMode.Dark)
        compose.onNodeWithContentDescription("Open sources").performClick()
        compose.waitForIdle()

        capture("drawer")
    }

    @Test
    fun `an article on the reading surface`() {
        seed()
        showArticle(firstReadableEntryOf("nullprogram.com"))

        capture("article")
    }

    @Test
    fun `the add-source sheet`() {
        seed()
        showHome(ThemeMode.Dark)
        compose.onNodeWithContentDescription("Open sources").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Add source").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        capture("add-source")
    }

    /**
     * U09's To-Read list under the bottom bar, rendered through the **real shell** rather
     * than through [CollectionScreen] alone: the bar is the thing being looked at, and it
     * lives outside the `NavHost` precisely so no one screen owns it.
     */
    @Test
    fun `the To-Read list with the bottom bar`() {
        seed()
        saveSomeEntries(count = 6)
        showShell(ThemeMode.Dark)
        compose.onNodeWithTag(NavTestTags.tab(PerchTab.ToRead)).performClick()
        compose.awaitInRealTime("the queue to load") {
            compose.onAllNodesWithTag(CollectionTestTags.ENTRY).fetchSemanticsNodes().isNotEmpty()
        }

        capture("to-read-dark")
    }

    @Test
    fun `the first launch with no sources`() {
        showHome(ThemeMode.Light)

        capture("empty-state")
    }

    // ---- harness ---------------------------------------------------------------

    /**
     * Writes the whole screen to `screenshots/<name>.png` and asserts it is worth looking
     * at. The size floor is T29's Done-condition; the distinct-colour check is what
     * actually catches the failure that matters, a capture of a blank slab where the
     * screen never composed. The drawing itself is [Screenshots.capture], which T32
     * shares.
     */
    private fun capture(name: String) {
        val shot = Screenshots.capture(compose, compose.activity, Screenshots.dir("screenshots"), name)

        assertThat(shot.file.length()).isGreaterThan(10_000L)
        assertThat(shot.distinctColours).isGreaterThan(8)
    }

    /** Fills the database from the T28 seed assets, the same way a debug install does. */
    private fun seed() = runBlocking {
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val added = DebugSeeder(assets, container.feeds, clock).seedIfEmpty()
        assertThat(added).isGreaterThan(0)
    }

    /**
     * Splits the seeded sources across folders so the drawer shot shows what U06 built —
     * two named sections and the built-in one — rather than a flat list under a single
     * header, which is what an unsorted seed would produce.
     */
    private fun sortIntoFolders() = runBlocking {
        val byHost = database.feedDao().getAll().associateBy { feed ->
            feed.feedUrl.substringAfter("://").substringBefore("/")
        }
        FOLDER_LAYOUT.forEach { (name, hosts) ->
            val folderId = container.folders.createFolder(name)
            hosts.forEach { host ->
                byHost.entries.firstOrNull { it.key.contains(host) }?.let { (_, feed) ->
                    container.folders.moveSource(feed.id, folderId)
                }
            }
        }
    }

    /** An entry with a body, from the source whose feed URL contains [host]. */
    private fun firstReadableEntryOf(host: String): Long = runBlocking {
        val feed = database.feedDao().getAll().first { it.feedUrl.contains(host) }
        database.entryDao().observeAll().first()
            .first { it.feedId == feed.id && !it.contentHtml.isNullOrBlank() }
            .id
    }

    /**
     * Home's rows are half thumbnail since U08, and Robolectric has no network — so
     * without a loader that answers, every shot of the list would be a wall of
     * placeholders and the critique could not judge the thing it exists to judge. This
     * maps any URL to a flat 16:9 bitmap, so a row whose entry carries an `imageUrl`
     * (krebsonsecurity and blog.regehr.org, in this seed) draws an image and the rest draw
     * the placeholder — the mix U08 asks to see.
     *
     * The article shot deliberately gets no loader: a failed figure collapsing is that
     * screen's designed behaviour (§8) and the T25 tests own it.
     */
    private fun stubThumbnails() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components { add(FlatColourImages(context)) }
                .dispatcher(Dispatchers.Main.immediate)
                .fetcherDispatcher(Dispatchers.Main.immediate)
                .decoderDispatcher(Dispatchers.Main.immediate)
                .transformationDispatcher(Dispatchers.Main.immediate)
                .build(),
        )
    }

    /** Every URL becomes the same muted slab — a stand-in for a lead image, not a mock of one. */
    private class FlatColourImages(private val context: Context) : Mapper<String, Drawable> {
        override fun map(data: String, options: Options): Drawable {
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(COLOUR)
            return BitmapDrawable(context.resources, bitmap)
        }

        private companion object {
            const val WIDTH = 320
            const val HEIGHT = 180
            const val COLOUR = 0xFF5B7F6E.toInt()
        }
    }

    private fun showHome(mode: ThemeMode) {
        stubThumbnails()
        homeViewModel = HomeViewModel(
            entries = container.entries,
            feeds = container.feeds,
            folders = container.folders,
            clock = clock,
            settings = settings,
        )
        val addSourceViewModel = AddSourceViewModel(container.feeds, container.folders)
        compose.setContent {
            PerchTheme(mode = mode, dynamicColor = false) {
                HomeScreen(
                    viewModel = homeViewModel,
                    addSourceViewModel = addSourceViewModel,
                    onOpenEntry = {},
                    onOpenSettings = {},
                )
            }
        }
        compose.awaitInRealTime("the list to load") { !homeViewModel.uiState.value.isLoading }
    }

    /**
     * How the eight seeded sources split up. Two folders and a remainder, deliberately:
     * the shot has to show sections *and* the built-in Uncategorized one below them.
     */
    private val FOLDER_LAYOUT = listOf(
        "Systems" to listOf("nullprogram.com", "blog.regehr.org", "fabiensanglard.net"),
        "Security" to listOf("doar-e.github.io", "krebsonsecurity.com"),
    )

    /** Files the newest [count] seeded entries under *Read later*, newest saved first. */
    private fun saveSomeEntries(count: Int) = runBlocking {
        database.entryDao().observeAll().first().take(count)
            .forEach { container.entries.setSaved(it.id, isSaved = true) }
    }

    private fun showShell(mode: ThemeMode) {
        stubThumbnails()
        compose.setContent {
            PerchTheme(mode = mode, dynamicColor = false) {
                PerchNavHost(container = container)
            }
        }
        compose.waitForIdle()
    }

    private fun showArticle(entryId: Long) {
        val viewModel = ArticleViewModel(
            entries = container.entries,
            feeds = container.feeds,
            entryId = entryId,
            zone = ZoneOffset.UTC,
        )
        compose.setContent {
            PerchTheme(mode = ThemeMode.Light, dynamicColor = false) {
                ArticleScreen(viewModel = viewModel, onBack = {})
            }
        }
        compose.awaitInRealTime("the article to load") {
            viewModel.state.value !is ArticleUiState.Loading
        }
    }
}
