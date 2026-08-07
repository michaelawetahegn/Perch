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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One row of the source drawer (DESIGN.md §5).
 *
 * @param publishedTitle the title the feed publishes for itself — what the next refresh
 *   overwrites, and what a cleared rename falls back to.
 * @param customTitle the reader's rename, or null. Both halves are carried separately
 *   because T24's rename dialog needs to tell them apart: it edits the rename and offers
 *   the published title as what emptying the field restores.
 * @param unreadCount zero is a real value here: a fully-read source stays in the drawer
 *   showing 0, it does not vanish.
 * @param hasError the source's last refresh failed, which the drawer renders as `⚠`.
 *   The message itself belongs to T26's banner; this is only the affordance.
 */
data class SourceUiItem(
    val id: Long,
    val publishedTitle: String,
    val customTitle: String?,
    val unreadCount: Int,
    val hasError: Boolean,
) {
    /** What the drawer and the app bar actually show. */
    val title: String get() = customTitle?.takeIf { it.isNotBlank() } ?: publishedTitle
}

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
 * @param selectedFeedId the drawer's filter; null is the unified inbox.
 * @param selectedTitle the display name to put in the app bar, or null for "Unread".
 *   It is derived from the same emission [entries] came from, so the bar can never name
 *   one source while the list shows another.
 */
data class HomeUiState(
    val isLoading: Boolean = true,
    val entries: List<EntryListItem> = emptyList(),
    val hasSources: Boolean = false,
    val nowMillis: Long = 0L,
    val sources: List<SourceUiItem> = emptyList(),
    val selectedFeedId: Long? = null,
    val selectedTitle: String? = null,
)

/**
 * Home's state: the reading list, the source drawer, and the filter that ties them
 * together. Refresh and the error banners are T26 and attach here.
 */
class HomeViewModel(
    entries: EntryRepository,
    private val feeds: FeedRepository,
    clock: Clock,
) : ViewModel() {

    /** Total unread, for the drawer's "All unread" row and the bar's subtitle. */
    val totalUnread: StateFlow<Int> = entries.observeTotalUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private val selectedFeedId = MutableStateFlow<Long?>(null)

    /**
     * The list, re-queried per selection. The selected id is carried *out* of the
     * `flatMapLatest` alongside the rows it produced, so a selection change can never
     * leave the app bar showing the new source over the old source's entries.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val filteredEntries: Flow<Pair<Long?, List<EntryListItem>>> =
        selectedFeedId.flatMapLatest { feedId ->
            entries.observeUnreadEntries(feedId).map { feedId to it }
        }

    val uiState: StateFlow<HomeUiState> = combine(
        filteredEntries,
        feeds.observeSources(),
        entries.observeUnreadCountsByFeed(),
    ) { (feedId, unread), sources, counts ->
        // A fully-read source is absent from the count map rather than mapped to 0.
        val items = sources.map { feed ->
            SourceUiItem(
                id = feed.id,
                publishedTitle = feed.title,
                customTitle = feed.customTitle,
                unreadCount = counts[feed.id] ?: 0,
                hasError = feed.lastError != null,
            )
        }
        // Removing the selected source (T24) drops the filter rather than stranding the
        // bar on a name nothing can produce entries for any more.
        val selected = items.firstOrNull { it.id == feedId }
        HomeUiState(
            isLoading = false,
            entries = unread,
            hasSources = items.isNotEmpty(),
            nowMillis = clock.millis(),
            sources = items,
            selectedFeedId = selected?.id,
            selectedTitle = selected?.title,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    /** Filters the list to one source, or back to the unified inbox with null. */
    fun selectSource(feedId: Long?) {
        selectedFeedId.value = feedId
    }

    /**
     * Renames a source for display (T24). A blank [name] clears the rename rather than
     * blanking the label — the repository stores null and the drawer falls back to the
     * feed's own title.
     */
    fun renameSource(feedId: Long, name: String) {
        viewModelScope.launch { feeds.rename(feedId, name) }
    }

    /**
     * Unsubscribes, taking the source's entries with it (T24). The filter is not cleared
     * here: [uiState] resolves the selection against the sources it just read, so the row
     * vanishing is already what drops the filter, whoever removed it.
     */
    fun removeSource(feedId: Long) {
        viewModelScope.launch { feeds.remove(feedId) }
    }

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
