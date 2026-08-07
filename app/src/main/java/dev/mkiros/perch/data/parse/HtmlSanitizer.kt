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
     * [html] reduced to the allowlist, with every surviving URL absolute against
     * [baseUrl] (the entry link). Null when nothing renderable is left.
     */
    fun sanitize(html: String?, baseUrl: String?): String? {
        val raw = html?.takeIf { it.isNotBlank() } ?: return null
        val dirty = runCatching { Jsoup.parse(raw, baseUrl.orEmpty()) }.getOrNull() ?: return null

        dirty.select(DROP_WHOLESALE).remove()
        dirty.select("img").filter { it.isTrackingPixel() }.forEach { it.remove() }

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
     * The first [maxChars] characters of [html] as plain text, cut on a word boundary.
     * Fed either raw or sanitized markup — it strips tags either way.
     */
    fun summarize(html: String?, maxChars: Int = SUMMARY_CHARS): String? {
        val raw = html?.takeIf { it.isNotBlank() } ?: return null
        val text = runCatching { Jsoup.parse(raw).text() }.getOrNull()
            ?.replace('\u00A0', ' ')?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        if (text.length <= maxChars) return text

        // One character of the budget belongs to the ellipsis.
        val head = text.take(maxChars - 1)
        val boundary = head.lastIndexOf(' ')
        val cut = if (boundary > 0) head.substring(0, boundary) else head
        return cut.trimEnd().trimEnd(',', ';', ':', '-', '—') + "…"
    }

    /**
     * A 1×1 image is a read receipt, not content. Publishers ship them from mail and
     * analytics vendors in otherwise ordinary paragraphs.
     */
    private fun Element.isTrackingPixel(): Boolean =
        listOf("width", "height").any { attr(it).trim().toIntOrNull()?.let { px -> px <= 1 } == true }

    /**
     * Elements whose *content* must go with them. jsoup unwraps a disallowed element and
     * keeps its children, which is right for a `<div>` wrapper and very wrong for a
     * `<script>` body.
     */
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
        // Restricting the protocol is also what makes jsoup rewrite the value to its
        // absolute form, so this line is doing the relative-URL resolution too.
        .addProtocols("a", "href", "http", "https")
        .addProtocols("img", "src", "http", "https")
}
