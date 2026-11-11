package com.swapstudio.ai.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.swapstudio.ai.ui.screens.HomeScreen
import com.swapstudio.ai.ui.screens.ModelManagerScreen
import com.swapstudio.ai.ui.screens.PhotoSwapScreen
import com.swapstudio.ai.ui.screens.ResultScreen
import com.swapstudio.ai.ui.screens.SettingsScreen
import com.swapstudio.ai.ui.screens.VideoSwapScreen

/** Every destination of the app. */
object Routes {
    const val HOME = "home"
    const val PHOTO = "photo"
    const val VIDEO = "video"
    const val MODELS = "models"
    const val SETTINGS = "settings"

    /** `result/{kind}` where kind is `photo` or `video`. */
    const val RESULT = "result/{kind}"

    fun result(kind: String) = "result/$kind"
}

/**
 * Navigation graph.
 *
 * Flat on purpose: the app is a tool, not a content feed, so every screen is one tap away from home and
 * the back stack stays predictable (the system back button always returns to home before exiting).
 */
@Composable
fun SwapStudioNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenPhoto = { navController.navigate(Routes.PHOTO) },
                onOpenVideo = { navController.navigate(Routes.VIDEO) },
                onOpenModels = { navController.navigate(Routes.MODELS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.PHOTO) {
            PhotoSwapScreen(
                onBack = { navController.popBackStack() },
                onDone = { navController.navigate(Routes.result("photo")) },
            )
        }
        composable(Routes.VIDEO) {
            VideoSwapScreen(
                onBack = { navController.popBackStack() },
                onDone = { navController.navigate(Routes.result("video")) },
            )
        }
        composable(Routes.MODELS) {
            ModelManagerScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.RESULT) { entry ->
            ResultScreen(
                kind = entry.arguments?.getString("kind") ?: "photo",
                onBack = { navController.popBackStack() },
                onHome = {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
            )
        }
    }
}
