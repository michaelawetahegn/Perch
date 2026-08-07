package dev.mkiros.perch.data.parse

import java.time.Instant

/**
 * A feed document as the parsers understand it — no database identity, no fetch
 * bookkeeping, and no sanitization yet (that is [HtmlSanitizer]'s job downstream).
 *
 * @param title never blank; a feed that names itself only by its site URL is titled
 *   after that host rather than left empty.
 * @param updatedAt the feed-level `lastBuildDate`/`pubDate`/`updated`, which doubles as
 *   the fallback publication date for entries that carry none (SPEC.md §5).
 */
data class ParsedFeed(
    val title: String,
    val siteUrl: String?,
    val updatedAt: Instant?,
    val entries: List<ParsedEntry>,
)
