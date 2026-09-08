package dev.mkiros.perch.data.db

/**
 * How far one source's stored history reaches (PLAN-7 §0.4) — the oldest entry Perch
 * holds for it, and how many it holds in total.
 *
 * Derived straight from `entries`, never a column: before a backfill ever runs this is
 * exactly what the feed's own XML carried, because a fresh source's entries are nothing
 * else; after one it is the true reach of the archive Perch now holds, no separate
 * bookkeeping required. That is also why it needs no migration.
 *
 * @param oldestPublishedAt null only when [entryCount] is 0 — a source with nothing
 *   stored yet.
 * @param oldestKnownPublishedAt the same, over the entries whose date the article
 *   published for itself. PLAN-9 §0.7 (#25): `MIN(publishedAt)` aggregates across rows
 *   that are individually guessed or not, so `EntryEntity.publishedIsEstimated` cannot
 *   survive it — a source whose oldest stored row carries a fetch-stamped guess would
 *   otherwise claim to reach back to the day Perch first saw it. Null when every date
 *   this source holds is a guess (and, as above, when nothing is stored at all); a
 *   reader-facing sentence falls back to [oldestPublishedAt] marked as a guess.
 */
data class FeedReach(
    val entryCount: Int,
    val oldestPublishedAt: Long?,
    val oldestKnownPublishedAt: Long?,
)
