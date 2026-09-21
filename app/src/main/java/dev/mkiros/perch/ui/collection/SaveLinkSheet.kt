package dev.mkiros.perch.ui.collection

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkiros.perch.R
import dev.mkiros.perch.data.repo.SaveLinkFailure
import dev.mkiros.perch.ui.source.DismissWhenDone
import dev.mkiros.perch.ui.source.UrlFormContent

/**
 * The save-link sheet (PLAN-6 §0.4, Y04): one text field, one primary button — the same
 * shape as [dev.mkiros.perch.ui.source.AddSourceSheet], and since D25 literally the same
 * [UrlFormContent], one step shorter (§0.4: there is nothing to confirm, since saving a
 * link never subscribes to anything).
 *
 * The sheet closes itself once a link has been saved; the reader never dismisses a sheet
 * that has already done its work.
 *
 * S02/#33: nor does the reader dismiss one that is still doing it. Fetching a page takes a
 * round trip, and every way out of a sheet — the scrim, a swipe, and the settle to Hidden
 * that follows the IME collapsing on `ImeAction.Go` — used to close it mid-flight and
 * [SaveLinkViewModel.reset] the spinner and the not-yet-arrived failure away. Both routes
 * now ask [SaveLinkViewModel.onDismissRequest] first, so a save either finishes and closes
 * the sheet or leaves its reason on screen. That rule is this container's alone; Add Source
 * has nothing in flight to protect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveLinkSheet(
    viewModel: SaveLinkViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** S02/#33: the entry just saved, so the list behind can say what it got. */
    onSaved: (Long) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // A swipe never reaches onDismissRequest — it settles the sheet Hidden first — so the
    // rule has to be refused here too, or a mid-save swipe leaves an invisible sheet up.
    val sheetState = rememberModalBottomSheetState(
        confirmValueChange = { value -> value != SheetValue.Hidden || state.canDismiss },
    )

    DismissWhenDone(
        resultId = state.savedEntryId,
        onResult = onSaved,
        reset = viewModel::reset,
        onDismiss = onDismiss,
    )

    ModalBottomSheet(
        onDismissRequest = {
            if (viewModel.onDismissRequest()) onDismiss()
        },
        sheetState = sheetState,
        modifier = modifier,
    ) {
        SaveLinkSheetContent(
            state = state,
            onUrlChange = viewModel::onUrlChange,
            onSubmit = viewModel::submit,
            onSubmitDocument = viewModel::submitDocument,
        )
    }
}

/**
 * The sheet's contents, independent of the container they sit in — the same split
 * [dev.mkiros.perch.ui.source.AddSourceSheetContent] uses, and for the same reason: it
 * lets a test drive the real paste → save path without a bottom sheet's animation in the
 * way. With nothing to confirm, Save Link is the shared form and nothing else.
 */
@Composable
fun SaveLinkSheetContent(
    state: SaveLinkUiState,
    onUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSubmitDocument: (Uri, String?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onSubmitDocument(uri, null)
        }
    }

    UrlFormContent(
        title = stringResource(R.string.save_link_title),
        fieldLabel = stringResource(R.string.save_link_field_label),
        url = state.url,
        onUrlChange = onUrlChange,
        error = state.error?.message(),
        submitLabel = stringResource(R.string.save_link_submit),
        canSubmit = state.canSubmit,
        isBusy = state.isBusy,
        onSubmit = onSubmit,
        fieldTag = SaveLinkTestTags.URL_FIELD,
        errorTag = SaveLinkTestTags.ERROR,
        submitTag = SaveLinkTestTags.SUBMIT,
        modifier = modifier,
        belowField = {
            androidx.compose.material3.TextButton(
                onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.testTag(SaveLinkTestTags.CHOOSE_FILE),
            ) {
                androidx.compose.material3.Text(stringResource(R.string.save_link_choose_file))
            }
        },
    )
}

/**
 * What the sheet says when a paste did not become a saved entry. [SaveLinkFailure.IsFeed]
 * gets its own phrasing rather than the repository's — this is where §0.4's "say so and
 * offer to subscribe instead" is actually said, since the repository layer only knows the
 * address is a feed, not that the drawer is where subscribing happens.
 */
@Composable
private fun SaveLinkFailure.message(): String = when (this) {
    is SaveLinkFailure.IsFeed -> stringResource(R.string.save_link_error_is_feed)
    is SaveLinkFailure.Unreachable ->
        message ?: stringResource(R.string.save_link_error_unreachable)
    is SaveLinkFailure.NotDocument -> stringResource(R.string.save_link_error_not_document)
}

/** Handles for the nodes a test drives. */
object SaveLinkTestTags {
    const val URL_FIELD = "save-link:url"
    const val SUBMIT = "save-link:submit"
    const val ERROR = "save-link:error"
    const val CHOOSE_FILE = "save-link:choose-file"
}
