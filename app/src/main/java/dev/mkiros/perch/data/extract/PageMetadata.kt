package dev.mkiros.perch.data.extract

import dev.mkiros.perch.data.parse.DateParser
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * What a page says about itself, read from published standards alone (PLAN-6 §0).
 *
 * Everything here is a **fallback chain, ordered by how standard the source is**, never a
 * branch on which site this happens to be — the §0.1 grep gate holds `data/extract/` to
 * zero hostname literals, and this file is exactly what it holds to it. A page that answers
 * none of these questions is not a bug in the page; [title] and [publishedAt] are both
 * nullable for exactly that reason, and a caller with no date is expected to fall back to
 * when *it* fetched the page (`EntryEntity.publishedIsEstimated`), never to invent one.
 */
data class PageMetadata(
    val title: String?,
    val publishedAt: Instant?,
)

/**
 * Extracts [PageMetadata] from a page's own `<head>`, `<body>` and its own URL.
 *
 * `Document`-in, value-out — no network, no DB — the same shape as [ArticleExtractor],
 * which this sits beside. **Must run against the unlowered document**: `ArticleLowering`
 * deletes "Continue reading"-style markers as chrome (NOTES.md, U10), which is right for
 * rendering and would make a `<title>` carrying one unreadable by the time this ran.
 */
object PageMetadataExtractor {

    private val dateParser = DateParser()

    fun extract(document: Document, url: String?): PageMetadata =
        PageMetadata(title = title(document), publishedAt = publishedAt(document, url))

    // ---- title, first that answers -----------------------------------------------

    private fun title(doc: Document): String? =
        metaContent(doc, "meta[property=og:title], meta[name=og:title]")
            ?: metaContent(doc, "meta[name=twitter:title], meta[property=twitter:title]")
            ?: jsonLdString(doc, "headline")
            ?: bodyHeading(doc)
            ?: titleTagTrimmed(doc)

    /** The `<h1>` inside what `ArticleExtractor` would keep — never the page's own chrome. */
    private fun bodyHeading(doc: Document): String? {
        val body = ArticleExtractor.extract(doc.outerHtml(), doc.baseUri()) ?: return null
        return Jsoup.parse(body).selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
    }

    /**
     * `<title>` with a trailing " | Site Name" (or " – ", " — ", " · ", " - ") cut off.
     *
     * The **last** separator in the string is the cut point, on the assumption that a site
     * name is appended once at the end; a title that legitimately contains one of these
     * marks earlier survives intact. This is a typographic convention, not a list of names.
     */
    private fun titleTagTrimmed(doc: Document): String? {
        val raw = doc.title().trim().takeIf { it.isNotBlank() } ?: return null
        val cut = SITE_SEPARATORS.mapNotNull { sep -> raw.lastIndexOf(sep).takeIf { it > 0 } }
            .maxOrNull()
            ?: return raw
        return raw.substring(0, cut).trim().takeIf { it.isNotBlank() } ?: raw
    }

    // ---- published date, first that answers ----------------------------------------

    private fun publishedAt(doc: Document, url: String?): Instant? =
        metaContent(doc, "meta[property=article:published_time]")?.let(dateParser::parse)
            ?: jsonLdString(doc, "datePublished")?.let(dateParser::parse)
            ?: timeTag(doc)
            ?: metaContent(doc, "meta[name=date], meta[name=DC.date]")?.let(dateParser::parse)
            ?: urlDate(url)

    /** `<time datetime=…>` carrying `pubdate` or `itemprop="datePublished"` — nothing else. */
    private fun timeTag(doc: Document): Instant? {
        val time = doc.select("time[datetime]")
            .firstOrNull { it.hasAttr("pubdate") || it.attr("itemprop") == "datePublished" }
            ?: return null
        return dateParser.parse(time.attr("datetime"))
    }

    /**
     * The weakest source, and last for that reason: a date in the URL's own path
     * (`/2026/07/27/`), a convention every dated blog engine shares regardless of CMS. A
     * page whose metadata disagrees with its URL trusts the metadata — this only runs once
     * every stronger source has already said no.
     */
    private fun urlDate(url: String?): Instant? {
        val match = url?.let { URL_DATE.find(it) } ?: return null
        val (year, month, day) = match.destructured
        return dateParser.parse("$year-$month-${day}T00:00:00Z")
    }

    // ---- shared readers -----------------------------------------------------------

    private fun metaContent(doc: Document, selector: String): String? =
        doc.select(selector).firstNotNullOfOrNull { it.attr("content").trim().takeIf { c -> c.isNotBlank() } }

    /**
     * The first JSON-LD `<script>` naming [key], searched permissively — a bare object, an
     * array of them, or the `@graph` wrapper schema.org itself documents for a page
     * describing more than one thing. Malformed JSON is skipped, not fatal (U14: `org.json`
     * needs Robolectric — a bare-JVM test caller must supply it).
     */
    private fun jsonLdString(doc: Document, key: String): String? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val text = script.data().takeIf { it.isNotBlank() } ?: script.html()
            val root = runCatching { JSONTokener(text).nextValue() }.getOrNull()
            findJsonKey(root, key)?.let { return it }
        }
        return null
    }

    private fun findJsonKey(node: Any?, key: String): String? = when (node) {
        is JSONObject -> {
            val direct = node.optString(key, "").trim()
            if (direct.isNotBlank()) direct
            else node.keys().asSequence().firstNotNullOfOrNull { findJsonKey(node.opt(it), key) }
        }
        is JSONArray -> (0 until node.length()).asSequence()
            .firstNotNullOfOrNull { findJsonKey(node.opt(it), key) }
        else -> null
    }

    private val SITE_SEPARATORS = listOf(" | ", " – ", " — ", " · ", " - ")

    /** `/2026/07/27/` — year, then month, then day, each a fixed two-or-four digit run. */
    private val URL_DATE = Regex("""/(\d{4})/(\d{2})/(\d{2})(?:/|$)""")
}
