package dev.mkiros.perch.data.repo

import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.EntryListItem
import dev.mkiros.perch.data.db.entity.EntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Clock

/**
 * Reader state: the part of an entry that belongs to the reader rather than to the feed —
 * read, saved (*Read later*) and liked, three independent flags (PLAN-2 §0). Everything
 * here is either a reactive count or list (the UI must move on its own) or a state flip
 * stamped from an injected [Clock] so tests can assert *when*.
 */
class EntryRepository(
    private val entryDao: EntryDao,
    private val clock: Clock,
) {

    /**
     * The home reading list, newest first, each row already carrying its source's display
     * name (DESIGN.md §5).
     *
     * This is the whole paging story. Room re-emits the list on any write, `LazyColumn`
     * composes only the rows on screen, and a reader with 42 sources has a list measured
     * in hundreds — a paging library here would buy latency, not headroom.
     *
     * @param feedId the drawer's per-source filter; null is every source.
     * @param folderId the drawer's per-folder scope (U06); null is every folder. The two
     *   filters are independent predicates rather than a hierarchy, so a caller that has
     *   picked a source does not also have to work out which folder it is in.
     * @param includeRead Settings' "show read entries"; false is the unread inbox.
     * @param publishedAfter home's time window (U07), inclusive; null is All Time. A
     *   window, unlike the other two filters, is a *reading* decision rather than a
     *   subscription one — it never applies to the To-Read or Liked lists (PLAN-2 §0).
     */
    fun observeEntries(
        feedId: Long? = null,
        folderId: Long? = null,
        includeRead: Boolean = false,
        publishedAfter: Long? = null,
    ): Flow<List<EntryListItem>> =
        entryDao.observeListItems(feedId, folderId, includeRead, publishedAfter)
            .distinctUntilChanged()

    /** The unread inbox — [observeEntries] as home reads it by default. */
    fun observeUnreadEntries(feedId: Long? = null): Flow<List<EntryListItem>> =
        observeEntries(feedId, includeRead = false)

    /** Total unread, for the inbox badge. */
    fun observeTotalUnreadCount(): Flow<Int> =
        entryDao.observeUnreadCount().distinctUntilChanged()

    /**
     * Unread per source, for the drawer badges. A source with nothing unread is absent
     * from the map rather than mapped to 0 — read it as `counts[feedId] ?: 0`.
     */
    fun observeUnreadCountsByFeed(): Flow<Map<Long, Int>> =
        entryDao.observeUnreadCountsByFeed().distinctUntilChanged()

    /** One entry by id, or null if the source was removed or retention collected the row. */
    suspend fun find(entryId: Long): EntryEntity? = entryDao.findById(entryId)

    /** Marking unread forgets when it was read; there is no half-read state to keep. */
    suspend fun setRead(entryId: Long, isRead: Boolean) {
        entryDao.setRead(
            ids = listOf(entryId),
            isRead = isRead,
            readAt = if (isRead) clock.millis() else null,
        )
    }

    suspend fun toggleRead(entryId: Long) {
        val entry = entryDao.findById(entryId) ?: return
        setRead(entryId, isRead = !entry.isRead)
    }

    // ---- read later and liked (U04) --------------------------------------------

    /**
     * The To-Read queue, most recently saved first, exempt from home's time filter
     * (PLAN-2 §0). Read entries stay in it — only the reader takes something out.
     */
    fun observeSaved(): Flow<List<EntryListItem>> =
        entryDao.observeSaved().distinctUntilChanged()

    /** The Liked list, most recently liked first. Same exemption as [observeSaved]. */
    fun observeLiked(): Flow<List<EntryListItem>> =
        entryDao.observeLiked().distinctUntilChanged()

    /**
     * Files an entry under *Read later*, or takes it off the queue.
     *
     * Un-saving nulls `savedAt` for the same reason marking unread nulls `readAt`: the
     * timestamp is the flag's evidence, and a cleared flag that keeps its timestamp is a
     * row that two different queries can disagree about.
     */
    suspend fun setSaved(entryId: Long, isSaved: Boolean) {
        entryDao.setSaved(
            id = entryId,
            isSaved = isSaved,
            savedAt = if (isSaved) clock.millis() else null,
        )
    }

    /** *Liked* — the `isStarred` column, which has had no UI since T12, finally used. */
    suspend fun setLiked(entryId: Long, isLiked: Boolean) {
        entryDao.setStarred(
            id = entryId,
            isStarred = isLiked,
            starredAt = if (isLiked) clock.millis() else null,
        )
    }

    /**
     * How many entries in [feedIds] the reader saved or liked (U09a).
     *
     * Removing a source cascades to its entries, and U04's whole point is that saved and
     * liked entries are the reader's, not the feed's. This is the number the delete
     * confirmation names so the loss is stated before it happens rather than discovered
     * afterwards in an empty To-Read list.
     */
    suspend fun countSavedOrLikedIn(feedIds: Collection<Long>): Int =
        if (feedIds.isEmpty()) 0 else entryDao.countSavedOrLikedIn(feedIds.toList())

    /**
     * Marks everything unread as read, scoped exactly as [observeEntries] is — to one
     * source, to one folder, to one time window, or to none of them.
     *
     * Returns the token [undoMarkAllRead] needs. The token names the exact entries this
     * call flipped, so undo cannot resurrect an entry the user had already read before
     * the batch, nor one they read after it.
     */
    suspend fun markAllRead(
        feedId: Long?,
        folderId: Long? = null,
        publishedAfter: Long? = null,
    ): MarkAllReadUndo {
        val flipped = entryDao.unreadIds(feedId, folderId, publishedAfter)
        entryDao.setRead(flipped, isRead = true, readAt = clock.millis())
        return MarkAllReadUndo(flipped)
    }

    suspend fun undoMarkAllRead(undo: MarkAllReadUndo) {
        entryDao.setRead(undo.entryIds, isRead = false, readAt = null)
    }
}

/**
 * What a single [EntryRepository.markAllRead] flipped. [count] is what the snackbar
 * says; [entryIds] is what undo restores.
 */
data class MarkAllReadUndo(val entryIds: List<Long>) {
    val count: Int get() = entryIds.size
}
