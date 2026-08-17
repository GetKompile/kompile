package ai.kompile.chat.local.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ai.kompile.chat.local.android.ui.screens.ChatScreen
import ai.kompile.chat.local.android.ui.screens.SettingsScreen
import ai.kompile.chat.local.android.viewmodel.AppNavigationEvent
import ai.kompile.chat.local.android.viewmodel.ChatViewModel

private const val ROUTE_CHAT     = "chat"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun AppNavigation(vm: ChatViewModel) {
    val navController = rememberNavController()
    val openChat = {
        // Settings is normally above the existing chat destination. Reveal that destination
        // directly instead of navigating to chat while also popping to chat, which can briefly
        // leave NavHost without composed content. Activation events remain safe when chat is
        // already current because the fallback is single-top.
        if (!navController.popBackStack(ROUTE_CHAT, inclusive = false)) {
            navController.navigate(ROUTE_CHAT) { launchSingleTop = true }
        }
    }

    LaunchedEffect(vm, navController) {
        // A restored Activity may otherwise reopen the scrollable Settings destination.
        // Every fresh app composition starts from the actual chat surface.
        openChat()
        vm.navigationEvents.collect { event ->
            when (event) {
                AppNavigationEvent.OpenChat -> openChat()
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = ROUTE_CHAT
    ) {
        composable(ROUTE_CHAT) {
            ChatScreen(
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                vm = vm
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = openChat,
                vm = vm
            )
        }
    }
}
