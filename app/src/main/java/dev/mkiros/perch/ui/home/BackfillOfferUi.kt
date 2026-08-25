package dev.mkiros.perch.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.mkiros.perch.R
import dev.mkiros.perch.ui.theme.Dimens

/**
 * PLAN-7 §0.3's offer: how many pages Perch would fetch, named before anything is fetched,
 * so the reader agrees to a stated cost rather than to a vague "get more history".
 */
@Composable
fun BackfillOfferDialog(
    pageCount: Int,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.backfill_offer_title)) },
        text = {
            Text(
                text = pluralStringResource(R.plurals.backfill_offer_body, pageCount, pageCount),
                modifier = Modifier.testTag(BackfillTestTags.OFFER_BODY),
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept, modifier = Modifier.testTag(BackfillTestTags.OFFER_ACCEPT)) {
                Text(stringResource(R.string.backfill_offer_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline, modifier = Modifier.testTag(BackfillTestTags.OFFER_DECLINE)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        modifier = Modifier.testTag(BackfillTestTags.OFFER_DIALOG),
    )
}

/**
 * §0.3's "progress while it runs, and a way to stop it" — a slim strip above the list, the
 * same band [BannerStrip] occupies, so a backfill in flight reads as one more thing Perch
 * is saying rather than a screen of its own standing between the reader and the list.
 *
 * Dismissing it (the terminal state's `×`) only stops the strip watching — the run itself
 * is [dev.mkiros.perch.work.BackfillWorker]'s and, mid-flight, keeps going regardless
 * (§0.3: it survives the reader leaving the screen).
 */
@Composable
fun BackfillProgressStrip(
    progress: BackfillProgress,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = Dimens.md, vertical = Dimens.sm)
            .testTag(BackfillTestTags.PROGRESS_STRIP),
    ) {
        Text(
            text = if (progress.isRunning) {
                stringResource(R.string.backfill_progress, progress.done, progress.total)
            } else {
                stringResource(R.string.backfill_progress_done)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f).testTag(BackfillTestTags.PROGRESS_LABEL),
        )
        if (progress.isRunning) {
            IconButton(onClick = onCancel, modifier = Modifier.testTag(BackfillTestTags.PROGRESS_STOP)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.backfill_progress_stop),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        } else {
            IconButton(onClick = onDismiss, modifier = Modifier.testTag(BackfillTestTags.PROGRESS_DISMISS)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/**
 * §0.4: the source's honest reach, so "All Time" stops implying "all history" — shown only
 * scoped to one source with [TimeFilter.AllTime] active, the one place the confusion #21
 * was really about lives.
 */
@Composable
fun ReachSentence(oldestPublishedAt: Long, nowMillis: Long) {
    Text(
        text = stringResource(
            R.string.backfill_reach_sentence,
            RelativeTime.format(oldestPublishedAt, nowMillis),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = Dimens.screenHorizontal, vertical = Dimens.xs)
            .testTag(BackfillTestTags.REACH_SENTENCE),
    )
}

object BackfillTestTags {
    const val OFFER_DIALOG = "backfill:offer"
    const val OFFER_BODY = "backfill:offer:body"
    const val OFFER_ACCEPT = "backfill:offer:accept"
    const val OFFER_DECLINE = "backfill:offer:decline"
    const val PROGRESS_STRIP = "backfill:progress"
    const val PROGRESS_LABEL = "backfill:progress:label"
    const val PROGRESS_STOP = "backfill:progress:stop"
    const val PROGRESS_DISMISS = "backfill:progress:dismiss"
    const val REACH_SENTENCE = "backfill:reach"
}
