package dev.mkiros.perch.ui.article

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkiros.perch.R
import dev.mkiros.perch.ui.theme.ArticleType
import dev.mkiros.perch.ui.theme.Dimens

/**
 * One entry, read in the app (DESIGN.md §8).
 *
 * The scaffold is the furniture — back, and the headline over the body. T25 replaces the
 * body with the `ArticleBlock` renderer and adds "Open in browser", mark-unread and share;
 * the type it will use ([ArticleType], serif) is already what the headline renders in, so
 * the reading surface never drifts back to the sans furniture face.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleScreen(
    viewModel: ArticleViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            modifier = Modifier.size(Dimens.icon),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Dimens.screenHorizontal),
        ) {
            when (val current = state) {
                ArticleUiState.Loading -> Unit
                ArticleUiState.Missing -> Text(
                    text = stringResource(R.string.article_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                is ArticleUiState.Loaded -> Column {
                    Text(text = current.title, style = ArticleType.headline)
                    current.summary?.let { summary ->
                        Text(
                            text = summary,
                            style = ArticleType.body,
                            modifier = Modifier.padding(top = Dimens.paragraphSpacing),
                        )
                    }
                }
            }
        }
    }
}
