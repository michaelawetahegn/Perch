package dev.mkiros.perch.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
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
import dev.mkiros.perch.R
import dev.mkiros.perch.data.db.EntryListItem
import dev.mkiros.perch.data.repo.PerchPaging
import dev.mkiros.perch.ui.theme.Dimens

/**
 * Whether a folder header is due above this row (PLAN-2 §0, U07a).
 *
 * The whole question is answered by two adjacent rows, never by the list: the folder a row
 * belongs to is a column on the row, and the query orders by folder first, so a header
 * falls exactly where the folder changes. That is what survives paging — [previous] is the
 * row before this one *in the list*, not in its page, so a page boundary in the middle of
 * a folder is invisible and the header does not reappear at the top of every page.
 *
 * @param previous the row above, or null when this row is the first one loaded.
 */
internal fun startsSection(previous: EntryListItem?, item: EntryListItem): Boolean =
    previous == null || previous.folderId != item.folderId

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
