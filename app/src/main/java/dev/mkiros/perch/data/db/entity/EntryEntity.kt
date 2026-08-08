package dev.mkiros.perch.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One article, per SPEC.md §4.
 *
 * Identity is `(feedId, guid)`, not the row id: a refetch of the same feed must land on
 * the same row rather than duplicating it, which is why that pair is uniquely indexed.
 * Two different feeds may legitimately use the same guid, so the index is scoped.
 *
 * @param publishedAt epoch millis; never null — the repository substitutes `fetchedAt`
 *   when neither the entry nor the feed carried a usable date, and records that fact in
 *   [publishedIsEstimated].
 * @param summary plain-text snippet (≤300 chars) for the list row.
 * @param contentHtml sanitized HTML for the article screen.
 * @param isSaved *Read later* (PLAN-2 §0): a queue the reader fills and empties by hand.
 *   Reading a saved entry does not clear it.
 * @param isStarred *Liked*. Carried since v1 as a column with no UI; U04 gives it one.
 *
 * The three reader-owned flags — [isRead], [isSaved], [isStarred] — are independent, and
 * each pairs with a nullable timestamp that is set when the flag goes on and nulled when
 * it goes off. Nowhere in the app is one derived from another.
 */
@Entity(
    tableName = "entries",
    foreignKeys = [
        ForeignKey(
            entity = FeedEntity::class,
            parentColumns = ["id"],
            childColumns = ["feedId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["feedId", "guid"], unique = true),
        Index(value = ["publishedAt"]),
        Index(value = ["isRead"]),
        Index(value = ["isSaved"]),
        Index(value = ["isStarred"]),
    ],
)
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedId: Long,
    val guid: String,
    val title: String,
    val link: String?,
    val author: String?,
    val publishedAt: Long,
    val publishedIsEstimated: Boolean,
    val summary: String?,
    val contentHtml: String?,
    val imageUrl: String?,
    val isRead: Boolean = false,
    val readAt: Long?,
    val isSaved: Boolean = false,
    val savedAt: Long? = null,
    val isStarred: Boolean = false,
    val starredAt: Long? = null,
    val fetchedAt: Long,
)
