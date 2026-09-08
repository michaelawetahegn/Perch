package dev.mkiros.perch.support

import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity

/**
 * The one way a test builds a [FeedEntity] or an [EntryEntity].
 *
 * Both entities have a column for every field the app persists, so spelling one out is
 * twenty lines of `null` around the two that the test is actually about. Every test file
 * that seeds a database had grown its own copy of those twenty lines; the copies agreed
 * on all of it, which is why the divergence was invisible and why a new column meant
 * editing thirty files.
 *
 * The defaults are the ones those copies already agreed on. They are deliberately *plausible*
 * rather than empty — a feed has a site, an entry has a link — so that a test which does not
 * mention a field still exercises the shape the app sees in the wild. Pass a field only when
 * the test is about it: that is then the one thing the call site says, and it reads as the
 * fixture's point rather than as noise.
 *
 * This lives in `src/test` because AGP compiles it into `testDebugUnitTest` alongside
 * `src/testDebug` (PLAN-10 §0.4), so one file serves both source sets.
 *
 * These build rows; they do not insert them. A `feedId` is whatever the database handed
 * back from an earlier insert, and the seeded `Uncategorized` and saved-links ids belong to
 * `PerchDatabase.inMemory` (U03) — the builders never guess one.
 */
fun testFeed(
    title: String = "Example",
    feedUrl: String = "https://example.com/${title.hashCode()}/feed.xml",
    siteUrl: String? = "https://example.com",
    customTitle: String? = null,
    faviconUrl: String? = null,
    etag: String? = null,
    lastModified: String? = null,
    lastFetchedAt: Long? = null,
    lastSuccessAt: Long? = null,
    lastError: String? = null,
    consecutiveFailures: Int = 0,
    addedAt: Long = 0L,
    sortIndex: Int = 0,
    folderId: Long = FolderEntity.UNCATEGORIZED_ID,
    isSynthetic: Boolean = false,
): FeedEntity = FeedEntity(
    feedUrl = feedUrl,
    siteUrl = siteUrl,
    title = title,
    customTitle = customTitle,
    faviconUrl = faviconUrl,
    etag = etag,
    lastModified = lastModified,
    lastFetchedAt = lastFetchedAt,
    lastSuccessAt = lastSuccessAt,
    lastError = lastError,
    consecutiveFailures = consecutiveFailures,
    addedAt = addedAt,
    sortIndex = sortIndex,
    folderId = folderId,
    isSynthetic = isSynthetic,
)

/**
 * An entry of [feedId], per [testFeed]'s doctrine.
 *
 * Three defaults are derived rather than fixed, because that is the relationship the copies
 * kept restating by hand: [guid] is unique per [title], [contentHtml] is the [summary] in a
 * paragraph (so a body-less fixture stays body-less), and [fetchedAt] is [publishedAt] — a
 * test that only cares about *when* says it once.
 */
fun testEntry(
    feedId: Long,
    title: String = "Untitled",
    guid: String = "guid-${title.hashCode()}",
    link: String? = "https://example.com/post",
    author: String? = null,
    publishedAt: Long = 0L,
    publishedIsEstimated: Boolean = false,
    summary: String? = null,
    contentHtml: String? = summary?.let { "<p>$it</p>" },
    imageUrl: String? = null,
    readAt: Long? = null,
    isRead: Boolean = readAt != null,
    savedAt: Long? = null,
    isSaved: Boolean = savedAt != null,
    starredAt: Long? = null,
    isStarred: Boolean = starredAt != null,
    bodyIsExcerpt: Boolean = false,
    fullTextAt: Long? = null,
    fetchedAt: Long = publishedAt,
): EntryEntity = EntryEntity(
    feedId = feedId,
    guid = guid,
    title = title,
    link = link,
    author = author,
    publishedAt = publishedAt,
    publishedIsEstimated = publishedIsEstimated,
    summary = summary,
    contentHtml = contentHtml,
    imageUrl = imageUrl,
    isRead = isRead,
    readAt = readAt,
    isSaved = isSaved,
    savedAt = savedAt,
    isStarred = isStarred,
    starredAt = starredAt,
    bodyIsExcerpt = bodyIsExcerpt,
    fullTextAt = fullTextAt,
    fetchedAt = fetchedAt,
)
