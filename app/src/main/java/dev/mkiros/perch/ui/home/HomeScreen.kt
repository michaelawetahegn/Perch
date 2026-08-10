package dev.mkiros.perch.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.mkiros.perch.R
import dev.mkiros.perch.data.db.EntryListItem
import dev.mkiros.perch.ui.source.AddSourceSheet
import dev.mkiros.perch.ui.source.AddSourceViewModel
import dev.mkiros.perch.ui.brand.PerchMark
import dev.mkiros.perch.ui.brand.PerchWordmark
import dev.mkiros.perch.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * The reading list and the source drawer around it (DESIGN.md §5).
 *
 * The list is the unified unread inbox: every source, newest first, one row shape for all
 * forty-two of them. Its four states (§7) are all here — skeleton on first load, one of
 * two empty states depending on *why* it is empty, and the list itself. Selecting a source
 * in the drawer narrows the same list and retitles the bar; long-pressing one offers
 * rename and remove. Pulling refreshes the scope on screen, and the two things that can
 * be wrong — no network, a failing source — say so in a slim strip *above* the list
 * rather than in place of it (§7). No FAB, deliberately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    addSourceViewModel: AddSourceViewModel,
    onOpenEntry: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    // Both hoisted so the shell can reason about them (U09): the back chain has to know
    // whether the drawer is open, and Feed's scroll offset has to outlive a tab switch —
    // which it cannot do if it is remembered inside the screen the switch tears down.
    // They default to their own state so a test can still compose this screen alone.
    drawerState: DrawerState = rememberDrawerState(DrawerValue.Closed),
    listState: LazyListState = rememberLazyListState(),
    // Hoisted for the same reason (U09a): the back chain's first rung is "leave selection",
    // and the shell has to be able to see one. `rememberSaveable` rather than `remember`
    // because §0's rule is that a rotation or a process death must not quietly discard a
    // batch the reader assembled by hand.
    selection: MutableState<DrawerSelection> = rememberSaveable(
        stateSaver = DrawerSelection.Saver,
    ) { mutableStateOf(DrawerSelection.None) },
    // Hoisted for the third time and the same reason (V08). What the list is narrowed to
    // is now a rung of the back chain — back widens a scoped Feed before it thinks about
    // leaving — and the drawer is no longer the only thing that sets it: tapping a
    // source's name in an article scopes the Feed to that source from another screen
    // entirely. One owner, up here, and the view-model is told.
    homeScope: MutableState<HomeScope> = rememberSaveable(
        stateSaver = HomeScope.Saver,
    ) { mutableStateOf<HomeScope>(HomeScope.All) },
) {
    val totalUnread by viewModel.totalUnread.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // The rows arrive on their own flow now (U07a), a page at a time, and carry their own
    // load state — which is why the screen below asks *this* whether the list is empty
    // rather than asking [uiState].
    val entries = viewModel.pagedEntries.collectAsLazyPagingItems()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val pendingUndo by viewModel.pendingUndo.collectAsStateWithLifecycle()
    val collapsedFolders by viewModel.collapsedFolders.collectAsStateWithLifecycle()
    val folderUndo by viewModel.pendingFolderUndo.collectAsStateWithLifecycle()
    val deletePrompt by viewModel.sourceDeletePrompt.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var addingSource by rememberSaveable { mutableStateOf(false) }
    // Which source a dialog is about is held as an id, not as the item: the item is a
    // snapshot of a row that a refresh rewrites underneath us, and resolving it against
    // the current state means a source that disappears takes its dialog with it.
    var renamingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var movingId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Folder dialogs, held the same way and for the same reason.
    var folderActionsForId by rememberSaveable { mutableStateOf<Long?>(null) }
    var renamingFolderId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingFolderId by rememberSaveable { mutableStateOf<Long?>(null) }
    var creatingFolder by rememberSaveable { mutableStateOf(false) }
    // Non-null while "New folder…" was reached from a source's move dialog: the folder is
    // created and the source filed into it in one gesture, never two.
    var creatingFolderFor by rememberSaveable { mutableStateOf<Long?>(null) }
    // Which row's long-press sheet is up (U09), held as an id for the same reason the
    // source dialogs are: a refresh rewrites the row underneath us.
    var entryActionsForId by rememberSaveable { mutableStateOf<Long?>(null) }

    fun sourceOf(id: Long?) = uiState.sources.firstOrNull { it.id == id }

    fun folderOf(id: Long?) = uiState.folders.firstOrNull { it.id == id }

    // The hoisted scope is the only writer; the view-model is told, never asked. Keyed on
    // the value so a recomposition does not re-issue a query the list is already showing.
    LaunchedEffect(homeScope.value) { viewModel.setScope(homeScope.value) }

    fun select(feedId: Long?) {
        homeScope.value = if (feedId == null) HomeScope.All else HomeScope.Source(feedId)
        scope.launch { drawerState.close() }
    }

    fun selectFolder(folderId: Long) {
        homeScope.value = HomeScope.Folder(folderId)
        scope.launch { drawerState.close() }
    }

    fun addSource() {
        scope.launch { drawerState.close() }
        addingSource = true
    }

    // ---- selection mode (U09a) ---------------------------------------------------

    fun leaveSelection() {
        selection.value = DrawerSelection.None
    }

    /**
     * The one selected row's id. Only ever read behind `count == 1`, which is the only
     * state in which the bar offers rename or move at all.
     */
    fun theOne(): Long = selection.value.ids.first()

    fun renameSelection() {
        when (selection.value) {
            is DrawerSelection.Sources -> renamingId = theOne()
            is DrawerSelection.Folders -> renamingFolderId = theOne()
            DrawerSelection.None -> Unit
        }
        leaveSelection()
    }

    fun moveSelection() {
        if (selection.value is DrawerSelection.Sources) movingId = theOne()
        leaveSelection()
    }

    /**
     * The two deletes of U09a, which differ because their risk differs. Folders go
     * straight through to an undo snackbar — nothing is lost. Sources arm a dialog and
     * the selection stays ticked behind it, so cancelling leaves the batch intact rather
     * than making the reader assemble it again.
     */
    fun deleteSelection() {
        when (val ticked = selection.value) {
            is DrawerSelection.Folders -> {
                leaveSelection()
                viewModel.deleteFolders(ticked.ids)
            }
            is DrawerSelection.Sources -> viewModel.promptRemoveSources(ticked.ids)
            DrawerSelection.None -> Unit
        }
    }

    // The undo snackbar is driven off the armed batch rather than fired at the call site,
    // so the offer survives a rotation: the token is view-model state, and whatever
    // recomposes next puts the snackbar back up for whatever is left of its time.
    val context = LocalContext.current
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(pendingUndo) {
        val undo = pendingUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = context.resources.getQuantityString(
                R.plurals.home_marked_read,
                undo.count,
                undo.count,
            ),
            actionLabel = undoLabel,
            withDismissAction = false,
            duration = SnackbarDuration.Short,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.undoMarkAllRead()
            SnackbarResult.Dismissed -> viewModel.clearPendingUndo()
        }
    }

    // The folder batch delete's undo, armed the same way and for the same reason (U09a).
    // It says what happened to the *sources* as well as to the folders, because "3 folders
    // deleted" on its own leaves the reader wondering what it cost them.
    LaunchedEffect(folderUndo) {
        val undo = folderUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = context.getString(
                R.string.folders_deleted_snackbar,
                context.resources.getQuantityString(
                    R.plurals.folders_deleted,
                    undo.folderCount,
                    undo.folderCount,
                ),
                context.resources.getQuantityString(
                    R.plurals.folders_deleted_moved,
                    undo.movedSourceCount,
                    undo.movedSourceCount,
                ),
            ),
            actionLabel = undoLabel,
            withDismissAction = false,
            duration = SnackbarDuration.Short,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.undoDeleteFolders()
            SnackbarResult.Dismissed -> viewModel.clearFolderUndo()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            SourceDrawer(
                state = uiState,
                totalUnread = totalUnread,
                collapsedFolders = collapsedFolders,
                selection = selection.value,
                onSelectSource = ::select,
                onSelectFolder = ::selectFolder,
                onToggleFolder = viewModel::toggleFolderExpanded,
                onFolderActions = { folderActionsForId = it },
                onToggleSourceTick = { selection.value = selection.value.toggleSource(it) },
                onToggleFolderTick = { selection.value = selection.value.toggleFolder(it) },
                onLeaveSelection = ::leaveSelection,
                onRenameSelection = ::renameSelection,
                onMoveSelection = ::moveSelection,
                onDeleteSelection = ::deleteSelection,
                onAddSource = ::addSource,
                onNewFolder = { creatingFolder = true },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = uiState.selectedTitle
                                ?: stringResource(R.string.home_title_feed),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(HomeTestTags.TITLE),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = stringResource(R.string.action_open_sources),
                                modifier = Modifier.size(Dimens.icon),
                            )
                        }
                    },
                    actions = {
                        HomeOverflow(
                            onRefresh = viewModel::refresh,
                            onMarkAllRead = viewModel::markAllRead,
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                uiState.banner?.let { banner ->
                    BannerStrip(
                        banner = banner,
                        onRetry = viewModel::refresh,
                        onDismiss = viewModel::dismissBanner,
                    )
                }
                TimeRangeControl(
                    active = uiState.timeFilter,
                    onSelect = viewModel::selectTimeFilter,
                )
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(HomeTestTags.LIST),
                ) {
                    // "Empty" is only ever asserted once the first page has come back:
                    // a paged list is momentarily empty on every query change, and an
                    // empty state that flashes between two full lists reads as a bug.
                    val loadingFirstPage = entries.loadState.refresh is LoadState.Loading
                    when {
                        uiState.isLoading || (entries.itemCount == 0 && loadingFirstPage) ->
                            SkeletonList()
                        entries.itemCount == 0 -> EmptyState(
                            hasSources = uiState.hasSources,
                            widerFilter = uiState.widerFilter,
                            onAddSource = ::addSource,
                            onWiden = viewModel::widenTimeFilter,
                        )
                        else -> EntryList(
                            entries = entries,
                            showSections = uiState.showSections,
                            nowMillis = uiState.nowMillis,
                            listState = listState,
                            onOpenEntry = onOpenEntry,
                            onLongPressEntry = { entryActionsForId = it },
                        )
                    }
                }
            }
        }

        if (addingSource) {
            AddSourceSheet(
                viewModel = addSourceViewModel,
                onDismiss = { addingSource = false },
            )
        }

        sourceOf(movingId)?.let { source ->
            MoveSourceDialog(
                sourceTitle = source.title,
                folders = uiState.folders,
                currentFolderId = source.folderId,
                onMove = { folderId ->
                    movingId = null
                    viewModel.moveSource(source.id, folderId)
                },
                onNewFolder = {
                    movingId = null
                    creatingFolderFor = source.id
                },
                onDismiss = { movingId = null },
            )
        }

        sourceOf(renamingId)?.let { source ->
            RenameSourceDialog(
                customTitle = source.customTitle,
                publishedTitle = source.publishedTitle,
                onConfirm = { name ->
                    renamingId = null
                    viewModel.renameSource(source.id, name)
                },
                onDismiss = { renamingId = null },
            )
        }

        deletePrompt?.let { prompt ->
            DeleteSourcesDialog(
                sourceCount = prompt.sourceCount,
                savedOrLikedCount = prompt.savedOrLikedCount,
                onConfirm = {
                    leaveSelection()
                    viewModel.confirmRemoveSources()
                },
                onDismiss = viewModel::cancelRemoveSources,
            )
        }

        folderOf(folderActionsForId)?.let { folder ->
            FolderActionsDialog(
                folderName = folder.name,
                onRename = {
                    folderActionsForId = null
                    renamingFolderId = folder.id
                },
                onDelete = {
                    folderActionsForId = null
                    deletingFolderId = folder.id
                },
                onDismiss = { folderActionsForId = null },
            )
        }

        folderOf(renamingFolderId)?.let { folder ->
            FolderNameDialog(
                title = stringResource(R.string.folder_rename_title),
                initialName = folder.name,
                onConfirm = { name ->
                    renamingFolderId = null
                    viewModel.renameFolder(folder.id, name)
                },
                onDismiss = { renamingFolderId = null },
            )
        }

        folderOf(deletingFolderId)?.let { folder ->
            DeleteFolderDialog(
                folderName = folder.name,
                onConfirm = {
                    deletingFolderId = null
                    viewModel.deleteFolder(folder.id)
                },
                onDismiss = { deletingFolderId = null },
            )
        }

        if (creatingFolder) {
            FolderNameDialog(
                title = stringResource(R.string.folder_new_title),
                onConfirm = { name ->
                    creatingFolder = false
                    viewModel.createFolder(name)
                },
                onDismiss = { creatingFolder = false },
            )
        }

        // Resolved against the rows that are actually loaded rather than against the whole
        // list, which no longer exists in one place (U07a). That is not a narrowing: the
        // only row whose sheet can be open is one the reader long-pressed, and a row they
        // could press is a row that is loaded.
        entries.itemSnapshotList.items.firstOrNull { it.id == entryActionsForId }?.let { item ->
            EntryActionsSheet(
                item = item,
                onToggleSaved = {
                    entryActionsForId = null
                    viewModel.setSaved(item.id, !item.isSaved)
                },
                onToggleLiked = {
                    entryActionsForId = null
                    viewModel.setLiked(item.id, !item.isStarred)
                },
                onToggleRead = {
                    entryActionsForId = null
                    viewModel.setRead(item.id, !item.isRead)
                },
                onShare = {
                    entryActionsForId = null
                    shareEntry(context, item.title, item.link)
                },
                onDismiss = { entryActionsForId = null },
            )
        }

        creatingFolderFor?.let { feedId ->
            FolderNameDialog(
                title = stringResource(R.string.folder_new_title),
                onConfirm = { name ->
                    creatingFolderFor = null
                    viewModel.createFolder(name) { folderId ->
                        viewModel.moveSource(feedId, folderId)
                    }
                },
                onDismiss = { creatingFolderFor = null },
            )
        }
    }
}

/**
 * The app bar's overflow (DESIGN.md §5). Refresh is here as well as under the pull
 * gesture because a list short enough not to scroll still has to be refreshable, and
 * because it is where a reader looks for it. "Show read entries" joins these two in T27.
 */
@Composable
private fun HomeOverflow(onRefresh: () -> Unit, onMarkAllRead: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.action_more),
            modifier = Modifier.size(Dimens.icon),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_mark_all_read)) },
            onClick = {
                expanded = false
                onMarkAllRead()
            },
            modifier = Modifier.testTag(HomeTestTags.MARK_ALL_READ),
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_refresh)) },
            onClick = {
                expanded = false
                onRefresh()
            },
            modifier = Modifier.testTag(HomeTestTags.REFRESH),
        )
    }
}

/**
 * The slim strip above the list (DESIGN.md §7).
 *
 * It never replaces the list and never blocks it: a source that is failing still shows
 * every entry it fetched before it broke, and an offline device is a fully readable app.
 * That is the whole point of the strip being a sibling of the list rather than a state
 * the list collapses into.
 *
 * Offline carries no Retry — there is nothing to retry until the network comes back, and
 * a button that cannot work is worse than no button.
 */
@Composable
private fun BannerStrip(
    banner: HomeBanner,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isError = banner != HomeBanner.Offline
    val container =
        if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val content =
        if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val message = when (banner) {
        HomeBanner.Offline -> stringResource(R.string.home_banner_offline)
        HomeBanner.AllSourcesFailing -> stringResource(R.string.home_banner_all_failing)
        is HomeBanner.SourceError -> banner.message
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(container)
            .padding(
                start = Dimens.screenHorizontal,
                top = Dimens.sm,
                end = Dimens.sm,
                bottom = Dimens.sm,
            )
            .testTag(HomeTestTags.BANNER),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (banner != HomeBanner.Offline) {
            TextButton(onClick = onRetry, modifier = Modifier.testTag(HomeTestTags.BANNER_RETRY)) {
                Text(stringResource(R.string.action_retry), color = content)
            }
        }
        // Only the global banner is dismissible: a per-source message is tied to the
        // filter the reader chose, so leaving that source is already how it goes away.
        if (banner == HomeBanner.AllSourcesFailing) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(HomeTestTags.BANNER_DISMISS),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    tint = content,
                    modifier = Modifier.size(Dimens.icon),
                )
            }
        }
    }
}

/**
 * The source drawer (DESIGN.md §5, PLAN-2 §0): the unified inbox, then the folder
 * sections with their sources nested beneath them, then add-source, new-folder and
 * settings.
 *
 * Folders are *sections*, not destinations of their own — the drawer's whole job is
 * scoping the Feed, so a header both narrows the list to that folder and, via its
 * chevron, shows or hides the sources under it. Uncategorized is the one folder with no
 * overflow: the repository refuses to rename or delete it, so offering the menu would be
 * offering two dead items.
 *
 * A source that failed its last refresh trades its icon for a `⚠` in `error` — the
 * affordance only; the message and the retry are T26's banner. A source with nothing
 * unread stays listed showing 0 rather than disappearing, because the drawer is the
 * subscription list, not a second inbox; the same is true of a folder. Long-pressing a
 * source offers rename, move and remove — see [SourceRow].
 *
 * The whole sheet scrolls: forty-two sources do not fit on a phone.
 */
@Composable
private fun SourceDrawer(
    state: HomeUiState,
    totalUnread: Int,
    collapsedFolders: Set<Long>,
    selection: DrawerSelection,
    onSelectSource: (Long?) -> Unit,
    onSelectFolder: (Long) -> Unit,
    onToggleFolder: (Long) -> Unit,
    onFolderActions: (Long) -> Unit,
    onToggleSourceTick: (Long) -> Unit,
    onToggleFolderTick: (Long) -> Unit,
    onLeaveSelection: () -> Unit,
    onRenameSelection: () -> Unit,
    onMoveSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onAddSource: () -> Unit,
    onNewFolder: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // An empty Uncategorized is noise on a first launch; an empty folder the reader made
    // is the folder they are about to fill, so it stays visible.
    val sections = state.folders.filter { it.sources.isNotEmpty() || !it.isBuiltIn }
    val selecting = selection.isActive

    ModalDrawerSheet {
        // §0's back chain, first rung (U09a). It lives here rather than in the shell
        // because the drawer answers back itself and, being composed deeper, would win —
        // back would shut the drawer and throw away a batch instead of undoing a step.
        BackHandler(enabled = selecting, onBack = onLeaveSelection)

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            if (selecting) {
                SelectionBar(
                    selection = selection,
                    onLeave = onLeaveSelection,
                    onRename = onRenameSelection,
                    onMove = onMoveSelection,
                    onDelete = onDeleteSelection,
                )
            } else {
                // The lockup, not a title: the drawer is the only surface in the app that
                // is unambiguously *Perch* rather than someone's feed, so it is where the
                // name belongs. In selection mode the contextual bar takes the band, and
                // the wordmark stands down rather than competing with the count.
                PerchWordmark(
                    modifier = Modifier.padding(
                        horizontal = Dimens.brandHeaderHorizontal,
                        vertical = Dimens.brandHeaderVertical,
                    ),
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Inbox, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_all_sources)) },
                    badge = {
                        Text(
                            text = totalUnread.toString(),
                            modifier = Modifier.testTag(HomeTestTags.ALL_UNREAD_BADGE),
                        )
                    },
                    selected = state.scope == HomeScope.All,
                    onClick = { onSelectSource(null) },
                    modifier = Modifier.padding(horizontal = Dimens.md),
                )
            }
            if (sections.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(Dimens.md))
            }
            sections.forEach { folder ->
                FolderHeaderRow(
                    folder = folder,
                    selected = state.selectedFolderId == folder.id,
                    expanded = folder.id !in collapsedFolders,
                    selection = selection,
                    onSelect = {
                        if (selecting) onToggleFolderTick(folder.id) else onSelectFolder(folder.id)
                    },
                    onLongPress = { onToggleFolderTick(folder.id) },
                    onToggle = { onToggleFolder(folder.id) },
                    onActions = { onFolderActions(folder.id) },
                )
                if (folder.id !in collapsedFolders) {
                    folder.sources.forEach { source ->
                        SourceRow(
                            source = source,
                            selected = state.selectedFeedId == source.id,
                            selection = selection,
                            onSelect = {
                                if (selecting) {
                                    onToggleSourceTick(source.id)
                                } else {
                                    onSelectSource(source.id)
                                }
                            },
                            onLongPress = { onToggleSourceTick(source.id) },
                        )
                    }
                }
            }
            // The three ways out of the drawer are navigation, and navigating away mid
            // selection is how a reader loses a batch they were halfway through building.
            if (!selecting) {
                HorizontalDivider(modifier = Modifier.padding(Dimens.md))
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_add_source)) },
                    selected = false,
                    onClick = onAddSource,
                    modifier = Modifier.padding(horizontal = Dimens.md),
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_new_folder)) },
                    selected = false,
                    onClick = onNewFolder,
                    modifier = Modifier.padding(horizontal = Dimens.md),
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_settings)) },
                    selected = false,
                    onClick = onOpenSettings,
                    modifier = Modifier.padding(horizontal = Dimens.md),
                )
            }
        }
    }
}

/**
 * One folder section header (U06).
 *
 * Three targets rather than one, each with its own hit area: the chevron shows or hides
 * the sources, the name scopes the list to the folder, and the `⋮` renames or deletes it.
 * They are deliberately *not* merged into a single node — a merged row could offer only
 * one of the three, and expanding a folder is not the same gesture as reading it.
 *
 * A selection the header cannot join draws it as unavailable rather than letting the
 * press land on nothing (V10): dimmed, no ripple, and `disabled` in its semantics so the
 * rule reaches a screen reader too. Which selections those are is [refusesFolder]'s to
 * say — the drawer draws the refusal, it does not restate it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderHeaderRow(
    folder: FolderUiItem,
    selected: Boolean,
    expanded: Boolean,
    selection: DrawerSelection,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    onToggle: () -> Unit,
    onActions: () -> Unit,
) {
    val unavailable = selection.refusesFolder(folder.id)
    val container =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    // Two colours, because the row is not uniformly unavailable: the *header* is refused
    // but the chevron beside it is not, and dimming a control that still works would
    // misreport it. So [live] paints the controls and [content] the name and its count —
    // dimmed, never invisible, since the reader still reads the name to find the source
    // underneath it.
    val live =
        if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val content = if (unavailable) live.copy(alpha = UNAVAILABLE_ALPHA) else live

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            // Sections need air between them or the drawer reads as one long list of
            // rows that all happen to be 56dp tall.
            .padding(top = Dimens.sm)
            .padding(horizontal = Dimens.md)
            .fillMaxWidth()
            .height(Dimens.drawerRowHeight)
            .clip(CircleShape)
            .background(container),
    ) {
        // Only a *folder* selection puts checkboxes on folders — the homogeneous rule is
        // drawn, not merely enforced, or a source selection would offer a tick here that
        // silently does nothing. Mid-source-selection the chevron stays, which is what
        // lets a reader open a collapsed folder to reach the sources inside it.
        if (selection is DrawerSelection.Folders) {
            // The chevron's slot, taken over: a folder cannot be expanded and ticked at
            // the same time, and the tick has to sit where the eye is already looking.
            // Uncategorized draws a disabled box rather than none, so the reason it
            // cannot be deleted reads as a rule rather than as a missing control.
            Checkbox(
                checked = selection.holdsFolder(folder.id),
                onCheckedChange = { onLongPress() },
                enabled = !folder.isBuiltIn,
                modifier = Modifier.testTag(SelectionTestTags.folderCheckbox(folder.id)),
            )
        } else {
            IconButton(
                onClick = onToggle,
                modifier = Modifier.testTag(HomeTestTags.folderExpand(folder.id)),
            ) {
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                    contentDescription = stringResource(
                        if (expanded) R.string.folder_collapse else R.string.folder_expand,
                        folder.name,
                    ),
                    tint = live,
                    modifier = Modifier.size(Dimens.icon),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .combinedClickable(
                    enabled = !unavailable,
                    onClick = onSelect,
                    onLongClick = onLongPress,
                )
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = Dimens.xs)
                .testTag(HomeTestTags.folderHeader(folder.id)),
        ) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleSmall,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(Dimens.sm))
            // Right-aligned, and a step quieter than the name: pushed up against it the
            // count reads as part of the folder's title rather than as its own column.
            Text(
                text = folder.unreadCount.toString(),
                style = MaterialTheme.typography.labelLarge,
                // The count follows the name down when the header is unavailable: half a
                // row dimmed would read as a rendering fault rather than as a state.
                color = if (selected || unavailable) {
                    content
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.testTag(HomeTestTags.folderBadge(folder.id)),
            )
        }
        if (!folder.isBuiltIn && !selection.isActive) {
            IconButton(
                onClick = onActions,
                modifier = Modifier.testTag(HomeTestTags.folderOverflow(folder.id)),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.folder_more, folder.name),
                    tint = live,
                    modifier = Modifier.size(Dimens.icon),
                )
            }
        } else {
            Spacer(modifier = Modifier.width(Dimens.touchTarget))
        }
    }
}

/**
 * One source in the drawer, nested under its folder header.
 *
 * Hand-built rather than a [NavigationDrawerItem] for one reason: §5 puts rename, move and
 * remove behind a long press, and the Material item answers taps only — wrapping it would
 * not help, because its own `clickable` consumes the gesture before any parent sees it.
 * The metrics are copied from it so the row still lines up with "All sources" above and
 * "Add source" below.
 *
 * The semantics are merged so the row, not its label, is the node that carries both
 * actions — which is also what lets a test long-press it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceRow(
    source: SourceUiItem,
    selected: Boolean,
    selection: DrawerSelection,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
) {
    val container =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val content =
        if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = Dimens.drawerNestIndent, end = Dimens.md)
            .fillMaxWidth()
            .height(Dimens.drawerRowHeight)
            .clip(CircleShape)
            .background(container)
            .combinedClickable(onClick = onSelect, onLongClick = onLongPress)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Dimens.drawerRowPadding),
    ) {
        // Likewise: only a source selection ticks sources (see [FolderHeaderRow]).
        if (selection is DrawerSelection.Sources) {
            // In the icon's slot, not beside it: a checkbox that pushed the row's contents
            // sideways would reflow the whole drawer the moment selection began.
            Checkbox(
                checked = selection.holdsSource(source.id),
                onCheckedChange = { onSelect() },
                modifier = Modifier
                    .size(Dimens.icon)
                    .testTag(SelectionTestTags.sourceCheckbox(source.id)),
            )
        } else if (source.hasError) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = stringResource(R.string.drawer_source_error),
                tint = MaterialTheme.colorScheme.error,
            )
        } else {
            Icon(Icons.Default.RssFeed, contentDescription = null, tint = content)
        }
        Spacer(modifier = Modifier.width(Dimens.drawerRowGap))
        Text(
            text = source.title,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = source.unreadCount.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = content,
            modifier = Modifier.testTag(HomeTestTags.sourceBadge(source.id)),
        )
    }
}

/**
 * Handles for the few nodes whose text is ambiguous on screen — the selected source's
 * name is in the bar *and* in the drawer, two sources can share an unread count, and the
 * drawer's always-composed "Add source" row shares its label with the empty state's button.
 */
object HomeTestTags {
    const val TITLE = "home:title"
    const val ALL_UNREAD_BADGE = "home:all-unread:badge"
    const val EMPTY_ADD_SOURCE = "home:empty:add-source"
    /** The pull-to-refresh surface, so a test can make the gesture the reader makes. */
    const val LIST = "home:list"

    /**
     * The `LazyColumn` inside it. Separate from [LIST] because the two answer different
     * gestures: [LIST] is the surface a pull starts on, this is the thing that scrolls —
     * and U09's "the Feed's position survives a tab switch" is a question only this one
     * can be asked.
     */
    const val ENTRY_LIST = "home:list:entries"

    /**
     * Every row carries the same tag, so a device test taps `ENTRY` at `index: 0` rather
     * than at a screen coordinate that the app bar or a banner would shift.
     */
    const val ENTRY = "home:entry"

    const val MARK_ALL_READ = "home:overflow:mark-all-read"
    const val REFRESH = "home:overflow:refresh"
    const val BANNER = "home:banner"
    const val BANNER_RETRY = "home:banner:retry"
    const val BANNER_DISMISS = "home:banner:dismiss"

    fun sourceBadge(feedId: Long) = "home:source:$feedId:badge"

    /**
     * A folder header carries three separate targets (U06), so each needs its own handle —
     * and a folder's name is on screen in the drawer, in the app bar when it is the scope,
     * and again inside the move dialog.
     */
    fun folderHeader(folderId: Long) = "home:folder:$folderId"
    fun folderBadge(folderId: Long) = "home:folder:$folderId:badge"
    fun folderExpand(folderId: Long) = "home:folder:$folderId:expand"
    fun folderOverflow(folderId: Long) = "home:folder:$folderId:more"

    /**
     * U08a's time-range dropdown: the closed control, the label inside it (the control
     * itself merges the chevron's description in, so an assertion about what the reader
     * reads has to address the text), the open menu, and one handle per range.
     */
    const val TIME_RANGE = "home:range"
    const val TIME_RANGE_LABEL = "home:range:label"
    const val RANGE_MENU = "home:range:menu"

    fun rangeItem(filter: TimeFilter) = "home:range:${filter.name}"

    /**
     * A folder section header *in the list*, as opposed to the folder's row in the
     * drawer — the two carry the same name and the assertions have to tell them apart.
     */
    fun section(folderId: Long) = "home:section:$folderId"

    /** The empty bucket's way out, which only the bucket case has. */
    const val EMPTY_WIDEN = "home:empty:widen"
}

/**
 * The list proper, paged (U07a).
 *
 * `LazyColumn` was already composing only what is on screen; what it was not doing was
 * *loading* only that. The rows now arrive a page at a time, and the reader is meant never
 * to find out: the only visible difference is a small footer while the next page is in
 * flight, and a marker where the list genuinely ends.
 *
 * `peek` rather than indexing for the neighbours: reading a row through `get` tells Paging
 * the reader has reached it, and asking "what folder was the row above in" is not the
 * reader reaching anything. Indexing the neighbour would drag the prefetch window along
 * behind the list by one row for no reason.
 *
 * [rememberLazyListState] is saveable, so the scroll offset survives opening an article
 * and coming back; the reader returns to the row they left, not to the top.
 */
@Composable
private fun EntryList(
    entries: LazyPagingItems<EntryListItem>,
    showSections: Boolean,
    nowMillis: Long,
    listState: LazyListState,
    onOpenEntry: (Long) -> Unit,
    onLongPressEntry: (Long) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(HomeTestTags.ENTRY_LIST),
    ) {
        items(
            count = entries.itemCount,
            key = entries.itemKey { it.id },
        ) { index ->
            val item = entries[index] ?: return@items
            // Placeholders are off (see PerchPaging), so every index below `itemCount` is
            // a loaded row and both neighbours are answerable — which is what keeps the
            // header from reappearing at the top of every page.
            val previous = if (index == 0) null else entries.peek(index - 1)
            val next =
                if (index + 1 < entries.itemCount) entries.peek(index + 1) else null
            val opensSection = showSections && startsSection(previous, item)
            val endsSection = showSections && next != null && next.folderId != item.folderId
            Column(modifier = Modifier.animateItem()) {
                if (opensSection) SectionHeader(folderId = item.folderId, name = item.folderName)
                EntryRow(
                    item = item,
                    now = nowMillis,
                    onClick = { onOpenEntry(item.id) },
                    onLongClick = { onLongPressEntry(item.id) },
                    modifier = Modifier.testTag(HomeTestTags.ENTRY),
                )
                // The header below is the break; a rule as well would be two.
                if (next != null && !endsSection) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = Dimens.dividerInset),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
        pagedFooter(entries)
    }
}

/**
 * A folder's heading in the list (PLAN-2 §0, U07).
 *
 * Set in the accent colour rather than in `onSurface`: it is furniture the eye should be
 * able to skip past when scanning titles, and colour separates it from the row titles
 * without spending the vertical space a rule or a filled bar would.
 */
@Composable
private fun SectionHeader(folderId: Long, name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Dimens.rowHorizontal,
                end = Dimens.rowHorizontal,
                top = Dimens.sectionHeaderTop,
                bottom = Dimens.sectionHeaderBottom,
            )
            .testTag(HomeTestTags.section(folderId)),
    )
}

/**
 * PLAN-2 §0's time filter: five windows, narrowest first, exactly one active (U08a).
 *
 * A **dropdown**, not a row of chips. Five always-visible chips spend a horizontal band
 * restating the four options the reader is not choosing, and on a narrow phone they do it
 * behind a horizontal scroll, so the band is both expensive and incomplete. This spends a
 * word: the active range, a chevron, and the other four only when they are being chosen.
 *
 * It still sits above the list rather than inside it, so it never scrolls away — the
 * reader has to be able to see what window they are in at the moment they wonder why an
 * article is missing. Nothing here is width-constrained, so the longest label simply makes
 * the control wider at a large font scale instead of being clipped.
 */
@Composable
private fun TimeRangeControl(active: TimeFilter, onSelect: (TimeFilter) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier = Modifier.padding(
            start = Dimens.rangeControlInset,
            top = Dimens.rangeRowVertical,
            bottom = Dimens.rangeRowVertical,
        ),
    ) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(HomeTestTags.TIME_RANGE),
        ) {
            Text(
                text = stringResource(active.labelRes()),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.testTag(HomeTestTags.TIME_RANGE_LABEL),
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.home_filter_range),
                modifier = Modifier
                    .padding(start = Dimens.xs)
                    .size(Dimens.icon),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // The menu's own node, so a test can ask which of *these* items is selected —
            // the drawer behind the list is composed even while closed and carries
            // selected rows of its own.
            Column(modifier = Modifier.testTag(HomeTestTags.RANGE_MENU)) {
                TimeFilter.entries.forEach { filter ->
                    val isActive = filter == active
                    DropdownMenuItem(
                        text = { Text(stringResource(filter.labelRes())) },
                        onClick = {
                            expanded = false
                            onSelect(filter)
                        },
                        // The tick reserves its space on every row, so the labels line up
                        // in one column rather than the active one stepping out.
                        leadingIcon = {
                            if (isActive) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(Dimens.icon),
                                )
                            } else {
                                Spacer(modifier = Modifier.size(Dimens.icon))
                            }
                        },
                        modifier = Modifier
                            .testTag(HomeTestTags.rangeItem(filter))
                            .semantics { selected = isActive },
                    )
                }
            }
        }
    }
}

/** The ranges' labels, in §0's words. */
internal fun TimeFilter.labelRes(): Int = when (this) {
    TimeFilter.Today -> R.string.home_filter_today
    TimeFilter.PastWeek -> R.string.home_filter_week
    TimeFilter.PastMonth -> R.string.home_filter_month
    TimeFilter.PastYear -> R.string.home_filter_year
    TimeFilter.AllTime -> R.string.home_filter_all
}

/**
 * First load only (§7). Six inert blocks — no shimmer, no spinner. A refresh never comes
 * back through here; it shows in the pull indicator instead of replacing what is already
 * readable on screen.
 */
@Composable
private fun SkeletonList() {
    Column(modifier = Modifier.fillMaxSize()) {
        repeat(SKELETON_ROWS) {
            Column(
                modifier = Modifier.padding(
                    horizontal = Dimens.rowHorizontal,
                    vertical = Dimens.rowVertical,
                ),
            ) {
                SkeletonBar(widthFraction = TITLE_BAR_FRACTION, height = Dimens.skeletonTitle)
                Spacer(modifier = Modifier.size(Dimens.sm))
                SkeletonBar(widthFraction = META_BAR_FRACTION, height = Dimens.skeletonMeta)
            }
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(Dimens.skeletonCorner))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    )
}

/**
 * Nothing to show, and *why* nothing (§7). With no sources the reader has to add one;
 * with sources but no unread they have finished. Those are opposite situations and the
 * screen says so rather than showing one grey "empty" for both.
 *
 * With no sources the state carries the action that resolves it, because a reader looking
 * at an empty app should not have to find the drawer to fill it. ("Show read entries" is
 * T27 and attaches to the other case the same way.)
 */
@Composable
private fun EmptyState(
    hasSources: Boolean,
    widerFilter: TimeFilter?,
    onAddSource: () -> Unit,
    onWiden: () -> Unit,
) {
    // An empty *window* is a third situation, and the one most likely to be mistaken for
    // a broken app: the articles are there, the reader is just looking at a narrow slice
    // of time. So it says so and offers the one step out (§0), rather than claiming the
    // reader is caught up on things they have never seen.
    val emptyWindow = hasSources && widerFilter != null
    // Null means the brand mark rather than a glyph — see the no-sources branch below.
    val icon: ImageVector?
    val title: String
    val body: String
    when {
        emptyWindow -> {
            icon = Icons.Default.Schedule
            title = stringResource(R.string.home_empty_window_title)
            body = stringResource(R.string.home_empty_window_body)
        }

        hasSources -> {
            icon = Icons.Default.DoneAll
            title = stringResource(R.string.home_empty_all_read_title)
            body = stringResource(R.string.home_empty_all_read_body)
        }

        else -> {
            // An app with nothing in it yet is the one moment the reader is looking at
            // Perch rather than at their feeds, so this state shows the mark. A generic
            // RSS glyph here says "this is a feed reader" to someone who just installed
            // one; the mark says which.
            icon = null
            title = stringResource(R.string.home_empty_no_sources_title)
            body = stringResource(R.string.home_empty_no_sources_body)
        }
    }

    // Scrollable on purpose (V03/#6). `PullToRefreshBox` only ever sees a drag its child
    // dispatches down the nested-scroll chain, and a plain `Column` dispatches nothing —
    // so pull-to-refresh, the one gesture a reader reaches for when the screen is empty,
    // was inert exactly where they need it. One item at the parent's full size keeps the
    // content centred and reserves the whole surface for the gesture.
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                modifier = Modifier
                    .fillParentMaxSize()
                    .padding(horizontal = Dimens.screenHorizontal),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (icon == null) {
                    PerchMark(size = Dimens.brandMark)
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Dimens.emptyIcon),
                    )
                }
                Spacer(modifier = Modifier.size(Dimens.lg))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.size(Dimens.sm))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(Dimens.emptyContentWidth),
                )
                // `emptyWindow` is only true when `widerFilter` is non-null, so the smart
                // cast inside carries; re-testing it here is what the compiler warns about.
                if (emptyWindow) {
                    Spacer(modifier = Modifier.size(Dimens.xl))
                    Button(
                        onClick = onWiden,
                        modifier = Modifier.testTag(HomeTestTags.EMPTY_WIDEN),
                    ) {
                        Text(
                            stringResource(
                                R.string.home_empty_widen,
                                stringResource(widerFilter.labelRes()),
                            ),
                        )
                    }
                } else if (!hasSources) {
                    Spacer(modifier = Modifier.size(Dimens.xl))
                    Button(
                        onClick = onAddSource,
                        modifier = Modifier.testTag(HomeTestTags.EMPTY_ADD_SOURCE),
                    ) {
                        Text(stringResource(R.string.drawer_add_source))
                    }
                }
            }
        }
    }
}

/** Material's disabled-content opacity: the one number a reader already knows (V10). */
private const val UNAVAILABLE_ALPHA = 0.38f

private const val SKELETON_ROWS = 6
private const val TITLE_BAR_FRACTION = 0.85f
private const val META_BAR_FRACTION = 0.4f
