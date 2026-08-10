package dev.mkiros.perch.ui.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.mkiros.perch.data.db.EntryListItem
import dev.mkiros.perch.data.repo.EntryRepository
import dev.mkiros.perch.data.repo.FeedRepository
import dev.mkiros.perch.di.AppContainer
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Which reader-owned list a screen is showing (PLAN-2 §0).
 *
 * The two are one screen because they are one shape — the same row, the same actions, the
 * same exemption from the time filter — differing only in the column they read and the
 * sentence their empty state says. Splitting them into two screens would be two copies of
 * everything for the sake of one `WHERE` clause.
 */
enum class Collection {

    /** *Read later*: `isSaved`, newest-saved first. A queue the reader empties. */
    ToRead,

    /** *Liked*: `isStarred`, newest-liked first. Permanent. */
    Liked,
}

/**
 * What a removal from one of these lists undid, while its snackbar is up.
 *
 * Held as state rather than fired as an event for T26's reason: a rotation mid-snackbar
 * must not lose the offer, and the id is the only record of which row that gesture took
 * out. [title] is carried because by the time the snackbar is drawn the row is gone from
 * the list, so there is nothing left to look the name up in.
 */
data class CollectionUndo(val entryId: Long, val title: String)

/**
 * One reader-owned list, and the actions that add to and take from it.
 *
 * Neither list filters on `isRead` and neither takes a time window: reading something you
 * saved is not the same as being done with it, and a to-read list that hides last month's
 * articles is not a to-read list (§0). Both properties are in the DAO's query rather than
 * here, so nothing downstream can reintroduce them.
 */
class CollectionViewModel(
    private val repository: EntryRepository,
    private val feeds: FeedRepository,
    private val clock: Clock,
    val collection: Collection,
) : ViewModel() {

    /**
     * The list, a page at a time (U07a). These two are the likeliest of the three lists to
     * grow without bound — retention exempts saved and liked rows (U04), so nothing ever
     * ages out of them.
     *
     * `cachedIn(viewModelScope)` for home's reason: without it every recomposition of the
     * screen starts the list again at the top.
     */
    val entries: Flow<PagingData<EntryListItem>> =
        when (collection) {
            Collection.ToRead -> repository.pagedSaved()
            Collection.Liked -> repository.pagedLiked()
        }.cachedIn(viewModelScope)

    /**
     * The instant the rows' relative times are measured against, read once per
     * composition. There is no state class left to carry it: since U07a the rows arrive a
     * page at a time and report their own load state, so everything else this screen knew
     * about the list now lives on the list.
     */
    val nowMillis: Long get() = clock.millis()

    /** Drives the pull indicator only, exactly as home's does. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * The pull gesture, which these two lists never had (V03/#6).
     *
     * Always `refreshAll`: there is no scope to narrow to here — a saved article can come
     * from any source — so the reader's "now" means all of them, the same thing it means
     * on an unfiltered Feed. What it changes on *these* lists is the rows themselves: a
     * refresh rewrites the bodies and titles of entries the reader has queued, and it is
     * where a restored profile's parked flags are consumed (U14). Re-entrant pulls are
     * dropped rather than queued, for home's reason.
     */
    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                feeds.refreshAll()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private val _pendingUndo = MutableStateFlow<CollectionUndo?>(null)
    val pendingUndo: StateFlow<CollectionUndo?> = _pendingUndo.asStateFlow()

    /**
     * Puts an entry on the *Read later* queue or takes it off.
     *
     * Taking one off **while looking at To-Read** arms the undo: that gesture makes a row
     * the reader is looking at disappear, and a queue is exactly the kind of list where a
     * mis-aimed tap costs something that cannot be found again. Adding one arms nothing —
     * nothing vanished — and neither does un-saving from anywhere else, where the row was
     * never on screen to begin with.
     */
    fun setSaved(item: EntryListItem, isSaved: Boolean) {
        viewModelScope.launch {
            repository.setSaved(item.id, isSaved)
            if (!isSaved && collection == Collection.ToRead) {
                _pendingUndo.value = CollectionUndo(item.id, item.title)
            }
        }
    }

    /** *Liked*, with the same undo rule and for the same reason. */
    fun setLiked(item: EntryListItem, isLiked: Boolean) {
        viewModelScope.launch {
            repository.setLiked(item.id, isLiked)
            if (!isLiked && collection == Collection.Liked) {
                _pendingUndo.value = CollectionUndo(item.id, item.title)
            }
        }
    }

    fun setRead(entryId: Long, isRead: Boolean) {
        viewModelScope.launch { repository.setRead(entryId, isRead) }
    }

    /**
     * Puts the one entry back, on the list it was taken from.
     *
     * Restoring re-stamps `savedAt`/`starredAt` from the clock rather than restoring the
     * old one, so the row comes back at the top rather than wherever it used to be. That is
     * the honest answer: the reader just touched it, and a restored row that reappears
     * fourteen screens down has not visibly been restored at all.
     */
    fun undo() {
        val undo = _pendingUndo.value ?: return
        _pendingUndo.value = null
        viewModelScope.launch {
            when (collection) {
                Collection.ToRead -> repository.setSaved(undo.entryId, isSaved = true)
                Collection.Liked -> repository.setLiked(undo.entryId, isLiked = true)
            }
        }
    }

    /** The snackbar timed out or was swiped away; the removal stands. */
    fun clearPendingUndo() {
        _pendingUndo.value = null
    }

    companion object {
        fun factory(container: AppContainer, collection: Collection) = viewModelFactory {
            initializer {
                CollectionViewModel(container.entries, container.feeds, container.clock, collection)
            }
        }
    }
}
