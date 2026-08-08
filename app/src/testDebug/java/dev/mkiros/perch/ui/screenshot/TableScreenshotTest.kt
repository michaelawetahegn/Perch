package dev.mkiros.perch.ui.screenshot

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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.parse.ArticleBlock
import dev.mkiros.perch.data.parse.ArticleLowering
import dev.mkiros.perch.data.parse.HtmlSanitizer
import dev.mkiros.perch.data.parse.RichSpan
import dev.mkiros.perch.ui.article.ArticleBody
import dev.mkiros.perch.ui.article.ArticleTestTags
import dev.mkiros.perch.ui.theme.Dimens
import dev.mkiros.perch.ui.theme.PerchTheme
import dev.mkiros.perch.ui.theme.ThemeMode
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * U11a's captures, in `build/perch-screenshots/`.
 *
 * The wide table is **real**: a Zero Day Initiative advisory out of `fixtures/articles/`,
 * through the same sanitize → lower path the app runs, so the six columns and the
 * paragraph-length impact cell are Dustin Childs's markup rather than a shape invented
 * here to flatter the treatment. Only its row count is cut, because a capture of two
 * hundred CVEs shows nothing the first eight do not.
 *
 * The narrow table is hand-set. Two columns of short values is the other end of the range
 * the treatment has to cover — the case where measured columns must *not* stretch to the
 * page — and no source in the corpus publishes one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class TableScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a real ZDI advisory renders as a table in both themes`() {
        val blocks = advisory()

        for (mode in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            show(blocks, mode)
            capture("u11a-advisory-${mode.name.lowercase()}")
        }
    }

    @Test
    fun `a narrow table keeps its columns tight in both themes`() {
        for (mode in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            show(narrow(), mode)
            capture("u11a-narrow-${mode.name.lowercase()}")
        }
    }

    @Test
    fun `scrolling a wide advisory sideways carries its header along`() {
        show(advisory(), ThemeMode.Dark)

        compose.onNodeWithTag(ArticleTestTags.TABLE)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(SCROLL_BY, 0f) }
        compose.waitForIdle()

        capture("u11a-advisory-dark-scrolled")
    }

    // ---- content ----------------------------------------------------------------

    /** The June Apple review: a lead paragraph and the first rows of its CVE table. */
    private fun advisory(): List<ArticleBlock> {
        val html = File(fixtures(), "$ADVISORY.html").readText()
        val blocks = ArticleLowering.toBlocks(
            HtmlSanitizer.sanitize(html, "https://www.thezdi.com/blog/$ADVISORY"),
        )
        val table = blocks.filterIsInstance<ArticleBlock.Table>().first()
        check(table.header.size >= WIDE_COLUMNS) { "the advisory table lost its columns" }

        return blocks.filterIsInstance<ArticleBlock.Paragraph>().take(LEAD_PARAGRAPHS) +
            table.copy(rows = table.rows.take(ADVISORY_ROWS))
    }

    private fun narrow() = listOf(
        ArticleBlock.Heading(2, RichSpan("Severity by CVSS")),
        ArticleBlock.Table(
            header = listOf(RichSpan("Rating"), RichSpan("Score")),
            rows = listOf(
                listOf(RichSpan("Critical"), RichSpan("9.8")),
                listOf(RichSpan("Important"), RichSpan("7.5")),
                listOf(RichSpan("Moderate"), RichSpan("5.3")),
                listOf(RichSpan("Low"), RichSpan("3.1")),
            ),
        ),
    )

    // ---- harness ----------------------------------------------------------------

    private val theme = mutableStateOf(ThemeMode.Dark)
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

    private fun fixtures(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, "fixtures/articles").takeIf { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        error("fixtures/articles not found")
    }

    private companion object {
        const val ADVISORY = "zdi-june-2026-apple-update-review"
        const val ADVISORY_ROWS = 8
        const val LEAD_PARAGRAPHS = 2
        const val WIDE_COLUMNS = 6
        const val SCROLL_BY = 300f
        const val MIN_COLOURS = 8
    }
}
