package dev.mkiros.perch.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
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
 * What a folder header's overflow offers (U06): rename, or delete.
 *
 * Only user-made folders get here at all — Uncategorized draws no overflow, because
 * `FolderRepository` refuses both actions on it and a menu whose every item is a no-op is
 * worse than no menu.
 */
@Composable
fun FolderActionsDialog(
    folderName: String,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = folderName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                DialogRow(
                    icon = Icons.Default.DriveFileRenameOutline,
                    label = stringResource(R.string.folder_action_rename),
                    testTag = FolderActionTestTags.RENAME,
                    onClick = onRename,
                )
                DialogRow(
                    icon = Icons.Default.DeleteOutline,
                    label = stringResource(R.string.folder_action_delete),
                    testTag = FolderActionTestTags.DELETE,
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        },
        confirmButton = {},
        dismissButton = { FolderCancelButton(onDismiss) },
    )
}

/**
 * Naming a folder — the same dialog for creating one and for renaming one, because they
 * ask the same question and a reader cannot tell two text fields apart anyway.
 *
 * Blank is not a name: the button is disabled rather than the dialog accepting one and
 * `FolderRepository` throwing behind it.
 */
@Composable
fun FolderNameDialog(
    title: String,
    initialName: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.folder_name_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(FolderActionTestTags.NAME_FIELD),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag(FolderActionTestTags.NAME_CONFIRM),
            ) {
                Text(stringResource(R.string.folder_name_confirm))
            }
        },
        dismissButton = { FolderCancelButton(onDismiss) },
    )
}

/**
 * Deleting a folder is not deleting its sources, and the body says so — the fear this
 * dialog exists to answer is "am I about to lose forty subscriptions", not "am I sure".
 */
@Composable
fun DeleteFolderDialog(
    folderName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_delete_title, folderName)) },
        text = { Text(stringResource(R.string.folder_delete_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(FolderActionTestTags.DELETE_CONFIRM),
            ) {
                Text(
                    text = stringResource(R.string.folder_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = { FolderCancelButton(onDismiss) },
    )
}

/**
 * Where a source lives (U06). Every folder is a row, the current one is ticked, and
 * "New folder" is the last row rather than a separate trip through the drawer — filing
 * something somewhere that does not exist yet is the common case, not the exception.
 */
@Composable
fun MoveSourceDialog(
    sourceTitle: String,
    folders: List<FolderUiItem>,
    currentFolderId: Long,
    onMove: (Long) -> Unit,
    onNewFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.folder_move_title, sourceTitle),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = Dimens.dialogListMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                folders.forEach { folder ->
                    DialogRow(
                        icon = if (folder.id == currentFolderId) {
                            Icons.Default.Check
                        } else {
                            Icons.Default.Folder
                        },
                        label = folder.name,
                        testTag = FolderActionTestTags.folderChoice(folder.id),
                        onClick = { onMove(folder.id) },
                    )
                }
                DialogRow(
                    icon = Icons.Default.CreateNewFolder,
                    label = stringResource(R.string.folder_new),
                    testTag = FolderActionTestTags.NEW_FOLDER,
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onNewFolder,
                )
            }
        },
        confirmButton = {},
        dismissButton = { FolderCancelButton(onDismiss) },
    )
}

@Composable
private fun FolderCancelButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(FolderActionTestTags.CANCEL)) {
        Text(stringResource(R.string.action_cancel))
    }
}

@Composable
private fun DialogRow(
    icon: ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    ListItem(
        headlineContent = {
            Text(text = label, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(Dimens.icon),
            )
        },
        // The dialog already paints its own container; a second surface on top of it
        // would show as a band across the body.
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag(testTag),
    )
}

/**
 * Handles for the folder dialogs. Their labels repeat across dialogs the same way the
 * source ones do ("Delete" both chooses and confirms), and a folder's name is on screen
 * in the drawer behind the dialog as well as in it.
 */
object FolderActionTestTags {
    const val RENAME = "folder:action:rename"
    const val DELETE = "folder:action:delete"
    const val DELETE_CONFIRM = "folder:delete:confirm"
    const val NAME_FIELD = "folder:name:field"
    const val NAME_CONFIRM = "folder:name:confirm"
    const val NEW_FOLDER = "folder:move:new"
    const val CANCEL = "folder:cancel"

    fun folderChoice(folderId: Long) = "folder:move:$folderId"
}
