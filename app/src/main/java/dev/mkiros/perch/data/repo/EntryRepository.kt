package dev.mkiros.perch.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.EntryListItem
import dev.mkiros.perch.data.db.FeedReach
import dev.mkiros.perch.data.db.FtsQuery
import dev.mkiros.perch.data.db.entity.EntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import java.time.Clock

/**
 * How the three lists page (U07a).
 *
 * One config for all of them, because they are one list shape and a reader who scrolls
 * To-Read should not meet a different loading rhythm than one who scrolls the Feed.
 *
 * The numbers exist so the reader never learns that paging is happening at all. A page of
 * 30 is roughly four screens of 96dp rows, and [PagingConfig.prefetchDistance] of 10 asks
 * for the next one while a third of a screen still stands between the reader and the end
 * of what is loaded — at any plausible flick speed the rows are there before the eye is.
 *
 * [PagingConfig.enablePlaceholders] is off deliberately: with placeholders the list is full
 * of nulls, and every question a row is asked — what it is filed under, when it was
 * published, whether it has been read — has no answer at a page edge. Off, every index below
 * `itemCount` is a row that has been loaded, so the questions are always answerable.
 *
 * `initialLoadSize` is one page rather than Paging's default of three. Three is tuned for
 * a cold list on a fast scroll; this list is re-collected every time a flag changes, and
 * the first load is exactly the cost this task exists to stop paying repeatedly.
 */
object PerchPaging {

    const val PAGE_SIZE = 30
    const val PREFETCH_DISTANCE = 10

    val config = PagingConfig(
        pageSize = PAGE_SIZE,
        prefetchDistance = PREFETCH_DISTANCE,
        enablePlaceholders = false,
        initialLoadSize = PAGE_SIZE,
    )
}

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
     * The home reading list as a whole, newest first, each row already carrying its
     * source's display name (DESIGN.md §5).
     *
     * Home reads [pagedEntries] instead; this stays for the callers that genuinely want
     * every matching row at once — the tests that assert what the query selects, and the
     * live acceptance run that counts a corpus. **Nothing on a screen should call it**: it
     * materialises every match and re-emits all of them when one entry's flags change.
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

    /**
     * The home reading list, a page at a time (U07a) — what the Feed actually collects.
     *
     * Same filters, same order and same row as [observeEntries]; the difference is that
     * only what has been scrolled to is loaded, and a write invalidates that much rather
     * than the whole match. The `Flow` is cold and builds a new `Pager` per collection, so
     * a caller that survives configuration changes must `cachedIn` a scope that does too —
     * see [dev.mkiros.perch.ui.home.HomeViewModel].
     */
    fun pagedEntries(
        feedId: Long? = null,
        folderId: Long? = null,
        includeRead: Boolean = false,
        publishedAfter: Long? = null,
    ): Flow<PagingData<EntryListItem>> =
        Pager(PerchPaging.config) {
            entryDao.pagedListItems(feedId, folderId, includeRead, publishedAfter)
        }.flow

    // ---- search (S09, #28) ------------------------------------------------------

    /**
     * Every stored article matching what the reader typed, newest first.
     *
     * [raw] is the reader's own text, straight from the field, because this is the seam
     * where it stops being text and becomes a query: [FtsQuery] sanitises it once, here,
     * so no caller can forget and no screen has to know that FTS has a syntax at all. A
     * question that reduces to nothing — empty, whitespace, punctuation, emoji — is not a
     * search that found nothing, it is the absence of a search, and both answer with an
     * empty list without touching the database.
     *
     * The scope is the surface the search was opened from (PLAN-9 §0.8) and nothing else:
     * search ignores the time window and the read filter deliberately, which
     * [dev.mkiros.perch.data.db.EntryQueries.SEARCH] explains.
     *
     * As with [observeEntries], **nothing on a screen should call this one** — it
     * materialises every match. Screens read [pagedSearch].
     *
     * @param feedId the source the search was opened from; null is every source.
     * @param folderId the folder it was opened from; null is every folder.
     * @param savedOnly true when it was opened from To-Read; [likedOnly] for Liked.
     */
    fun searchEntries(
        raw: String,
        feedId: Long? = null,
        folderId: Long? = null,
        savedOnly: Boolean = false,
        likedOnly: Boolean = false,
    ): Flow<List<EntryListItem>> {
        val query = FtsQuery.from(raw) ?: return flowOf(emptyList())
        return entryDao.searchListItems(query, feedId, folderId, savedOnly, likedOnly)
            .distinctUntilChanged()
    }

    /** The same search, a page at a time (U07a) — what the search screen collects. */
    fun pagedSearch(
        raw: String,
        feedId: Long? = null,
        folderId: Long? = null,
        savedOnly: Boolean = false,
        likedOnly: Boolean = false,
    ): Flow<PagingData<EntryListItem>> {
        val query = FtsQuery.from(raw) ?: return flowOf(PagingData.empty())
        return Pager(PerchPaging.config) {
            entryDao.pagedSearch(query, feedId, folderId, savedOnly, likedOnly)
        }.flow
    }

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
     * The two reader-owned lists, paged (U07a).
     *
     * They page for the same reason the Feed does and are the more likely of the three to
     * need it: nothing ever ages out of them. Retention exempts saved and liked rows
     * (U04), so a queue three years old is a queue with every article still in it.
     */
    fun pagedSaved(): Flow<PagingData<EntryListItem>> =
        Pager(PerchPaging.config) { entryDao.pagedSaved() }.flow

    fun pagedLiked(): Flow<PagingData<EntryListItem>> =
        Pager(PerchPaging.config) { entryDao.pagedLiked() }.flow

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

    /** PLAN-7 §0.4: how far [feedId]'s stored history reaches — see [FeedReach]. */
    suspend fun reach(feedId: Long): FeedReach = entryDao.reach(feedId)

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
