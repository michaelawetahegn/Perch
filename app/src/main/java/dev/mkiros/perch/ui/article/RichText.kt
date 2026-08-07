package dev.mkiros.perch.ui.article

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import dev.mkiros.perch.data.parse.RichSpan
import dev.mkiros.perch.data.parse.SpanStyle
import dev.mkiros.perch.ui.theme.ArticleType

/**
 * The inline half of the reading surface (DESIGN.md §8): [RichSpan] → `AnnotatedString`.
 *
 * The mapping is total and fixed. A feed cannot express colour, size, face or alignment
 * — the lowering already refused to carry them — so the six marks below are the entire
 * typographic vocabulary a source gets, and each one has exactly one rendering. That is
 * what makes a paragraph from a WordPress blog and one from hand-written XHTML come out
 * the same shape.
 *
 * Not a composable: colour is passed in so the same function serves a light scheme, a
 * dark one, and a plain JVM test.
 *
 * @param inlineCodeBackground the chip behind inline `code`, normally `surfaceContainerHigh`.
 * @param onOpenLink handed the URL of a tapped link; the screen turns that into a Custom Tab.
 */
fun RichSpan.toAnnotatedString(
    inlineCodeBackground: Color,
    onOpenLink: (String) -> Unit = {},
): AnnotatedString = buildAnnotatedString {
    append(text)
    for (mark in marks) {
        // Ranges come from a tree walk over sanitized HTML, but a defensive clamp costs
        // nothing and an out-of-bounds range would take the whole article down.
        val start = mark.start.coerceIn(0, text.length)
        val end = mark.end.coerceIn(start, text.length)
        if (start == end) continue

        when (val style = mark.style) {
            SpanStyle.Strong -> addStyle(ArticleType.strong, start, end)
            SpanStyle.Em -> addStyle(ArticleType.emphasis, start, end)
            SpanStyle.Sup -> addStyle(ArticleType.superscript, start, end)
            SpanStyle.Sub -> addStyle(ArticleType.subscript, start, end)
            SpanStyle.InlineCode ->
                addStyle(ArticleType.inlineCode.copy(background = inlineCodeBackground), start, end)
            is SpanStyle.Link -> addLink(linkAnnotation(style.url, onOpenLink), start, end)
        }
    }
}

/**
 * An editorial underline and nothing else — no colour override, because a blue hyperlink
 * in the middle of serif body text is the single loudest way to look like a feed reader
 * rather than a publication (§8).
 */
private fun linkAnnotation(url: String, onOpenLink: (String) -> Unit) = LinkAnnotation.Url(
    url = url,
    styles = TextLinkStyles(
        style = androidx.compose.ui.text.SpanStyle(textDecoration = ArticleType.link),
    ),
    linkInteractionListener = { onOpenLink(url) },
)
