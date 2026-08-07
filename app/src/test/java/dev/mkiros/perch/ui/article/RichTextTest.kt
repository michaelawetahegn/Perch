package dev.mkiros.perch.ui.article

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.parse.RichSpan
import dev.mkiros.perch.data.parse.SpanStyle
import org.junit.Test

/**
 * The inline half of the renderer (T25): `RichSpan` → `AnnotatedString`.
 *
 * Pure JVM — no composition, no resources. The marks a feed is allowed to carry are few
 * enough to enumerate, and each one has exactly one visual meaning; asserting that
 * mapping here is what lets the block tests stay about layout.
 */
class RichTextTest {

    private val chip = Color.LightGray

    @Test
    fun `a span with no marks is its plain text`() {
        val annotated = RichSpan("Just words.").toAnnotatedString(chip)

        assertThat(annotated.text).isEqualTo("Just words.")
        assertThat(annotated.spanStyles).isEmpty()
    }

    @Test
    fun `strong and em apply bold and italic over their own ranges`() {
        val span = RichSpan(
            text = "bold and italic",
            marks = listOf(
                RichSpan.Mark(SpanStyle.Strong, 0, 4),
                RichSpan.Mark(SpanStyle.Em, 9, 15),
            ),
        )

        val annotated = span.toAnnotatedString(chip)

        val bold = annotated.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertThat(bold.start to bold.end).isEqualTo(0 to 4)
        val italic = annotated.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertThat(italic.start to italic.end).isEqualTo(9 to 15)
    }

    @Test
    fun `marks nested over the same range both apply`() {
        val span = RichSpan(
            text = "shout",
            marks = listOf(
                RichSpan.Mark(SpanStyle.Strong, 0, 5),
                RichSpan.Mark(SpanStyle.Em, 0, 5),
            ),
        )

        val annotated = span.toAnnotatedString(chip)

        assertThat(annotated.spanStyles).hasSize(2)
        assertThat(annotated.spanStyles.map { it.item.fontWeight })
            .contains(FontWeight.Bold)
        assertThat(annotated.spanStyles.map { it.item.fontStyle })
            .contains(FontStyle.Italic)
    }

    @Test
    fun `inline code gets the mono face and a chip background`() {
        val span = RichSpan("call fork() twice", listOf(RichSpan.Mark(SpanStyle.InlineCode, 5, 11)))

        val annotated = span.toAnnotatedString(chip)

        val code = annotated.spanStyles.single()
        assertThat(code.start to code.end).isEqualTo(5 to 11)
        assertThat(code.item.background).isEqualTo(chip)
        assertThat(code.item.fontFamily.toString()).contains("Monospace")
    }

    @Test
    fun `sub and sup shift the baseline in opposite directions`() {
        val span = RichSpan(
            text = "x2 H2O",
            marks = listOf(
                RichSpan.Mark(SpanStyle.Sup, 1, 2),
                RichSpan.Mark(SpanStyle.Sub, 4, 5),
            ),
        )

        val annotated = span.toAnnotatedString(chip)

        val sup = annotated.spanStyles.single { it.start == 1 }.item.baselineShift!!.multiplier
        val sub = annotated.spanStyles.single { it.start == 4 }.item.baselineShift!!.multiplier
        assertThat(sup).isGreaterThan(0f)
        assertThat(sub).isLessThan(0f)
    }

    @Test
    fun `a link keeps its text and carries its url as a link annotation`() {
        val span = RichSpan(
            text = "see the RFC for the detail",
            marks = listOf(
                RichSpan.Mark(SpanStyle.Link("https://www.rfc-editor.org/rfc/rfc4287"), 8, 11),
            ),
        )

        val annotated = span.toAnnotatedString(chip)

        assertThat(annotated.text).isEqualTo("see the RFC for the detail")
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        assertThat(link.start to link.end).isEqualTo(8 to 11)
        assertThat((link.item as LinkAnnotation.Url).url)
            .isEqualTo("https://www.rfc-editor.org/rfc/rfc4287")
    }

    @Test
    fun `a link is underlined rather than recoloured blue`() {
        val span = RichSpan("home", listOf(RichSpan.Mark(SpanStyle.Link("https://example.com"), 0, 4)))

        val annotated = span.toAnnotatedString(chip)

        val link = annotated.getLinkAnnotations(0, annotated.length).single().item as LinkAnnotation.Url
        assertThat(link.styles?.style?.textDecoration).isEqualTo(TextDecoration.Underline)
        // Unspecified, not "blue": the link inherits the body colour it sits in.
        assertThat(link.styles?.style?.color).isEqualTo(Color.Unspecified)
    }

    @Test
    fun `tapping a link hands its url to the opener`() {
        val span = RichSpan("home", listOf(RichSpan.Mark(SpanStyle.Link("https://example.com"), 0, 4)))
        val opened = mutableListOf<String>()

        val annotated = span.toAnnotatedString(chip, onOpenLink = { opened += it })
        val link = annotated.getLinkAnnotations(0, annotated.length).single().item as LinkAnnotation.Url
        link.linkInteractionListener!!.onClick(link)

        assertThat(opened).containsExactly("https://example.com")
    }

    @Test
    fun `a mark whose range falls outside the text is dropped rather than thrown on`() {
        val span = RichSpan("short", listOf(RichSpan.Mark(SpanStyle.Strong, 3, 99)))

        val annotated = span.toAnnotatedString(chip)

        assertThat(annotated.text).isEqualTo("short")
        val bold = annotated.spanStyles.single()
        assertThat(bold.start to bold.end).isEqualTo(3 to 5)
    }
}
