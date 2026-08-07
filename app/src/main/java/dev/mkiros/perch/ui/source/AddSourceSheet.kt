package dev.mkiros.perch.ui.source

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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkiros.perch.R
import dev.mkiros.perch.ui.theme.Dimens

/**
 * The add-source sheet (DESIGN.md §5): one text field, one primary button.
 *
 * Adding is confirm-then-commit. The first tap resolves — following a redirect and, for a
 * homepage, the `<link rel=alternate>` it declares — and the sheet then shows the feed's
 * own title and how many entries came back, so the reader agrees to what they are about
 * to follow rather than to what they typed. The second tap commits it.
 *
 * The sheet closes itself once a source has been added; the reader never dismisses a sheet
 * that has already done its work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSourceSheet(
    viewModel: AddSourceViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(state.addedFeedId) {
        if (state.addedFeedId != null) {
            viewModel.reset()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.reset()
            onDismiss()
        },
        sheetState = sheetState,
        modifier = modifier,
    ) {
        AddSourceSheetContent(
            state = state,
            onUrlChange = viewModel::onUrlChange,
            onSubmit = viewModel::submit,
        )
    }
}

/**
 * The sheet's contents, independent of the container they sit in — which is what lets a
 * test drive the real paste → resolve → confirm → commit path without a bottom sheet's
 * animation in the way.
 */
@Composable
fun AddSourceSheetContent(
    state: AddSourceUiState,
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
            text = stringResource(R.string.add_source_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.size(Dimens.lg))

        OutlinedTextField(
            value = state.url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.add_source_field_label)) },
            singleLine = true,
            isError = state.error != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AddSourceTestTags.URL_FIELD),
        )

        state.error?.let { error ->
            Spacer(modifier = Modifier.size(Dimens.sm))
            Text(
                text = error.message(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(AddSourceTestTags.ERROR),
            )
        }

        state.resolved?.let { resolved ->
            Spacer(modifier = Modifier.size(Dimens.lg))
            Column(modifier = Modifier.testTag(AddSourceTestTags.CONFIRMATION)) {
                Text(
                    text = resolved.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.size(Dimens.xs))
                Text(
                    text = pluralStringResource(
                        R.plurals.add_source_entry_count,
                        resolved.entryCount,
                        resolved.entryCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.size(Dimens.xl))
        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AddSourceTestTags.SUBMIT),
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(
                    strokeWidth = Dimens.hairline,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(Dimens.buttonSpinner),
                )
            } else {
                Text(
                    stringResource(
                        if (state.resolved != null) R.string.add_source_confirm
                        else R.string.add_source_resolve,
                    ),
                )
            }
        }
    }
}

/** What the sheet says when a paste did not become a source. */
@Composable
private fun AddSourceError.message(): String = when (this) {
    AddSourceError.NoFeedFound -> stringResource(R.string.add_source_error_no_feed)
    is AddSourceError.Unreachable ->
        message ?: stringResource(R.string.add_source_error_unreachable)
    is AddSourceError.AlreadySubscribed ->
        stringResource(R.string.add_source_error_duplicate, title)
}

/** Handles for the nodes a test drives; the button's label changes under it. */
object AddSourceTestTags {
    const val URL_FIELD = "add-source:url"
    const val SUBMIT = "add-source:submit"
    const val ERROR = "add-source:error"
    const val CONFIRMATION = "add-source:confirmation"
}
