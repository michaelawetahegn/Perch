package dev.mkiros.perch.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mkiros.perch.data.db.EntryListItem
import dev.mkiros.perch.data.repo.EntryRepository
import dev.mkiros.perch.data.repo.FeedRepository
import dev.mkiros.perch.di.AppContainer
import java.time.Clock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * What home is showing (DESIGN.md §7's four states, minus the ones nothing can produce
 * yet — the error and offline banners arrive with T26).
 *
 * @param isLoading true only before the first database emission. A refresh never returns
 *   here; it shows in the pull indicator, never as a full-screen replace.
 * @param hasSources whether any source is subscribed at all. An empty list means
 *   "add your first source" with zero sources and "you're all caught up" with any.
 * @param nowMillis the instant the row timestamps are relative to, fixed per emission so
 *   the list cannot render two different "now"s.
 */
data class HomeUiState(
    val isLoading: Boolean = true,
    val entries: List<EntryListItem> = emptyList(),
    val hasSources: Boolean = false,
    val nowMillis: Long = 0L,
)

/**
 * Home's state. The list and the inbox badge; the source drawer (T22) and refresh (T26)
 * attach here as this screen grows.
 */
class HomeViewModel(
    entries: EntryRepository,
    feeds: FeedRepository,
    clock: Clock,
) : ViewModel() {

    /** Total unread, for the drawer's "All unread" row and the bar's subtitle. */
    val totalUnread: StateFlow<Int> = entries.observeTotalUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    val uiState: StateFlow<HomeUiState> =
        combine(entries.observeUnreadEntries(), feeds.observeSourceCount()) { unread, sources ->
            HomeUiState(
                isLoading = false,
                entries = unread,
                hasSources = sources > 0,
                nowMillis = clock.millis(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    companion object {
        /** Five seconds outlives a rotation, so the query is not torn down and rebuilt. */
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                HomeViewModel(
                    entries = container.entries,
                    feeds = container.feeds,
                    clock = container.clock,
                )
            }
        }
    }
}
