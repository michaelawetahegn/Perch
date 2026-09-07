package dev.mkiros.perch.ui.collection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkiros.perch.R
import dev.mkiros.perch.data.repo.SaveLinkFailure
import dev.mkiros.perch.ui.theme.Dimens

/**
 * The save-link sheet (PLAN-6 §0.4, Y04): one text field, one primary button — the same
 * shape as [dev.mkiros.perch.ui.source.AddSourceSheet], one step shorter (§0.4: there is
 * nothing to confirm, since saving a link never subscribes to anything).
 *
 * The sheet closes itself once a link has been saved; the reader never dismisses a sheet
 * that has already done its work.
 *
 * S02/#33: nor does the reader dismiss one that is still doing it. Fetching a page takes a
 * round trip, and every way out of a sheet — the scrim, a swipe, and the settle to Hidden
 * that follows the IME collapsing on `ImeAction.Go` — used to close it mid-flight and
 * [SaveLinkViewModel.reset] the spinner and the not-yet-arrived failure away. Both routes
 * now ask [SaveLinkViewModel.onDismissRequest] first, so a save either finishes and closes
 * the sheet or leaves its reason on screen.
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

    LaunchedEffect(state.savedEntryId) {
        val savedEntryId = state.savedEntryId
        if (savedEntryId != null) {
            // Read before reset(), the same as AddSourceSheet's onAdded: the host's one
            // chance to name what landed, behind the sheet's own close animation.
            onSaved(savedEntryId)
            viewModel.reset()
            onDismiss()
        }
    }

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
        )
    }
}

/**
 * The sheet's contents, independent of the container they sit in — the same split
 * [dev.mkiros.perch.ui.source.AddSourceSheetContent] uses, and for the same reason: it
 * lets a test drive the real paste → save path without a bottom sheet's animation in the
 * way.
 */
@Composable
fun SaveLinkSheetContent(
    state: SaveLinkUiState,
    onUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Dimens.screenHorizontal)
            .padding(bottom = Dimens.xl),
    ) {
        Text(
            text = stringResource(R.string.save_link_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.size(Dimens.lg))

        OutlinedTextField(
            value = state.url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.save_link_field_label)) },
            singleLine = true,
            isError = state.error != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SaveLinkTestTags.URL_FIELD),
        )

        state.error?.let { error ->
            Spacer(modifier = Modifier.size(Dimens.sm))
            Text(
                text = error.message(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(SaveLinkTestTags.ERROR),
            )
        }

        Spacer(modifier = Modifier.size(Dimens.xl))
        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SaveLinkTestTags.SUBMIT),
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(
                    strokeWidth = Dimens.hairline,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(Dimens.buttonSpinner),
                )
            } else {
                Text(stringResource(R.string.save_link_submit))
            }
        }
    }
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
}

/** Handles for the nodes a test drives. */
object SaveLinkTestTags {
    const val URL_FIELD = "save-link:url"
    const val SUBMIT = "save-link:submit"
    const val ERROR = "save-link:error"
}
