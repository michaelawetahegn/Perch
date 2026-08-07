package dev.mkiros.perch.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A subscribed source, per SPEC.md §4.
 *
 * @param feedUrl the resolved feed address — post-discovery, post-redirect — and the
 *   identity of the source, so it is uniquely indexed.
 * @param title the title the feed gives itself; [customTitle] is the user's rename and
 *   never overwrites it. Display name is `customTitle ?: title`.
 * @param etag conditional-GET validators handed back to the server on the next fetch.
 * @param lastError null means healthy; non-null is what the drawer's `⚠` renders from.
 */
@Entity(tableName = "feeds", indices = [Index(value = ["feedUrl"], unique = true)])
data class FeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedUrl: String,
    val siteUrl: String?,
    val title: String,
    val customTitle: String?,
    val faviconUrl: String?,
    val etag: String?,
    val lastModified: String?,
    val lastFetchedAt: Long?,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val consecutiveFailures: Int = 0,
    val addedAt: Long,
    val sortIndex: Int = 0,
)
