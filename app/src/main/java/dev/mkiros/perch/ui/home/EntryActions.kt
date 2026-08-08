package dev.mkiros.perch.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.mkiros.perch.R
import dev.mkiros.perch.data.db.EntryListItem
import dev.mkiros.perch.ui.theme.Dimens

/**
 * What one row can have done to it (PLAN-2 §0, U09): filed under *Read later*, liked,
 * marked read or unread, or shared.
 *
 * A sheet rather than a dialog, unlike the drawer's source actions: none of these is
 * destructive or needs confirming, three of the four are *toggles*, and a sheet rising
 * from the bottom is the gesture-adjacent surface for something the reader reached by
 * long-pressing a row halfway down the screen.
 *
 * Every toggle says which way it is about to go — *Save for later* against *Remove from
 * Read later* — rather than showing one label with a state indicator beside it. A menu
 * item is a verb; a verb that means one of two things depending on a checkmark is a
 * question the reader has to answer before they can press it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryActionsSheet(
    item: EntryListItem,
    onToggleSaved: () -> Unit,
    onToggleLiked: () -> Unit,
    onToggleRead: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        modifier = Modifier.testTag(EntryActionTestTags.SHEET),
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.screenHorizontal, vertical = Dimens.sm),
            )
            ActionRow(
                icon = if (item.isSaved) {
                    Icons.Default.PlaylistRemove
                } else {
                    Icons.AutoMirrored.Filled.LibraryBooks
                },
                label = stringResource(
                    if (item.isSaved) R.string.entry_action_unsave else R.string.entry_action_save,
                ),
                testTag = EntryActionTestTags.SAVE,
                onClick = onToggleSaved,
            )
            ActionRow(
                icon = if (item.isStarred) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                label = stringResource(
                    if (item.isStarred) R.string.entry_action_unlike else R.string.entry_action_like,
                ),
                testTag = EntryActionTestTags.LIKE,
                onClick = onToggleLiked,
            )
            ActionRow(
                icon = if (item.isRead) {
                    Icons.Default.MarkEmailUnread
                } else {
                    Icons.Default.MarkEmailRead
                },
                label = stringResource(
                    if (item.isRead) {
                        R.string.entry_action_mark_unread
                    } else {
                        R.string.entry_action_mark_read
                    },
                ),
                testTag = EntryActionTestTags.READ,
                onClick = onToggleRead,
            )
            ActionRow(
                icon = Icons.Default.Share,
                label = stringResource(R.string.entry_action_share),
                testTag = EntryActionTestTags.SHARE,
                onClick = onShare,
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(Dimens.icon),
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        // `ListItem` has no onClick slot, so the gesture is a modifier — and it goes on the
        // same node as the tag, or a test would address a node that answers nothing.
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag),
    )
}

/**
 * Sharing is the one action here that leaves Perch, so it is the one that has to survive a
 * device with nowhere to send it: an `ACTION_SEND` with no handler throws, and crashing out
 * of a reading list because a phone has no share targets is not a trade worth making.
 *
 * The link is what gets shared, with the title as the subject — a reader forwarding an
 * article means the article, not Perch's copy of its text.
 */
fun shareEntry(context: Context, title: String, link: String?) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, link ?: title)
    }
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (_: ActivityNotFoundException) {
        // Nothing on the device can receive it. There is nothing useful to say.
    }
}

object EntryActionTestTags {
    const val SHEET = "entry:actions"
    const val SAVE = "entry:actions:save"
    const val LIKE = "entry:actions:like"
    const val READ = "entry:actions:read"
    const val SHARE = "entry:actions:share"
}
