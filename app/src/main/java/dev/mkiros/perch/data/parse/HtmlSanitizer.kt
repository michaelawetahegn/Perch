package dev.mkiros.perch.data.parse

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * Entry bodies arrive as whatever HTML a publisher's CMS emitted, and the article screen
 * (T25) renders them. This is the gate between the two: the SPEC.md §5 allowlist, plus a
 * plain-text snippet for the entry list.
 *
 * Total, like the parsers — malformed input yields an empty result, never an exception.
 */
object HtmlSanitizer {

    /** SPEC.md §5's snippet budget: enough for two lines in `EntryRow`. */
    const val SUMMARY_CHARS: Int = 300

    /**
     * The one class the allowlist keeps, and the only form it keeps it in (U11).
     *
     * `class` is otherwise exactly the kind of presentational hook DESIGN.md §8 refuses to
     * let a feed reach us through, so rather than open the attribute up, [sanitize]
     * rewrites whatever language claim it can find into `class="language-x"` on the `pre`
     * and drops everything else. Downstream, `ArticleLowering` only ever has to read this.
     */
    const val LANGUAGE_PREFIX: String = "language-"

    /**
     * [html] reduced to the allowlist, with every surviving URL absolute against
     * [baseUrl] (the entry link). Null when nothing renderable is left.
     */
    fun sanitize(html: String?, baseUrl: String?): String? {
        val raw = html?.takeIf { it.isNotBlank() } ?: return null
        val dirty = runCatching { Jsoup.parse(raw, baseUrl.orEmpty()) }.getOrNull() ?: return null

        // Before DROP_WHOLESALE: a `<button>` is one of the two things that make a block a
        // plea rather than prose, and the wholesale pass would take it first.
        dirty.select("[class], [id]").filter { isPromotional(it) }.forEach { it.remove() }
        dirty.select(DROP_WHOLESALE).remove()
        dirty.select("img").forEach { promoteLazySource(it) }
        dirty.select("img").filter { isTrackingPixel(it) }.forEach { it.remove() }
        dirty.select("pre").forEach { it.normalizeLanguage() }

        val clean = runCatching { Cleaner(SAFELIST).clean(dirty) }.getOrNull() ?: return null
        // A URL the allowlist refused (`javascript:`, `data:`, an unresolvable relative
        // path) leaves the attribute gone but the element behind: an anchor is then just
        // its text, and an image is nothing at all.
        clean.select("a:not([href])").forEach { it.unwrap() }
        clean.select("img:not([src])").remove()
        clean.outputSettings().prettyPrint(false)

        // Markup that survived but has neither words nor pictures is not worth keeping.
        val renders = clean.body().text().isNotBlank() || clean.select("img").isNotEmpty()
        return clean.body().html().trim().takeIf { it.isNotEmpty() && renders }
    }

    /**
     * [html] as plain text, whole. Null when there are no words left in it.
     *
     * The search index's body (S08, #28): SQLite's tokenizer would otherwise index tag
     * names, class names and URLs, so a search for `class` or `https` would answer with the
     * whole database. [summarize] is the same reduction, cut short.
     */
    fun flatten(html: String?): String? {
        val raw = html?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Jsoup.parse(raw).text() }.getOrNull()
            ?.replace('\u00A0', ' ')?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * The first [maxChars] characters of [html] as plain text, cut on a word boundary.
     * Fed either raw or sanitized markup — it strips tags either way.
     */
    fun summarize(html: String?, maxChars: Int = SUMMARY_CHARS): String? {
        val text = flatten(html) ?: return null
        if (text.length <= maxChars) return text

        // One character of the budget belongs to the ellipsis.
        val head = text.take(maxChars - 1)
        val boundary = head.lastIndexOf(' ')
        val cut = if (boundary > 0) head.substring(0, boundary) else head
        return cut.trimEnd().trimEnd(',', ';', ':', '-', '—') + "…"
    }

    /**
     * Collapses this `pre`'s language claim to `class="language-x"`, or removes the
     * attribute entirely.
     *
     * The claim is somewhere different in every CMS, which is the whole reason this is a
     * search rather than an attribute read. Prism and highlight.js put it on the `<code>`;
     * Jekyll's Rouge puts it on a wrapper `<div class="language-c highlighter-rouge">` two
     * levels above the `<pre>` — which is what nullprogram.com ships, and what U11's
     * screenshots are of. So look down first, then up, and stop before the search reaches
     * far enough to inherit an unrelated ancestor's class.
     */
    private fun Element.normalizeLanguage() {
        val found = languageToken()
            ?: selectFirst("code")?.languageToken()
            ?: parents().take(ANCESTOR_REACH).firstNotNullOfOrNull { it.languageToken() }
        if (found == null) removeAttr("class") else attr("class", LANGUAGE_PREFIX + found)
    }

    private fun Element.languageToken(): String? = classNames()
        .firstNotNullOfOrNull { LANGUAGE_CLASS.matchEntire(it)?.groupValues?.get(1) }
        ?.lowercase()

    /**
     * A 1×1 image is a read receipt, not content. Publishers ship them from mail and
     * analytics vendors in otherwise ordinary paragraphs.
     *
     * Shared with `ArticleExtractor`, whose chrome sweep must not mistake one for a figure.
     */
    internal fun isTrackingPixel(img: Element): Boolean =
        listOf("width", "height").any { img.attr(it).trim().toIntOrNull()?.let { px -> px <= 1 } == true }

    /**
     * A call-to-action block is not article text (F04, #70): its `class`/`id` tokens name it
     * ([PROMOTIONAL_TOKENS]) **and** it holds a link or a button **and** it carries fewer
     * than [SHORT_BLOCK_CEILING] characters of text. All three, so a `<div
     * id="donation-records">` wrapping three real paragraphs survives, and so does a naked
     * `<p class="promo">` with nothing to tap. Only class tokens ever name Bellingcat's
     * block — no `<aside>`, no `role`, no `<form>` — which is why this runs here, the last
     * point on the feed path where those attributes still exist, and why `ArticleExtractor`
     * asks the same question before its own image exemption.
     */
    internal fun isPromotional(element: Element): Boolean =
        PROMOTIONAL.containsMatchIn("${element.className()} ${element.id()}") &&
            element.selectFirst("a, button") != null &&
            element.text().length < SHORT_BLOCK_CEILING

    /**
     * Puts the picture a lazy-loading CMS hid behind a `data-*` attribute into `src`, so
     * the [Cleaner] — which keeps `src` and nothing else — keeps the picture (F03, #70).
     *
     * A present lazy attribute wins over `src` outright rather than only when `src` looks
     * like a placeholder: the placeholder is usually a real URL to a spacer GIF, which no
     * rule can tell from a picture. Failing that, an image with no usable `src` — absent,
     * blank, or a `data:` URI the allowlist would refuse anyway — takes the widest
     * candidate of its `srcset`. An image with a real `src` and a `srcset` is left alone;
     * the publisher chose that size.
     *
     * This is the one copy: `ArticleExtractor.absolutise` calls it for the page path, and
     * [sanitize]'s pre-[Cleaner] pass calls it for the feed path.
     */
    internal fun promoteLazySource(img: Element) {
        val lazy = LAZY_SRC.firstNotNullOfOrNull { img.attr(it).trim().takeIf { url -> url.isNotEmpty() } }
        val src = img.attr("src").trim()
        val usable = src.isNotEmpty() && !src.startsWith("data:", ignoreCase = true)
        val promoted = lazy
            ?: if (usable) return
            else LAZY_SRCSET.firstNotNullOfOrNull { widestCandidate(img.attr(it)) }
        promoted?.let { img.attr("src", it) }
    }

    /** The URL of the widest (or densest) candidate in a `srcset`, or null if it has none. */
    private fun widestCandidate(srcset: String): String? = srcset.split(",")
        .map { it.trim().split(WHITESPACE, limit = 2) }
        .filter { it.first().isNotEmpty() }
        .maxByOrNull { it.getOrNull(1)?.trimEnd('w', 'x')?.toDoubleOrNull() ?: 1.0 }
        ?.first()

    /**
     * Elements whose *content* must go with them. jsoup unwraps a disallowed element and
     * keeps its children, which is right for a `<div>` wrapper and very wrong for a
     * `<script>` body.
     */
    /**
     * `pre` → `div.highlight` → `div.language-c` is the deepest real nesting in the corpus;
     * beyond that a match is an unrelated section wrapper rather than a language claim.
     */
    private const val ANCESTOR_REACH = 3

    /** `language-c`, `lang-c`, `highlight-source-c`, `brush:c` — all the same claim. */
    private val LANGUAGE_CLASS = Regex(
        "(?:language|lang|highlight-source|brush)[-:]([A-Za-z0-9+#._-]+)",
    )

    /**
     * Under this much text, a named block is judged by what it holds rather than what it
     * says: a chrome-named container that is mostly a picture is a figure (F03), and a
     * promotion-named one that is mostly a button is a plea (F04). A block above it is
     * prose whatever its name.
     */
    internal const val SHORT_BLOCK_CEILING: Int = 400

    /**
     * The one list of tokens that name a promotional block, as an alternation. [PROMOTIONAL]
     * binds it to whole tokens here; `ArticleExtractor.NEGATIVE` reads the same string so
     * the extractor's scoring agrees with the sanitizer's removal.
     */
    internal const val PROMOTIONAL_TOKENS: String =
        "donat\\w*|promo\\w*|banner|newsletter|subscribe|subscription|appeal|membership|" +
            "support-us|cta|call-to-action"

    private val PROMOTIONAL = Regex("\\b(?:$PROMOTIONAL_TOKENS)\\b", RegexOption.IGNORE_CASE)

    /** Where a lazy-loading CMS puts the real picture, in the order the corpus meets them. */
    private val LAZY_SRC = listOf("data-src", "data-lazy-src", "data-original")
    private val LAZY_SRCSET = listOf("data-srcset", "srcset")
    private val WHITESPACE = Regex("\\s+")

    private const val DROP_WHOLESALE =
        "script, style, noscript, iframe, frame, object, embed, applet, svg, math, " +
            "form, input, button, select, textarea, link, meta, base"

    private val SAFELIST: Safelist = Safelist()
        .addTags(
            "p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote",
            "pre", "code", "em", "strong", "b", "i", "a", "img", "figure", "figcaption",
            "hr", "table", "thead", "tbody", "tr", "th", "td", "sub", "sup",
        )
        .addAttributes("a", "href")
        .addAttributes("img", "src", "alt")
        // Only on `pre`, and only ever holding what `normalizeLanguage` put there.
        .addAttributes("pre", "class")
        // A span is the table's *shape*, not the source voting on typography (§8): without
        // it the lowering cannot tell a merged cell from a missing one, and every column
        // to its right shifts by one for the rest of the table.
        .addAttributes("td", "colspan", "rowspan")
        .addAttributes("th", "colspan", "rowspan")
        // Restricting the protocol is also what makes jsoup rewrite the value to its
        // absolute form, so this line is doing the relative-URL resolution too.
        .addProtocols("a", "href", "http", "https")
        .addProtocols("img", "src", "http", "https")
}
