package com.abhijit.airealityscanner.navigation

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.abhijit.airealityscanner.features.chat.ChatHomeScreen
import com.abhijit.airealityscanner.features.compare.CompareScreen
import com.abhijit.airealityscanner.features.discover.DiscoverScreen
import com.abhijit.airealityscanner.features.history.HistoryScreen
import com.abhijit.airealityscanner.features.result.ResultScreen
import com.abhijit.airealityscanner.features.scanner.ScannerScreen
import com.abhijit.airealityscanner.features.settings.SettingsScreen

private enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    SCANNER("scanner", "Scanner", Icons.Rounded.CenterFocusStrong),
    DISCOVER("discover", "Discover", Icons.Rounded.Explore),
    CHAT("chat", "AI Chat", Icons.AutoMirrored.Rounded.Chat),
    HISTORY("history", "History", Icons.Rounded.History),
    SETTINGS("settings", "Settings", Icons.Rounded.Settings),
}

object Routes {
    const val RESULT = "result/{sessionId}?q={q}"
    const val COMPARE = "compare"
    fun result(sessionId: String, question: String? = null): String =
        if (question.isNullOrBlank()) "result/$sessionId" else "result/$sessionId?q=${Uri.encode(question)}"
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = TopLevel.entries.any { it.route == currentRoute }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevel.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = TopLevel.SCANNER.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopLevel.SCANNER.route) {
                ScannerScreen(
                    onOpenResult = { id, q -> nav.navigate(Routes.result(id, q)) },
                    onOpenSettings = { nav.navigateTab(TopLevel.SETTINGS.route) },
                )
            }
            composable(TopLevel.DISCOVER.route) {
                DiscoverScreen(
                    onStartScan = { nav.navigateTab(TopLevel.SCANNER.route) },
                    onCompare = { nav.navigate(Routes.COMPARE) },
                    onOpenSettings = { nav.navigateTab(TopLevel.SETTINGS.route) },
                )
            }
            composable(TopLevel.CHAT.route) {
                ChatHomeScreen(onOpenConversation = { nav.navigate(Routes.result(it)) })
            }
            composable(TopLevel.HISTORY.route) {
                HistoryScreen(onOpenScan = { nav.navigate(Routes.result(it)) })
            }
            composable(TopLevel.SETTINGS.route) {
                SettingsScreen()
            }
            composable(
                route = Routes.RESULT,
                arguments = listOf(
                    navArgument("sessionId") { type = NavType.StringType },
                    navArgument("q") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                ResultScreen(
                    onBack = { nav.popBackStack() },
                    onOpenSettings = { nav.navigateTab(TopLevel.SETTINGS.route) },
                )
            }
            composable(Routes.COMPARE) {
                CompareScreen(
                    onBack = { nav.popBackStack() },
                    onOpenResult = { id ->
                        nav.navigate(Routes.result(id)) { popUpTo(Routes.COMPARE) { inclusive = true } }
                    },
                )
            }
        }
    }
}
