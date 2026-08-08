package dev.mkiros.perch.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
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
 * @param folderId the section this source appears under (PLAN-2 §0), never null. The
 *   foreign key deliberately does **not** cascade: SQLite's `ON DELETE SET DEFAULT` is
 *   not expressible through Room, and cascading here would delete subscriptions when a
 *   user tidied their folders. `FolderDao.deleteAndReassign` moves them instead, in one
 *   transaction, and this constraint is what makes forgetting to do so a loud failure
 *   rather than a silent orphan.
 */
@Entity(
    tableName = "feeds",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
        ),
    ],
    indices = [Index(value = ["feedUrl"], unique = true), Index(value = ["folderId"])],
)
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
    // Literal because an annotation argument must be a compile-time constant; it is
    // FolderEntity.UNCATEGORIZED_ID, and migration 1 → 2 defaults the column to the same.
    @ColumnInfo(defaultValue = "1")
    val folderId: Long = FolderEntity.UNCATEGORIZED_ID,
)
