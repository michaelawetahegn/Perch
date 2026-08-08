package dev.mkiros.perch.data.db

/**
 * One entry's reader state, addressed the way a profile file addresses it (U14) — by the
 * feed's URL and the entry's own guid, never by a row id that means nothing on another
 * install.
 */
data class EntryStateRow(
    val feedUrl: String,
    val guid: String,
    val isRead: Boolean,
    val readAt: Long?,
    val isSaved: Boolean,
    val savedAt: Long?,
    val isStarred: Boolean,
    val starredAt: Long?,
)
