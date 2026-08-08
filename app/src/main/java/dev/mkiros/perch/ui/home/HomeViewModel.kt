package dev.mkiros.perch.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mkiros.perch.data.db.EntryListItem
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity
import dev.mkiros.perch.data.net.ConnectivityMonitor
import dev.mkiros.perch.data.repo.EntryRepository
import dev.mkiros.perch.data.repo.FeedRepository
import dev.mkiros.perch.data.repo.FolderDeleteUndo
import dev.mkiros.perch.data.repo.FolderRepository
import dev.mkiros.perch.data.repo.MarkAllReadUndo
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.di.AppContainer
import java.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
 * @param folderId the section it is nested under (U06). Never null — a source with no
 *   folder chosen is in Uncategorized, which is a real folder (PLAN-2 §0).
 */
data class SourceUiItem(
    val id: Long,
    val publishedTitle: String,
    val customTitle: String?,
    val unreadCount: Int,
    val errorMessage: String?,
    val folderId: Long = FolderEntity.UNCATEGORIZED_ID,
) {
    /** What the drawer and the app bar actually show. */
    val title: String get() = customTitle?.takeIf { it.isNotBlank() } ?: publishedTitle

    /** The drawer's `⚠` affordance. */
    val hasError: Boolean get() = errorMessage != null
}

/**
 * One folder section of the drawer (PLAN-2 §0, U06).
 *
 * @param unreadCount the folder's own `GROUP BY` count, not a sum of [sources]' counts —
 *   see [dev.mkiros.perch.data.db.FolderDao.observeUnreadCountsByFolder]. Zero is a real
 *   value: a fully-read folder stays in the drawer showing 0.
 * @param isBuiltIn Uncategorized, which the reader may neither rename nor delete, so its
 *   header carries no overflow at all rather than an overflow whose items do nothing.
 */
data class FolderUiItem(
    val id: Long,
    val name: String,
    val unreadCount: Int,
    val sources: List<SourceUiItem>,
) {
    val isBuiltIn: Boolean get() = id == FolderEntity.UNCATEGORIZED_ID
}

/**
 * What the drawer has narrowed the reading list to (PLAN-2 §0).
 *
 * Folder and source are siblings rather than a hierarchy: they are two independent SQL
 * predicates, so nothing here has to resolve a folder into the feeds it holds — a move
 * would invalidate that list the moment it happened.
 */
sealed interface HomeScope {

    /** The unified inbox. */
    data object All : HomeScope

    data class Folder(val id: Long) : HomeScope

    data class Source(val id: Long) : HomeScope

    val feedId: Long? get() = (this as? Source)?.id
    val folderId: Long? get() = (this as? Folder)?.id
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
 * @param folders the drawer's sections, in folder order with Uncategorized last. Every
 *   source in [sources] appears in exactly one of them.
 * @param scope the drawer's filter; [HomeScope.All] is the unified inbox.
 * @param selectedTitle the display name to put in the app bar, or null for "Unread".
 *   It is derived from the same emission [entries] came from, so the bar can never name
 *   one source while the list shows another.
 * @param banner the one strip above the list, or null. It never replaces the list: a
 *   failing source keeps its cached entries on screen (§7).
 * @param timeFilter how far back [entries] reaches (U07). It is a filter, not a section:
 *   it decides which rows survive, and the folder each survivor sits under is a separate
 *   question the row answers itself.
 */
data class HomeUiState(
    val isLoading: Boolean = true,
    val entries: List<EntryListItem> = emptyList(),
    val hasSources: Boolean = false,
    val nowMillis: Long = 0L,
    val sources: List<SourceUiItem> = emptyList(),
    val folders: List<FolderUiItem> = emptyList(),
    val scope: HomeScope = HomeScope.All,
    val selectedTitle: String? = null,
    val banner: HomeBanner? = null,
    val timeFilter: TimeFilter = TimeFilter.Default,
) {
    val selectedFeedId: Long? get() = scope.feedId
    val selectedFolderId: Long? get() = scope.folderId

    /**
     * Whether the list draws folder headers (PLAN-2 §0).
     *
     * Scoping the drawer to one folder or one source collapses them away — there is only
     * one section, and a header over the whole list says nothing the app bar has not
     * already said. So does a reader who has never made a second folder.
     *
     * Deliberately answered from the scope and the folder *list*, never from the entries:
     * "how many distinct folders are in this list" is a question only the whole list can
     * answer, and U07a is about to stop having the whole list.
     */
    val showSections: Boolean get() = scope is HomeScope.All && folders.size > 1

    /** The empty bucket's way out (§0): the next window out, or null at All Time. */
    val widerFilter: TimeFilter? get() = timeFilter.wider
}

/**
 * A source delete waiting on its confirmation (U09a).
 *
 * @param savedOrLikedCount how many of the entries about to be cascaded away the reader
 *   saved or liked. Zero is a normal answer and the dialog simply omits the line.
 */
data class SourceDeletePrompt(
    val feedIds: Set<Long>,
    val savedOrLikedCount: Int,
) {
    val sourceCount: Int get() = feedIds.size
}

/**
 * Home's state: the reading list, the source drawer, the filter that ties them together,
 * and the refresh/error/offline surfacing around all three (T26).
 */
class HomeViewModel(
    private val entries: EntryRepository,
    private val feeds: FeedRepository,
    private val folders: FolderRepository,
    private val clock: Clock,
    connectivity: ConnectivityMonitor = ConnectivityMonitor.AlwaysOnline,
    private val settings: SettingsStore = SettingsStore.inMemory(),
) : ViewModel() {

    /** Total unread, for the drawer's "All unread" row and the bar's subtitle. */
    val totalUnread: StateFlow<Int> = entries.observeTotalUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private val scope = MutableStateFlow<HomeScope>(HomeScope.All)

    /**
     * Which folder sections are shut. Collapsed rather than expanded ids so that a folder
     * created while the drawer is open comes up open, and so the default — everything
     * visible — is the empty set.
     */
    private val _collapsedFolders = MutableStateFlow<Set<Long>>(emptySet())
    val collapsedFolders: StateFlow<Set<Long>> = _collapsedFolders.asStateFlow()

    /** Settings' "show read entries" (T27). Flipping it re-queries; it never filters here. */
    private val showReadEntries: Flow<Boolean> =
        settings.settings.map { it.showReadEntries }.distinctUntilChanged()

    /**
     * The time range's selection (U07, U08a), read from DataStore rather than held here so
     * it survives process death — and so the widen affordance and the dropdown are the same
     * one piece of state, whichever of them the reader used.
     */
    private val timeFilter: Flow<TimeFilter> =
        settings.settings.map { it.timeFilter }.distinctUntilChanged()

    /** What one collection of the list query produced, and what produced it. */
    private data class ListData(
        val scope: HomeScope,
        val timeFilter: TimeFilter,
        val items: List<EntryListItem>,
    )

    /**
     * The list, re-queried per scope, per window, and per "show read entries". All three
     * are carried *out* of the `flatMapLatest` alongside the rows they produced, so a
     * selection or a range change can never leave the bar and the dropdown describing a
     * list that is still the previous query's.
     *
     * `since` is resolved here, once per query, rather than inside [TimeFilter]: the
     * boundary is a moment in time, and re-deriving it per row would let a list straddle
     * midnight and disagree with itself.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val filteredEntries: Flow<ListData> =
        combine(scope, showReadEntries, timeFilter, ::Triple)
            .flatMapLatest { (scope, showRead, filter) ->
                entries.observeEntries(
                    feedId = scope.feedId,
                    folderId = scope.folderId,
                    includeRead = showRead,
                    publishedAfter = filter.since(clock),
                ).map { ListData(scope, filter, it) }
            }

    /** Everything the drawer draws, gathered before the top-level `combine` runs out of arity. */
    private data class DrawerData(
        val sources: List<FeedEntity>,
        val sourceCounts: Map<Long, Int>,
        val folders: List<FolderEntity>,
        val folderCounts: Map<Long, Int>,
    )

    private val drawer: Flow<DrawerData> = combine(
        feeds.observeSources(),
        entries.observeUnreadCountsByFeed(),
        folders.observeFolders(),
        folders.observeUnreadCountsByFolder(),
        ::DrawerData,
    )

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

    /**
     * What the last batch folder delete took, while its snackbar is up (U09a). Held for
     * the same reason [_pendingUndo] is: the folders and their memberships are the only
     * record of what to restore, and a rotation mid-snackbar must not lose it.
     */
    private val _pendingFolderUndo = MutableStateFlow<FolderDeleteUndo?>(null)
    val pendingFolderUndo: StateFlow<FolderDeleteUndo?> = _pendingFolderUndo.asStateFlow()

    /** The armed source delete, or null. Non-null is what puts the dialog on screen. */
    private val _sourceDeletePrompt = MutableStateFlow<SourceDeletePrompt?>(null)
    val sourceDeletePrompt: StateFlow<SourceDeletePrompt?> = _sourceDeletePrompt.asStateFlow()

    /** The reader dismissed the "everything is failing" banner; cleared by the next refresh. */
    private val globalErrorDismissed = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        filteredEntries,
        drawer,
        connectivity.observeOnline(),
        globalErrorDismissed,
    ) { list, drawer, online, dismissed ->
        val scope = list.scope
        // A fully-read source is absent from the count map rather than mapped to 0.
        val items = drawer.sources.map { feed ->
            SourceUiItem(
                id = feed.id,
                publishedTitle = feed.title,
                customTitle = feed.customTitle,
                unreadCount = drawer.sourceCounts[feed.id] ?: 0,
                errorMessage = feed.lastError,
                folderId = feed.folderId,
            )
        }
        val sections = drawer.folders.map { folder ->
            FolderUiItem(
                id = folder.id,
                name = folder.name,
                unreadCount = drawer.folderCounts[folder.id] ?: 0,
                sources = items.filter { it.folderId == folder.id },
            )
        }
        // Removing the selected source (T24), or deleting the selected folder, drops the
        // filter rather than stranding the bar on a name nothing can produce entries for.
        val selected = items.firstOrNull { it.id == scope.feedId }
        val selectedFolder = sections.firstOrNull { it.id == scope.folderId }
        val resolved = when {
            selected != null -> HomeScope.Source(selected.id)
            selectedFolder != null -> HomeScope.Folder(selectedFolder.id)
            else -> HomeScope.All
        }
        HomeUiState(
            isLoading = false,
            entries = list.items,
            hasSources = items.isNotEmpty(),
            nowMillis = clock.millis(),
            sources = items,
            folders = sections,
            scope = resolved,
            selectedTitle = selected?.title ?: selectedFolder?.name,
            banner = bannerFor(items, selected, online, dismissed),
            timeFilter = list.timeFilter,
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
        scope.value = if (feedId == null) HomeScope.All else HomeScope.Source(feedId)
    }

    /** Filters the list to one folder's sources (U06). */
    fun selectFolder(folderId: Long) {
        scope.value = HomeScope.Folder(folderId)
    }

    /**
     * Narrows or widens home's window (U07). Written straight to DataStore rather than to
     * a local flow, so the dropdown, the widen affordance and the next launch all read the
     * one value.
     */
    fun selectTimeFilter(filter: TimeFilter) {
        viewModelScope.launch { settings.setTimeFilter(filter) }
    }

    /** The empty bucket's affordance: one step out, or nothing at All Time. */
    fun widenTimeFilter() {
        viewModelScope.launch {
            settings.current().timeFilter.wider?.let { settings.setTimeFilter(it) }
        }
    }

    /** Shows or hides one folder's sources in the drawer. Presentation only. */
    fun toggleFolderExpanded(folderId: Long) {
        _collapsedFolders.update { collapsed ->
            if (folderId in collapsed) collapsed - folderId else collapsed + folderId
        }
    }

    // ---- folders (U06) ----------------------------------------------------------

    /**
     * Creates a folder, or finds the one already called [name] — [FolderRepository] makes
     * that decision, case-insensitively, so two spellings of one folder cannot appear in
     * the drawer. [then] receives the id either way, which is what lets the move dialog's
     * "New folder" create and file in one gesture.
     */
    fun createFolder(name: String, then: (Long) -> Unit = {}) {
        if (name.isBlank()) return
        viewModelScope.launch { then(folders.createFolder(name)) }
    }

    fun renameFolder(folderId: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { folders.renameFolder(folderId, name) }
    }

    /** Deletes a folder; its sources move to Uncategorized rather than going with it. */
    fun deleteFolder(folderId: Long) {
        viewModelScope.launch { folders.deleteFolder(folderId) }
    }

    // ---- multi-select delete (U09a) ---------------------------------------------

    /**
     * Deletes a whole batch of folders and arms the undo snackbar.
     *
     * No dialog, deliberately: §0 makes a folder a grouping and never an owner, so this
     * costs the reader nothing they cannot get back with one tap. Confirming it would
     * teach them to dismiss the confirmation that *does* matter — the source one.
     */
    fun deleteFolders(folderIds: Set<Long>) {
        if (folderIds.isEmpty()) return
        viewModelScope.launch {
            val undo = folders.deleteFolders(folderIds)
            if (undo.folderCount > 0) _pendingFolderUndo.value = undo
        }
    }

    /** Puts back the folders that batch took, and every source's membership with them. */
    fun undoDeleteFolders() {
        val undo = _pendingFolderUndo.value ?: return
        _pendingFolderUndo.value = null
        viewModelScope.launch { folders.undoDeleteFolders(undo) }
    }

    /** The snackbar timed out or was swiped away; the batch stands. */
    fun clearFolderUndo() {
        _pendingFolderUndo.value = null
    }

    /**
     * Asks what a source batch would cost, and arms the confirmation with the answer.
     *
     * The count is read here rather than in the dialog because it is a database question,
     * and a dialog that opened first and filled its own number in a frame later would show
     * "0 saved or liked articles" for exactly as long as it takes to read it.
     */
    fun promptRemoveSources(feedIds: Set<Long>) {
        if (feedIds.isEmpty()) return
        viewModelScope.launch {
            _sourceDeletePrompt.value =
                SourceDeletePrompt(feedIds, entries.countSavedOrLikedIn(feedIds))
        }
    }

    /** Unsubscribes the armed batch. Entries go with the sources; there is no undo. */
    fun confirmRemoveSources() {
        val prompt = _sourceDeletePrompt.value ?: return
        _sourceDeletePrompt.value = null
        viewModelScope.launch { feeds.removeAll(prompt.feedIds) }
    }

    fun cancelRemoveSources() {
        _sourceDeletePrompt.value = null
    }

    fun moveSource(feedId: Long, folderId: Long) {
        viewModelScope.launch { folders.moveSource(feedId, folderId) }
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
        val scope = scope.value
        viewModelScope.launch {
            try {
                when (scope) {
                    is HomeScope.All -> feeds.refreshAll()
                    is HomeScope.Folder -> feeds.refreshFolder(scope.id)
                    is HomeScope.Source -> feeds.refresh(scope.id)
                }
            } finally {
                globalErrorDismissed.value = false
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Marks everything in the current scope read and arms the undo snackbar.
     *
     * "The current scope" includes U07's window: the reader is looking at Today, so Today
     * is what gets read. Flipping a year of unseen articles because the range happened to
     * be narrow is the one mistake here that undo would not obviously invite them to fix.
     *
     * A no-op batch arms nothing: offering to undo zero entries is a snackbar that does
     * nothing whichever button the reader presses.
     */
    fun markAllRead() {
        val scope = scope.value
        viewModelScope.launch {
            // Read from the store rather than from [uiState], which is `WhileSubscribed`
            // and therefore reports its *initial* value to a caller arriving while
            // nothing is collecting — the one moment when getting this wrong would read
            // a window the reader never chose.
            val since = settings.current().timeFilter.since(clock)
            val undo = entries.markAllRead(
                feedId = scope.feedId,
                folderId = scope.folderId,
                publishedAfter = since,
            )
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

    // ---- one row's reader state (U09) -------------------------------------------

    /**
     * The three flags the long-press sheet toggles (PLAN-2 §0).
     *
     * Nothing here touches [uiState]: each is a single-column write, and the list is a
     * Room `Flow` that re-emits on its own the moment the write lands. Optimistically
     * editing the state as well would give the row two sources of truth that disagree for
     * one frame — and marking read *removes* the row from the unread inbox, which is a
     * change only the query can decide.
     */
    fun setSaved(entryId: Long, isSaved: Boolean) {
        viewModelScope.launch { entries.setSaved(entryId, isSaved) }
    }

    fun setLiked(entryId: Long, isLiked: Boolean) {
        viewModelScope.launch { entries.setLiked(entryId, isLiked) }
    }

    /** §0's explicit **Mark unread**, which also nulls `readAt` — see [EntryRepository.setRead]. */
    fun setRead(entryId: Long, isRead: Boolean) {
        viewModelScope.launch { entries.setRead(entryId, isRead) }
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

    companion object {
        /** Five seconds outlives a rotation, so the query is not torn down and rebuilt. */
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                HomeViewModel(
                    entries = container.entries,
                    feeds = container.feeds,
                    folders = container.folders,
                    clock = container.clock,
                    connectivity = container.connectivity,
                    settings = container.settings,
                )
            }
        }
    }
}
