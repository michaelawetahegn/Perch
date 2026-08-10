package dev.mkiros.perch.ui.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.map.Mapper
import coil.request.Options
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.parse.ArticleBlock
import dev.mkiros.perch.data.parse.RichSpan
import dev.mkiros.perch.ui.CUTOUT_PX
import dev.mkiros.perch.ui.applyWindowInsets
import dev.mkiros.perch.ui.article.ArticleBody
import dev.mkiros.perch.ui.article.ArticleTestTags
import dev.mkiros.perch.ui.article.zoom.ImageViewer
import dev.mkiros.perch.ui.article.zoom.ZoomedImage
import dev.mkiros.perch.ui.theme.Dimens
import dev.mkiros.perch.ui.theme.PerchTheme
import dev.mkiros.perch.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * U12's captures, in `build/perch-screenshots/`.
 *
 * The figure is drawn here rather than fetched, because what the shot has to show is the
 * *viewer* — the scrim, the close affordance, and what a zoomed figure looks like against
 * both — and a real photograph would make that judgement about the photograph. It is a
 * wide, pale schematic on purpose: the two things that can go wrong are furniture lost
 * against light pixels and a scrim too weak to separate the figure from the article, and
 * a light wide image is the case that exposes both.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ImageViewerScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components { add(Schematic(context)) }
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
    fun `a figure opens full screen and zooms`() {
        show(ThemeMode.Dark)

        compose.onNodeWithTag(ArticleTestTags.IMAGE).performClick()
        compose.waitForIdle()
        capture("image-viewer-fit-dark")

        compose.onNodeWithTag(ArticleTestTags.IMAGE_VIEWER).performTouchInput { doubleClick() }
        compose.waitForIdle()
        capture("image-viewer-zoomed-dark")
    }

    /**
     * V04's evidence. Robolectric has no cutout profile, so the insets are dispatched by
     * hand — the shot shows the close affordance sitting below a 66dp punch-hole instead
     * of underneath it, which is the symptom issue #3 reported. The number behind the
     * picture is asserted in `WindowInsetsTest`; this is what it looks like.
     */
    @Test
    fun `the close affordance clears a cutout`() {
        show(ThemeMode.Dark)

        compose.onNodeWithTag(ArticleTestTags.IMAGE).performClick()
        compose.applyWindowInsets(cutoutPx = CUTOUT_PX)

        capture("image-viewer-cutout-dark")
    }

    @Test
    fun `the viewer is the same dark surface in a light article`() {
        show(ThemeMode.Light)

        compose.onNodeWithTag(ArticleTestTags.IMAGE).performClick()
        compose.waitForIdle()

        capture("image-viewer-fit-light")
    }

    // ---- harness ----------------------------------------------------------------

    private val theme = mutableStateOf(ThemeMode.Dark)
    private val zoomed = mutableStateOf<ZoomedImage?>(null)

    private fun show(mode: ThemeMode) {
        theme.value = mode
        compose.setContent {
            PerchTheme(mode = theme.value, dynamicColor = false) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .verticalScroll(rememberScrollState())
                            .padding(Dimens.screenHorizontal),
                    ) {
                        ArticleBody(
                            blocks = article(),
                            articleLink = null,
                            onOpenLink = {},
                            onOpenImage = { zoomed.value = ZoomedImage(it.url, it.alt) },
                        )
                    }
                    zoomed.value?.let { image ->
                        ImageViewer(image = image, onDismiss = { zoomed.value = null })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun article() = listOf(
        ArticleBlock.Heading(2, RichSpan("The scheduler's fast path")),
        ArticleBlock.Paragraph(
            RichSpan(
                "Every wake-up takes one of two routes through the run queue, and which " +
                    "one it takes is decided before the lock is ever acquired.",
            ),
        ),
        ArticleBlock.Image(
            url = IMAGE_URL,
            alt = "The two routes a wake-up can take through the run queue",
            caption = RichSpan("Figure 1 — the fast path, and the one nobody measures."),
        ),
        ArticleBlock.Paragraph(
            RichSpan("The slow path is the interesting one, so it is the one drawn wider."),
        ),
    )

    private fun capture(name: String) {
        val shot = Screenshots.capture(
            compose,
            compose.activity,
            Screenshots.dir("build/perch-screenshots"),
            name,
        )
        assertThat(shot.file.length()).isGreaterThan(0L)
        assertThat(shot.distinctColours).isGreaterThan(MIN_COLOURS)
    }

    /** A pale wide schematic, drawn rather than downloaded — Coil never leaves the JVM. */
    private class Schematic(private val context: Context) : Mapper<String, Drawable> {
        override fun map(data: String, options: Options): Drawable? {
            if (data != IMAGE_URL) return null
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { isAntiAlias = true }

            canvas.drawColor(PAPER)
            paint.color = INK
            paint.strokeWidth = STROKE
            var y = HEIGHT / 5f
            while (y < HEIGHT) {
                canvas.drawLine(MARGIN, y, WIDTH - MARGIN, y, paint)
                y += HEIGHT / 5f
            }
            paint.color = ACCENT
            canvas.drawRect(MARGIN, MARGIN, WIDTH / 3f, HEIGHT / 3f, paint)
            paint.color = INK
            paint.textSize = TEXT
            canvas.drawText("wake_up_process()", MARGIN * 2f, HEIGHT / 2f, paint)
            canvas.drawText("try_to_wake_up()", MARGIN * 2f, HEIGHT / 2f + TEXT * 2f, paint)

            return BitmapDrawable(context.resources, bitmap)
        }

        private companion object {
            const val WIDTH = 1600
            const val HEIGHT = 900
            const val MARGIN = 60f
            const val STROKE = 4f
            const val TEXT = 64f
            const val PAPER = 0xFFF4F1EA.toInt()
            const val INK = 0xFF2C3330.toInt()
            const val ACCENT = 0xFFE0A33A.toInt()
        }
    }

    private companion object {
        const val IMAGE_URL = "https://example.com/run-queue.png"
        const val MIN_COLOURS = 8
    }
}
