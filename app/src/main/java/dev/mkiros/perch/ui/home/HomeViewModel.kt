package dev.mkiros.perch.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mkiros.perch.data.db.EntryListItem
import dev.mkiros.perch.data.net.ConnectivityMonitor
import dev.mkiros.perch.data.repo.EntryRepository
import dev.mkiros.perch.data.repo.FeedRepository
import dev.mkiros.perch.data.repo.MarkAllReadUndo
import dev.mkiros.perch.di.AppContainer
import java.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * @param errorMessage why the source's last refresh failed, already phrased for a reader
 *   by [FeedRepository]. The drawer shows only `⚠`; the banner above the list shows this.
 */
data class SourceUiItem(
    val id: Long,
    val publishedTitle: String,
    val customTitle: String?,
    val unreadCount: Int,
    val errorMessage: String?,
) {
    /** What the drawer and the app bar actually show. */
    val title: String get() = customTitle?.takeIf { it.isNotBlank() } ?: publishedTitle

    /** The drawer's `⚠` affordance. */
    val hasError: Boolean get() = errorMessage != null
}

/**
 * The one slim strip that may sit above the list (DESIGN.md §7).
 *
 * There is at most one, and the order in [HomeViewModel.uiState] is the order of
 * explanatory power: with no network every source is failing for the same reason, so
 * saying so once beats forty-two identical per-source complaints.
 */
sealed interface HomeBanner {

    /** No network. Not dismissible and not retryable — the list below it still reads. */
    data object Offline : HomeBanner

    /** The source being filtered on is failing; [message] is its `lastError`. */
    data class SourceError(val feedId: Long, val message: String) : HomeBanner

    /** Every source failed its last refresh. Dismissible, per §7. */
    data object AllSourcesFailing : HomeBanner
}

/**
 * What home is showing (DESIGN.md §7's four states).
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
 * @param banner the one strip above the list, or null. It never replaces the list: a
 *   failing source keeps its cached entries on screen (§7).
 */
data class HomeUiState(
    val isLoading: Boolean = true,
    val entries: List<EntryListItem> = emptyList(),
    val hasSources: Boolean = false,
    val nowMillis: Long = 0L,
    val sources: List<SourceUiItem> = emptyList(),
    val selectedFeedId: Long? = null,
    val selectedTitle: String? = null,
    val banner: HomeBanner? = null,
)

/**
 * Home's state: the reading list, the source drawer, the filter that ties them together,
 * and the refresh/error/offline surfacing around all three (T26).
 */
class HomeViewModel(
    private val entries: EntryRepository,
    private val feeds: FeedRepository,
    clock: Clock,
    connectivity: ConnectivityMonitor = ConnectivityMonitor.AlwaysOnline,
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

    /** Drives the pull indicator only — a refresh never replaces what is already readable. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * What the last mark-all-read flipped, while its snackbar is up. Held as state rather
     * than sent as an event so that a rotation mid-snackbar cannot lose the undo token —
     * the ids are the only record of which entries that one call touched.
     */
    private val _pendingUndo = MutableStateFlow<MarkAllReadUndo?>(null)
    val pendingUndo: StateFlow<MarkAllReadUndo?> = _pendingUndo.asStateFlow()

    /** The reader dismissed the "everything is failing" banner; cleared by the next refresh. */
    private val globalErrorDismissed = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        filteredEntries,
        feeds.observeSources(),
        entries.observeUnreadCountsByFeed(),
        connectivity.observeOnline(),
        globalErrorDismissed,
    ) { (feedId, unread), sources, counts, online, dismissed ->
        // A fully-read source is absent from the count map rather than mapped to 0.
        val items = sources.map { feed ->
            SourceUiItem(
                id = feed.id,
                publishedTitle = feed.title,
                customTitle = feed.customTitle,
                unreadCount = counts[feed.id] ?: 0,
                errorMessage = feed.lastError,
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
            banner = bannerFor(items, selected, online, dismissed),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    /**
     * At most one banner, most-explanatory first.
     *
     * Offline outranks everything because it is *why* the sources are failing, and the
     * per-source message outranks the global one because the reader is looking at that
     * source. With no sources at all there is nothing to be failing, so the empty state
     * gets the screen to itself.
     */
    private fun bannerFor(
        sources: List<SourceUiItem>,
        selected: SourceUiItem?,
        online: Boolean,
        dismissed: Boolean,
    ): HomeBanner? = when {
        !online -> HomeBanner.Offline
        selected?.errorMessage != null -> HomeBanner.SourceError(selected.id, selected.errorMessage)
        selected != null -> null
        sources.isNotEmpty() && sources.all { it.hasError } && !dismissed ->
            HomeBanner.AllSourcesFailing
        else -> null
    }

    /** Filters the list to one source, or back to the unified inbox with null. */
    fun selectSource(feedId: Long?) {
        selectedFeedId.value = feedId
    }

    /**
     * Polls, in the scope the reader is looking at (SPEC.md §"manual pull-to-refresh
     * refreshes all feeds in the current scope"), from either the pull gesture, the
     * overflow item, or a banner's Retry.
     *
     * Deliberately `refreshAll`/`refresh(id)` rather than the worker's `refreshDue`: a
     * pull is the reader saying "now", and §7's five-failures-then-6h floor must not
     * silently swallow the one gesture that exists to work around it.
     *
     * Re-entrant pulls are dropped rather than queued. The indicator is already up, so a
     * second gesture has no way to say anything the first one is not already saying, and
     * letting it through would fan the whole subscription list out twice.
     */
    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        val feedId = selectedFeedId.value
        viewModelScope.launch {
            try {
                if (feedId == null) feeds.refreshAll() else feeds.refresh(feedId)
            } finally {
                globalErrorDismissed.value = false
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Marks everything in the current scope read and arms the undo snackbar.
     *
     * A no-op batch arms nothing: offering to undo zero entries is a snackbar that does
     * nothing whichever button the reader presses.
     */
    fun markAllRead() {
        val feedId = selectedFeedId.value
        viewModelScope.launch {
            val undo = entries.markAllRead(feedId)
            if (undo.count > 0) _pendingUndo.value = undo
        }
    }

    /**
     * Puts back exactly the entries the armed batch flipped — not "everything unread
     * again", which would resurrect entries the reader had read days ago.
     */
    fun undoMarkAllRead() {
        val undo = _pendingUndo.value ?: return
        _pendingUndo.value = null
        viewModelScope.launch { entries.undoMarkAllRead(undo) }
    }

    /** The snackbar timed out or was swiped away; the batch stands. */
    fun clearPendingUndo() {
        _pendingUndo.value = null
    }

    /** Hides the "every source is failing" banner until the next refresh (DESIGN.md §7). */
    fun dismissBanner() {
        globalErrorDismissed.value = true
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
                    connectivity = container.connectivity,
                )
            }
        }
    }
}
