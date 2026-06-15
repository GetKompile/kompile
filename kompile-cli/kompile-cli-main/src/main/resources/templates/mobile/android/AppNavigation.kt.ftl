package {{packageName}}.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import {{packageName}}.ui.screens.ChatScreen
import {{packageName}}.ui.screens.DocumentImportScreen
import {{packageName}}.ui.screens.IndexBrowserScreen
import {{packageName}}.ui.screens.SessionListScreen
import {{packageName}}.ui.screens.SettingsScreen
import {{packageName}}.ui.screens.SourceListScreen

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Chat : Screen("chat", "Chat", Icons.Default.Chat)
    data object Sessions : Screen("sessions", "Sessions", Icons.Default.History)
    data object Sources : Screen("sources", "Sources", Icons.Default.Description)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

val bottomNavItems = listOf(
    Screen.Chat,
    Screen.Sessions,
    Screen.Sources,
    Screen.Settings
)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Chat.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Chat.route) {
                ChatScreen()
            }
            composable(Screen.Sessions.route) {
                SessionListScreen(
                    onSessionClick = { sessionId ->
                        navController.navigate("chat?sessionId=$sessionId")
                    }
                )
            }
            composable(Screen.Sources.route) {
                SourceListScreen(
                    onImportClick = {
                        navController.navigate("document_import")
                    },
                    onBrowseIndexClick = {
                        navController.navigate("index_browser")
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
            composable("document_import") {
                DocumentImportScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("index_browser") {
                IndexBrowserScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("chat?sessionId={sessionId}") { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId")
                ChatScreen(sessionId = sessionId)
            }
        }
    }
}
