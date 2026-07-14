package ai.kompile.chat.local.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ai.kompile.chat.local.android.ui.screens.ChatScreen
import ai.kompile.chat.local.android.ui.screens.SettingsScreen

private const val ROUTE_CHAT     = "chat"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = ROUTE_CHAT
    ) {
        composable(ROUTE_CHAT) {
            ChatScreen(
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) }
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
