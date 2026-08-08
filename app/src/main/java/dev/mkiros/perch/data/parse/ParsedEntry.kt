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
 * @param bodyIsExcerpt true when the richest thing on offer was a `<description>` /
 *   `<summary>` — a blurb standing in for an article (U10, PLAN-2 §0). False when the feed
 *   shipped real content, and false when it shipped nothing at all: an absent body is not
 *   an excerpt, it is an absence, and the two want different words in the UI.
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
    val bodyIsExcerpt: Boolean = false,
)
