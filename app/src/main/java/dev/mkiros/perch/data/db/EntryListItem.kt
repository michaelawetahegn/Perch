package dev.mkiros.perch.data.db

/**
 * What a home-screen row needs, and nothing else (DESIGN.md §5).
 *
 * A row shows the source name, so the naive shape would be an entry plus a lookup per
 * row; this is the join instead, done once in SQL. It also leaves `contentHtml` in the
 * database — a list of two hundred rows has no use for two hundred article bodies.
 *
 * @param sourceTitle the feed's display name: its `customTitle` if the reader renamed
 *   it, otherwise the title the feed publishes for itself.
 */
data class EntryListItem(
    val id: Long,
    val feedId: Long,
    val title: String,
    val summary: String?,
    val imageUrl: String?,
    val publishedAt: Long,
    val isRead: Boolean,
    val sourceTitle: String,
)
