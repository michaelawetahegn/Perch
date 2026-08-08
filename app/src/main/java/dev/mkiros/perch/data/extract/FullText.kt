package dev.mkiros.perch.data.extract

import dev.mkiros.perch.data.parse.ArticleBlock
import dev.mkiros.perch.data.parse.ArticleLowering
import org.jsoup.Jsoup

/**
 * Whether a feed body is the article, or only where the article used to be (U10, §0).
 *
 * A length threshold on its own is not enough, and the two shapes §0 names are why. One
 * ships nothing and is obvious. The other ships a paragraph or two of teaser and is not:
 * a "Continue reading →" body of two thousand characters looks, to any threshold you
 * choose, exactly like a short post. So there are four independent signals and any one of
 * them is sufficient.
 *
 * Being wrong in one direction is cheap and in the other direction is not, which decides
 * where the caution goes. Extracting an article that did not need it costs one HTTP
 * request and, because [dev.mkiros.perch.data.repo.ArticleTextRepository] only ever
 * replaces a body it is longer than, changes nothing the reader sees. *Failing* to extract
 * leaves someone reading a blurb and reaching for the browser — which is the thing U10
 * exists to stop. The triggers are therefore generous.
 */
object FullText {

    /**
     * Below this much prose, assume the feed is holding out on us.
     *
     * Roughly three screens on a phone: long enough that no real article is under it by
     * accident, short enough that most teasers are.
     */
    const val MIN_PROSE_CHARS = 1200

    /**
     * @param bodyIsExcerpt what the parser recorded about where this body came from —
     *   `EntryEntity.bodyIsExcerpt`. The one signal not visible in [contentHtml] itself.
     */
    fun needsExtraction(contentHtml: String?, bodyIsExcerpt: Boolean): Boolean {
        if (contentHtml.isNullOrBlank()) return true
        if (bodyIsExcerpt) return true
        // The marker has to be looked for in the *unlowered* text. `ArticleLowering`
        // recognises "Continue reading →" and "The post … appeared first on …" as chrome
        // and drops them (T25), which is right for rendering and would make this question
        // unanswerable — by the time there are blocks, the evidence is gone.
        if (flatText(contentHtml).endsTruncated()) return true
        return prose(contentHtml).length < MIN_PROSE_CHARS
    }

    private fun flatText(html: String): String =
        runCatching { Jsoup.parse(html).text() }.getOrDefault("")

    /**
     * The body as the reader would read it: every block's words, in order.
     *
     * Deliberately *not* the markup's length, and deliberately not code. A stub with a
     * hundred-line listing under it is still a stub, and measuring the HTML would count a
     * paragraph of nested `<span>`s as an article.
     */
    fun prose(contentHtml: String?): String = flatten(ArticleLowering.toBlocks(contentHtml))

    private fun flatten(blocks: List<ArticleBlock>): String = blocks.joinToString(" ") { block ->
        when (block) {
            is ArticleBlock.Paragraph -> block.text.text
            is ArticleBlock.Heading -> block.text.text
            is ArticleBlock.ListBlock -> block.items.joinToString(" ") { it.text }
            is ArticleBlock.Quote -> flatten(block.blocks)
            is ArticleBlock.Table ->
                (block.header + block.rows.flatten()).joinToString(" ") { it.text }
            is ArticleBlock.Code, is ArticleBlock.Image,
            is ArticleBlock.Unsupported, ArticleBlock.Rule,
            -> ""
        }
    }.trim().replace(WHITESPACE, " ")

    /**
     * The tell that a body was cut rather than written short.
     *
     * The WordPress line — "The post … appeared first on …" — is on the list because that
     * plugin appends it to excerpt feeds constantly. It also appears on some full-text
     * ones, which is harmless: the extraction has to beat the body to replace it.
     */
    private fun String.endsTruncated(): Boolean {
        val tail = takeLast(TAIL_CHARS)
        if (WORDPRESS_FOOTER.containsMatchIn(tail)) return true
        // The arrow or bracket a "read more" link is usually dressed in is punctuation, not
        // the marker; strip it before asking what the body actually ends with.
        return CUT_OFF.containsMatchIn(tail.trimEnd().trimEnd(*TRAILING_ORNAMENTS))
    }

    private const val TAIL_CHARS = 120

    private val TRAILING_ORNAMENTS = charArrayOf('.', ')', ']', '»', '›', '→', '▸', '>', ' ')

    private val WHITESPACE = Regex("""\s+""")

    /** "The post X appeared first on Y." — the excerpt plugin's signature. */
    private val WORDPRESS_FOOTER = Regex(
        """\bthe post\b.*\bappeared first on\b""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val CUT_OFF = Regex(
        """(…|\[\s*(…|\.\.\.)\s*]|\.\.\.|\bread\s+more\b|\bcontinue\s+reading\b)$""",
        RegexOption.IGNORE_CASE,
    )
}
