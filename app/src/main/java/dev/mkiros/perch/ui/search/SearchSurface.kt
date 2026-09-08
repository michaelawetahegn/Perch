package dev.mkiros.perch.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import dev.mkiros.perch.R
import dev.mkiros.perch.ui.home.PagedEntryList
import dev.mkiros.perch.ui.theme.Dimens

/**
 * Finding an article by remembering a word from it (#28).
 *
 * Drawn *over* the surface it was opened from rather than navigated to (§0.8), which is
 * what makes "the search I opened on Liked" a thing that exists at all: the list behind it
 * is never torn down, so leaving search is not a restore, and the scope the reader was
 * already in comes along without being re-chosen. The shell owns the state and the back
 * chain's [dev.mkiros.perch.ui.nav.BackStep.LeaveSearch] rung; this screen writes to that
 * one state and reads nothing else.
 *
 * Three things the reader can do here and no more: type, widen, leave. There is
 * deliberately no filter panel — §0.8 settled that one action ("Search everything") is the
 * whole scope control, because every other narrowing is already reachable by opening the
 * search from the list that means it.
 *
 * @param state the hoisted question. Null is "no search open"; this composable draws
 *   nothing then, and writes null into it when the reader leaves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSurface(
    viewModel: SearchViewModel,
    state: MutableState<SearchState?>,
    onOpenEntry: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = state.value ?: return
    val results = viewModel.results.collectAsLazyPagingItems()
    val focusRequester = remember { FocusRequester() }

    // Answered here rather than only by the root chain, for the reason the drawer's and the
    // image viewer's rungs are: `NavController` registers its own callback when the graph is
    // composed, so on To-Read or Liked it is *later* in the dispatcher than the shell's
    // handler and pops the tab first — back out of a search on Liked would land on the Feed.
    // Composed deeper still, this one is reached before either.
    // `BackStep.LeaveSearch` keeps modelling the rung so the policy stays legible as an
    // order; it is not what runs.
    BackHandler { state.value = null }

    // The one writer, the same shape as home's `LaunchedEffect(homeScope.value)`: the
    // shell owns the question and the view-model is told, never asked.
    LaunchedEffect(current) { viewModel.ask(current) }

    // The reader tapped a magnifier; they meant to type. Anything less than a focused
    // field with the keyboard up is a second tap for no reason.
    //
    // After a frame, not straight away: a `LaunchedEffect` runs when the composition is
    // applied, which is before the field's focus node has been attached to the tree, and
    // requesting focus on a node that is not there yet throws rather than waiting.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = current.query,
                        onValueChange = { state.value = current.asking(it) },
                        placeholder = {
                            Text(
                                text = current.label
                                    ?.let { stringResource(R.string.search_hint_in, it) }
                                    ?: stringResource(R.string.search_hint),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        // The field *is* the title, so it must not look like a control
                        // dropped into the bar: no container, no indicator, nothing but
                        // the text and the cursor sitting where the title would have been.
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag(SearchTestTags.FIELD),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { state.value = null },
                        modifier = Modifier.testTag(SearchTestTags.CLOSE),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.search_leave),
                            modifier = Modifier.size(Dimens.icon),
                        )
                    }
                },
                actions = {
                    if (current.query.isNotEmpty()) {
                        IconButton(
                            onClick = { state.value = current.asking("") },
                            modifier = Modifier.testTag(SearchTestTags.CLEAR),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.search_clear),
                                modifier = Modifier.size(Dimens.icon),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // §0.8's only scope control, and only where there is something to widen. A
            // strip rather than an overflow item because it is also the *label*: it is how
            // the reader learns that the list they are looking at is not everything.
            if (current.isNarrowed) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.sm),
                ) {
                    TextButton(
                        onClick = { state.value = current.widened() },
                        modifier = Modifier.testTag(SearchTestTags.WIDEN),
                    ) {
                        Text(stringResource(R.string.search_everything))
                    }
                }
            }

            when {
                // Nothing asked yet. DESIGN.md §7: one empty state per cause, and "you
                // have not typed anything" is not "there is nothing to find".
                !current.isAsking -> Prompt(
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.search_prompt_title),
                    body = stringResource(R.string.search_prompt_body),
                    tag = SearchTestTags.PROMPT,
                )
                // The first page of a question is in flight. Nothing is drawn: these
                // answer in a frame, and an empty state that flashes between two full
                // lists reads as a bug (home's rule, U07a).
                results.itemCount == 0 && results.loadState.refresh is LoadState.Loading -> Unit
                results.itemCount == 0 -> Prompt(
                    icon = Icons.Default.SearchOff,
                    title = stringResource(R.string.search_no_results_title, current.query),
                    body = stringResource(R.string.search_no_results_body),
                    tag = SearchTestTags.EMPTY,
                )
                else -> PagedEntryList(
                    entries = results,
                    nowMillis = viewModel.nowMillis,
                    rowTag = SearchTestTags.RESULT,
                    onOpenEntry = onOpenEntry,
                    modifier = Modifier.testTag(SearchTestTags.LIST),
                    animate = false,
                )
            }
        }
    }
}

/**
 * Both of search's empty states, which differ only in what they say (DESIGN.md §7).
 *
 * A `LazyColumn` holding one full-size item rather than a plain `Column`, for V03/#6's
 * reason — it is the shape every empty state in Perch has, and keeping it means this one
 * cannot be the odd one out if a pull gesture is ever added here.
 */
@Composable
private fun Prompt(icon: ImageVector, title: String, body: String, tag: String) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                modifier = Modifier
                    .fillParentMaxSize()
                    .padding(horizontal = Dimens.screenHorizontal)
                    .testTag(tag),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimens.emptyIcon),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Dimens.lg, bottom = Dimens.sm),
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(Dimens.emptyContentWidth),
                )
            }
        }
    }
}

/**
 * Handles for the nodes whose text is ambiguous or absent — the field has no label of its
 * own, and the two empty states differ by their sentence rather than by their shape.
 */
object SearchTestTags {
    const val FIELD = "search:field"
    const val CLOSE = "search:close"
    const val CLEAR = "search:clear"

    /** §0.8's one scope control, so a test asserting its absence asserts a rule. */
    const val WIDEN = "search:widen"
    const val LIST = "search:list"
    const val RESULT = "search:result"

    /** Nothing typed yet. Separate from [EMPTY] because they have separate causes. */
    const val PROMPT = "search:prompt"
    const val EMPTY = "search:empty"
}
