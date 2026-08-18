package dev.mkiros.perch.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.PendingEntryStateEntity
import dev.mkiros.perch.data.db.entity.mergedWith
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
     * Home: one chronological stream, newest first (PLAN-4 §0, W03).
     *
     * It used to order by folder first so that section headers fell out of the row order.
     * The reader asked for the opposite — everything mixed together — so the order is now
     * exactly [SAVED]'s and [LIKED]'s, recency and then id to break a tie. The folder
     * still travels on the row, but as a *label* the row prints rather than as a
     * position: which folder an article is in no longer decides where it appears.
     */
    const val LIST_ITEMS = """
        $ROW
        WHERE (:includeRead OR e.isRead = 0) AND (:feedId IS NULL OR e.feedId = :feedId)
          AND (:folderId IS NULL OR f.folderId = :folderId)
          AND (:publishedAfter IS NULL OR e.publishedAt >= :publishedAfter)
        ORDER BY e.publishedAt DESC, e.id DESC
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

    // ---- profile transfer (U14) -----------------------------------------------

    /**
     * Everything a profile export carries about articles: the entries the reader has
     * actually done something to, addressed by `(feedUrl, guid)`.
     *
     * Only rows with a flag set. A profile is state, not an archive, and an export that
     * listed every untouched entry would be a hundred times the size while saying nothing —
     * and, worse, a restore reading it back would have to decide what an all-false row
     * *means*. It means nothing, so it is not written.
     */
    @Query(
        """
        SELECT f.feedUrl AS feedUrl, e.guid AS guid, e.isRead AS isRead, e.readAt AS readAt,
               e.isSaved AS isSaved, e.savedAt AS savedAt, e.isStarred AS isStarred,
               e.starredAt AS starredAt
        FROM entries e JOIN feeds f ON f.id = e.feedId
        WHERE e.isRead = 1 OR e.isSaved = 1 OR e.isStarred = 1
        ORDER BY f.feedUrl, e.guid
        """,
    )
    abstract suspend fun statesToExport(): List<EntryStateRow>

    /** Parks restored state. `REPLACE` is what makes restoring the same file twice a no-op. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPendingState(rows: List<PendingEntryStateEntity>)

    @Query("SELECT * FROM pending_entry_state")
    abstract suspend fun allPendingState(): List<PendingEntryStateEntity>

    @Query("SELECT COUNT(*) FROM pending_entry_state")
    abstract suspend fun countPendingState(): Int

    /** The parked state belonging to one source, joined through the URL it was filed under. */
    @Query(
        """
        SELECT p.* FROM pending_entry_state p
        JOIN feeds f ON f.feedUrl = p.feedUrl
        WHERE f.id = :feedId
        """,
    )
    abstract suspend fun pendingStateFor(feedId: Long): List<PendingEntryStateEntity>

    @Query("DELETE FROM pending_entry_state WHERE feedUrl = :feedUrl AND guid IN (:guids)")
    abstract suspend fun clearPendingState(feedUrl: String, guids: List<String>)

    @Query("SELECT id FROM feeds WHERE feedUrl = :feedUrl")
    abstract suspend fun feedIdForUrl(feedUrl: String): Long?

    /**
     * Applies every parked row whose entry is now here, and returns how many landed.
     *
     * Run at the end of a restore, for the entries that were already on the phone. The
     * other direction — state parked before its entry exists — is handled inside
     * [upsertAll], because that is where entries arrive and a restore cannot be expected to
     * still be running when they do.
     */
    @Transaction
    open suspend fun applyPendingState(): Int {
        var applied = 0
        for ((feedUrl, parked) in allPendingState().groupBy { it.feedUrl }) {
            val feedId = feedIdForUrl(feedUrl) ?: continue
            val consumed = mutableListOf<String>()
            for (row in parked) {
                val entry = findByGuid(feedId, row.guid) ?: continue
                update(entry.mergedWith(row))
                consumed += row.guid
            }
            consumed.chunked(MAX_IDS_PER_STATEMENT).forEach { clearPendingState(feedUrl, it) }
            applied += consumed.size
        }
        return applied
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
     * It is also where a restored profile finally lands (U14). Entries arriving for the
     * first time on a fresh install are exactly the entries whose state was parked in
     * `pending_entry_state` seconds earlier, and this is the only code any of them go
     * through — so a restore followed by a refresh keeps what the restore brought instead
     * of being overwritten by a feed that has never heard of the reader.
     *
     * @return how many entries were genuinely new.
     */
    @Transaction
    open suspend fun upsertAll(entries: List<EntryEntity>): Int {
        var inserted = 0
        // Read once per source rather than once per entry: a feed's whole batch shares one
        // set of parked rows, and a refresh of forty sources would otherwise be forty
        // thousand queries.
        val parked = HashMap<Long, Map<String, PendingEntryStateEntity>>()
        val consumed = HashMap<String, MutableList<String>>()
        for (entry in entries) {
            val byGuid = parked.getOrElse(entry.feedId) {
                pendingStateFor(entry.feedId).associateBy { it.guid }
                    .also { parked[entry.feedId] = it }
            }
            val restored = byGuid[entry.guid]
            val existing = findByGuid(entry.feedId, entry.guid)
            val row = if (existing == null) {
                entry
            } else {
                val keepExtracted = existing.fullTextAt != null &&
                    (entry.contentHtml?.length ?: 0) <= (existing.contentHtml?.length ?: 0)
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
                )
            }
            val merged = if (restored == null) row else row.mergedWith(restored)
            if (existing == null) {
                insert(merged)
                inserted++
            } else {
                update(merged)
            }
            if (restored != null) {
                consumed.getOrPut(restored.feedUrl) { mutableListOf() } += restored.guid
            }
        }
        consumed.forEach { (feedUrl, guids) ->
            guids.chunked(MAX_IDS_PER_STATEMENT).forEach { clearPendingState(feedUrl, it) }
        }
        return inserted
    }

    private companion object {
        /** SQLite's 999-variable ceiling, less headroom for the other bound arguments. */
        const val MAX_IDS_PER_STATEMENT = 900
    }
}
