package dev.mkiros.perch.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import dev.mkiros.perch.R
import dev.mkiros.perch.data.db.EntryListItem
import dev.mkiros.perch.ui.theme.Dimens

/**
 * One entry in the reading list (DESIGN.md §5).
 *
 * ```
 * ● Title (≤3 lines)
 *   Snippet (≤2 lines)
 *   Source · 3h ago          [thumb]
 * ```
 *
 * The unread dot is `primary` and is the only coloured thing on the row (§2); a read row
 * drops its title to `onSurfaceVariant` and loses the dot, and that contrast delta is the
 * entire read/unread affordance — no strikethrough, no opacity, no badge. The dot's
 * gutter is reserved whether or not the dot is drawn, so titles stay on one left edge.
 */
@Composable
fun EntryRow(
    item: EntryListItem,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.rowHorizontal, vertical = Dimens.rowVertical),
    ) {
        Box(modifier = Modifier.width(Dimens.unreadGutter)) {
            if (!item.isRead) {
                Box(
                    modifier = Modifier
                        .size(Dimens.unreadDot)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (item.isRead) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = TITLE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            item.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Spacer(modifier = Modifier.size(Dimens.xs))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = SNIPPET_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.size(Dimens.xs))
            Text(
                text = stringResource(
                    R.string.home_entry_meta,
                    item.sourceTitle,
                    RelativeTime.format(item.publishedAt, now),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        item.imageUrl?.let { url ->
            Spacer(modifier = Modifier.size(Dimens.md))
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(Dimens.thumbnail)
                    .clip(RoundedCornerShape(Dimens.imageCorner))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                alignment = Alignment.Center,
            )
        }
    }
}

private const val TITLE_MAX_LINES = 3
private const val SNIPPET_MAX_LINES = 2
