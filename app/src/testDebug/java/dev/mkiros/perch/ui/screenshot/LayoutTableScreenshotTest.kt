package dev.mkiros.perch.ui.screenshot

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.extract.ArticleExtractor
import dev.mkiros.perch.data.parse.ArticleBlock
import dev.mkiros.perch.data.parse.ArticleLowering
import dev.mkiros.perch.data.parse.HtmlSanitizer
import dev.mkiros.perch.model.ThemeMode
import dev.mkiros.perch.ui.article.ArticleBody
import dev.mkiros.perch.ui.theme.Dimens
import dev.mkiros.perch.ui.theme.PerchTheme
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * H03's captures, in `build/perch-screenshots/`: the top of a page laid out in tables (#83).
 *
 * The page is **real** — Paul Graham's "Making Startups Powerful" out of `fixtures/articles/`,
 * through the same extract → sanitize → lower path a pasted URL takes, so the outer layout
 * table, the nested one holding the essay and the `<br><br>` paragraph breaks are his markup.
 * Only the title GIF is drawn here, at its declared 220 × 18, because Coil never leaves the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class LayoutTableScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun stubTitle() {
        stubImages(ApplicationProvider.getApplicationContext<Context>()) { url ->
            titleGif().takeIf { url.endsWith(TITLE_GIF) }
        }
    }

    @After
    fun resetCoil() = Coil.reset()

    @Test
    fun `a page laid out in tables reads as an essay in both themes`() {
        val blocks = essay()
        assertThat(blocks.filterIsInstance<ArticleBlock.Table>()).isEmpty()

        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            show(blocks, mode)
            Screenshots.captureAndAssert(compose, "layout-table-essay-${mode.name.lowercase()}")
        }
    }

    // ---- content ----------------------------------------------------------------

    private fun essay(): List<ArticleBlock> {
        val html = File(fixtures(), "$FIXTURE.html").readText()
        val extracted = requireNotNull(ArticleExtractor.extract(html, URL))
        return ArticleLowering.toBlocks(HtmlSanitizer.sanitize(extracted, URL))
    }

    /** PG's titles are black serif set on white; a stand-in at the GIF's own size. */
    private fun titleGif() = StubImage(TITLE_WIDTH, TITLE_HEIGHT) {
        drawColor(android.graphics.Color.WHITE)
        val paint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
            textSize = TITLE_TEXT
            typeface = Typeface.SERIF
        }
        drawText("Making Startups Powerful", 0f, TITLE_BASELINE, paint)
    }

    // ---- harness ----------------------------------------------------------------

    private val theme = mutableStateOf(ThemeMode.Light)
    private var content = mutableStateOf(emptyList<ArticleBlock>())
    private var composed = false

    private fun show(blocks: List<ArticleBlock>, mode: ThemeMode) {
        theme.value = mode
        content.value = blocks
        if (!composed) {
            composed = true
            compose.setContent {
                PerchTheme(mode = theme.value, dynamicColor = false) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface)
                                .verticalScroll(rememberScrollState())
                                .padding(Dimens.screenHorizontal),
                        ) {
                            ArticleBody(
                                blocks = content.value,
                                articleLink = null,
                                onOpenLink = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun fixtures(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, "fixtures/articles").takeIf { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        error("fixtures/articles not found")
    }

    private companion object {
        const val FIXTURE = "paulgraham-powerful"
        const val URL = "https://paulgraham.com/powerful.html"
        const val TITLE_GIF = "making-startups-powerful-1.gif"
        const val TITLE_WIDTH = 220
        const val TITLE_HEIGHT = 18
        const val TITLE_TEXT = 15f
        const val TITLE_BASELINE = 14f
    }
}
