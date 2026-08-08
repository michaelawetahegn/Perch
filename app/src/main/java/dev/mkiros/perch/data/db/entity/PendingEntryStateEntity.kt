package dev.mkiros.perch.data.db.entity

import androidx.room.Entity

/**
 * Reader state waiting for the article it belongs to (U14).
 *
 * A profile restore arrives before the entries it describes do — the sources it just added
 * have not been fetched yet — so the state it carries has nowhere to live for the first few
 * seconds of a new install. Dropping it would make a restore followed by the refresh it
 * obviously implies lose everything it just restored, which is the single failure this
 * table exists to prevent.
 *
 * Identity is `(feedUrl, guid)` rather than an entry id, for the same reason the profile
 * itself is keyed that way: row ids are local to one install and mean nothing on the other
 * side of a wipe, while the pair is what the feed itself says the article is.
 *
 * Rows are consumed — applied and then deleted — by [dev.mkiros.perch.data.db.EntryDao],
 * the one place entries arrive.
 */
@Entity(tableName = "pending_entry_state", primaryKeys = ["feedUrl", "guid"])
data class PendingEntryStateEntity(
    val feedUrl: String,
    val guid: String,
    val isRead: Boolean,
    val readAt: Long?,
    val isSaved: Boolean,
    val savedAt: Long?,
    val isStarred: Boolean,
    val starredAt: Long?,
)

/**
 * This entry with [pending]'s state merged in.
 *
 * A merge and never an overwrite: a restore may only ever turn a flag **on**. The profile
 * carries the entries the reader had done something to, so "absent from the file" means
 * "the file has nothing to say", not "mark it unread" — and a reader who reads an article
 * between two restores must not have that undone by the second one. Timestamps follow the
 * flag they belong to, and an existing one wins, because it is the one that ordered the
 * list the reader is currently looking at.
 */
fun EntryEntity.mergedWith(pending: PendingEntryStateEntity): EntryEntity = copy(
    isRead = isRead || pending.isRead,
    readAt = readAt ?: pending.readAt,
    isSaved = isSaved || pending.isSaved,
    savedAt = savedAt ?: pending.savedAt,
    isStarred = isStarred || pending.isStarred,
    starredAt = starredAt ?: pending.starredAt,
)
