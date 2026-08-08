package dev.mkiros.perch.data.parse

import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * The entry thumbnail, per PLAN-2.md §0: `media:thumbnail`, then a `media:content` that
 * declares itself an image, then an image `<enclosure>`, then the first real `<img>` in
 * the body. Relative URLs resolve against the entry link, which is the page the markup
 * was written for — the feed URL often lives on another host entirely.
 *
 * **A missing image is a designed state, not a failure.** Most of the corpus is text-only
 * blogs that will never have one, so every rung resolves to null rather than to a guessed
 * or broken URL, and U08's row draws the placeholder for it.
 */
internal object LeadImage {

    /**
     * Below this many declared pixels the image is furniture — a rule, a badge, a share
     * icon, a read receipt — not something a 96dp thumbnail can show.
     */
    const val MIN_PX = 64

    /** The RSS-family chain: the media rungs, then `<enclosure>`. */
    fun fromItem(item: Element, base: String?): String? =
        item.mediaImage(base)
            ?: item.childElements("enclosure").imageOfType(base, urlAttr = "url")

    /** The Atom chain. Only the enclosure differs: Atom spells it `<link rel="enclosure">`. */
    fun fromEntry(entry: Element, base: String?): String? =
        entry.mediaImage(base)
            ?: entry.childElements("link")
                .filter { it.attr("rel").equals("enclosure", ignoreCase = true) }
                .imageOfType(base, urlAttr = "href")

    /**
     * The first picture in the entry body that is content rather than furniture. Parsed
     * with the HTML parser rather than the XML one because that is what the markup is,
     * whether it arrived escaped, in CDATA, or as real XHTML children.
     */
    fun fromBody(html: String?, base: String?): String? {
        val raw = html?.takeIf { it.isNotBlank() } ?: return null
        val document = runCatching { Jsoup.parse(raw) }.getOrNull() ?: return null
        // An image the sanitizer will drop wholesale must not be the one we advertise.
        document.select(NOT_CONTENT).remove()
        return document.select("img").firstNotNullOfOrNull { it.imageUrl(base, LAZY_SRC_ATTRS) }
    }

    /** `media:thumbnail` first, then any `media:content` that says it is an image. */
    private fun Element.mediaImage(base: String?): String? {
        val candidates = children() + childElements("media:group").flatMap { it.children() }
        val thumbnails = candidates.filter { it.tagName().equals("media:thumbnail", true) }
        val contents = candidates.filter {
            it.tagName().equals("media:content", true) && it.declaresImage()
        }
        return (thumbnails + contents).firstNotNullOfOrNull { it.imageUrl(base, listOf("url")) }
    }

    /**
     * An `<enclosure>` is just as often a podcast MP3, so the media type decides — never
     * the mere presence of the element.
     */
    private fun List<Element>.imageOfType(base: String?, urlAttr: String): String? =
        firstOrNull { it.attr("type").startsWith("image/", ignoreCase = true) }
            ?.imageUrl(base, listOf(urlAttr))

    /** `medium="image"` is the Media RSS way; a `type` is the common shorthand. */
    private fun Element.declaresImage(): Boolean =
        attr("medium").equals("image", ignoreCase = true) ||
            attr("type").startsWith("image/", ignoreCase = true)

    /** The element's image URL, or null if it is too small, tracker-shaped, or unusable. */
    private fun Element.imageUrl(base: String?, urlAttrs: List<String>): String? {
        if (belowFloor()) return null
        val raw = urlAttrs.firstNotNullOfOrNull { attr(it).trim().takeIf(String::isNotEmpty) }
        return resolveUrl(base, raw)?.takeIf(::isContent)
    }

    /**
     * Dimensions are *declared*, not measured — we never fetch an image to size it. A
     * feed that declares nothing gets the benefit of the doubt, which is why the check is
     * "known to be small" rather than "not known to be large".
     */
    private fun Element.belowFloor(): Boolean = listOf("width", "height").any { name ->
        attr(name).trim().removeSuffix("px").toIntOrNull()?.let { it < MIN_PX } == true
    }

    /**
     * Trackers that declare no size, recognised by the only thing left to go on: their
     * name. Matched against the file stem and against the two feed-proxy paths that are
     * always beacons, so an article legitimately named `pixels-and-color.jpg` survives.
     */
    private fun isContent(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        if (FEED_BEACON_PATH.containsMatchIn(path)) return false
        val stem = path.substringAfterLast('/').substringBeforeLast('.')
        return !TRACKER_STEM.matches(stem)
    }

    /** Feeds lazy-load in the reader too; `src` is authoritative, the rest are its stand-ins. */
    private val LAZY_SRC_ATTRS = listOf("src", "data-src", "data-original", "data-lazy-src")

    private val TRACKER_STEM =
        Regex("(1x1|pixel|spacer|beacon|blank|transparent|clear|dot)([._-].*)?", RegexOption.IGNORE_CASE)

    /** FeedBurner's `~ff` / `~r` proxies serve ad and stat images, never article art. */
    private val FEED_BEACON_PATH = Regex("/~(ff|r)/")

    /**
     * Markup `HtmlSanitizer` removes content and all. An `<img>` in there is a fallback
     * for a script that never runs, or an ad — either way it is not this entry's picture.
     */
    private const val NOT_CONTENT = "script, style, noscript, iframe, object, embed, svg"
}
