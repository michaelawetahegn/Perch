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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkiros.perch.R
import dev.mkiros.perch.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * The reading list and the source drawer around it (DESIGN.md §5).
 *
 * The list is the unified unread inbox: every source, newest first, one row shape for all
 * forty-two of them. Its four states (§7) are all here — skeleton on first load, one of
 * two empty states depending on *why* it is empty, and the list itself. The source rows
 * and per-source filter are T22, adding a source is T23, pull-to-refresh and the error
 * banners are T26; each attaches to a slot this file already defines. No FAB, deliberately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenEntry: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalUnread by viewModel.totalUnread.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ModalDrawerSheet {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Inbox, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_all_unread)) },
                    badge = { Text(totalUnread.toString()) },
                    selected = true,
                    onClick = { scope.launch { drawerState.close() } },
                    modifier = Modifier.padding(horizontal = Dimens.md),
                )
                HorizontalDivider(modifier = Modifier.padding(Dimens.md))
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_settings)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    modifier = Modifier.padding(horizontal = Dimens.md),
                )
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.home_title_unread)) },
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
                    uiState.entries.isEmpty() -> EmptyState(hasSources = uiState.hasSources)
                    else -> EntryList(state = uiState, onOpenEntry = onOpenEntry)
                }
            }
        }
    }
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
 * The action button each case wants belongs to the screen that can perform it — the
 * add-source sheet is T23 and "show read entries" is T27; both attach here.
 */
@Composable
private fun EmptyState(hasSources: Boolean) {
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
    }
}

private const val SKELETON_ROWS = 6
private const val TITLE_BAR_FRACTION = 0.85f
private const val META_BAR_FRACTION = 0.4f
