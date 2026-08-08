package dev.mkiros.perch.ui.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mkiros.perch.data.db.EntryListItem
import dev.mkiros.perch.data.repo.EntryRepository
import dev.mkiros.perch.di.AppContainer
import java.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
 * @param isLoading true only before the first database emission, exactly as home's is.
 * @param nowMillis the instant the rows' relative times are against, fixed per emission.
 */
data class CollectionUiState(
    val isLoading: Boolean = true,
    val entries: List<EntryListItem> = emptyList(),
    val nowMillis: Long = 0L,
)

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
    private val entries: EntryRepository,
    private val clock: Clock,
    val collection: Collection,
) : ViewModel() {

    val uiState: StateFlow<CollectionUiState> =
        when (collection) {
            Collection.ToRead -> entries.observeSaved()
            Collection.Liked -> entries.observeLiked()
        }
            .map { CollectionUiState(isLoading = false, entries = it, nowMillis = clock.millis()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CollectionUiState())

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
            entries.setSaved(item.id, isSaved)
            if (!isSaved && collection == Collection.ToRead) {
                _pendingUndo.value = CollectionUndo(item.id, item.title)
            }
        }
    }

    /** *Liked*, with the same undo rule and for the same reason. */
    fun setLiked(item: EntryListItem, isLiked: Boolean) {
        viewModelScope.launch {
            entries.setLiked(item.id, isLiked)
            if (!isLiked && collection == Collection.Liked) {
                _pendingUndo.value = CollectionUndo(item.id, item.title)
            }
        }
    }

    fun setRead(entryId: Long, isRead: Boolean) {
        viewModelScope.launch { entries.setRead(entryId, isRead) }
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
                Collection.ToRead -> entries.setSaved(undo.entryId, isSaved = true)
                Collection.Liked -> entries.setLiked(undo.entryId, isLiked = true)
            }
        }
    }

    /** The snackbar timed out or was swiped away; the removal stands. */
    fun clearPendingUndo() {
        _pendingUndo.value = null
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(container: AppContainer, collection: Collection) = viewModelFactory {
            initializer { CollectionViewModel(container.entries, container.clock, collection) }
        }
    }
}
