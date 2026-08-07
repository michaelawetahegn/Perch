package dev.mkiros.perch.ui.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.debug.DebugSeeder
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.article.ArticleScreen
import dev.mkiros.perch.ui.article.ArticleUiState
import dev.mkiros.perch.ui.article.ArticleViewModel
import dev.mkiros.perch.ui.home.HomeScreen
import dev.mkiros.perch.ui.home.HomeViewModel
import dev.mkiros.perch.ui.source.AddSourceViewModel
import dev.mkiros.perch.ui.theme.PerchTheme
import dev.mkiros.perch.ui.theme.ThemeMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PerchDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        container = AppContainer(
            database = database,
            httpClient = PerchHttp.client(cacheDir = null),
            clock = clock,
        )
    }

    @After
    fun tearDown() {
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

    @Test
    fun `the source drawer over the list`() {
        seed()
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
     * screen never composed.
     *
     * It draws the view tree onto a bitmap itself rather than calling `captureToImage()`:
     * that goes through `PixelCopy` and first blocks on a frame-commit callback, which a
     * Robolectric window never delivers — the capture times out after 2s having drawn
     * nothing. With `@GraphicsMode(NATIVE)` a plain `View.draw(Canvas)` rasterizes the
     * same real pixels, and it is synchronous.
     */
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = Bitmap.createBitmap(
            compose.activity.window.decorView.width,
            compose.activity.window.decorView.height,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        windowsFrontToBack().forEach { it.draw(canvas) }

        val file = File(screenshotDir(), "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertThat(file.length()).isGreaterThan(10_000L)
        assertThat(distinctColours(bitmap)).isGreaterThan(8)
    }

    /**
     * Every window on screen, activity first. A sheet or a dialog is a window of its own,
     * so painting only the activity's decor view would screenshot the list with the
     * add-source sheet missing; each extra Compose root is drawn over the one below it,
     * scrim included, which is what the reader sees.
     */
    private fun windowsFrontToBack(): List<View> {
        val decor = compose.activity.window.decorView
        val others = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            .map { (it.root as ViewRootForTest).view.rootView }
            .filter { it !== decor }
            .distinct()
        return listOf(decor) + others
    }

    /** How many distinct colours a coarse sample of the bitmap contains. */
    private fun distinctColours(bitmap: Bitmap): Int {
        val seen = mutableSetOf<Int>()
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                seen += bitmap.getPixel(x, y)
                x += SAMPLE_STRIDE
            }
            y += SAMPLE_STRIDE
        }
        return seen.size
    }

    /** `screenshots/` at the repository root, wherever the test's working directory is. */
    private fun screenshotDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "PLAN.md").isFile) return File(dir, "screenshots").apply { mkdirs() }
            dir = dir.parentFile
        }
        error("PLAN.md not found above ${File("").absolutePath}")
    }

    /** Fills the database from the T28 seed assets, the same way a debug install does. */
    private fun seed() = runBlocking {
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val added = DebugSeeder(assets, container.feeds, clock).seedIfEmpty()
        assertThat(added).isGreaterThan(0)
    }

    /** An entry with a body, from the source whose feed URL contains [host]. */
    private fun firstReadableEntryOf(host: String): Long = runBlocking {
        val feed = database.feedDao().getAll().first { it.feedUrl.contains(host) }
        database.entryDao().observeAll().first()
            .first { it.feedId == feed.id && !it.contentHtml.isNullOrBlank() }
            .id
    }

    private fun showHome(mode: ThemeMode) {
        homeViewModel = HomeViewModel(
            entries = container.entries,
            feeds = container.feeds,
            clock = clock,
        )
        val addSourceViewModel = AddSourceViewModel(container.feeds)
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
        await { !homeViewModel.uiState.value.isLoading }
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
        await { viewModel.state.value !is ArticleUiState.Loading }
    }

    /**
     * Waits in *wall-clock* time, for the reason the other UI tests record: `waitUntil`
     * only advances Compose's virtual clock, so its timeout can expire without Room's
     * query executor — a real background thread here — ever being scheduled.
     */
    private fun await(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (predicate()) {
                compose.waitForIdle()
                return
            }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting for the screen to load")
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 10L

        /** Every Nth pixel in both axes — enough to tell a rendered screen from a slab. */
        const val SAMPLE_STRIDE = 7
    }
}
