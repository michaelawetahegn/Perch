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
 * @param folderId the folder this row's source is filed in. W03 stopped the list ordering
 *   by it, so it no longer decides where a row goes; it stays on the row because the row
 *   prints the folder as a category label (W04) and the join costs nothing extra.
 * @param folderName that label's text, joined for the same reason [sourceTitle] is.
 * @param publishedIsEstimated true when [publishedAt] is Perch's guess — a feed item
 *   with no date, or a pasted link whose page published none, stamped with the moment of
 *   the fetch — rather than the article's own. The row prints it with a `~` (S06, #25).
 *   Deliberately **without a default**: a row that silently claims a guessed date is
 *   known is the bug this fixes, so every construction site has to say which it means.
 * @param isSaved on the *Read later* queue (U04). Carried on the row because U09's
 *   long-press sheet has to offer *Save for later* or *Remove from Read later* — a sheet
 *   that has to go and ask the database which one it means opens showing the wrong verb.
 * @param isStarred *Liked*, carried for the same reason.
 * @param link the article's own address, and the only thing worth sharing from a row
 *   (U09) — a reader forwarding an article means the article, not Perch's copy of it.
 *   Null for a feed that ships items with no link at all.
 */
data class EntryListItem(
    val id: Long,
    val feedId: Long,
    val title: String,
    val summary: String?,
    val imageUrl: String?,
    val publishedAt: Long,
    val publishedIsEstimated: Boolean,
    val isRead: Boolean,
    val sourceTitle: String,
    val folderId: Long,
    val folderName: String,
    val isSaved: Boolean = false,
    val isStarred: Boolean = false,
    val link: String? = null,
)
