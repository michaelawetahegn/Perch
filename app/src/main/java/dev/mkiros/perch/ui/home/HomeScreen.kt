package dev.mkiros.perch.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
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
 * in the drawer narrows the same list and retitles the bar. Adding a source is T23,
 * long-press rename/remove is T24, pull-to-refresh and the error banners are T26; each
 * attaches to a slot this file already defines. No FAB, deliberately.
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
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var addingSource by rememberSaveable { mutableStateOf(false) }

    fun select(feedId: Long?) {
        viewModel.selectSource(feedId)
        scope.launch { drawerState.close() }
    }

    fun addSource() {
        scope.launch { drawerState.close() }
        addingSource = true
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            SourceDrawer(
                state = uiState,
                totalUnread = totalUnread,
                onSelectSource = ::select,
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
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
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

        if (addingSource) {
            AddSourceSheet(
                viewModel = addSourceViewModel,
                onDismiss = { addingSource = false },
            )
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
 * subscription list, not a second inbox.
 *
 * The whole sheet scrolls: forty-two sources do not fit on a phone.
 */
@Composable
private fun SourceDrawer(
    state: HomeUiState,
    totalUnread: Int,
    onSelectSource: (Long?) -> Unit,
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
                NavigationDrawerItem(
                    icon = {
                        if (source.hasError) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription =
                                    stringResource(R.string.drawer_source_error),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Icon(Icons.Default.RssFeed, contentDescription = null)
                        }
                    },
                    label = {
                        Text(
                            text = source.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    badge = {
                        Text(
                            text = source.unreadCount.toString(),
                            modifier = Modifier.testTag(HomeTestTags.sourceBadge(source.id)),
                        )
                    },
                    selected = state.selectedFeedId == source.id,
                    onClick = { onSelectSource(source.id) },
                    modifier = Modifier.padding(horizontal = Dimens.md),
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
 * Handles for the few nodes whose text is ambiguous on screen — the selected source's
 * name is in the bar *and* in the drawer, two sources can share an unread count, and the
 * drawer's always-composed "Add source" row shares its label with the empty state's button.
 */
object HomeTestTags {
    const val TITLE = "home:title"
    const val ALL_UNREAD_BADGE = "home:all-unread:badge"
    const val EMPTY_ADD_SOURCE = "home:empty:add-source"

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
