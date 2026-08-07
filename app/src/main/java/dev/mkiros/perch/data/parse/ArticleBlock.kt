package dev.mkiros.perch.data.parse

/**
 * The canonical block model, per SPEC.md §5.
 *
 * Sanitized HTML is not the rendering input — this is. Forty-two sources ship forty-two
 * dialects of markup, and the article renderer (T25) knows only these nine shapes, which
 * is what lets it draw them all identically without a single source-specific branch.
 *
 * Produced by [ArticleLowering.toBlocks].
 */
sealed interface ArticleBlock {

    data class Paragraph(val text: RichSpan) : ArticleBlock

    /** Always 2 or 3: `h1`/`h2` lower to 2, `h3`–`h6` to 3. The article title owns level 1. */
    data class Heading(val level: Int, val text: RichSpan) : ArticleBlock

    /** `pre`/`code`, whitespace intact — the one place the source's formatting is content. */
    data class Code(val text: String) : ArticleBlock

    data class Image(val url: String, val alt: String?, val caption: RichSpan?) : ArticleBlock

    data class Quote(val blocks: List<ArticleBlock>) : ArticleBlock

    data class ListBlock(val ordered: Boolean, val items: List<RichSpan>) : ArticleBlock

    data class Table(val header: List<RichSpan>, val rows: List<List<RichSpan>>) : ArticleBlock

    data object Rule : ArticleBlock

    /** An element the mapper does not know. The renderer offers "read on the web" instead. */
    data class Unsupported(val label: String) : ArticleBlock
}

/**
 * The inline layer: plain text plus the handful of meanings worth keeping.
 *
 * [marks] never partially overlap — they come from a tree, so a range is either disjoint
 * from another or wholly inside it (`<strong><em>x</em></strong>` is two marks over the
 * same range). Colour, size, font and alignment are *not* representable here on purpose:
 * DESIGN.md §8 gives the source no vote on typography.
 */
data class RichSpan(val text: String, val marks: List<Mark> = emptyList()) {

    /** [style] applied over `text[start, end)`. */
    data class Mark(val style: SpanStyle, val start: Int, val end: Int)
}

sealed interface SpanStyle {
    data object Em : SpanStyle
    data object Strong : SpanStyle
    data object InlineCode : SpanStyle
    data object Sub : SpanStyle
    data object Sup : SpanStyle
    data class Link(val url: String) : SpanStyle
}
