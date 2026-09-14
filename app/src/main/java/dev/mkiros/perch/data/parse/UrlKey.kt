package dev.mkiros.perch.data.parse

import java.net.URI

/**
 * The *matching* form of a URL (PLAN-12 §0.2, #69): what the backfill compares a sitemap
 * candidate against the stored guids and links with, so that `https://www.example.com/post/`,
 * `https://example.com/post?utm_source=rss` and `https://example.com/post#top` are one post.
 *
 * Lowercase scheme and host, a leading `www.` dropped, the fragment dropped, `utm_*` /
 * `fbclid` / `gclid` query parameters dropped (and the `?` with the last of them), a default
 * port dropped, one trailing `/` stripped. Nothing else — the path and any remaining query are
 * kept byte for byte, and anything that is not a hierarchical URL keys to itself.
 *
 * Used for matching only: `entries.guid` and `entries.link` are stored exactly as the feed
 * or page gave them, and nothing here ever rewrites a stored value.
 */
fun urlKey(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    val scheme = uri.scheme?.lowercase() ?: return url
    val host = uri.host?.lowercase()?.removePrefix("www.") ?: return url
    val port = uri.port.takeUnless { it == -1 || it == DEFAULT_PORTS[scheme] }
    val query = uri.rawQuery
        ?.split('&')
        ?.filterNot { param -> TRACKING_PARAMETER.matches(param.substringBefore('=')) }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString("&")
    // A `/` is trailing only when it ends the whole URL — `example.com/?p=1` keeps its slash.
    val path = uri.rawPath.orEmpty().let { if (query == null) it.removeSuffix("/") else it }
    return buildString {
        append(scheme).append("://").append(host)
        if (port != null) append(':').append(port)
        append(path)
        if (query != null) append('?').append(query)
    }
}

private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)
private val TRACKING_PARAMETER = Regex("(?i)utm_[a-z0-9_]*|fbclid|gclid")
