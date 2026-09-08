package dev.mkiros.perch.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.mkiros.perch.data.db.EntryListItem
import dev.mkiros.perch.data.repo.EntryRepository
import dev.mkiros.perch.di.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import java.time.Clock

/**
 * The results of one question (S10, #28).
 *
 * Thinner than the other three list view-models on purpose: search owns no filter, no
 * window, no drawer and no undo, and it deliberately owns no *state* either — the question
 * and its scope are hoisted into the shell (§0.8), and this is told what they are. Making
 * it a second owner of the question is the mistake V08 named for the scoped list: two
 * writers with no single owner is how the field and the list come to disagree.
 *
 * A [ViewModel] rather than a `remember` in the composable for `cachedIn`'s sake, which is
 * what stops every keystroke's recomposition from restarting the list at the top.
 */
class SearchViewModel(
    private val repository: EntryRepository,
    private val clock: Clock,
) : ViewModel() {

    private val asked = MutableStateFlow(SearchState.everything())

    /**
     * The matches, a page at a time (U07a).
     *
     * [asked] is a `StateFlow`, so it already drops a state equal to the one before it —
     * which is what keeps a recomposition that re-pushes the same question from restarting
     * the list. `flatMapLatest` is the whole of the debounce there is: a keystroke supersedes the
     * query before it, and the superseded one is cancelled rather than raced. The index is
     * local and answers in a frame, so a timed debounce would only add a delay the reader
     * can feel and a virtual clock the tests would have to advance.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val results: Flow<PagingData<EntryListItem>> = asked
        .flatMapLatest { state ->
            repository.pagedSearch(
                raw = state.query,
                feedId = state.feedId,
                folderId = state.folderId,
                savedOnly = state.savedOnly,
                likedOnly = state.likedOnly,
            )
        }
        .cachedIn(viewModelScope)

    /** The shell's hoisted state, pushed down. The one writer. */
    fun ask(state: SearchState) {
        asked.value = state
    }

    /** What the rows' relative times are measured against, as on the other three lists. */
    val nowMillis: Long get() = clock.millis()

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SearchViewModel(container.entries, container.clock) }
        }
    }
}
