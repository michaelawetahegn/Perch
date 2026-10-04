package dev.mkiros.perch.ui.article

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.mkiros.perch.R
import dev.mkiros.perch.ui.theme.Dimens

/** Where every report goes. The one place the repository's address is written down. */
private const val NEW_ISSUE_URL = "https://github.com/michaelawetahegn/Perch/issues/new"

/** The note is a hint, not an essay; past this it is cut. */
const val REPORT_NOTE_MAX = 500

/** #86 — what a reader can say is wrong with an article. [label] reads the same in the sheet and on GitHub. */
enum class RenderProblem(@StringRes val label: Int) {
    TextMissing(R.string.report_problem_text),
    ImagesBroken(R.string.report_problem_images),
    CodeOrTables(R.string.report_problem_code),
    Layout(R.string.report_problem_layout),
    Other(R.string.report_problem_other),
}

/**
 * #86 — a new-issue link on Perch's repository, prefilled from the article. Pure: [problem] is the
 * label already resolved and [version] is passed in. The `render` label comes from the template's
 * front matter, since a `labels=` parameter only works for someone with triage rights.
 */
fun renderReportUrl(
    link: String,
    source: String?,
    problem: String,
    note: String,
    version: String,
): String {
    val host = Uri.parse(link).host?.removePrefix("www.") ?: link
    val trimmed = note.trim().take(REPORT_NOTE_MAX)
    val body = buildString {
        appendLine("- **Link:** $link")
        appendLine("- **Source:** ${source ?: "unknown"}")
        appendLine("- **Problem:** $problem")
        if (trimmed.isNotEmpty()) appendLine("- **Note:** $trimmed")
        append("- **Version:** Perch $version")
    }
    return Uri.parse(NEW_ISSUE_URL).buildUpon()
        .appendQueryParameter("template", "render-report.md")
        .appendQueryParameter("title", "Render: $host — $problem")
        .appendQueryParameter("body", body)
        .build()
        .toString()
}

/**
 * #86 — the Report sheet: what is wrong, an optional note, and one button that hands the
 * prefilled link to [onOpen]. The sheet closes once it has. Nothing is chosen up front, so
 * the button waits for a row: a report that names no problem is no use to anyone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenderReportSheet(onOpen: (RenderProblem, String) -> Unit, onDismiss: () -> Unit) {
    var problem by rememberSaveable { mutableStateOf<RenderProblem?>(null) }
    var note by rememberSaveable { mutableStateOf("") }

    // Five rows, a field and a button run past half the screen; a half-open sheet would hide the button.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        RenderReportSheetContent(
            problem = problem,
            onProblemChange = { problem = it },
            note = note,
            onNoteChange = { note = it.take(REPORT_NOTE_MAX) },
            onOpen = {
                problem?.let { onOpen(it, note) }
                onDismiss()
            },
        )
    }
}

/** The sheet's contents, stateless: [RenderReportSheet] holds the choice and the note. */
@Composable
fun RenderReportSheetContent(
    problem: RenderProblem?,
    onProblemChange: (RenderProblem) -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Dimens.screenHorizontal)
            .padding(bottom = Dimens.xl)
            .testTag(ArticleTestTags.REPORT_SHEET),
    ) {
        Text(
            text = stringResource(R.string.report_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.size(Dimens.sm))
        Column(modifier = Modifier.selectableGroup()) {
            RenderProblem.entries.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.touchTarget)
                        .selectable(
                            selected = option == problem,
                            onClick = { onProblemChange(option) },
                            role = Role.RadioButton,
                        )
                        .testTag(ArticleTestTags.reportProblem(option)),
                ) {
                    // The row carries the click; the radio only draws the state.
                    RadioButton(selected = option == problem, onClick = null)
                    Spacer(modifier = Modifier.size(Dimens.md))
                    Text(
                        text = stringResource(option.label),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.size(Dimens.sm))
        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            label = { Text(stringResource(R.string.report_note_label)) },
            maxLines = 4,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ArticleTestTags.REPORT_NOTE),
        )
        Spacer(modifier = Modifier.size(Dimens.xl))
        Button(
            onClick = onOpen,
            enabled = problem != null,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ArticleTestTags.REPORT_SUBMIT),
        ) {
            Text(stringResource(R.string.report_submit))
        }
    }
}
