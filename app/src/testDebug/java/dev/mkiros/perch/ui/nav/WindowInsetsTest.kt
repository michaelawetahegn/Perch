package dev.mkiros.perch.ui.nav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.map.Mapper
import coil.request.Options
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.CUTOUT_PX
import dev.mkiros.perch.ui.NAVIGATION_BAR_PX
import dev.mkiros.perch.ui.STATUS_BAR_PX
import dev.mkiros.perch.ui.applyWindowInsets
import dev.mkiros.perch.ui.article.ArticleTestTags
import dev.mkiros.perch.ui.article.zoom.ImageViewer
import dev.mkiros.perch.ui.article.zoom.ZoomedImage
import dev.mkiros.perch.ui.home.HomeTestTags
import dev.mkiros.perch.ui.home.TimeFilter
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
import dev.mkiros.perch.ui.theme.PerchTheme
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
import java.time.Clock

/**
 * V04 (issue #3): the app's one window-inset contract, asserted where it can be broken.
 *
 * The app is edge-to-edge, so *nothing* is safe by default — a surface either pads itself
 * or it is under a bar. The contract, stated once in [PerchNavHost]'s doc comment, has
 * four claims, and this is one test per claim:
 *
 * 1. Chrome that Material owns keeps Material's defaults — the top app bar and the drawer
 *    sheet pad for the status bar because `TopAppBar` and `ModalDrawerSheet` already do.
 * 2. The bottom bar pads for the navigation bar, and the screen above it **does not pad
 *    for it a second time** — the bar covers the handle, so the shell consumes that inset
 *    for the `NavHost` region rather than letting both spend it.
 * 3. An overlay that bypasses a `Scaffold` by design opts in explicitly: the image
 *    viewer's furniture sits inside `safeDrawing`, its figure does not.
 *
 * Every test here dispatches insets by hand ([applyWindowInsets]) — see that function for
 * why a Robolectric profile alone proves nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class WindowInsetsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer
    private lateinit var navController: NavHostController

    /** U07: the shell tests are not about the day filter, so it must not empty the list. */
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
            clock = Clock.systemDefaultZone(),
            settings = settings,
        )
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components { add(StubImage(context)) }
                .dispatcher(Dispatchers.Main.immediate)
                .fetcherDispatcher(Dispatchers.Main.immediate)
                .decoderDispatcher(Dispatchers.Main.immediate)
                .transformationDispatcher(Dispatchers.Main.immediate)
                .build(),
        )
    }

    @After
    fun tearDown() {
        Coil.reset()
        database.close()
    }

    // ---- the overlay, which is the one with a reported symptom ---------------------

    @Test
    fun `the image viewer's close button clears the status bar and the cutout`() {
        showViewer()

        compose.applyWindowInsets(cutoutPx = CUTOUT_PX)

        val top = compose.onNodeWithTag(ArticleTestTags.IMAGE_VIEWER_CLOSE)
            .getUnclippedBoundsInRoot().top
        assertThat(top.value).isAtLeast(px(CUTOUT_PX).value)
    }

    @Test
    fun `the figure itself still fills the screen behind the bars`() {
        showViewer()

        compose.applyWindowInsets(cutoutPx = CUTOUT_PX)

        // The point of a full-screen viewer: only the furniture moves out of the way.
        val figure = compose.onNodeWithTag(ArticleTestTags.IMAGE_VIEWER_IMAGE)
            .getUnclippedBoundsInRoot()
        assertThat(figure.top.value).isWithin(TOLERANCE).of(0f)
    }

    // ---- the shell ------------------------------------------------------------------

    @Test
    fun `the bottom bar clears the navigation bar`() {
        showNavHost()

        compose.applyWindowInsets()

        val bar = compose.onNodeWithTag(NavTestTags.BOTTOM_BAR).getUnclippedBoundsInRoot()
        val tab = compose.onNodeWithTag(NavTestTags.tab(PerchTab.Feed)).getUnclippedBoundsInRoot()
        assertThat((bar.bottom - tab.bottom).value)
            .isAtLeast(px(NAVIGATION_BAR_PX).value - TOLERANCE)
    }

    @Test
    fun `the feed does not pad for a navigation bar the bottom bar already covers`() {
        showNavHost()

        compose.applyWindowInsets()

        // Two siblings, one inset: without the shell consuming it, the screen's Scaffold
        // leaves a navigation-bar-sized strip of dead space directly above the bar.
        val list = compose.onNodeWithTag(HomeTestTags.LIST).getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag(NavTestTags.BOTTOM_BAR).getUnclippedBoundsInRoot()
        assertThat((bar.top - list.bottom).value).isWithin(TOLERANCE).of(0f)
    }

    @Test
    fun `the home title clears the status bar`() {
        showNavHost()

        compose.applyWindowInsets()

        val title = compose.onNodeWithTag(HomeTestTags.TITLE).getUnclippedBoundsInRoot()
        assertThat(title.top.value).isAtLeast(px(STATUS_BAR_PX).value)
    }

    @Test
    fun `the drawer's wordmark clears the status bar`() {
        showNavHost()
        compose.applyWindowInsets()

        openDrawer()

        // The lockup collapses its own semantics onto one label (see `PerchWordmark`), so
        // it is addressed by that label rather than by a tag it deliberately clears.
        val mark = compose.onNodeWithContentDescription(APP_NAME).getUnclippedBoundsInRoot()
        assertThat(mark.top.value).isAtLeast(px(STATUS_BAR_PX).value)
    }

    // ---- harness --------------------------------------------------------------------

    private val zoomed = mutableStateOf<ZoomedImage?>(null)

    private fun showViewer() {
        zoomed.value = ZoomedImage(IMAGE_URL, alt = "A figure")
        compose.setContent {
            PerchTheme(dynamicColor = false) {
                Box(modifier = Modifier.fillMaxSize()) {
                    zoomed.value?.let { image ->
                        ImageViewer(image = image, onDismiss = { zoomed.value = null })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showNavHost() {
        compose.setContent {
            PerchTheme(dynamicColor = false) {
                navController = rememberNavController()
                PerchNavHost(container = container, navController = navController)
            }
        }
        compose.waitForIdle()
    }

    /**
     * The drawer is opened through the app bar's own control rather than a `DrawerState`
     * this test holds, because the thing under test is the sheet as the shell composes it.
     * The sheet animates in on a coroutine, so the wait is in wall-clock time.
     */
    private fun openDrawer() {
        compose.onNodeWithContentDescription(OPEN_SOURCES)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.awaitInRealTime("the drawer sheet") {
            compose.onAllNodesWithContentDescription(APP_NAME).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** [px] at this test's density (xhdpi ⇒ 2.0), as a [Dp] the bounds can be compared to. */
    private fun px(value: Int): Dp = (value / DENSITY).dp

    /** A stand-in figure — Coil never leaves the JVM. */
    private class StubImage(private val context: Context) : Mapper<String, Drawable> {
        override fun map(data: String, options: Options): Drawable? {
            if (data != IMAGE_URL) return null
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.GRAY)
            return BitmapDrawable(context.resources, bitmap)
        }

        private companion object {
            const val WIDTH = 800
            const val HEIGHT = 600
        }
    }

    private companion object {
        const val IMAGE_URL = "https://example.com/figure.png"
        const val APP_NAME = "Perch"
        const val OPEN_SOURCES = "Open sources"
        const val DENSITY = 2f

        /** Layout rounds to whole pixels; half a pixel at xhdpi is a quarter of a dp. */
        const val TOLERANCE = 0.5f
    }
}
