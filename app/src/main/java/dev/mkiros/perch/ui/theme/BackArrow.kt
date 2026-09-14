package dev.mkiros.perch.ui.theme

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.mkiros.perch.R

/**
 * The top bar's "Back" — the one navigation icon a pushed screen (Settings, Article) has.
 * Search's arrow is not this: it leaves a surface rather than a screen and says so.
 */
@Composable
fun BackArrow(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
            modifier = Modifier.size(Dimens.icon),
        )
    }
}
