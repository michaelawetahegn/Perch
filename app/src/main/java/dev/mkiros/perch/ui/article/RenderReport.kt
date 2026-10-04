package dev.mkiros.perch.ui.article

import android.net.Uri
import androidx.annotation.StringRes
import dev.mkiros.perch.R

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
