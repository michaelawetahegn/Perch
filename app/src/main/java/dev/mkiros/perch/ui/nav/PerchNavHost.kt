package dev.mkiros.perch.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
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
import dev.mkiros.perch.ui.article.zoom.ZoomedImage
import dev.mkiros.perch.ui.collection.Collection
import dev.mkiros.perch.ui.collection.CollectionScreen
import dev.mkiros.perch.ui.collection.CollectionViewModel
import dev.mkiros.perch.ui.collection.SaveLinkViewModel
import dev.mkiros.perch.ui.home.DrawerSelection
import dev.mkiros.perch.ui.home.HomeScope
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
 * ## The window-inset contract (V04, issue #3)
 *
 * `MainActivity` calls `enableEdgeToEdge()`, so **nothing is safe by default**: the window
 * extends under the status bar, the gesture handle and any cutout, and every surface either
 * pads itself or is partly untappable. The contract is four clauses, stated here so it is
 * decided in one place rather than re-decided as a `.statusBarsPadding()` per screen:
 *
 * 1. **Chrome Material owns keeps Material's defaults.** `TopAppBar`, `NavigationBar` and
 *    `ModalDrawerSheet` already pad for the bars they sit against, and a `Scaffold` passes
 *    whatever inset it has no bar for down to its body. Do not re-pad any of them.
 * 2. **Content draws under the bars; only furniture moves.** A list scrolls its rows behind
 *    a translucent bar — that is the point of edge-to-edge — so the inset belongs on the
 *    controls, never on the surface behind them.
 * 3. **Where two surfaces would spend the same inset, the shell consumes it.** That happens
 *    exactly once, below: the bottom bar and the `NavHost` are siblings (U09), so the
 *    screen's `Scaffold` cannot see that the bar is already covering the gesture handle.
 * 4. **An overlay that bypasses a `Scaffold` opts in explicitly.** The image viewer (U12)
 *    is a sibling of the article's `Scaffold` by design; it wraps its own furniture in
 *    `safeDrawing` and leaves the figure full-bleed. Any future overlay does the same.
 *
 * `WindowInsetsTest` is one test per clause. Robolectric's device profiles have no bars and
 * no cutout, so it dispatches insets by hand — a test that does not will pass on a tree
 * that handles nothing.
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

    // U12's viewer is opened from inside an article but hoisted to here, for the reason the
    // drawer's state is: it is a rung of the back chain, and a rung the chain cannot see is
    // a rung that is only true by luck of composition order. `rememberSaveable` so process
    // death restores the reader to the figure they were looking at.
    val zoomedImage = rememberSaveable(stateSaver = ZoomedImage.Saver) {
        mutableStateOf<ZoomedImage?>(null)
    }

    // What the Feed is narrowed to (V08), hoisted for the third time and the third time
    // for the same reason: it is a rung of the back chain. It also has two writers now —
    // the drawer, and the source name in an article's byline — and two writers with no
    // single owner is how the drawer and the list come to disagree about what is on
    // screen. `rememberSaveable` so process death does not quietly widen the list.
    val homeScope = rememberSaveable(stateSaver = HomeScope.Saver) {
        mutableStateOf<HomeScope>(HomeScope.All)
    }

    val backState = BackState(
        selectionActive = drawerSelection.value.isActive,
        overlayOpen = drawerState.isOpen,
        imageViewerOpen = zoomedImage.value != null,
        onArticle = route == Routes.ARTICLE,
        tab = tab ?: PerchTab.Feed,
        feedScoped = homeScope.value.isNarrowed,
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
            BackStep.CloseImageViewer -> zoomedImage.value = null
            BackStep.PopArticle -> navController.popBackStack()
            BackStep.ReturnToFeed -> selectTab(navController, PerchTab.Feed)
            BackStep.LeaveScope -> homeScope.value = HomeScope.All
            // Not a navigation: nothing is popped and nothing animates as a transition.
            BackStep.ScrollFeedToTop -> scope.launch { feedListState.animateScrollToItem(0) }
            BackStep.Exit -> Unit
        }
    }

    Column(modifier = modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        NavHost(
            navController = navController,
            startDestination = Routes.FEED,
            modifier = Modifier
                .weight(1f)
                // The one place the contract needs a hand (V04). The bar and the graph are
                // siblings, so a screen's `Scaffold` cannot see that the strip of screen
                // its content would otherwise keep clear of the gesture handle is already
                // covered by the bar — and both spend it, leaving a bar-height band of
                // dead space above the bar. Consumed here, exactly when the bar is there
                // to consume it; on the article and settings routes the screen keeps the
                // inset because nothing else is standing on it.
                .then(if (tab != null) Modifier.consumeWindowInsets(bottomBarInsets()) else Modifier),
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
                    homeScope = homeScope,
                )
            }

            composable(Routes.SAVED) {
                CollectionScreen(
                    viewModel = viewModel(
                        factory = CollectionViewModel.factory(container, Collection.ToRead),
                    ),
                    onOpenEntry = { entryId -> navController.navigate(Routes.article(entryId)) },
                    saveLinkViewModel = viewModel(factory = SaveLinkViewModel.factory(container)),
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
                    // V08: the scoped list is *state*, not a route. Perch already has one
                    // way to be narrowed to a source — the drawer's — and giving the Feed
                    // an optional `feedId` argument would have made a second, so that a
                    // reader could be scoped by the route and unscoped by the drawer at
                    // once. Scoping the shell's state and switching to the Feed tab reuses
                    // the list, its title, its banner and its pull-to-refresh exactly as
                    // they already are, and leaves the other tabs' saved state alone.
                    onOpenSource = { feedId ->
                        homeScope.value = HomeScope.Source(feedId)
                        // Pop first, then switch tabs only if the article was opened from
                        // one of the other two. [selectTab] alone cannot do this from the
                        // article: its `popUpTo(start) { saveState }` saves the article as
                        // it pops it and its `restoreState` puts it straight back, so the
                        // navigation is a no-op. Popping is also the truthful move — the
                        // reader is not opening a fourth destination, they are going back
                        // to the list with it narrowed.
                        navController.popBackStack()
                        if (PerchTab.ofRoute(navController.currentDestination?.route)
                            != PerchTab.Feed
                        ) {
                            selectTab(navController, PerchTab.Feed)
                        }
                    },
                    zoomed = zoomedImage,
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

/**
 * Exactly what [PerchBottomBar] pads itself by — `NavigationBar` applies
 * `NavigationBarDefaults.windowInsets`, the bottom edge of the system bars. Consuming any
 * more would push content up over a gap nothing is standing in; consuming any less would
 * leave the band this exists to remove.
 */
@Composable
private fun bottomBarInsets(): WindowInsets =
    WindowInsets.systemBars.only(WindowInsetsSides.Bottom)

private fun <T> slide() = tween<T>(durationMillis = TRANSITION_MS, easing = FastOutSlowInEasing)

private fun <T> fade() = tween<T>(durationMillis = TRANSITION_MS, easing = FastOutSlowInEasing)
