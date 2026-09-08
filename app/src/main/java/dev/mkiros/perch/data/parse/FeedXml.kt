package dev.mkiros.perch.data.parse

import java.net.URI
import java.security.MessageDigest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * Shared plumbing for the three feed parsers: lenient XML parsing, element lookup,
 * and the text/URL/GUID normalization SPEC.md §5 requires of all of them.
 *
 * Everything here is total — malformed input yields an empty result, never an exception.
 */

/** Lenient XML parse. jsoup's XML tree builder never throws on truncated or mismatched markup. */
internal fun parseFeedXml(xml: String, baseUri: String? = null): Document =
    Jsoup.parse(xml, baseUri.orEmpty(), Parser.xmlParser())

/**
 * The first *direct child* named by [names], in the order [names] are given rather than
 * in document order — callers pass them most-preferred-first (`content:encoded` before
 * `description`), and feeds do not order their elements to suit us.
 *
 * Direct children only, on purpose: a truncated document nests the next `<item>` inside
 * the previous one, and a descendant search would then attribute the child's fields to
 * its accidental parent.
 */
internal fun Element.childElement(vararg names: String): Element? =
    names.firstNotNullOfOrNull { name ->
        children().firstOrNull { name.equals(it.tagName(), ignoreCase = true) }
    }

/** Every direct child matching any of [names]. */
internal fun Element.childElements(vararg names: String): List<Element> =
    children().filter { child -> names.any { it.equals(child.tagName(), ignoreCase = true) } }

/** The tag name with any namespace prefix removed: `RDF` from `rdf:RDF`. */
internal fun Element.localName(): String = tagName().substringAfterLast(':')

/**
 * Every direct child whose *local* name is [name], whatever prefix binds it. RSS 1.0 is
 * the one format where the prefix is not predictable — the document, not the spec,
 * chooses it — so its elements are matched by local name rather than by literal tag.
 */
internal fun Element.childElementsNamed(name: String): List<Element> =
    children().filter { name.equals(it.localName(), ignoreCase = true) }

/** The value of the attribute whose local name is [name], under whatever prefix. */
internal fun Element.attrNamed(name: String): String? =
    attributes().firstOrNull { name.equals(it.key.substringAfterLast(':'), ignoreCase = true) }
        ?.value?.trim()?.takeIf { it.isNotEmpty() }

/** The raw text of the first direct child named by [names], or null if blank/absent. */
internal fun Element.childText(vararg names: String): String? =
    childElement(*names)?.wholeText()?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The richest markup an element holds. Feeds escape entry bodies (`&lt;p&gt;`) or wrap
 * them in CDATA — both arrive as text and are returned verbatim — but XHTML content
 * arrives as real child elements, in which case the inner markup is what matters.
 */
internal fun Element.markup(): String? {
    val raw = if (children().isEmpty()) wholeText() else html()
    return raw.trim().takeIf { it.isNotEmpty() }
}

/**
 * Titles and author names, per SPEC.md §5: entities decoded, tags stripped, whitespace
 * collapsed. Feeds routinely double-escape markup into a title, so the XML-decoded text
 * gets a second pass through the HTML parser.
 */
internal fun plainText(raw: String?): String? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return Jsoup.parse(text).text().trim().takeIf { it.isNotEmpty() }
}

/**
 * Resolves [raw] against [base] and keeps it only if it ends up http(s) — that drops
 * `javascript:`, `data:` and anything unparseable, which is what T09 asserts of every
 * entry link.
 */
internal fun resolveUrl(base: String?, raw: String?): String? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val resolved = runCatching {
        val target = URI(text)
        if (base.isNullOrBlank()) target else URI(base).resolve(target)
    }.getOrNull() ?: return null
    val scheme = resolved.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    return resolved.toString()
}

/** `example.com` from `https://www.example.com/blog` — a last-resort feed title. */
internal fun hostOf(url: String?): String? {
    val host = runCatching { URI(url ?: return null).host }.getOrNull() ?: return null
    return host.removePrefix("www.").takeIf { it.isNotEmpty() }
}

/**
 * `scheme://host[:port]` for [url], or null if it has neither. The root a site's
 * conventional paths hang off: [FeedDiscovery]'s `/feed`, `/rss.xml`… guesses, and the
 * `robots.txt` that [dev.mkiros.perch.data.archive.ArchiveDiscovery] reads for `Sitemap:`
 * lines and [dev.mkiros.perch.data.repo.BackfillRepository] reads again for `Disallow`.
 */
internal fun hostRoot(url: String): String? = runCatching {
    val uri = URI(url)
    val host = uri.host ?: return null
    val scheme = uri.scheme ?: return null
    if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
}.getOrNull()

/**
 * `/blog/a-post` from `https://example.com/blog/a-post?x=1`, or null when [url] does not
 * parse. Every caller decides for itself what an unparseable URL means, so this one only
 * reports it — a robots rule matches nothing, a lead image keeps its empty stem.
 */
internal fun pathOf(url: String): String? = runCatching { URI(url).path }.getOrNull()

/**
 * Last rung of the GUID chain: a digest of whatever identifies the entry in the
 * document. Deterministic, so refetching the same feed re-derives the same identity and
 * the upsert in T15 recognises the entry instead of duplicating it.
 */
internal fun stableGuid(vararg parts: String?): String {
    val joined = parts.joinToString("\u0000") { it.orEmpty() }
    val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * `Rook` from `rook@birdwire.example (Rook)`. RFC-822 puts the human-readable name in
 * parentheses after the mailbox; a feed that gives only a mailbox keeps it.
 */
internal fun personName(raw: String?): String? {
    val text = plainText(raw) ?: return null
    return MAILBOX_NAME.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: text
}

private val MAILBOX_NAME = Regex("\\(([^)]*)\\)\\s*$")

/** What a feed with no usable `<title>` and no resolvable host is called. */
internal const val UNTITLED_FEED = "(untitled feed)"

/** What an entry with no usable `<title>` is called. */
internal const val UNTITLED_ENTRY = "(untitled)"
