package dev.mkiros.perch.ui.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.mkiros.perch.R

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

/**
 * Handles for the rename dialog. "Save" and "Cancel" are on screen in more than one of
 * Perch's dialogs, so matching them by text would be matching on a coincidence.
 */
object SourceActionTestTags {
    const val RENAME_FIELD = "source:rename:field"
    const val RENAME_CONFIRM = "source:rename:confirm"
    const val CANCEL = "source:cancel"
}
