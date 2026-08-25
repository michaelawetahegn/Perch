package dev.mkiros.perch.ui.brand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.map.Mapper
import coil.request.Options
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.FolderEntity
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.debug.DebugSeeder
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.home.HomeScreen
import dev.mkiros.perch.ui.home.HomeTestTags
import dev.mkiros.perch.ui.home.HomeViewModel
import dev.mkiros.perch.ui.home.SelectionTestTags
import dev.mkiros.perch.ui.home.TimeFilter
import dev.mkiros.perch.ui.screenshot.Screenshots
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
import dev.mkiros.perch.ui.source.AddSourceViewModel
import dev.mkiros.perch.ui.theme.PerchTheme
import dev.mkiros.perch.ui.theme.ThemeMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
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
 * The brand where it appears inside the app (U09b): the wordmark at the top of the
 * drawer, the mark standing in for an icon on a first launch.
 *
 * Both surfaces are captured in **light and dark**, because that is the one thing the
 * assertions cannot check. The mark is fixed artwork — paper stays paper in both themes
 * — so the risk is not that it inverts wrong, it is that a light page on a dark drawer
 * reads as a sticker stuck onto the app. Only the screenshots show that.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class BrandScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer
    private lateinit var homeViewModel: HomeViewModel

    private val clock = Clock.fixed(Instant.parse("2026-08-07T12:00:00Z"), ZoneOffset.UTC)

    // Home opens on Today and the seed is a few days old — without this the drawer shot
    // would be taken over an empty list, in the wrong empty state.
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
    fun `the drawer header carries the wordmark in dark`() {
        seed()
        showHome(ThemeMode.Dark)
        openDrawer()

        compose.onNodeWithContentDescription(PERCH).assertIsDisplayed()
        capture("brand-drawer-dark")
    }

    @Test
    fun `the drawer header carries the wordmark in light`() {
        seed()
        showHome(ThemeMode.Light)
        openDrawer()

        compose.onNodeWithContentDescription(PERCH).assertIsDisplayed()
        capture("brand-drawer-light")
    }

    @Test
    fun `a first launch shows the mark rather than a generic feed glyph in dark`() {
        showHome(ThemeMode.Dark)

        compose.onNodeWithTag(BrandTestTags.MARK).assertIsDisplayed()
        capture("brand-empty-dark")
    }

    @Test
    fun `a first launch shows the mark rather than a generic feed glyph in light`() {
        showHome(ThemeMode.Light)

        compose.onNodeWithTag(BrandTestTags.MARK).assertIsDisplayed()
        capture("brand-empty-light")
    }

    /**
     * The header band is not the wordmark's by right — U09a's contextual bar occupies the
     * same band, and two things claiming it at once is how a selection count ends up
     * reading as a subtitle to the app's name.
     */
    @Test
    fun `the wordmark stands down while the drawer is selecting`() {
        seed()
        showHome(ThemeMode.Dark)
        openDrawer()
        // §0.1: DebugSeeder's sources land in Uncategorized, shut like every folder.
        compose.onNodeWithTag(HomeTestTags.folderExpand(FolderEntity.UNCATEGORIZED_ID))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        val source = homeViewModel.uiState.value.sources.first().title
        compose.onAllNodesWithText(source).filterToOne(hasClickAction())
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()

        compose.onNodeWithTag(SelectionTestTags.COUNT).assertIsDisplayed()
        val wordmarks = compose.onAllNodesWithContentDescription(PERCH).fetchSemanticsNodes()
        assertThat(wordmarks).isEmpty()
    }

    /**
     * Renders `design/brand/perch-wordmark.png`, which is what the README leads with.
     *
     * It is generated rather than drawn by hand for the same reason the drawer's is:
     * there is one Perch mark, and a README carrying a second, slightly different one is
     * how a brand starts drifting. Captured at **xxxhdpi** so the asset is 4× and stays
     * crisp on a retina screen, and plated on the *light* surface rather than left
     * transparent — a transparent PNG with dark lettering disappears against GitHub's
     * dark theme.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxxhdpi")
    fun `the README wordmark asset`() {
        compose.setContent {
            PerchTheme(mode = ThemeMode.Light, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PerchWordmark()
                    }
                }
            }
        }
        compose.waitForIdle()

        val bounds = compose.onNodeWithContentDescription(PERCH)
            .fetchSemanticsNode().boundsInRoot
        val shot = Screenshots.capture(
            compose,
            compose.activity,
            Screenshots.dir("build/perch-screenshots"),
            "wordmark-full",
        )
        val full = checkNotNull(BitmapFactory.decodeFile(shot.file.path))
        val pad = bounds.height / 2f
        val cropped = Bitmap.createBitmap(
            full,
            (bounds.left - pad).toInt(),
            (bounds.top - pad).toInt(),
            (bounds.width + 2 * pad).toInt(),
            (bounds.height + 2 * pad).toInt(),
        )

        val asset = File(Screenshots.repoRoot(), "design/brand/perch-wordmark.png")
        asset.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // A 4× lockup is wider than it is tall and several hundred pixels across; a crop
        // that silently missed would be neither.
        assertThat(cropped.width).isGreaterThan(cropped.height)
        assertThat(cropped.width).isGreaterThan(400)
    }

    // ---- harness ---------------------------------------------------------------

    private fun openDrawer() {
        compose.onNodeWithContentDescription("Open sources").performClick()
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val shot = Screenshots.capture(
            compose,
            compose.activity,
            Screenshots.dir("build/perch-screenshots"),
            name,
        )

        assertThat(shot.file.length()).isGreaterThan(10_000L)
        assertThat(shot.distinctColours).isGreaterThan(8)
    }

    private fun seed() = runBlocking {
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        assertThat(DebugSeeder(assets, container.feeds, clock).seedIfEmpty()).isGreaterThan(0)
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

    /** T29's stand-in loader: the list behind the drawer draws rows, not placeholders. */
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

    private class FlatColourImages(private val context: Context) : Mapper<String, Drawable> {
        override fun map(data: String, options: Options): Drawable {
            val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xFF5B7F6E.toInt())
            return BitmapDrawable(context.resources, bitmap)
        }
    }

    private companion object {
        const val PERCH = "Perch"
    }
}
