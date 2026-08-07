package dev.mkiros.perch.ui.nav

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.article.ArticleScreen
import dev.mkiros.perch.ui.article.ArticleViewModel
import dev.mkiros.perch.ui.home.HomeScreen
import dev.mkiros.perch.ui.home.HomeViewModel
import dev.mkiros.perch.ui.settings.SettingsScreen
import dev.mkiros.perch.ui.settings.SettingsViewModel
import dev.mkiros.perch.ui.source.AddSourceViewModel

/** Every destination in the app. There are three; there will not be more. */
object Routes {
    const val HOME = "home"
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
 * The whole navigation graph.
 *
 * Screens get their dependencies through a ViewModel factory built from [container]; no
 * screen reaches for a singleton, which is what lets a test compose any route over an
 * in-memory database. Back is the platform's — `NavHost` owns the dispatcher and nothing
 * here intercepts it (DESIGN.md §5).
 */
@Composable
fun PerchNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
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
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = viewModel(factory = HomeViewModel.factory(container)),
                addSourceViewModel = viewModel(factory = AddSourceViewModel.factory(container)),
                onOpenEntry = { entryId -> navController.navigate(Routes.article(entryId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.ARTICLE,
            arguments = listOf(navArgument(Routes.ARG_ENTRY_ID) { type = NavType.LongType }),
        ) { backStackEntry ->
            val entryId = checkNotNull(backStackEntry.arguments).getLong(Routes.ARG_ENTRY_ID)
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
}

private fun <T> slide() = tween<T>(durationMillis = TRANSITION_MS, easing = FastOutSlowInEasing)

private fun <T> fade() = tween<T>(durationMillis = TRANSITION_MS, easing = FastOutSlowInEasing)
