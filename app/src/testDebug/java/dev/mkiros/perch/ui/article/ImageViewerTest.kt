package dev.mkiros.perch.ui.article

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.map.Mapper
import coil.request.Options
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.ui.article.zoom.ImageViewer
import dev.mkiros.perch.ui.article.zoom.ZoomGeometry
import dev.mkiros.perch.ui.article.zoom.ZoomState
import dev.mkiros.perch.ui.article.zoom.ZoomedImage
import dev.mkiros.perch.ui.theme.PerchTheme
import dev.mkiros.perch.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * U12's viewer, driven by actual fingers.
 *
 * The arithmetic is [dev.mkiros.perch.ui.article.zoom.ZoomGeometryTest]'s job; what is left
 * here is the wiring, and the wiring is where a zoomable image usually goes wrong — the
 * gesture that reaches the wrong handler, the dismiss that fires mid-pan, the back press
 * that pops the article instead of closing the overlay. Each test below is one of those.
 *
 * `NATIVE` graphics because the transform is applied by a real `graphicsLayer`; the scroll
 * state behind the overlay is read directly, since "the article is where I left it" is a
 * claim about state and not about pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageViewerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val state = ZoomState()
    private val open = mutableStateOf(false)
    private var dismissed = false

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
    }

    @Test
    fun `a pinch past the maximum settles at the maximum rather than running away`() {
        showViewer()

        compose.onNodeWithTag(ArticleTestTags.IMAGE_VIEWER).performTouchInput {
            pinch(
                start0 = center - Offset(SPREAD, 0f),
                end0 = center - Offset(SPREAD * PINCH_FACTOR, 0f),
                start1 = center + Offset(SPREAD, 0f),
                end1 = center + Offset(SPREAD * PINCH_FACTOR, 0f),
            )
        }
        compose.waitForIdle()

        assertThat(state.scale).isWithin(TOLERANCE).of(ZoomGeometry.MAX_SCALE)
        assertThat(dismissed).isFalse()
    }

    @Test
    fun `a double tap zooms in, and a second one returns to fit`() {
        showViewer()

        compose.onNodeWithTag(ArticleTestTags.IMAGE_VIEWER).performTouchInput { doubleClick() }
        compose.waitForIdle()

        assertThat(state.scale).isWithin(TOLERANCE).of(ZoomGeometry.DOUBLE_TAP_SCALE)

        compose.onNodeWithTag(ArticleTestTags.IMAGE_VIEWER).performTouchInput { doubleClick() }
        compose.waitForIdle()

        assertThat(state.scale).isWithin(TOLERANCE).of(ZoomGeometry.MIN_SCALE)
        assertThat(dismissed).isFalse()
    }

    @Test
    fun `a drag down at fit dismisses the viewer`() {
        showViewer()

        compose.onNodeWithTag(ArticleTestTags.IMAGE_VIEWER).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertThat(dismissed).isTrue()
    }

    /**
     * The failure this whole feature is usually shipped with: at 3× the same downward drag
     * is a pan, and if it also dismisses, the reader cannot look at the bottom of a diagram.
     */
    @Test
    fun `the same drag while zoomed pans the image and does not dismiss`() {
        showViewer()
        compose.runOnIdle { state.onGesture(centroid = Offset.Zero, pan = Offset.Zero, zoom = 3f) }

        compose.onNodeWithTag(ArticleTestTags.IMAGE_VIEWER).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertThat(dismissed).isFalse()
        assertThat(state.scale).isWithin(TOLERANCE).of(3f)
        assertThat(state.offset.y).isGreaterThan(0f)
    }

    @Test
    fun `a tap outside the image closes the viewer`() {
        showViewer()

        compose.onNodeWithTag(ArticleTestTags.IMAGE_VIEWER).performClick()
        // A single tap is only a single tap once the double-tap window has closed, and that
        // window is wall-clock time the test clock does not spend on its own.
        compose.mainClock.advanceTimeBy(DOUBLE_TAP_WINDOW_MS)
        compose.waitForIdle()

        assertThat(dismissed).isTrue()
    }

    @Test
    fun `back closes the viewer and leaves the article where the reader left it`() {
        val scroll = showViewerOverScrolledArticle()

        val scrolledTo = scroll()
        assertThat(scrolledTo).isGreaterThan(0)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertThat(dismissed).isTrue()
        assertThat(scroll()).isEqualTo(scrolledTo)
    }

    /** The viewer over nothing in particular: every gesture test but the last one. */
    private fun showViewer() {
        compose.setContent {
            PerchTheme(mode = ThemeMode.Dark, dynamicColor = false) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Viewer()
                }
            }
        }
        open.value = true
        compose.waitForIdle()
    }

    /**
     * The viewer over a long, already-scrolled article — the real arrangement, where the
     * overlay is a sibling of the reading surface rather than a destination on top of it.
     *
     * The scroll happens *before* the viewer opens, because once it is open it covers the
     * article and eats the gesture — which is the point of an overlay and would otherwise
     * make this test scroll nothing.
     */
    private fun showViewerOverScrolledArticle(): () -> Int {
        var read: () -> Int = { 0 }
        compose.setContent {
            PerchTheme(mode = ThemeMode.Dark, dynamicColor = false) {
                val scroll = rememberScrollState()
                read = { scroll.value }
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll)) {
                        repeat(PARAGRAPHS) { index ->
                            Text(text = "Paragraph $index", modifier = Modifier.height(PARAGRAPH_HEIGHT.dp))
                        }
                    }
                    Viewer()
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Paragraph 0").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle { open.value = true }
        compose.waitForIdle()
        return read
    }

    @Composable
    private fun Viewer() {
        if (open.value) {
            ImageViewer(
                image = ZoomedImage(url = IMAGE_URL, alt = "A diagram"),
                onDismiss = {
                    dismissed = true
                    open.value = false
                },
                state = state,
            )
        }
    }

    /** Coil offline: one real bitmap, so the viewer has a content aspect to bound pans with. */
    private class StubImage(private val context: Context) : Mapper<String, Drawable> {
        override fun map(data: String, options: Options): Drawable? = if (data == IMAGE_URL) {
            BitmapDrawable(
                context.resources,
                Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888),
            )
        } else {
            null
        }

        private companion object {
            const val WIDTH = 800
            const val HEIGHT = 1200
        }
    }

    private companion object {
        const val IMAGE_URL = "https://example.com/diagram.png"
        const val TOLERANCE = 0.05f

        /** Half the gap between the pinching fingers, and how far apart they end up. */
        const val SPREAD = 40f
        const val PINCH_FACTOR = 20f

        /** Comfortably past `ViewConfiguration`'s 300 ms double-tap timeout. */
        const val DOUBLE_TAP_WINDOW_MS = 1_000L

        const val PARAGRAPHS = 40
        const val PARAGRAPH_HEIGHT = 80
    }
}
