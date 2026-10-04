package dev.mkiros.perch.ui.screenshot

import android.content.Context
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
 * J05's captures, in `build/perch-screenshots/`: the two pages readers reported (#85, #87).
 *
 * Both are **real** — out of `fixtures/articles/`, through the same extract → sanitize → lower
 * path a pasted URL takes. IEEE Spectrum's first in-body photo (a lazy `data-runner-src`) with
 * its caption and credit, and sh4dy's first two Hexo code blocks (line-numbered tables). Every
 * photo is an 800 × 450 grey slab, because Coil never leaves the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ReportedPagesScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun stubPhotos() {
        stubImages(ApplicationProvider.getApplicationContext<Context>()) {
            StubImage(SLAB_WIDTH, SLAB_HEIGHT, colour = android.graphics.Color.GRAY)
        }
    }

    @After
    fun resetCoil() = Coil.reset()

    @Test
    fun `a lazy photo shows with its caption and credit under it in both themes`() {
        val blocks = blocks(IEEE, IEEE_URL)
        val photo = blocks.indexOfFirst { it is ArticleBlock.Image }
        assertThat(photo).isGreaterThan(0)
        val slice = blocks.subList(photo - 1, minOf(blocks.size, photo + 3))

        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            show(slice, mode)
            Screenshots.captureAndAssert(compose, "ieee-figure-${mode.name.lowercase()}")
        }
    }

    @Test
    fun `a Hexo post's line-numbered code reads as code in both themes`() {
        val blocks = blocks(HEXO, HEXO_URL)
        val start = blocks.indexOfFirst {
            it is ArticleBlock.Paragraph && it.text.text.startsWith("Before actually writing LLVM passes")
        }
        assertThat(start).isAtLeast(0)
        val codes = blocks.withIndex().filter { it.index > start && it.value is ArticleBlock.Code }
        val slice = blocks.subList(start, codes[1].index + 1)

        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            show(slice, mode)
            Screenshots.captureAndAssert(compose, "hexo-code-${mode.name.lowercase()}")
        }
    }

    // ---- content ----------------------------------------------------------------

    private fun blocks(fixture: String, url: String): List<ArticleBlock> {
        val html = File(fixtures(), "$fixture.html").readText()
        val extracted = requireNotNull(ArticleExtractor.extract(html, url))
        return ArticleLowering.toBlocks(HtmlSanitizer.sanitize(extracted, url))
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
        const val IEEE = "ieee-spectrum-bloomberg-terminal"
        const val IEEE_URL = "https://spectrum.ieee.org/bloomberg-terminal"
        const val HEXO = "sh4dy-learning-llvm-01"
        const val HEXO_URL = "https://sh4dy.com/2024/06/29/learning_llvm_01/"
        const val SLAB_WIDTH = 800
        const val SLAB_HEIGHT = 450
    }
}
