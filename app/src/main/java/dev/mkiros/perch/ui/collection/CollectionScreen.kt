package dev.mkiros.perch.ui.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkiros.perch.R
import dev.mkiros.perch.ui.home.EntryActionsSheet
import dev.mkiros.perch.ui.home.EntryRow
import dev.mkiros.perch.ui.home.shareEntry
import dev.mkiros.perch.ui.theme.Dimens

/**
 * *To-Read* and *Liked* (PLAN-2 §0, U09) — the reader's own two lists.
 *
 * The same [EntryRow] as the Feed, deliberately: these are the same articles, and a
 * different row shape here would make them feel like a different kind of object. What
 * differs is the order (when the reader filed it, not when it was published), the absence
 * of the time filter, and the absence of the drawer — there is nothing to scope. There is
 * no folder sectioning either: these lists are ordered by the reader's own gesture, so
 * folder headers would cut across the one ordering that means anything here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    viewModel: CollectionViewModel,
    onOpenEntry: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingUndo by viewModel.pendingUndo.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val context = LocalContext.current
    var actionsForId by rememberSaveable { mutableStateOf<Long?>(null) }

    val undoLabel = stringResource(R.string.action_undo)
    val removedMessage = stringResource(
        when (viewModel.collection) {
            Collection.ToRead -> R.string.collection_removed_to_read
            Collection.Liked -> R.string.collection_removed_liked
        },
    )
    // Driven off the armed removal rather than fired at the call site, so the offer
    // survives a rotation — the same shape as T26's mark-all-read undo.
    LaunchedEffect(pendingUndo) {
        pendingUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = removedMessage,
            actionLabel = undoLabel,
            withDismissAction = false,
            duration = SnackbarDuration.Short,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.undo()
            SnackbarResult.Dismissed -> viewModel.clearPendingUndo()
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(viewModel.collection.titleRes())) },
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
            when {
                uiState.isLoading -> Unit
                uiState.entries.isEmpty() -> EmptyState(viewModel.collection)
                else -> EntryList(
                    state = uiState,
                    onOpenEntry = onOpenEntry,
                    onLongPress = { actionsForId = it },
                )
            }
        }
    }

    uiState.entries.firstOrNull { it.id == actionsForId }?.let { item ->
        EntryActionsSheet(
            item = item,
            onToggleSaved = {
                actionsForId = null
                viewModel.setSaved(item, !item.isSaved)
            },
            onToggleLiked = {
                actionsForId = null
                viewModel.setLiked(item, !item.isStarred)
            },
            onToggleRead = {
                actionsForId = null
                viewModel.setRead(item.id, !item.isRead)
            },
            onShare = {
                actionsForId = null
                shareEntry(context, item.title, item.link)
            },
            onDismiss = { actionsForId = null },
        )
    }
}

@Composable
private fun EntryList(
    state: CollectionUiState,
    onOpenEntry: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag(CollectionTestTags.LIST)) {
        itemsIndexed(state.entries, key = { _, item -> item.id }) { index, item ->
            // `animateItem` is what makes un-saving read as the row leaving rather than as
            // the list blinking: Room re-emits without it, and the row it dropped simply
            // is not there on the next frame.
            Column(modifier = Modifier.animateItem()) {
                EntryRow(
                    item = item,
                    now = state.nowMillis,
                    onClick = { onOpenEntry(item.id) },
                    onLongClick = { onLongPress(item.id) },
                    modifier = Modifier.testTag(CollectionTestTags.ENTRY),
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
 * Empty, and what the list is *for* (§0's explicit requirement, and DESIGN.md §7's rule
 * that an empty state names its cause).
 *
 * Neither of these lists can be empty for more than one reason — nothing has filtered them
 * — so there is exactly one sentence to say, and it is an instruction rather than a
 * report: a reader looking at an empty To-Read has not filled it yet and needs to be told
 * how, not told that it is empty, which they can see.
 */
@Composable
private fun EmptyState(collection: Collection) {
    val icon = when (collection) {
        Collection.ToRead -> Icons.AutoMirrored.Filled.LibraryBooks
        Collection.Liked -> Icons.Default.FavoriteBorder
    }
    val title = when (collection) {
        Collection.ToRead -> R.string.collection_empty_to_read_title
        Collection.Liked -> R.string.collection_empty_liked_title
    }
    val body = when (collection) {
        Collection.ToRead -> R.string.collection_empty_to_read_body
        Collection.Liked -> R.string.collection_empty_liked_body
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.screenHorizontal)
            .testTag(CollectionTestTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.emptyIcon),
        )
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Dimens.lg, bottom = Dimens.sm),
            )
            Text(
                text = stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(Dimens.emptyContentWidth),
            )
        }
    }
}

internal fun Collection.titleRes(): Int = when (this) {
    Collection.ToRead -> R.string.tab_to_read
    Collection.Liked -> R.string.tab_liked
}

object CollectionTestTags {
    const val LIST = "collection:list"
    const val ENTRY = "collection:entry"
    const val EMPTY = "collection:empty"
}
