package dev.mkiros.perch.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.article.ArticleScreen
import dev.mkiros.perch.ui.article.ArticleViewModel
import dev.mkiros.perch.ui.collection.Collection
import dev.mkiros.perch.ui.collection.CollectionScreen
import dev.mkiros.perch.ui.collection.CollectionViewModel
import dev.mkiros.perch.ui.home.DrawerSelection
import dev.mkiros.perch.ui.home.HomeScreen
import dev.mkiros.perch.ui.home.HomeViewModel
import dev.mkiros.perch.ui.settings.SettingsScreen
import dev.mkiros.perch.ui.settings.SettingsViewModel
import dev.mkiros.perch.ui.source.AddSourceViewModel
import kotlinx.coroutines.launch

/** Every destination in the app. Three of them are tabs (U09); two are not. */
object Routes {
    const val FEED = "home"
    const val SAVED = "saved"
    const val LIKED = "liked"
    const val ARTICLE = "article/{entryId}"
    const val SETTINGS = "settings"

    const val ARG_ENTRY_ID = "entryId"

    fun article(entryId: Long): String = "article/$entryId"
}

/** DESIGN.md §6: slide + fade, 250ms, `FastOutSlowInEasing`. Nothing hand-rolled. */
private const val TRANSITION_MS = 250

/** Destinations slide a fraction of the width, not the whole way — a full slide reads as a shove. */
private const val SLIDE_DIVISOR = 4

/**
 * The whole navigation graph, and the shell around it.
 *
 * Screens get their dependencies through a ViewModel factory built from [container]; no
 * screen reaches for a singleton, which is what lets a test compose any route over an
 * in-memory database.
 *
 * **The bottom bar lives here, outside the `NavHost`** (PLAN-2 §0, U09), for two reasons.
 * It has to be able to say which tab is selected, which is a question about the back stack
 * rather than about any one screen; and it has to be *absent* on the article route, which a
 * bar composed inside a screen could not be. Tab switches carry `saveState`/`restoreState`
 * and `launchSingleTop`, so Feed → To-Read → Feed returns to the Feed that was already
 * there rather than stacking a second copy of it.
 *
 * [testTagsAsResourceId] is set once here, at the root of the main window: it publishes
 * every `Modifier.testTag` in the graph as the node's `resource-id` in the accessibility
 * tree, which is the only handle an out-of-process driver (T30's Maestro flow) has on a
 * Compose node. It does not reach dialogs or the add-source sheet: those are their own
 * windows, and their controls are addressed by their labels.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PerchNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val tab = PerchTab.ofRoute(route)
    val scope = rememberCoroutineScope()

    // Feed's drawer and Feed's scroll position are hoisted out of HomeScreen because the
    // back chain has to reason about both, and because a scroll offset that lived inside
    // the Feed composable would be torn down and restored on every tab switch — which is
    // precisely the thing §0 says must survive one.
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val feedListState = rememberLazyListState()

    // The drawer's multi-select (U09a), hoisted for the same reason: it is the first rung
    // of the back chain. `rememberSaveable` so neither a rotation nor a process death
    // quietly discards a batch the reader ticked by hand.
    val drawerSelection = rememberSaveable(stateSaver = DrawerSelection.Saver) {
        mutableStateOf<DrawerSelection>(DrawerSelection.None)
    }

    val backState = BackState(
        selectionActive = drawerSelection.value.isActive,
        overlayOpen = drawerState.isOpen,
        onArticle = route == Routes.ARTICLE,
        tab = tab ?: PerchTab.Feed,
        feedScrolled = feedListState.canScrollBackward,
    )
    val step = nextBackStep(backState)

    // One handler for the whole policy. It is disabled at `Exit`, which is what hands the
    // gesture back to the platform — the only way out of the app (§0). The overlay and
    // article rungs are the components' own (a `ModalNavigationDrawer` and the `NavHost`
    // both answer back themselves, and being composed deeper they are reached first); the
    // chain still models them so that this handler stays out of their way rather than
    // stealing a predictive-back gesture mid-swipe.
    BackHandler(enabled = step != BackStep.Exit) {
        when (step) {
            BackStep.LeaveSelection -> drawerSelection.value = DrawerSelection.None
            BackStep.CloseOverlay -> scope.launch { drawerState.close() }
            BackStep.PopArticle -> navController.popBackStack()
            BackStep.ReturnToFeed -> selectTab(navController, PerchTab.Feed)
            // Not a navigation: nothing is popped and nothing animates as a transition.
            BackStep.ScrollFeedToTop -> scope.launch { feedListState.animateScrollToItem(0) }
            BackStep.Exit -> Unit
        }
    }

    Column(modifier = modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        NavHost(
            navController = navController,
            startDestination = Routes.FEED,
            modifier = Modifier.weight(1f),
            enterTransition = {
                slideInHorizontally(animationSpec = slide()) { it / SLIDE_DIVISOR } + fadeIn(fade())
            },
            exitTransition = {
                slideOutHorizontally(animationSpec = slide()) { -it / SLIDE_DIVISOR } + fadeOut(fade())
            },
            popEnterTransition = {
                slideInHorizontally(animationSpec = slide()) { -it / SLIDE_DIVISOR } + fadeIn(fade())
            },
            popExitTransition = {
                slideOutHorizontally(animationSpec = slide()) { it / SLIDE_DIVISOR } + fadeOut(fade())
            },
        ) {
            composable(Routes.FEED) {
                HomeScreen(
                    viewModel = viewModel(factory = HomeViewModel.factory(container)),
                    addSourceViewModel = viewModel(factory = AddSourceViewModel.factory(container)),
                    onOpenEntry = { entryId -> navController.navigate(Routes.article(entryId)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    drawerState = drawerState,
                    listState = feedListState,
                    selection = drawerSelection,
                )
            }

            composable(Routes.SAVED) {
                CollectionScreen(
                    viewModel = viewModel(
                        factory = CollectionViewModel.factory(container, Collection.ToRead),
                    ),
                    onOpenEntry = { entryId -> navController.navigate(Routes.article(entryId)) },
                )
            }

            composable(Routes.LIKED) {
                CollectionScreen(
                    viewModel = viewModel(
                        factory = CollectionViewModel.factory(container, Collection.Liked),
                    ),
                    onOpenEntry = { entryId -> navController.navigate(Routes.article(entryId)) },
                )
            }

            composable(
                route = Routes.ARTICLE,
                arguments = listOf(navArgument(Routes.ARG_ENTRY_ID) { type = NavType.LongType }),
            ) { entry ->
                val entryId = checkNotNull(entry.arguments).getLong(Routes.ARG_ENTRY_ID)
                ArticleScreen(
                    viewModel = viewModel(factory = ArticleViewModel.factory(container, entryId)),
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.SETTINGS) {
                // The context is only ever used to reach WorkManager, and the factory keeps
                // the application one — a ViewModel outliving this composition is the point.
                val context = LocalContext.current
                SettingsScreen(
                    viewModel = viewModel(factory = SettingsViewModel.factory(container, context)),
                    onBack = { navController.popBackStack() },
                )
            }
        }

        // Absent, not merely disabled, on the article and settings routes: the reading
        // surface is the one screen with no furniture under it (§0).
        if (tab != null) {
            PerchBottomBar(
                current = tab,
                onSelect = { selected ->
                    if (selected == tab) return@PerchBottomBar
                    selectTab(navController, selected)
                },
            )
        }
    }
}

/**
 * Switches tabs without stacking them.
 *
 * `popUpTo(start) { saveState }` plus `restoreState` is what makes each tab keep its own
 * scroll offset and its own view-model state across a switch; `launchSingleTop` is what
 * stops a second tap on the current tab from putting a duplicate on the stack. Getting
 * any one of the three wrong looks fine for two taps and then quietly grows a back stack
 * the reader has to press their way out of.
 */
private fun selectTab(navController: NavHostController, tab: PerchTab) {
    navController.navigate(tab.route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun <T> slide() = tween<T>(durationMillis = TRANSITION_MS, easing = FastOutSlowInEasing)

private fun <T> fade() = tween<T>(durationMillis = TRANSITION_MS, easing = FastOutSlowInEasing)
