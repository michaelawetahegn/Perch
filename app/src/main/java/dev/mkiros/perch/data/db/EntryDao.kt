package dev.mkiros.perch.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.mkiros.perch.data.db.entity.EntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Articles. The interesting method is [upsertAll]: a refetch re-presents every entry the
 * feed still lists, so writing them must be idempotent *and* must not trample what the
 * reader has since done to them.
 */
@Dao
abstract class EntryDao {

    /** Newest first; a tie breaks toward the more recently inserted row. */
    @Query("SELECT * FROM entries ORDER BY publishedAt DESC, id DESC")
    abstract fun observeAll(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE feedId = :feedId ORDER BY publishedAt DESC, id DESC")
    abstract fun observeByFeed(feedId: Long): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE id = :id")
    abstract suspend fun findById(id: Long): EntryEntity?

    @Query("SELECT * FROM entries WHERE feedId = :feedId AND guid = :guid")
    abstract suspend fun findByGuid(feedId: Long, guid: String): EntryEntity?

    @Query("SELECT COUNT(*) FROM entries")
    abstract suspend fun countAll(): Int

    // ---- read state -----------------------------------------------------------

    /** The unified inbox badge. */
    @Query("SELECT COUNT(*) FROM entries WHERE isRead = 0")
    abstract fun observeUnreadCount(): Flow<Int>

    /**
     * The per-source drawer badges.
     *
     * `GROUP BY` has no row to emit for a source whose entries are all read, so a fully
     * read feed is **absent** from the map, not mapped to 0. Read it as `counts[id] ?: 0`.
     */
    @Query(
        """
        SELECT feedId, COUNT(*) AS unreadCount FROM entries
        WHERE isRead = 0 GROUP BY feedId
        """,
    )
    abstract fun observeUnreadCountsByFeed():
        Flow<Map<@MapColumn("feedId") Long, @MapColumn("unreadCount") Int>>

    /** Unread ids, optionally scoped to one source. `null` means every source. */
    @Query(
        """
        SELECT id FROM entries
        WHERE isRead = 0 AND (:feedId IS NULL OR feedId = :feedId)
        ORDER BY id
        """,
    )
    abstract suspend fun unreadIds(feedId: Long?): List<Long>

    @Query("UPDATE entries SET isRead = :isRead, readAt = :readAt WHERE id IN (:ids)")
    abstract suspend fun setReadForIds(ids: List<Long>, isRead: Boolean, readAt: Long?)

    /**
     * Flips read state for a batch of ids.
     *
     * SQLite binds every id in an `IN (…)` clause as its own host variable and stops at
     * 999, so a mark-all-read over a busy inbox has to be chunked. Doing it here rather
     * than at the call site means no caller can forget.
     */
    @Transaction
    open suspend fun setRead(ids: List<Long>, isRead: Boolean, readAt: Long?) {
        ids.chunked(MAX_IDS_PER_STATEMENT).forEach { setReadForIds(it, isRead, readAt) }
    }

    @Insert
    abstract suspend fun insert(entry: EntryEntity): Long

    @Update
    abstract suspend fun update(entry: EntryEntity)

    /**
     * Writes a parsed batch, matching on `(feedId, guid)`.
     *
     * Room's `@Upsert` is not usable here: it recovers from the conflict by updating on
     * the *primary key*, which for a freshly parsed entry is still 0, so the row would
     * be silently dropped. Matching on the identity the feed actually gives us and
     * carrying the old row's id forward is what makes a refetch a no-op.
     *
     * Read state ([EntryEntity.isRead], [EntryEntity.readAt], [EntryEntity.isStarred])
     * belongs to the reader, never to the feed, so it is preserved across the update.
     *
     * @return how many entries were genuinely new.
     */
    @Transaction
    open suspend fun upsertAll(entries: List<EntryEntity>): Int {
        var inserted = 0
        for (entry in entries) {
            val existing = findByGuid(entry.feedId, entry.guid)
            if (existing == null) {
                insert(entry)
                inserted++
            } else {
                update(
                    entry.copy(
                        id = existing.id,
                        isRead = existing.isRead,
                        readAt = existing.readAt,
                        isStarred = existing.isStarred,
                    ),
                )
            }
        }
        return inserted
    }

    private companion object {
        /** SQLite's 999-variable ceiling, less headroom for the other bound arguments. */
        const val MAX_IDS_PER_STATEMENT = 900
    }
}
