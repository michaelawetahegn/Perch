package dev.mkiros.perch.ui.article

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.parse.ArticleBlock
import dev.mkiros.perch.data.parse.RichSpan
import dev.mkiros.perch.ui.theme.PerchTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The table treatment (U11a, DESIGN.md §8).
 *
 * Native graphics and a real device qualifier are not optional here: under Robolectric's
 * default text measurement every glyph is about a pixel wide and every string therefore
 * "fits", which would make a test about column widths, wrapping and scroll agree with
 * anything. (Same trap as U08a's dropdown-label test.)
 *
 * What the assertions are really defending is that a *column* is a real thing: one width,
 * one alignment, the same in the header as in every row. That is what lets a reader scan
 * an advisory down a column instead of reading it as run-together prose.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ArticleTableTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a table wider than the page scrolls sideways and takes its header with it`() {
        show(wideTable())

        val range = compose.onNodeWithTag(ArticleTestTags.TABLE).fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange]
        assertThat(range.maxValue()).isGreaterThan(0f)

        val headerBefore = xOf("Component")
        val cellBefore = xOf("Kernel")
        compose.onNodeWithTag(ArticleTestTags.TABLE)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(SCROLL_BY, 0f) }
        compose.waitForIdle()

        // A header that stayed put while the body slid would put every value under the
        // wrong column name — the one failure mode worse than not scrolling at all.
        assertThat(xOf("Component")).isLessThan(headerBefore)
        assertThat(headerBefore - xOf("Component")).isEqualTo(cellBefore - xOf("Kernel"))
    }

    @Test
    fun `a long cell wraps inside its column rather than widening the table`() {
        show(wideTable())

        val cell = compose.onNodeWithText(LONG_CELL).fetchSemanticsNode()
        assertThat(layoutOf(LONG_CELL).lineCount).isAtLeast(2)
        // The column stopped growing; the text found somewhere to go instead.
        assertThat(cell.size.width).isAtMost(compose.onNodeWithText("Kernel").fetchSemanticsNode().size.width * 6)
    }

    @Test
    fun `a column is as wide as its own content, not a fixed slot`() {
        show(wideTable())

        val yes = widthOf("Yes")
        // "Yes" against a paragraph of impact text: a fixed column width gives these two
        // the same slot, which is both a crushed sentence and a wasted third of the page.
        assertThat(yes).isLessThan(widthOf(LONG_CELL))
        assertThat(yes).isLessThan(widthOf("macOS Tahoe 26.5.2"))
    }

    @Test
    fun `the rules span the whole table rather than collapsing inside the scroll`() {
        show(wideTable())

        val viewport = compose.onNodeWithTag(ArticleTestTags.TABLE).fetchSemanticsNode().size.width
        // A `fillMaxWidth` divider inside a horizontal scroll measures against an unbounded
        // constraint and lands on zero — a table of hairlines nobody can see. The rule has
        // to span the *content*, which on a wide table is wider than the viewport.
        assertThat(widthOfTag(ArticleTestTags.TABLE_RULE))
            .isEqualTo(widthOfTag(ArticleTestTags.TABLE_HEADER))
        assertThat(widthOfTag(ArticleTestTags.TABLE_RULE)).isGreaterThan(viewport)
    }

    @Test
    fun `a narrow table takes the width its columns need and no more`() {
        show(
            ArticleBlock.Table(
                header = listOf(RichSpan("Flag"), RichSpan("Effect")),
                rows = listOf(listOf(RichSpan("-O2"), RichSpan("optimize"))),
            ),
        )

        val table = compose.onNodeWithTag(ArticleTestTags.TABLE).fetchSemanticsNode()
        val screen = compose.onRoot().fetchSemanticsNode().size.width
        assertThat(widthOfTag(ArticleTestTags.TABLE_HEADER)).isLessThan(screen)
        assertThat(table.size.width).isLessThan(screen)
    }

    @Test
    fun `the header row is set apart from the body`() {
        show(wideTable())

        assertThat(layoutOf("Component").layoutInput.style.fontWeight)
            .isEqualTo(FontWeight.SemiBold)
        assertThat(layoutOf("Kernel").layoutInput.style.fontWeight)
            .isEqualTo(FontWeight.Normal)
    }

    @Test
    fun `a table with no header keeps every row a body row`() {
        show(
            ArticleBlock.Table(
                header = emptyList(),
                rows = listOf(
                    listOf(RichSpan("CVE"), RichSpan("Severity")),
                    listOf(RichSpan("CVE-2026-1"), RichSpan("Important")),
                ),
            ),
        )

        assertThat(layoutOf("CVE").layoutInput.style.fontWeight).isEqualTo(FontWeight.Normal)
    }

    @Test
    fun `a column of numbers is right aligned and a column of words is not`() {
        show(
            ArticleBlock.Table(
                header = listOf(RichSpan("CVE"), RichSpan("CVSS")),
                rows = listOf(
                    listOf(RichSpan("CVE-2026-1"), RichSpan("7.5")),
                    listOf(RichSpan("CVE-2026-2"), RichSpan("9.8")),
                ),
            ),
        )

        assertThat(layoutOf("7.5").layoutInput.style.textAlign).isEqualTo(TextAlign.End)
        assertThat(layoutOf("CVSS").layoutInput.style.textAlign).isEqualTo(TextAlign.End)
        assertThat(layoutOf("CVE-2026-1").layoutInput.style.textAlign).isEqualTo(TextAlign.Start)
    }

    // ---- harness ---------------------------------------------------------------

    /** The ZDI advisory shape: an identifier, a paragraph of impact, and yes/no columns. */
    private fun wideTable() = ArticleBlock.Table(
        header = listOf(
            RichSpan("CVE ID"), RichSpan("Component"), RichSpan("Impact"),
            RichSpan("iOS 26.5.2"), RichSpan("macOS Tahoe 26.5.2"), RichSpan("Safari 26.5.2"),
        ),
        rows = listOf(
            listOf(
                RichSpan("CVE-2026-43743"), RichSpan("Kernel"), RichSpan(LONG_CELL),
                RichSpan("Yes"), RichSpan("Yes"), RichSpan("No"),
            ),
            listOf(
                RichSpan("CVE-2026-39868"), RichSpan("WebKit"), RichSpan("A short one."),
                RichSpan("No"), RichSpan("Yes"), RichSpan("Yes"),
            ),
        ),
    )

    private fun show(block: ArticleBlock) {
        compose.setContent {
            PerchTheme(dynamicColor = false) {
                ArticleBody(
                    blocks = listOf(block),
                    articleLink = null,
                    onOpenLink = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun xOf(text: String): Float =
        compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.x

    /** The first cell with this text: `Yes` is a column of them, and they agree. */
    private fun widthOf(text: String): Int =
        compose.onAllNodesWithText(text)[0].fetchSemanticsNode().size.width

    /** The first node with this tag: a table has one rule per row, and they agree. */
    private fun widthOfTag(tag: String): Int =
        compose.onAllNodesWithTag(tag)[0].fetchSemanticsNode().size.width

    private fun layoutOf(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first()
    }

    private companion object {
        const val SCROLL_BY = 120f
        const val LONG_CELL =
            "An app may be able to cause unexpected system termination or corrupt kernel memory"
    }
}
