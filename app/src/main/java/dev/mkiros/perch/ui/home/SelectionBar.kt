package dev.mkiros.perch.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.mkiros.perch.R
import dev.mkiros.perch.ui.theme.Dimens

/**
 * The drawer's header while a selection is live (PLAN-2 U09a).
 *
 * It replaces "All sources" rather than sitting above it, which is what makes selection
 * mode legible as a *mode*: the drawer stops being a place to navigate to and becomes a
 * place to act on, and the only two ways out of it are the close action and back.
 *
 * Rename and move appear only at exactly one selected row. They are U06's actions, which
 * used to live behind the long press that now starts a selection — a batch rename means
 * nothing, but a reader who long-pressed one source to rename it must still be one tap
 * away from doing so.
 */
@Composable
fun SelectionBar(
    selection: DrawerSelection,
    onLeave: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val single = selection.count == 1
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = Dimens.md)
            .fillMaxWidth()
            .height(Dimens.drawerRowHeight)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .testTag(SelectionTestTags.BAR),
    ) {
        BarAction(
            icon = Icons.Default.Close,
            label = stringResource(R.string.selection_close),
            testTag = SelectionTestTags.CLOSE,
            onClick = onLeave,
        )
        Text(
            text = LocalContext.current.resources.getQuantityString(
                R.plurals.selection_count,
                selection.count,
                selection.count,
            ),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Dimens.xs)
                .testTag(SelectionTestTags.COUNT),
        )
        if (single) {
            BarAction(
                icon = Icons.Default.DriveFileRenameOutline,
                label = stringResource(R.string.selection_rename),
                testTag = SelectionTestTags.RENAME,
                onClick = onRename,
            )
        }
        if (single && selection is DrawerSelection.Sources) {
            BarAction(
                icon = Icons.AutoMirrored.Filled.DriveFileMove,
                label = stringResource(R.string.selection_move),
                testTag = SelectionTestTags.MOVE,
                onClick = onMove,
            )
        }
        BarAction(
            icon = Icons.Default.DeleteOutline,
            label = stringResource(R.string.selection_delete),
            testTag = SelectionTestTags.DELETE,
            onClick = onDelete,
        )
    }
}

@Composable
private fun BarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(testTag)) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(Dimens.icon),
        )
    }
}

/**
 * The one delete in Perch that cannot be taken back, so the only one that asks first.
 *
 * Deleting sources cascades to their entries — **including the saved and liked ones**,
 * which is precisely the loss U04 exists to prevent. So when the batch holds any, the
 * dialog counts them out loud: "this cannot be undone" is a warning about the abstract,
 * and "9 saved or liked articles will be deleted" is a warning about the reader's own
 * afternoon.
 */
@Composable
fun DeleteSourcesDialog(
    sourceCount: Int,
    savedOrLikedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalContext.current.resources
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                resources.getQuantityString(
                    R.plurals.sources_delete_title,
                    sourceCount,
                    sourceCount,
                ),
            )
        },
        text = {
            Column {
                Text(
                    resources.getQuantityString(
                        R.plurals.sources_delete_body,
                        sourceCount,
                    ),
                )
                if (savedOrLikedCount > 0) {
                    Text(
                        text = resources.getQuantityString(
                            R.plurals.sources_delete_curated,
                            savedOrLikedCount,
                            savedOrLikedCount,
                        ),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(top = Dimens.sm)
                            .testTag(SelectionTestTags.DELETE_CURATED),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(SelectionTestTags.DELETE_CONFIRM),
            ) {
                Text(
                    text = stringResource(R.string.sources_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(SelectionTestTags.DELETE_CANCEL),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * Handles for selection mode. Every label here is on screen elsewhere too — "Rename" is
 * also in the source dialog, "Delete" is also on a folder's overflow — so nothing in these
 * tests may be addressed by its text.
 */
object SelectionTestTags {
    const val BAR = "selection:bar"
    const val COUNT = "selection:count"
    const val CLOSE = "selection:close"
    const val RENAME = "selection:rename"
    const val MOVE = "selection:move"
    const val DELETE = "selection:delete"
    const val DELETE_CONFIRM = "selection:delete:confirm"
    const val DELETE_CANCEL = "selection:delete:cancel"
    const val DELETE_CURATED = "selection:delete:curated"

    fun sourceCheckbox(feedId: Long) = "selection:source:$feedId"

    fun folderCheckbox(folderId: Long) = "selection:folder:$folderId"
}
