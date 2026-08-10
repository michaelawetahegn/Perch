package dev.mkiros.perch.ui.article

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.parse.ArticleBlock
import dev.mkiros.perch.ui.theme.PerchCodeColorsDark
import dev.mkiros.perch.ui.theme.PerchTheme
import dev.mkiros.perch.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The code block's two U11 promises, both of which are about what the *reader* gets rather
 * than about colour: the gutter is numbering, not text, and it stays put when the code
 * moves.
 *
 * `@GraphicsMode(NATIVE)` because the alignment assertions are geometric — Robolectric's
 * default text measurement gives every character the same 1px advance, under which a
 * three-digit number and a one-digit number are indistinguishable and the whole point of
 * the widest-number sizing is invisible.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodeBlockTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the copied text is the program, with no line numbers in it`() {
        val source = "fun main() {\n    println(1)\n}"

        show(ArticleBlock.Code(source, "kotlin"))

        assertThat(textOf(ArticleTestTags.CODE_TEXT)).isEqualTo(source)
        assertThat(textOf(ArticleTestTags.CODE_GUTTER)).isEqualTo("1\n2\n3")
    }

    @Test
    fun `a hundred-line block numbers every line and keeps one left edge for the code`() {
        val source = (1..120).joinToString("\n") { "line $it" }

        show(ArticleBlock.Code(source, "plaintext"))

        val gutter = textOf(ArticleTestTags.CODE_GUTTER).lines()
        assertThat(gutter).hasSize(120)
        assertThat(gutter.first()).isEqualTo("1")
        assertThat(gutter[8]).isEqualTo("9")
        assertThat(gutter[9]).isEqualTo("10")
        assertThat(gutter[98]).isEqualTo("99")
        assertThat(gutter[99]).isEqualTo("100")
        assertThat(gutter.last()).isEqualTo("120")
    }

    @Test
    fun `the gutter is not inside the part that scrolls sideways`() {
        show(ArticleBlock.Code("int a = 1;\nint b = 2;", "c"))

        compose.onNodeWithTag(ArticleTestTags.CODE).assert(hasScrollAction())
        val gutter = compose.onNodeWithTag(ArticleTestTags.CODE_GUTTER).fetchSemanticsNode()
        val scroller = compose.onNodeWithTag(ArticleTestTags.CODE).fetchSemanticsNode()
        assertThat(gutter.positionInRoot.x).isLessThan(scroller.positionInRoot.x)
    }

    @Test
    fun `a single-line block has no gutter at all`() {
        show(ArticleBlock.Code("git rebase --onto main", "shell"))

        compose.onNodeWithTag(ArticleTestTags.CODE_TEXT).assertIsDisplayed()
        compose.onNodeWithTag(ArticleTestTags.CODE_GUTTER).assertDoesNotExist()
        compose.onNodeWithTag(ArticleTestTags.CODE_GUTTER_RULE).assertDoesNotExist()
    }

    @Test
    fun `a rule stands between the numbers and the code, with air on both sides`() {
        show(ArticleBlock.Code("int a = 1;\nint b = 2;", "c"))

        val gutter = compose.onNodeWithTag(ArticleTestTags.CODE_GUTTER).fetchSemanticsNode()
        val rule = compose.onNodeWithTag(ArticleTestTags.CODE_GUTTER_RULE).fetchSemanticsNode()
        val code = compose.onNodeWithTag(ArticleTestTags.CODE).fetchSemanticsNode()

        // V11/1: scrolled left, a wide line used to slide to within a few dp of the
        // numbers and the two columns read as one. The rule is what separates them — so
        // it has to be *between* them and touching neither.
        assertThat(rule.positionInRoot.x)
            .isGreaterThan(gutter.positionInRoot.x + gutter.size.width)
        assertThat(rule.positionInRoot.x + rule.size.width)
            .isLessThan(code.positionInRoot.x)
        // And it is a rule, not a tick: it runs the height of the block.
        assertThat(rule.size.height).isAtLeast(code.size.height)
    }

    @Test
    fun `the rule stays with the numbers when the code scrolls under it`() {
        show(ArticleBlock.Code(WIDE_SOURCE, "c"))

        val before = compose.onNodeWithTag(ArticleTestTags.CODE_GUTTER_RULE)
            .fetchSemanticsNode().positionInRoot.x
        val codeBefore = compose.onNodeWithTag(ArticleTestTags.CODE_TEXT)
            .fetchSemanticsNode().positionInRoot.x
        compose.onNodeWithTag(ArticleTestTags.CODE)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(SCROLL_BY, 0f) }
        compose.waitForIdle()

        // The code moved — otherwise this asserts nothing — and the rule did not.
        assertThat(compose.onNodeWithTag(ArticleTestTags.CODE_TEXT)
            .fetchSemanticsNode().positionInRoot.x).isLessThan(codeBefore)
        assertThat(compose.onNodeWithTag(ArticleTestTags.CODE_GUTTER_RULE)
            .fetchSemanticsNode().positionInRoot.x).isEqualTo(before)
    }

    @Test
    fun `an unknown language renders its text unstyled rather than blank`() {
        val source = "(defun square (x)\n  (* x x))"

        show(ArticleBlock.Code(source, "lisp"))

        assertThat(textOf(ArticleTestTags.CODE_TEXT)).isEqualTo(source)
        assertThat(spanColoursOf(ArticleTestTags.CODE_TEXT)).isEmpty()
    }

    @Test
    fun `a declared language colours its keywords and its strings differently`() {
        show(ArticleBlock.Code("val greeting = \"hello\"\nval n = 1", "kotlin"))

        val colours = spanColoursOf(ArticleTestTags.CODE_TEXT)
        assertThat(colours).contains(PerchCodeColorsDark.keyword)
        assertThat(colours).contains(PerchCodeColorsDark.string)
        assertThat(colours).contains(PerchCodeColorsDark.number)
    }

    @Test
    fun `an undeclared block is still highlighted from what it looks like`() {
        show(ArticleBlock.Code("#include <stdio.h>\nint main(void) { return 0; }", null))

        assertThat(spanColoursOf(ArticleTestTags.CODE_TEXT))
            .contains(PerchCodeColorsDark.meta)
    }

    // ---- harness ---------------------------------------------------------------

    /**
     * Composed inside a `SelectionContainer` because that is how `ArticleScreen` composes
     * it, and the gutter's `DisableSelection` only means anything in that context.
     */
    private fun show(block: ArticleBlock.Code) {
        compose.setContent {
            PerchTheme(mode = ThemeMode.Dark, dynamicColor = false) {
                SelectionContainer {
                    ArticleBody(
                        blocks = listOf(block),
                        articleLink = null,
                        onOpenLink = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun annotatedOf(tag: String): AnnotatedString =
        compose.onNodeWithTag(tag).fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()

    private fun textOf(tag: String): String = annotatedOf(tag).text

    private fun spanColoursOf(tag: String) =
        annotatedOf(tag).spanStyles.mapNotNull { it.item.color.takeIf { c -> c.alpha > 0f } }
            .toSet()

    private companion object {
        const val SCROLL_BY = 240f

        /** Long enough that the block really does scroll on any test device. */
        val WIDE_SOURCE = """
            static int compare_entries(const struct entry *a, const struct entry *b, int flags);
            int main(void) { return 0; }
        """.trimIndent()
    }
}
