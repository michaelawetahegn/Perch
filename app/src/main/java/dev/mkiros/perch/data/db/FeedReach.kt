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
 */
data class FeedReach(val entryCount: Int, val oldestPublishedAt: Long?)
