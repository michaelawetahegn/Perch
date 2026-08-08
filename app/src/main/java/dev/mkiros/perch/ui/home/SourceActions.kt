package dev.mkiros.perch.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.mkiros.perch.R
import dev.mkiros.perch.ui.theme.Dimens

/**
 * What a long press on a drawer source offers (DESIGN.md §5, U06): rename, move to another
 * folder, or remove.
 *
 * Three steps rather than one, because they are not equally reversible. Rename and move
 * are dialogs the reader can just cancel out of; remove takes the source's entries with
 * it via `ON DELETE CASCADE` and there is no undo for that, so it asks again by name.
 * (Mark-all-read *does* get an undo — that is T26 — because it is cheap to restore.)
 */
@Composable
fun SourceActionsDialog(
    sourceTitle: String,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = sourceTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column {
                ActionRow(
                    icon = Icons.Default.DriveFileRenameOutline,
                    label = stringResource(R.string.source_action_rename),
                    testTag = SourceActionTestTags.RENAME,
                    onClick = onRename,
                )
                ActionRow(
                    icon = Icons.AutoMirrored.Filled.DriveFileMove,
                    label = stringResource(R.string.source_action_move),
                    testTag = SourceActionTestTags.MOVE,
                    onClick = onMove,
                )
                ActionRow(
                    icon = Icons.Default.DeleteOutline,
                    label = stringResource(R.string.source_action_remove),
                    testTag = SourceActionTestTags.REMOVE,
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onRemove,
                )
            }
        },
        confirmButton = {},
        dismissButton = { CancelButton(onDismiss) },
    )
}

/**
 * Confirmation before an unrecoverable delete. It names the source rather than saying
 * "this source", so a mis-aimed long press is caught here and not after the fact.
 */
@Composable
fun RemoveSourceDialog(
    sourceTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_remove_title, sourceTitle)) },
        text = { Text(stringResource(R.string.source_remove_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(SourceActionTestTags.REMOVE_CONFIRM),
            ) {
                Text(
                    text = stringResource(R.string.source_remove_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = { CancelButton(onDismiss) },
    )
}

/**
 * Renaming is display-only: it writes `customTitle` and never touches the title the feed
 * publishes for itself, which is what the next refresh overwrites. Emptying the field is
 * therefore not an error but the way back — the repository stores null and the drawer
 * falls back to [publishedTitle], which is what the hint says.
 */
@Composable
fun RenameSourceDialog(
    customTitle: String?,
    publishedTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(customTitle.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.source_rename_label)) },
                supportingText = {
                    Text(stringResource(R.string.source_rename_hint, publishedTitle))
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceActionTestTags.RENAME_FIELD),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                modifier = Modifier.testTag(SourceActionTestTags.RENAME_CONFIRM),
            ) {
                Text(stringResource(R.string.source_rename_confirm))
            }
        },
        dismissButton = { CancelButton(onDismiss) },
    )
}

@Composable
private fun CancelButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(SourceActionTestTags.CANCEL)) {
        Text(stringResource(R.string.action_cancel))
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    ListItem(
        headlineContent = { Text(text = label, color = tint) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(Dimens.icon),
            )
        },
        // The dialog already paints its own container; a ListItem that painted a second
        // surface on top of it would show as a band across the body.
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag(testTag),
    )
}

/**
 * Handles for the dialog buttons. Every one of these labels appears in more than one of
 * the three dialogs ("Remove" both chooses and confirms, "Cancel" is in all three), so
 * matching them by text would be matching on a coincidence.
 */
object SourceActionTestTags {
    const val RENAME = "source:action:rename"
    const val MOVE = "source:action:move"
    const val REMOVE = "source:action:remove"
    const val RENAME_FIELD = "source:rename:field"
    const val RENAME_CONFIRM = "source:rename:confirm"
    const val REMOVE_CONFIRM = "source:remove:confirm"
    const val CANCEL = "source:cancel"
}
