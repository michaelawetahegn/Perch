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
import dev.mkiros.perch.data.parse.FeedParser
import dev.mkiros.perch.data.parse.HtmlSanitizer
import dev.mkiros.perch.data.parse.ParseResult
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
 * U11's four captures, in `build/perch-screenshots/`.
 *
 * The C post is **real**: its blocks come out of the harvested `nullprogram-com.xml` by the
 * same parse → sanitize → lower path the app runs, so the `language-c` class the shots are
 * a test of is one Chris Wellons actually published rather than one written here to make
 * the feature look good. The Kotlin post is hand-set, because none of the 42 sources
 * publishes Kotlin and inventing a source would be worse than admitting the sample.
 *
 * The scrolled capture is the one that matters: it is the only way to see whether the
 * gutter is pinned, and the assertion beside it says so in numbers — a gutter that moved
 * with the code fails here rather than in a critique three tasks later.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class CodeScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a real nullprogram C post renders its highlighted code in both themes`() {
        val blocks = nullprogramCPost()

        for (mode in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            show(blocks, mode)
            capture("u11-c-${mode.name.lowercase()}")
        }
    }

    @Test
    fun `a kotlin post renders its highlighted code in both themes`() {
        for (mode in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            show(kotlinPost(), mode)
            capture("u11-kotlin-${mode.name.lowercase()}")
        }
    }

    @Test
    fun `scrolling a wide block sideways moves the code and leaves the numbers`() {
        show(kotlinPost(), ThemeMode.Dark)

        val gutterBefore = xOf(ArticleTestTags.CODE_GUTTER)
        val codeBefore = xOf(ArticleTestTags.CODE_TEXT)
        compose.onNodeWithTag(ArticleTestTags.CODE)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(SCROLL_BY, 0f) }
        compose.waitForIdle()

        assertThat(xOf(ArticleTestTags.CODE_TEXT)).isLessThan(codeBefore)
        assertThat(xOf(ArticleTestTags.CODE_GUTTER)).isEqualTo(gutterBefore)
        capture("u11-kotlin-dark-scrolled")
    }

    // ---- content ----------------------------------------------------------------

    /** The entry with the most C in it, lowered by the app's own pipeline. */
    private fun nullprogramCPost(): List<ArticleBlock> {
        val snapshot = File(repoRoot(), "fixtures/snapshots/nullprogram-com.xml")
        val parsed = FeedParser().parse(snapshot.readBytes(), null, "https://nullprogram.com/")
        val entries = (parsed as ParseResult.Success).feed.entries

        val best = entries
            .mapNotNull { entry ->
                HtmlSanitizer.sanitize(entry.contentHtml, entry.link)
                    ?.let { ArticleLowering.toBlocks(it) }
            }
            .maxByOrNull { blocks ->
                blocks.filterIsInstance<ArticleBlock.Code>()
                    .filter { it.language == "c" }
                    .sumOf { it.text.length }
            }
            .orEmpty()

        check(best.filterIsInstance<ArticleBlock.Code>().any { it.language == "c" }) {
            "no `language-c` block in the nullprogram snapshot"
        }
        // Enough of the post to show code in its surroundings without a 20-screen capture.
        return best.take(POST_BLOCKS)
    }

    private fun kotlinPost(): List<ArticleBlock> = listOf(
        ArticleBlock.Heading(2, RichSpan("Tokenising without a parser")),
        ArticleBlock.Paragraph(
            RichSpan(
                "A feed hands you the middle of a program as often as the whole of one, " +
                    "so the scanner has to keep going when a construct never closes:",
            ),
        ),
        ArticleBlock.Code(KOTLIN_SAMPLE, "kotlin"),
    )

    // ---- harness ----------------------------------------------------------------

    /**
     * The rule allows exactly one `setContent` per test, so the theme is state rather than
     * an argument — which is also the honest comparison: the same composition, relit.
     */
    private val theme = mutableStateOf(ThemeMode.Dark)
    private var composed = false

    private fun show(blocks: List<ArticleBlock>, mode: ThemeMode) {
        theme.value = mode
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
                            ArticleBody(blocks = blocks, articleLink = null, onOpenLink = {})
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
        // A slab of one background colour means the article never composed.
        assertThat(shot.distinctColours).isGreaterThan(MIN_COLOURS)
    }

    private fun xOf(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.x

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "fixtures/snapshots").isDirectory) return dir
            dir = dir.parentFile
        }
        error("fixtures/snapshots not found")
    }

    private companion object {
        const val POST_BLOCKS = 12
        const val SCROLL_BY = 240f
        const val MIN_COLOURS = 8

        val KOTLIN_SAMPLE = """
            private fun stringEnd(code: String, from: Int, quote: Char): Int {
                var i = from + 1
                while (i < code.length) {
                    when (code[i]) {
                        '\\' -> i++              // an escape eats whatever follows it
                        '\n' -> return i         // unterminated: the literal ends here
                        quote -> return i + 1
                    }
                    i++
                }
                return code.length               // ran off the end of the fragment
            }
        """.trimIndent()
    }
}
