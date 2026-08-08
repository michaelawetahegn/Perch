package dev.mkiros.perch.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.mkiros.perch.data.db.entity.EntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * The three list queries, written once (U07a).
 *
 * Each exists twice over — as a `Flow<List<…>>` and as a `PagingSource` — because Room
 * generates a different implementation for each return type from the same SQL. Holding the
 * text in one constant is what stops the two copies drifting apart, which would be the
 * quiet kind of bug: the paged list and the flow list would answer the same question
 * differently and only one of them is on screen.
 */
internal object EntryQueries {

    /**
     * The row shape every list draws (DESIGN.md §5), joined to its source and folder so a
     * row never has to look either of them up.
     */
    private const val ROW = """
        SELECT e.id AS id, e.feedId AS feedId, e.title AS title, e.summary AS summary,
               e.imageUrl AS imageUrl, e.publishedAt AS publishedAt, e.isRead AS isRead,
               COALESCE(NULLIF(TRIM(f.customTitle), ''), f.title) AS sourceTitle,
               fo.id AS folderId, fo.name AS folderName,
               e.isSaved AS isSaved, e.isStarred AS isStarred, e.link AS link
        FROM entries e
        JOIN feeds f ON f.id = e.feedId
        JOIN folders fo ON fo.id = f.folderId
    """

    /**
     * Home, ordered by folder first and recency second so §0's sections fall out of the
     * row order itself: the section a row belongs to is a property of the row, and a
     * header is due wherever the previous row's folder differs. Nothing has to hold the
     * whole list to work that out, which is what keeps the page boundaries honest.
     */
    const val LIST_ITEMS = """
        $ROW
        WHERE (:includeRead OR e.isRead = 0) AND (:feedId IS NULL OR e.feedId = :feedId)
          AND (:folderId IS NULL OR f.folderId = :folderId)
          AND (:publishedAfter IS NULL OR e.publishedAt >= :publishedAfter)
        ORDER BY (fo.id = 1) ASC, fo.sortIndex ASC, fo.name COLLATE NOCASE ASC,
                 e.publishedAt DESC, e.id DESC
    """

    const val SAVED = """
        $ROW
        WHERE e.isSaved = 1
        ORDER BY e.savedAt DESC, e.id DESC
    """

    const val LIKED = """
        $ROW
        WHERE e.isStarred = 1
        ORDER BY e.starredAt DESC, e.id DESC
    """
}

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

    /**
     * The home list (DESIGN.md §5): newest first, joined to its source so a row never has
     * to look its feed up.
     *
     * Reading an entry drops it out of this flow, which is exactly what an unread inbox
     * should do — until Settings' "show read entries" (T27) says otherwise, which is
     * [includeRead]. That is a predicate in the query rather than a filter in the UI for
     * the same reason [feedId] is: a read entry the list is not showing should not be
     * loaded, counted, or held in a `LazyColumn`'s item list at all.
     *
     * @param feedId the drawer's per-source filter; null is the unified inbox. Filtering
     *   in SQL rather than in the UI keeps the "which rows exist" question in one place,
     *   so a filtered list re-emits on a write the same way the unified one does.
     * @param folderId the drawer's per-folder scope (U06); null is every folder. It is a
     *   second predicate rather than a resolved list of feed ids because membership is a
     *   column on `feeds` that a move can change under us — the join already knows.
     * @param publishedAfter U07's time window, inclusive; null is All Time. In SQL rather
     *   than in the view model for the same reason as the other two: a filter the UI
     *   applied afterwards would page the rows it then threw away.
     *
     * Ordering, and why the section headers depend on it, are in [EntryQueries.LIST_ITEMS].
     */
    @Query(EntryQueries.LIST_ITEMS)
    abstract fun observeListItems(
        feedId: Long?,
        folderId: Long?,
        includeRead: Boolean,
        publishedAfter: Long?,
    ): Flow<List<EntryListItem>>

    /**
     * The same list, a page at a time (U07a) — what home actually reads.
     *
     * A `Flow<List<…>>` materialises every matching row and re-emits all of them whenever
     * any one entry's flags change, so marking a single article read re-does the work of
     * the whole screen. Room drives this one off the same invalidation signal, but the
     * reload is bounded by what is loaded rather than by what matches.
     */
    @Query(EntryQueries.LIST_ITEMS)
    abstract fun pagedListItems(
        feedId: Long?,
        folderId: Long?,
        includeRead: Boolean,
        publishedAfter: Long?,
    ): PagingSource<Int, EntryListItem>

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

    /**
     * Unread ids, optionally scoped to one source or one folder. `null` means every one.
     *
     * The scope has to match [observeListItems]' exactly — including U07's time window:
     * mark-all-read is "everything the reader is looking at", so a drawer scoped to a
     * folder that flipped the whole inbox would be marking articles the reader cannot see
     * as read, and a range set to Today that flipped a year of them would be worse.
     */
    @Query(
        """
        SELECT e.id FROM entries e JOIN feeds f ON f.id = e.feedId
        WHERE e.isRead = 0 AND (:feedId IS NULL OR e.feedId = :feedId)
          AND (:folderId IS NULL OR f.folderId = :folderId)
          AND (:publishedAfter IS NULL OR e.publishedAt >= :publishedAfter)
        ORDER BY e.id
        """,
    )
    abstract suspend fun unreadIds(
        feedId: Long?,
        folderId: Long?,
        publishedAfter: Long? = null,
    ): List<Long>

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

    // ---- read later and liked (U04) -------------------------------------------

    /**
     * The To-Read destination: the same row shape as home, ordered by when the reader
     * filed it rather than by when it was published.
     *
     * Read entries stay in it. Saving is a decision the reader makes and unmakes; reading
     * something you saved is not the same as being done with it, so nothing here filters
     * on `isRead`. It is also exempt from the time filter by construction — a to-read list
     * that hides last month's articles is not a to-read list (PLAN-2 §0).
     */
    @Query(EntryQueries.SAVED)
    abstract fun observeSaved(): Flow<List<EntryListItem>>

    /** To-Read, a page at a time (U07a) — what the screen reads. */
    @Query(EntryQueries.SAVED)
    abstract fun pagedSaved(): PagingSource<Int, EntryListItem>

    /** The Liked destination. Same rules as [observeSaved], ordered by when it was liked. */
    @Query(EntryQueries.LIKED)
    abstract fun observeLiked(): Flow<List<EntryListItem>>

    /** Liked, a page at a time (U07a). */
    @Query(EntryQueries.LIKED)
    abstract fun pagedLiked(): PagingSource<Int, EntryListItem>

    @Query("UPDATE entries SET isSaved = :isSaved, savedAt = :savedAt WHERE id = :id")
    abstract suspend fun setSaved(id: Long, isSaved: Boolean, savedAt: Long?)

    @Query("UPDATE entries SET isStarred = :isStarred, starredAt = :starredAt WHERE id = :id")
    abstract suspend fun setStarred(id: Long, isStarred: Boolean, starredAt: Long?)

    /**
     * How much of the reader's own curation sits inside [feedIds] — what U09a's delete
     * confirmation puts a number to before it cascades.
     *
     * `OR`, counted once per row: an entry that is both saved and liked is one article
     * about to be lost, and a dialog that called it two would overstate the damage in the
     * one place a reader is deciding whether to trust the number.
     */
    @Query(
        """
        SELECT COUNT(*) FROM entries
        WHERE feedId IN (:feedIds) AND (isSaved = 1 OR isStarred = 1)
        """,
    )
    abstract suspend fun countSavedOrLikedIn(feedIds: List<Long>): Int

    // ---- retention ------------------------------------------------------------

    /**
     * SPEC.md §7's 30-day sweep: drop read articles that have aged out, but never one the
     * feed is still listing.
     *
     * "Still listing" is expressed as [fetchedBefore] rather than as a list of surviving
     * guids: a refresh stamps every entry in the body with the current `fetchedAt`, so
     * anything the body still carries is newer than the moment that refresh began, and a
     * feed with a thousand entries needs no `IN (…)` clause to say so.
     *
     * Saved and liked entries are exempt (U04). A read-later queue that a background sweep
     * empties after thirty days is not a queue, and *Liked* is documented as permanent —
     * retention exists to bound storage, not to overrule the reader.
     *
     * @return how many rows were pruned.
     */
    @Query(
        """
        DELETE FROM entries
        WHERE feedId = :feedId AND isRead = 1 AND isSaved = 0 AND isStarred = 0
          AND publishedAt < :publishedBefore AND fetchedAt < :fetchedBefore
        """,
    )
    abstract suspend fun deleteReadOlderThan(
        feedId: Long,
        publishedBefore: Long,
        fetchedBefore: Long,
    ): Int

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
     * Reader state belongs to the reader, never to the feed, so **every** flag and its
     * timestamp is carried over from the existing row: read, saved (*Read later*) and
     * starred (*Liked*). A parsed entry arrives with all six at their defaults on every
     * single fetch, so anything not listed here is silently erased once a day by the
     * refresh worker — which is what would empty a to-read list nobody touched.
     *
     * An article Perch went and fetched (U10) is preserved on the same grounds and with
     * one extra condition: the feed wins if it has *more* to say than the extraction did.
     * Without this a recovered article would survive exactly until the next refresh, and a
     * reader who opened it twice would see it collapse back to a stub the second time.
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
                val keepExtracted = existing.fullTextAt != null &&
                    (entry.contentHtml?.length ?: 0) <= (existing.contentHtml?.length ?: 0)
                update(
                    entry.copy(
                        id = existing.id,
                        isRead = existing.isRead,
                        readAt = existing.readAt,
                        isSaved = existing.isSaved,
                        savedAt = existing.savedAt,
                        isStarred = existing.isStarred,
                        starredAt = existing.starredAt,
                        contentHtml = if (keepExtracted) existing.contentHtml else entry.contentHtml,
                        fullTextAt = if (keepExtracted) existing.fullTextAt else null,
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
