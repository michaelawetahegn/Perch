package dev.mkiros.perch.data.parse

import java.time.Instant

/**
 * One entry of a [ParsedFeed].
 *
 * @param guid identity across refetches; see the fallback chain in SPEC.md §5.
 * @param title plain text, entities decoded, `(untitled)` when the feed gives none.
 * @param link absolute http(s) or null — a relative or `javascript:` href is dropped.
 * @param publishedAt null only when neither the entry nor the feed carries a usable
 *   date; the repository then substitutes `fetchedAt`.
 * @param publishedIsEstimated true when [publishedAt] did not come from the entry itself.
 * @param contentHtml the richest content the feed offered, still unsanitized.
 */
data class ParsedEntry(
    val guid: String,
    val title: String,
    val link: String?,
    val author: String?,
    val publishedAt: Instant?,
    val publishedIsEstimated: Boolean,
    val contentHtml: String?,
    val imageUrl: String?,
)
