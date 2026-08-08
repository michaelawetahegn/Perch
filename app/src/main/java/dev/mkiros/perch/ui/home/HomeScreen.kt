package dev.mkiros.perch.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkiros.perch.R
import dev.mkiros.perch.ui.source.AddSourceSheet
import dev.mkiros.perch.ui.source.AddSourceViewModel
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
) {
    val totalUnread by viewModel.totalUnread.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val pendingUndo by viewModel.pendingUndo.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var addingSource by rememberSaveable { mutableStateOf(false) }
    // Which source a dialog is about is held as an id, not as the item: the item is a
    // snapshot of a row that a refresh rewrites underneath us, and resolving it against
    // the current state means a source that disappears takes its dialog with it.
    var actionsForId by rememberSaveable { mutableStateOf<Long?>(null) }
    var renamingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var removingId by rememberSaveable { mutableStateOf<Long?>(null) }

    fun sourceOf(id: Long?) = uiState.sources.firstOrNull { it.id == id }

    fun select(feedId: Long?) {
        viewModel.selectSource(feedId)
        scope.launch { drawerState.close() }
    }

    fun addSource() {
        scope.launch { drawerState.close() }
        addingSource = true
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            SourceDrawer(
                state = uiState,
                totalUnread = totalUnread,
                onSelectSource = ::select,
                onSourceActions = { actionsForId = it },
                onAddSource = ::addSource,
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
                                ?: stringResource(R.string.home_title_unread),
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
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(HomeTestTags.LIST),
                ) {
                    when {
                        uiState.isLoading -> SkeletonList()
                        uiState.entries.isEmpty() -> EmptyState(
                            hasSources = uiState.hasSources,
                            onAddSource = ::addSource,
                        )
                        else -> EntryList(state = uiState, onOpenEntry = onOpenEntry)
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

        sourceOf(actionsForId)?.let { source ->
            SourceActionsDialog(
                sourceTitle = source.title,
                onRename = {
                    actionsForId = null
                    renamingId = source.id
                },
                onRemove = {
                    actionsForId = null
                    removingId = source.id
                },
                onDismiss = { actionsForId = null },
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

        sourceOf(removingId)?.let { source ->
            RemoveSourceDialog(
                sourceTitle = source.title,
                onConfirm = {
                    removingId = null
                    viewModel.removeSource(source.id)
                },
                onDismiss = { removingId = null },
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
 * The source drawer (DESIGN.md §5): the unified inbox, then one row per source with its
 * unread count, then settings.
 *
 * A source that failed its last refresh trades its icon for a `⚠` in `error` — the
 * affordance only; the message and the retry are T26's banner. A source with nothing
 * unread stays listed showing 0 rather than disappearing, because the drawer is the
 * subscription list, not a second inbox. Long-pressing a source offers rename and remove
 * (T24) — see [SourceRow].
 *
 * The whole sheet scrolls: forty-two sources do not fit on a phone.
 */
@Composable
private fun SourceDrawer(
    state: HomeUiState,
    totalUnread: Int,
    onSelectSource: (Long?) -> Unit,
    onSourceActions: (Long) -> Unit,
    onAddSource: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            NavigationDrawerItem(
                icon = { Icon(Icons.Default.Inbox, contentDescription = null) },
                label = { Text(stringResource(R.string.drawer_all_unread)) },
                badge = {
                    Text(
                        text = totalUnread.toString(),
                        modifier = Modifier.testTag(HomeTestTags.ALL_UNREAD_BADGE),
                    )
                },
                selected = state.selectedFeedId == null,
                onClick = { onSelectSource(null) },
                modifier = Modifier.padding(horizontal = Dimens.md),
            )
            if (state.sources.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(Dimens.md))
            }
            state.sources.forEach { source ->
                SourceRow(
                    source = source,
                    selected = state.selectedFeedId == source.id,
                    onSelect = { onSelectSource(source.id) },
                    onLongPress = { onSourceActions(source.id) },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(Dimens.md))
            NavigationDrawerItem(
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                label = { Text(stringResource(R.string.drawer_add_source)) },
                selected = false,
                onClick = onAddSource,
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

/**
 * One source in the drawer.
 *
 * Hand-built rather than a [NavigationDrawerItem] for one reason: §5 puts rename and
 * remove behind a long press, and the Material item answers taps only — wrapping it would
 * not help, because its own `clickable` consumes the gesture before any parent sees it.
 * The metrics are copied from it so the row still lines up with "All unread" above and
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
            .padding(horizontal = Dimens.md)
            .fillMaxWidth()
            .height(Dimens.drawerRowHeight)
            .clip(CircleShape)
            .background(container)
            .combinedClickable(onClick = onSelect, onLongClick = onLongPress)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Dimens.drawerRowPadding),
    ) {
        if (source.hasError) {
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
}

/**
 * The list proper. Room hands the whole unread set over on every write and `LazyColumn`
 * composes only what is on screen — that is the paging story, and it is enough for a
 * reader whose inbox is measured in hundreds.
 *
 * [rememberLazyListState] is saveable, so the scroll offset survives opening an article
 * and coming back; the reader returns to the row they left, not to the top.
 */
@Composable
private fun EntryList(
    state: HomeUiState,
    onOpenEntry: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(state.entries, key = { _, item -> item.id }) { index, item ->
            Column(modifier = Modifier.animateItem()) {
                EntryRow(
                    item = item,
                    now = state.nowMillis,
                    onClick = { onOpenEntry(item.id) },
                    modifier = Modifier.testTag(HomeTestTags.ENTRY),
                )
                if (index < state.entries.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = Dimens.dividerInset),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
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
private fun EmptyState(hasSources: Boolean, onAddSource: () -> Unit) {
    val icon: ImageVector
    val title: String
    val body: String
    if (hasSources) {
        icon = Icons.Default.DoneAll
        title = stringResource(R.string.home_empty_all_read_title)
        body = stringResource(R.string.home_empty_all_read_body)
    } else {
        icon = Icons.Default.RssFeed
        title = stringResource(R.string.home_empty_no_sources_title)
        body = stringResource(R.string.home_empty_no_sources_body)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.emptyIcon),
        )
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
        if (!hasSources) {
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

private const val SKELETON_ROWS = 6
private const val TITLE_BAR_FRACTION = 0.85f
private const val META_BAR_FRACTION = 0.4f
