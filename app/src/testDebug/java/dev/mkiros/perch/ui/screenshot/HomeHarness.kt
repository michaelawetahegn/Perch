package dev.mkiros.perch.ui.screenshot

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import dev.mkiros.perch.data.net.ConnectivityMonitor
import dev.mkiros.perch.data.repo.BackfillRepository
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.support.PerchRule
import dev.mkiros.perch.ui.home.BackfillRunner
import dev.mkiros.perch.ui.home.DrawerSelection
import dev.mkiros.perch.ui.home.HomeScope
import dev.mkiros.perch.ui.home.HomeScreen
import dev.mkiros.perch.ui.home.HomeViewModel
import dev.mkiros.perch.ui.source.AddSourceViewModel
import dev.mkiros.perch.ui.theme.PerchTheme
import dev.mkiros.perch.ui.theme.ThemeMode
import java.time.Clock

/**
 * The Feed on screen, and everything a test needs to hold onto afterwards.
 *
 * Eleven test classes each built their own [HomeViewModel], their own [AddSourceViewModel]
 * and their own copy of the state `PerchNavHost` hoists, then waited for the same first
 * emission (D11 / issue #44). This is that, once. The hoisted state is *always* created
 * here — a test that never looks at [drawerState] simply ignores it — so the screen is
 * composed the same way in every test, and the ones whose subject *is* the hoisting
 * (`DrawerMultiSelectTest`, `BackfillOfferTest`) ask this object rather than re-declaring it.
 */
class HomeHarness(
    val viewModel: HomeViewModel,
    val addSourceViewModel: AddSourceViewModel,
    val drawerState: DrawerState,
    val listState: LazyListState,
    val selection: MutableState<DrawerSelection>,
    val homeScope: MutableState<HomeScope>,
)

/**
 * Builds the Feed's view-models over [perch]'s container, composes [HomeScreen] inside
 * [PerchTheme], and waits — in wall-clock time, because Room delivers its first emission
 * off the main thread — for the skeleton to give way to the list.
 *
 * Every parameter past [compose] is a seam some test actually needs: [clock] and
 * [settings] because a test class usually pins both, [connectivity] for the offline strip,
 * [backfill]/[backfillRunner] for the archive offer, [scope] for a Feed that opens already
 * narrowed, [themeMode] and [fontScale] for the screenshot and accessibility shots. The
 * defaults are what a test that cares about none of them would have written by hand.
 *
 * A test wanting a *later* condition than "loaded" — a particular row on screen — calls
 * [ComposeContentTestRule.awaitInRealTime] itself afterwards; this waits only for the
 * state every caller shares.
 */
fun showHome(
    perch: PerchRule,
    compose: ComposeContentTestRule,
    clock: Clock = Clock.systemDefaultZone(),
    settings: SettingsStore = perch.settings,
    connectivity: ConnectivityMonitor = ConnectivityMonitor.AlwaysOnline,
    backfill: BackfillRepository? = null,
    backfillRunner: BackfillRunner = BackfillRunner.NoOp,
    scope: HomeScope = HomeScope.All,
    themeMode: ThemeMode = ThemeMode.System,
    fontScale: Float = 1f,
    onOpenEntry: (Long) -> Unit = {},
    onOpenSettings: () -> Unit = {},
): HomeHarness {
    val viewModel = homeViewModel(
        perch = perch,
        clock = clock,
        settings = settings,
        connectivity = connectivity,
        backfill = backfill,
        backfillRunner = backfillRunner,
    )
    val addSourceViewModel = AddSourceViewModel(perch.container.feeds, perch.container.folders)
    lateinit var harness: HomeHarness
    compose.setContent {
        // Hoisted exactly as PerchNavHost hoists it (U09/U09a/V08), so a test can ask the
        // same questions the shell's back chain asks.
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val listState = rememberLazyListState()
        val selection = rememberSaveable(stateSaver = DrawerSelection.Saver) {
            mutableStateOf<DrawerSelection>(DrawerSelection.None)
        }
        val homeScope = rememberSaveable(stateSaver = HomeScope.Saver) {
            mutableStateOf(scope)
        }
        harness = HomeHarness(
            viewModel = viewModel,
            addSourceViewModel = addSourceViewModel,
            drawerState = drawerState,
            listState = listState,
            selection = selection,
            homeScope = homeScope,
        )
        AtFontScale(fontScale) {
            PerchTheme(mode = themeMode, dynamicColor = false) {
                HomeScreen(
                    viewModel = viewModel,
                    addSourceViewModel = addSourceViewModel,
                    onOpenEntry = onOpenEntry,
                    onOpenSettings = onOpenSettings,
                    drawerState = drawerState,
                    listState = listState,
                    selection = selection,
                    homeScope = homeScope,
                )
            }
        }
    }
    compose.awaitInRealTime("the Feed to load") { !viewModel.uiState.value.isLoading }
    return harness
}

/**
 * The Feed's view-model over [perch]'s container, with nothing composed.
 *
 * [showHome] builds its own through here, and so do the two tests that relaunch the Feed
 * to prove a choice outlived it — they need a second view-model over the same database,
 * which is the one thing a harness that composes cannot give them.
 */
fun homeViewModel(
    perch: PerchRule,
    clock: Clock = Clock.systemDefaultZone(),
    settings: SettingsStore = perch.settings,
    connectivity: ConnectivityMonitor = ConnectivityMonitor.AlwaysOnline,
    backfill: BackfillRepository? = null,
    backfillRunner: BackfillRunner = BackfillRunner.NoOp,
): HomeViewModel = HomeViewModel(
    entries = perch.container.entries,
    feeds = perch.container.feeds,
    folders = perch.container.folders,
    clock = clock,
    connectivity = connectivity,
    settings = settings,
    backfill = backfill,
    backfillRunner = backfillRunner,
)

/**
 * [content] at a font scale a reader could have chosen. Untouched at 1f rather than
 * re-provided with the same number, so the default path composes under exactly the
 * density Robolectric configured and the screenshot gate has nothing new to see.
 */
@Composable
private fun AtFontScale(scale: Float, content: @Composable () -> Unit) {
    if (scale == 1f) {
        content()
    } else {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, scale),
            content = content,
        )
    }
}
