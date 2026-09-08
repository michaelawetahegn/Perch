package dev.mkiros.perch.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import dev.mkiros.perch.R
import dev.mkiros.perch.data.db.EntryListItem
import dev.mkiros.perch.data.repo.PerchPaging
import dev.mkiros.perch.ui.theme.Dimens

/**
 * The one paged list of entries (U07a's shape), drawn by the Feed, by To-Read and Liked,
 * and by search.
 *
 * `LazyColumn` was already composing only what is on screen; what it was not doing was
 * *loading* only that. The rows arrive a page at a time and the reader is meant never to
 * find out: the only visible difference is the small footer [pagedFooter] puts at the far
 * end. There are no section headers on any of these lists (W03), so the only neighbour
 * question left is where the last rule goes, and a rule goes between every pair and never
 * under the last row.
 *
 * Four surfaces, four differences, and they are exactly the parameters:
 *  - **[rowTag]**, because each list's tests address their own rows.
 *  - **[listState]**, hoisted where a tab switch would otherwise throw the scroll offset
 *    away (the Feed's lives in `PerchNavHost`, U09), remembered here otherwise — the
 *    default is saveable, so the reader comes back to the row they left rather than to
 *    the top.
 *  - **[onLongPress]**, absent on search: a result is somewhere the reader is passing
 *    through on the way to one article, and the sheet's actions are all reachable on the
 *    article itself or on the list the search came from.
 *  - **[animate]**, on the lists a row can *leave*. It is what makes un-saving read as the
 *    row going rather than as the list blinking: without it the list re-emits and the row
 *    it dropped simply is not there on the next frame. Search results never leave under
 *    the reader, so search has nothing to animate.
 *
 * The list's own test tag rides on [modifier], since only two of the three want one.
 */
@Composable
internal fun PagedEntryList(
    entries: LazyPagingItems<EntryListItem>,
    nowMillis: Long,
    rowTag: String,
    onOpenEntry: (Long) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onLongPress: ((Long) -> Unit)? = null,
    animate: Boolean = true,
) {
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        items(count = entries.itemCount, key = entries.itemKey { it.id }) { index ->
            val item = entries[index] ?: return@items
            Column(modifier = if (animate) Modifier.animateItem() else Modifier) {
                EntryRow(
                    item = item,
                    now = nowMillis,
                    onClick = { onOpenEntry(item.id) },
                    onLongClick = onLongPress?.let { { it(item.id) } },
                    modifier = Modifier.testTag(rowTag),
                )
                if (index + 1 < entries.itemCount) {
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
 * What a paged list puts at its far end (U07a).
 *
 * Two states and no third. While the next page is in flight there is a small footer
 * indicator — never a full-screen spinner, because the rows above it are perfectly
 * readable and replacing them would punish the reader for scrolling. When there is no
 * next page the list says so once, quietly, and stops: an endless spinner at the bottom of
 * a finished list is a promise the list cannot keep.
 *
 * The end marker is earned rather than automatic. A list shorter than one page never
 * paged, so its end is simply where it stops — stamping "that's everything" under four
 * rows explains a mechanism the reader has not met.
 */
internal fun <T : Any> LazyListScope.pagedFooter(entries: LazyPagingItems<T>) {
    val append = entries.loadState.append
    when {
        append is LoadState.Loading -> item(key = FOOTER_KEY) { AppendIndicator() }
        append.endOfPaginationReached && entries.itemCount >= PerchPaging.PAGE_SIZE ->
            item(key = FOOTER_KEY) { EndOfList() }
    }
}

@Composable
private fun AppendIndicator() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimens.lg)
            .testTag(PagedListTestTags.APPENDING),
    ) {
        CircularProgressIndicator(
            strokeWidth = Dimens.appendIndicatorStroke,
            modifier = Modifier.size(Dimens.appendIndicator),
        )
    }
}

@Composable
private fun EndOfList() {
    Text(
        text = stringResource(R.string.list_end),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.xl, horizontal = Dimens.rowHorizontal)
            .testTag(PagedListTestTags.END),
    )
}

/** One key for both, so swapping the spinner for the marker is not a list insertion. */
private const val FOOTER_KEY = "paged:footer"

object PagedListTestTags {
    const val APPENDING = "paged:appending"
    const val END = "paged:end"
}
