package dev.mkiros.perch.data.extract

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pulls the article out of a page that has one (U10, PLAN-2 §0).
 *
 * Two feed shapes need this and neither is a bug in Perch: a feed that ships no body at
 * all (fabiensanglard.net — title, link, date, 144 items) and a feed whose `<description>`
 * is a teaser standing in for the body (gpuopen.com — 194 characters, no
 * `content:encoded`). In both cases the prose is on the page, so Perch goes and gets it;
 * the goal is that visiting the site is never required to read an article.
 *
 * The method is Readability's, over jsoup, which is already a dependency:
 *
 *  1. **Strip** the elements that are never article body — scripts, chrome landmarks, and
 *     containers whose class or id names them as navigation, comments or promotion.
 *  2. **Score** every paragraph by how much prose it carries — length, comma count — and
 *     credit that score to its ancestors, halving it per level up. Prose clusters under
 *     one subtree; chrome does not.
 *  3. **Divide by link density**, which is what separates a column of paragraphs from a
 *     column of headlines. A related-posts block is mostly anchor text and scores near
 *     zero however long it is.
 *  4. **Take the top subtree** plus any sibling that scores well enough to be part of the
 *     same article, and clean the chrome that survived inside it.
 *
 * The output is unsanitized HTML, exactly like a feed body, and goes through the same
 * `HtmlSanitizer` → `ArticleLowering` pipeline. That is the point: an extracted article
 * gets no special treatment downstream, so everything the article screen already renders
 * correctly it renders correctly here too.
 *
 * Total, like the parsers — malformed input yields null, never an exception.
 */
object ArticleExtractor {

    /**
     * The article inside [html], or null if the page does not appear to have one.
     *
     * [baseUrl] is the page's own address; every surviving `href` and `src` is rewritten
     * absolute against it, so the returned markup stands on its own and does not depend on
     * whoever parses it next knowing where it came from.
     */
    fun extract(html: String?, baseUrl: String?): String? {
        val raw = html?.takeIf { it.isNotBlank() } ?: return null
        val doc = runCatching { Jsoup.parse(raw, baseUrl.orEmpty()) }.getOrNull() ?: return null

        return runCatching {
            strip(doc)
            val scores = score(doc)
            val top = scores.maxByOrNull { it.value } ?: return null
            if (top.value < MIN_CANDIDATE_SCORE) return null

            val article = assemble(top.key.unwrapped(), top.value, scores)
            clean(article)
            absolutise(article)
            article.html().takeIf { article.text().length >= MIN_PROSE_CHARS }
        }.getOrNull()
    }

    /**
     * How much prose an extraction would recover, without keeping the markup.
     *
     * The caller that decides whether to *replace* a body with an extraction needs the
     * length of both before it commits to either — U10's rule is that a failed or thin
     * extraction keeps what the feed had, because replacing text with less text is the one
     * outcome worse than not trying.
     */
    fun proseLength(html: String?, baseUrl: String?): Int =
        extract(html, baseUrl)?.let { Jsoup.parse(it, baseUrl.orEmpty()).text().length } ?: 0

    // ---- 1. strip ---------------------------------------------------------------

    private fun strip(doc: Document) {
        doc.select(DROP_WHOLESALE).remove()
        // The chrome landmarks. `header` and `footer` are dropped everywhere rather than
        // only at document level: a WordPress post's own `entry-header` holds the title
        // and the share row, and the title is already on the article screen.
        doc.select(LANDMARKS).remove()
        doc.select("[aria-hidden=true], [hidden]").remove()

        // Readability's unlikely-candidate pass, in its original conservative form: a
        // container is only dropped when its name says chrome *and* says nothing that
        // sounds like an article. `class="post-comments"` goes; `class="content"` stays.
        doc.body().select("div, section, ul, ol, aside, span, table")
            .filter { it.namesChrome() }
            .forEach { it.remove() }
    }

    // ---- 2 & 3. score -----------------------------------------------------------

    /**
     * Every plausible container mapped to its link-density-adjusted score.
     *
     * A paragraph's own score is credited to its parent in full and to each further
     * ancestor halved again, which is what makes the *tightest* subtree containing the
     * prose win: an outer wrapper accumulates the same paragraphs at a discount.
     */
    private fun score(doc: Document): Map<Element, Double> {
        val raw = mutableMapOf<Element, Double>()

        for (node in doc.body().select(PROSE_TAGS)) {
            val text = node.text()
            if (text.length < MIN_PARAGRAPH_CHARS) continue

            val score = 1.0 +
                text.count { it == ',' }.coerceAtMost(MAX_COMMA_CREDIT) +
                (text.length / CHARS_PER_POINT).coerceAtMost(MAX_LENGTH_CREDIT)

            var ancestor: Element? = node.parent()
            var level = 0
            while (ancestor != null && level <= ANCESTOR_LEVELS && ancestor.tagName() != "html") {
                val divisor = if (level == 0) 1.0 else (level * 2).toDouble()
                raw[ancestor] = raw.getOrElse(ancestor) { ancestor.baseScore() } + score / divisor
                ancestor = ancestor.parent()
                level++
            }
        }

        // Link density last, so it discounts the accumulated total rather than each
        // contribution: a related-posts list is only recognisable as one once its whole
        // subtree is in view.
        return raw.mapValues { (element, value) -> value * (1.0 - element.linkDensity()) }
    }

    /** The head start a container gets for what it is and for what it calls itself. */
    private fun Element.baseScore(): Double {
        val tag = when (tagName()) {
            "article", "main" -> 8.0
            "div", "section" -> 5.0
            "pre", "td", "blockquote" -> 3.0
            "form", "ol", "ul", "dl", "dd", "dt", "li", "address" -> -3.0
            "th" -> -5.0
            else -> 0.0
        }
        return tag + classWeight()
    }

    private fun Element.classWeight(): Double {
        val name = "${className()} ${id()}"
        var weight = 0.0
        if (POSITIVE.containsMatchIn(name)) weight += CLASS_WEIGHT
        if (NEGATIVE.containsMatchIn(name)) weight -= CLASS_WEIGHT
        return weight
    }

    /**
     * The share of this element's text that is inside a link, 0..1.
     *
     * This is the single most useful signal in the whole method. Navigation, footers,
     * related-posts blocks and tag clouds are all long and all almost entirely anchor
     * text; an article is long and mostly not.
     */
    private fun Element.linkDensity(): Double {
        val total = text().length
        if (total == 0) return 0.0
        val linked = select("a").sumOf { it.text().length }
        return (linked.toDouble() / total).coerceIn(0.0, 1.0)
    }

    // ---- 4. assemble and clean --------------------------------------------------

    /**
     * The winning subtree, climbed out of any purely decorative wrapper it sits in.
     *
     * The scoring is deliberately biased towards the *tightest* subtree holding the prose,
     * and a page that wraps its body in a presentational `<div>` — a background, a
     * gradient, a column shell — turns that bias into a trap: the wrapper holds every
     * paragraph but one, and the last section, sitting outside it as a sibling, is lost.
     * ciechanow.ski is exactly this shape. Climbing while the parent has no other children
     * costs nothing (a single-child parent contains precisely what its child does) and
     * puts the real article root's siblings back within reach of [assemble].
     */
    private fun Element.unwrapped(): Element {
        var node = this
        while (true) {
            val parent = node.parent() ?: return node
            if (parent.tagName() == "body" || parent.children().size != 1) return node
            node = parent
        }
    }

    /**
     * The winning subtree, plus the siblings that belong to the same article.
     *
     * Pages that break an article into several sibling `<div>`s are common enough — a lede
     * block, then the body, then a code appendix — that taking the top candidate alone
     * truncates them. A sibling joins on its own score, on carrying substantial prose, or
     * on carrying a table, and on nothing else.
     */
    private fun assemble(top: Element, topScore: Double, scores: Map<Element, Double>): Element {
        val parent = top.parent() ?: return top.clone()
        val threshold = maxOf(MIN_SIBLING_SCORE, topScore * SIBLING_SHARE)

        val article = Element("div")
        for (sibling in parent.children()) {
            val keep = sibling === top ||
                (scores[sibling] ?: 0.0) >= threshold ||
                sibling.carriesSubstantialProse() ||
                sibling.carriesContentTable()
            if (keep) article.appendChild(sibling.clone())
        }
        return if (article.children().isEmpty()) top.clone() else article
    }

    /**
     * A paragraph long enough to be article, or a wrapper holding one.
     *
     * The wrapper case is what a block-per-`<div>` CMS needs: Squarespace's closing
     * paragraph is not a sibling `<p>`, it is a `sqs-block` two levels above one, and a
     * predicate that only recognises a bare `<p>` truncates the article at the last block
     * that happened to score.
     */
    private fun Element.carriesSubstantialProse(): Boolean {
        val prose = (if (tagName() == "p") listOf(this) else select("p")).sumOf { it.text().length }
        return prose > SIBLING_PARAGRAPH_CHARS && linkDensity() < LINKY
    }

    /**
     * A sibling that is, or wraps, a table carrying data (V09).
     *
     * Scoring is text density, and a table is mostly markup, so a table never clears
     * [MIN_SIBLING_SCORE] however much it says. On a CMS that gives every block its own
     * `<div>` — Squarespace's `sqs-block` — that puts the table one sibling away from the
     * winning subtree, and on a Zero Day Initiative post the table of the month's CVEs
     * *is* the post: dropping it leaves two paragraphs promising a look at all the bugs,
     * above nothing.
     *
     * The counter-risk is resurrecting the layout table, so this asks for the shape of
     * data rather than for a `<table>`: a real grid (several rows, more than one column),
     * no table nested inside it — nesting is how a page is laid out, not how data is
     * written — and prose rather than a column of links, which is the same signal that
     * separates an article from a navigation block everywhere else in this file.
     */
    private fun Element.carriesContentTable(): Boolean {
        if (namesChrome()) return false
        val table = (if (tagName() == "table") this else selectFirst("table")) ?: return false
        if (table.namesChrome() || table.select("table").size > 1) return false

        val rows = table.select("tr")
        if (rows.size < MIN_TABLE_ROWS) return false
        if (rows.maxOf { it.select("td, th").size } < MIN_TABLE_COLUMNS) return false
        return table.linkDensity() < LINKY
    }

    /**
     * The chrome that lives *inside* the article: a share row under the title, a
     * related-posts rail before the comments, a tag list at the foot.
     *
     * Two rules, in Readability's order. A container that names itself chrome goes
     * outright. A container that does not is judged on link density instead, because the
     * markup is often anonymous — an unnamed `<ul>` of nine links and eleven words is a
     * navigation block whatever it calls itself.
     */
    private fun clean(article: Element) {
        article.select(LANDMARKS).remove()
        article.select("div, section, ul, ol, table, aside, form, dl")
            .filter { it.namesChrome() }
            .forEach { it.remove() }

        article.select("ul, ol, div, table").forEach { block ->
            val text = block.text()
            if (block.select(PROSE_TAGS).isNotEmpty()) return@forEach
            if (text.length < CHROME_TEXT_CEILING && block.linkDensity() > LINKY) block.remove()
        }

        // Markup that survived with neither words nor pictures is furniture.
        article.select("div, section, span, p, li").reversed().forEach { element ->
            if (element.text().isBlank() && element.select("img").isEmpty()) element.remove()
        }
    }

    private fun Element.namesChrome(): Boolean {
        val name = "${className()} ${id()}"
        if (name.isBlank()) return false
        return NEGATIVE.containsMatchIn(name) && !POSITIVE.containsMatchIn(name)
    }

    /**
     * Rewrites every URL absolute, and promotes the lazy-loading attributes that CMSes put
     * a real image behind — a `src` of a 1×1 spacer with the picture in `data-src` is the
     * normal shape on a WordPress page, and taking it at face value loses every figure.
     */
    private fun absolutise(article: Element) {
        for (img in article.select("img")) {
            LAZY_SRC.firstOrNull { img.hasAttr(it) && img.attr("abs:$it").isNotBlank() }
                ?.let { img.attr("src", img.attr("abs:$it")) }
                ?: img.attr("abs:src").takeIf { it.isNotBlank() }?.let { img.attr("src", it) }
        }
        for (anchor in article.select("a[href]")) {
            anchor.attr("abs:href").takeIf { it.isNotBlank() }?.let { anchor.attr("href", it) }
        }
    }

    // ---- constants --------------------------------------------------------------

    /** Elements whose *content* must go with them, as in `HtmlSanitizer`. */
    private const val DROP_WHOLESALE =
        "script, style, noscript, iframe, frame, object, embed, applet, svg, math, canvas, " +
            "form, input, button, select, textarea, link, meta, base, dialog, template"

    private const val LANDMARKS =
        "nav, header, footer, aside, [role=navigation], [role=banner], [role=contentinfo], " +
            "[role=complementary], [role=search]"

    /** What counts as prose for scoring. Lists are excluded: navigation is a list. */
    private const val PROSE_TAGS = "p, pre, blockquote, td"

    private const val MIN_PARAGRAPH_CHARS = 25
    private const val MAX_COMMA_CREDIT = 10
    private const val CHARS_PER_POINT = 100
    private const val MAX_LENGTH_CREDIT = 3
    private const val ANCESTOR_LEVELS = 3
    private const val CLASS_WEIGHT = 25.0
    /**
     * Low, because it is not the gate it looks like. What actually decides whether a page
     * yielded an article is [MIN_PROSE_CHARS] and, above this class, the rule that an
     * extraction only replaces a body it is longer than. This number only has to keep a
     * page of pure navigation from being mistaken for a short post.
     */
    private const val MIN_CANDIDATE_SCORE = 10.0
    private const val MIN_SIBLING_SCORE = 10.0
    private const val SIBLING_SHARE = 0.2
    private const val SIBLING_PARAGRAPH_CHARS = 80

    /** A grid this small is a layout trick or a two-cell aside, not the article's data. */
    private const val MIN_TABLE_ROWS = 3
    private const val MIN_TABLE_COLUMNS = 2
    private const val LINKY = 0.5
    private const val CHROME_TEXT_CEILING = 200

    /** A page that yields less than this is a page we failed to read, not a short article. */
    const val MIN_PROSE_CHARS = 200

    private val LAZY_SRC = listOf("data-src", "data-lazy-src", "data-original", "data-srcset")

    private val POSITIVE = Regex(
        "article|body|content|entry|hentry|main|page|post|text|blog|story|column|prose",
        RegexOption.IGNORE_CASE,
    )

    private val NEGATIVE = Regex(
        "combx|comment|contact|foot|footer|footnote|masthead|media|meta|outbrain|promo|" +
            "related|scroll|share|shoutbox|sidebar|sponsor|shopping|tags|tool|widget|nav|" +
            "menu|banner|cookie|consent|newsletter|subscribe|social|breadcrumb|pagination|" +
            "pager|popup|modal|skip|toolbar|byline|author-bio|disqus|hidden|sr-only",
        RegexOption.IGNORE_CASE,
    )
}
