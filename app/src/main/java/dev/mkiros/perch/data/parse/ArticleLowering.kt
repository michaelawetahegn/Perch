package dev.mkiros.perch.data.parse

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Sanitized HTML → `List<ArticleBlock>`: the normalization layer (T25a).
 *
 * This is where forty-two sources stop being different, so the burden sits here rather
 * than in the renderer. It implements DESIGN.md §8's rules literally — wrapper soup
 * collapses, every inline style/colour/size/alignment is discarded, publisher chrome
 * (share widgets, "Read more" stubs, "The post X appeared first on Y", subscribe CTAs,
 * comment counts) is stripped, and runs of empty paragraphs or `<br><br>` become one
 * paragraph break.
 *
 * Total, like the parsers: [toBlocks] never throws, and an element it does not recognise
 * becomes [ArticleBlock.Unsupported] rather than a silent drop. An empty result is the
 * renderer's cue to show the summary plus "Read on the web".
 */
object ArticleLowering {

    /**
     * [html] should already have been through [HtmlSanitizer] — that is what resolves URLs
     * and removes the dangerous tags. Raw markup lowers too; it just meets a stricter
     * mapper than the sanitizer's allowlist and yields more [ArticleBlock.Unsupported].
     */
    fun toBlocks(html: String?): List<ArticleBlock> {
        val raw = html?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            val body = Jsoup.parseBodyFragment(raw).body()
            trimRules(lowerFlow(body).filterNot { it.isChrome() })
        }.getOrElse { emptyList() }
    }

    /**
     * The children of [parent] as a run of blocks: inline content accumulates into
     * paragraphs, and any block-level element flushes what has accumulated and lowers
     * itself. That flush is SPEC.md §5's "a block inside a `<p>` ends the paragraph rather
     * than nesting" — nesting is exactly what the block model refuses to represent.
     */
    private fun lowerFlow(parent: Element): List<ArticleBlock> {
        val out = mutableListOf<ArticleBlock>()
        val inline = SpanBuilder()

        fun flush() {
            inline.build().forEach { out += ArticleBlock.Paragraph(it) }
            inline.clear()
        }

        for (node in parent.childNodes()) {
            when {
                node is TextNode -> inline.appendText(node.wholeText)
                node !is Element -> Unit // comments, doctypes, XML declarations
                node.isInlineOnly() -> inline.appendInline(node)
                else -> {
                    flush()
                    out += lowerBlock(node)
                }
            }
        }
        flush()
        return out
    }

    private fun lowerBlock(el: Element): List<ArticleBlock> = when (val tag = el.lowerTag()) {
        // INLINE lands here only when it wraps a block — a linked image, mostly. The
        // wrapper contributes nothing the block model can hold, so it is walked as flow.
        in UNWRAP, in INLINE -> lowerFlow(el)
        "h1", "h2" -> heading(2, el)
        "h3", "h4", "h5", "h6" -> heading(3, el)
        "pre" -> code(el)
        "img" -> image(el, caption = null)
        "figure" -> figure(el)
        "blockquote" -> quote(el)
        "ul", "ol" -> listBlock(el)
        "table" -> table(el)
        "hr" -> listOf(ArticleBlock.Rule)
        else -> listOf(ArticleBlock.Unsupported(tag))
    }

    private fun heading(level: Int, el: Element): List<ArticleBlock> =
        el.inlineSpan()?.let { listOf(ArticleBlock.Heading(level, it)) }.orEmpty()

    /**
     * Verbatim, minus the newline a CMS puts after the opening tag and the indentation
     * before the closing one — those are markup, not code.
     */
    private fun code(el: Element): List<ArticleBlock> {
        val text = el.wholeText().replace('\u00A0', ' ').trimStart('\n', '\r').trimEnd()
        return if (text.isBlank()) emptyList() else listOf(ArticleBlock.Code(text))
    }

    private fun image(el: Element, caption: RichSpan?): List<ArticleBlock> {
        val url = el.attr("src").trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val alt = el.attr("alt").trim().takeIf { it.isNotEmpty() }
        return listOf(ArticleBlock.Image(url, alt, caption))
    }

    private fun figure(el: Element): List<ArticleBlock> {
        val images = el.select("img")
        if (images.isEmpty()) return lowerFlow(el)
        val caption = el.selectFirst("figcaption")?.inlineSpan()
        return images.flatMapIndexed { index, img -> image(img, if (index == 0) caption else null) }
    }

    private fun quote(el: Element): List<ArticleBlock> {
        val inner = lowerFlow(el).filterNot { it.isChrome() }
        return if (inner.isEmpty()) emptyList() else listOf(ArticleBlock.Quote(inner))
    }

    /**
     * A list item is a [RichSpan], so a nested list cannot nest — its items join the
     * parent's, in reading order. Losing one level of indentation beats losing the words.
     */
    private fun listBlock(el: Element): List<ArticleBlock> {
        val items = mutableListOf<RichSpan>()
        el.childElements("li").forEach { collectItem(it, items) }
        return if (items.isEmpty()) emptyList() else {
            listOf(ArticleBlock.ListBlock(ordered = el.lowerTag() == "ol", items = items))
        }
    }

    private fun collectItem(li: Element, into: MutableList<RichSpan>) {
        val nested = mutableListOf<Element>()
        val builder = SpanBuilder(hardBreaks = false)
        for (node in li.childNodes()) {
            when {
                node is TextNode -> builder.appendText(node.wholeText)
                node !is Element -> Unit
                node.lowerTag() == "ul" || node.lowerTag() == "ol" -> nested += node
                else -> builder.appendInline(node)
            }
        }
        builder.build().firstOrNull()?.let { into += it }
        nested.forEach { list -> list.childElements("li").forEach { collectItem(it, into) } }
    }

    /** A header row is one whose cells are *all* `th`; anything less is just a first row. */
    private fun table(el: Element): List<ArticleBlock> {
        val rows = el.select("tr").map { tr ->
            tr.children().filter { it.lowerTag() == "th" || it.lowerTag() == "td" }
                .map { it.inlineSpan() ?: RichSpan("") }
        }
        if (rows.isEmpty()) return emptyList()

        val first = el.select("tr").first()?.children().orEmpty()
        val hasHeader = first.isNotEmpty() && first.all { it.lowerTag() == "th" }
        val header = if (hasHeader) rows.first() else emptyList()
        val body = if (hasHeader) rows.drop(1) else rows
        return if (header.isEmpty() && body.isEmpty()) emptyList() else {
            listOf(ArticleBlock.Table(header, body))
        }
    }

    /** A leading or trailing rule is a separator with nothing to separate — often what a
     *  stripped chrome footer left behind. */
    private fun trimRules(blocks: List<ArticleBlock>): List<ArticleBlock> {
        val trimmed = blocks.dropWhile { it == ArticleBlock.Rule }.dropLastWhile { it == ArticleBlock.Rule }
        return trimmed.filterIndexed { index, block ->
            block != ArticleBlock.Rule || trimmed.getOrNull(index - 1) != ArticleBlock.Rule
        }
    }

    private fun ArticleBlock.isChrome(): Boolean {
        val text = when (this) {
            is ArticleBlock.Paragraph -> this.text.text
            is ArticleBlock.Heading -> this.text.text
            else -> return false
        }.trim()
        return CHROME.any { it.matches(text) }
    }

    private fun Element.lowerTag(): String = tagName().lowercase()

    private fun Element.childElements(name: String): List<Element> =
        children().filter { it.lowerTag() == name }

    /** This element's inline content as one span — for headings, captions and cells,
     *  where a paragraph break has nowhere to go. */
    private fun Element.inlineSpan(): RichSpan? =
        SpanBuilder(hardBreaks = false).also { it.appendInline(this) }.build().firstOrNull()

    /**
     * True when this element only ever contributes inline content. An `<a>` wrapped around
     * an image is not inline in that sense: the image is a block, so the anchor is walked
     * as flow instead (the link is lost, which the block model has no field for anyway).
     */
    private fun Element.isInlineOnly(): Boolean =
        lowerTag() in INLINE && select(BLOCK_DESCENDANT).isEmpty()

    /**
     * Accumulates inline nodes into text plus marks.
     *
     * @param hardBreaks when true a `<br>` becomes a newline and a run of two or more
     *   splits the result into separate paragraphs, per DESIGN.md §8. Headings and list
     *   items pass false: they are one span by definition.
     */
    private class SpanBuilder(private val hardBreaks: Boolean = true) {

        private val sb = StringBuilder()
        private val marks = mutableListOf<RichSpan.Mark>()

        fun clear() {
            sb.setLength(0)
            marks.clear()
        }

        /** Whitespace collapses to single spaces, and a space at a break is not a space. */
        fun appendText(raw: String) {
            val text = WHITESPACE.replace(raw.replace('\u00A0', ' '), " ")
            if (text.isEmpty()) return
            sb.append(if (text.startsWith(" ") && atBreak()) text.trimStart(' ') else text)
        }

        fun appendInline(el: Element) {
            when (el.lowerTag()) {
                "br" -> if (hardBreaks) sb.append('\n') else if (!atBreak()) sb.append(' ')
                "a" -> styled(el.attr("href").trim().takeIf { it.isNotEmpty() }?.let(SpanStyle::Link), el)
                "em", "i", "cite", "dfn", "var" -> styled(SpanStyle.Em, el)
                "strong", "b" -> styled(SpanStyle.Strong, el)
                "code", "kbd", "samp", "tt" -> styled(SpanStyle.InlineCode, el)
                "sub" -> styled(SpanStyle.Sub, el)
                "sup" -> styled(SpanStyle.Sup, el)
                // Everything else — span, font, u, small, mark, a stray <p> inside an
                // <li> — contributes its words and nothing else.
                else -> styled(null, el)
            }
        }

        /** The accumulated text, split where the source asked for a paragraph break. */
        fun build(): List<RichSpan> {
            val full = sb.toString()
            if (full.isBlank()) return emptyList()

            val pieces = mutableListOf<IntRange>()
            var cursor = 0
            for (match in PARAGRAPH_BREAK.findAll(full)) {
                pieces += cursor until match.range.first
                cursor = match.range.last + 1
            }
            pieces += cursor until full.length

            return pieces.mapNotNull { piece -> slice(full, piece.first, piece.last + 1) }
        }

        private fun slice(full: String, from: Int, until: Int): RichSpan? {
            var start = from
            var end = until
            while (start < end && full[start].isWhitespace()) start++
            while (end > start && full[end - 1].isWhitespace()) end--
            if (start >= end) return null

            val kept = marks.filter { it.start < end && it.end > start }
                .map { RichSpan.Mark(it.style, maxOf(it.start, start) - start, minOf(it.end, end) - start) }
            return RichSpan(full.substring(start, end), kept)
        }

        private fun styled(style: SpanStyle?, el: Element) {
            val start = sb.length
            for (child in el.childNodes()) {
                when {
                    child is TextNode -> appendText(child.wholeText)
                    child is Element -> appendInline(child)
                }
            }
            if (style == null) return

            // A mark that swallowed the space after its own words would underline it.
            var from = start
            var to = sb.length
            while (from < to && sb[from].isWhitespace()) from++
            while (to > from && sb[to - 1].isWhitespace()) to--
            if (to > from) marks += RichSpan.Mark(style, from, to)
        }

        private fun atBreak(): Boolean = sb.isEmpty() || sb.last() == ' ' || sb.last() == '\n'
    }

    private val WHITESPACE = Regex("\\s+")

    /** Two or more newlines, however much horizontal space is mixed in. */
    private val PARAGRAPH_BREAK = Regex("(?:[ \\t]*\\n){2,}[ \\t]*")

    /** Structure comes from the block model, never from the source's nesting. */
    private val UNWRAP = setOf(
        "p", "div", "section", "article", "main", "header", "footer", "aside", "center",
        "figcaption", "li", "dl", "dt", "dd", "details", "summary", "tr", "td", "th",
        "thead", "tbody", "tfoot", "caption", "colgroup", "col", "body", "html", "hgroup",
    )

    private val INLINE = setOf(
        "a", "em", "i", "strong", "b", "code", "kbd", "samp", "tt", "sub", "sup", "span",
        "u", "s", "strike", "small", "big", "mark", "font", "abbr", "acronym", "cite",
        "dfn", "q", "time", "label", "var", "ins", "del", "bdi", "bdo", "wbr", "br", "nobr",
    )

    private const val BLOCK_DESCENDANT =
        "img, p, div, ul, ol, li, table, blockquote, pre, hr, h1, h2, h3, h4, h5, h6, figure"

    /**
     * Publisher chrome, matched against a whole block's text so a real sentence that
     * merely opens with one of these words survives. DESIGN.md §8: the decoration feeds
     * append is not part of the article.
     */
    private val CHROME: List<Regex> = listOf(
        // WordPress/Jetpack's syndication footer and its cousins.
        """the post .+ appeared first on .+""",
        """(this|the) (post|article|story) (originally |first )?appeared on .+""",
        // Truncation stubs where the feed cut the body short.
        """(read (more|on|the rest|the full (post|article))|continue reading)[\s\p{Punct}→»…]*.{0,40}""",
        // Share and follow widgets.
        """share (this|it|on)[\s\p{Punct}].{0,60}""",
        """(tweet|share|email this|print this|pin it|like this)[\s\p{Punct}]*""",
        """(follow|find) (me|us) on .{0,60}""",
        // Subscribe CTAs.
        """subscribe .{0,120}""",
        """sign up (for|to) .{0,120}""",
        // Comment counts and their links.
        """(\d+|no|one) comments?[\s\p{Punct}]*""",
        """(leave|post|add|view) (a |your |all )?comments?.{0,20}""",
        """comments? \(\d+\)[\s\p{Punct}]*""",
    ).map { Regex(it, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) }
}
