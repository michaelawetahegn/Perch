package dev.mkiros.perch.ui.source

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.mkiros.perch.ui.theme.Dimens

/**
 * The body both URL sheets are made of: a title, one field you paste an address into, the
 * reason it did not work, and one primary button that spins while it is working.
 *
 * Add Source and Save Link ask the reader for the same thing and had grown the same column
 * twice. All that ever differed is words, test tags, and what Add Source puts *between* the
 * parts — so the slots draw the folder picker and the resolved-feed confirmation exactly
 * where they already were, and neither sheet's screenshots move.
 *
 * What does not come here is S02/#33's refusal to dismiss mid-save: a sheet has two exits
 * and neither is this form's (NOTES.md). That rule stays on the Save Link container.
 */
@Composable
internal fun UrlFormContent(
    title: String,
    fieldLabel: String,
    url: String,
    onUrlChange: (String) -> Unit,
    error: String?,
    submitLabel: String,
    canSubmit: Boolean,
    isBusy: Boolean,
    onSubmit: () -> Unit,
    fieldTag: String,
    errorTag: String,
    submitTag: String,
    modifier: Modifier = Modifier,
    belowField: @Composable ColumnScope.() -> Unit = {},
    belowError: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Dimens.screenHorizontal)
            .padding(bottom = Dimens.xl),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.size(Dimens.lg))

        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text(fieldLabel) },
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(fieldTag),
        )

        belowField()

        if (error != null) {
            Spacer(modifier = Modifier.size(Dimens.sm))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(errorTag),
            )
        }

        belowError()

        Spacer(modifier = Modifier.size(Dimens.xl))
        Button(
            onClick = onSubmit,
            enabled = canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(submitTag),
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    strokeWidth = Dimens.hairline,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(Dimens.buttonSpinner),
                )
            } else {
                Text(submitLabel)
            }
        }
    }
}

/**
 * The close both sheets do for themselves. The id goes to the host *before* the reset —
 * that read is its one chance to name what arrived — then the sheet clears and dismisses.
 */
@Composable
internal fun DismissWhenDone(
    resultId: Long?,
    onResult: (Long) -> Unit,
    reset: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(resultId) {
        if (resultId != null) {
            onResult(resultId)
            reset()
            onDismiss()
        }
    }
}
