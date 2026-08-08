package dev.mkiros.perch.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import dev.mkiros.perch.R
import dev.mkiros.perch.ui.theme.Dimens
import dev.mkiros.perch.ui.theme.PerchBrand
import dev.mkiros.perch.ui.theme.PerchMarkVector

/**
 * The Perch mark, in the app (U09b).
 *
 * The artwork is [PerchMarkVector] — the same paths the launcher icon is drawn from, so
 * the icon on the home screen and the mark in the drawer are one drawing and cannot drift
 * apart. It does not follow the theme: see the note on [PerchBrand].
 */
@Composable
fun PerchMark(
    modifier: Modifier = Modifier,
    size: Dp = Dimens.brandMark,
) {
    Image(
        imageVector = PerchMarkVector,
        contentDescription = null,
        modifier = modifier
            .testTag(BrandTestTags.MARK)
            .size(size),
    )
}

/**
 * The full lockup — mark, logotype, tagline — laid out horizontally rather than stacked
 * like the source art. The drawer header is a wide, short band; the stacked lockup would
 * spend three lines of it and push the subscription list below the fold.
 *
 * The lettering is the one part of the brand that follows the theme, because it is type
 * on a surface rather than artwork on a plate: `onSurface` in both, so the drawer header
 * reads as part of the drawer and not as a sticker on it.
 */
@Composable
fun PerchWordmark(modifier: Modifier = Modifier) {
    val name = stringResource(R.string.app_name)
    Row(
        modifier = modifier
            // One label for the lockup: the mark is decorative and "RSS READER" is a
            // tagline, so a reader on TalkBack hears the app's name once, not three times.
            // That label is also how a test addresses it — a test tag here would be
            // cleared along with everything else this collapses.
            .clearAndSetSemantics { contentDescription = name },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.brandGap),
    ) {
        PerchMark(size = Dimens.brandMarkSmall)
        Column {
            Text(
                text = name,
                style = PerchBrand.wordmark,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.brand_tagline),
                style = PerchBrand.tagline,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

object BrandTestTags {
    const val MARK = "brand:mark"
}
