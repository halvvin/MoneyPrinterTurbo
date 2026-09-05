package com.moneyprinterturbo.android.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.moneyprinterturbo.android.R
import com.moneyprinterturbo.android.ui.screens.*

object Routes {
    const val HOME = "home"
    const val CREATE = "create"
    const val PROJECT = "project/{id}"
    const val HISTORY = "history"
    const val TASK = "task/{id}"
    const val SETTINGS = "settings"
    const val PROVIDERS = "providers"
    const val PROVIDER_EDIT = "provider_edit/{id}"
    const val STOCK_KEYS = "stock_keys"
    const val MEDIA = "media"
    const val VOICES = "voices"

    fun project(id: String) = "project/$id"
    fun task(id: String) = "task/$id"
    fun providerEdit(id: String) = "provider_edit/$id"
}

private data class BottomDest(val route: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun MptApp() {
    val nav = rememberNavController()
    val dests = listOf(
        BottomDest(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
        BottomDest(Routes.CREATE, R.string.nav_create, Icons.Filled.Folder),
        BottomDest(Routes.HISTORY, R.string.nav_history, Icons.Filled.History),
        BottomDest(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
    )
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute in dests.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                dests.forEach { d ->
                    NavigationBarItem(
                        selected = currentRoute == d.route,
                        onClick = {
                            nav.navigate(d.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(d.icon, contentDescription = stringResource(d.labelRes)) },
                        label = { Text(stringResource(d.labelRes)) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(pad)) {
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.CREATE) { CreateScreen(nav) }
            composable(Routes.PROJECT) { entry ->
                ProjectScreen(nav, entry.arguments?.getString("id") ?: "")
            }
            composable(Routes.HISTORY) { HistoryScreen(nav) }
            composable(Routes.TASK) { entry -> TaskScreen(nav, entry.arguments?.getString("id") ?: "") }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.PROVIDERS) { ProvidersScreen(nav) }
            composable(Routes.PROVIDER_EDIT) { entry ->
                ProviderEditScreen(nav, entry.arguments?.getString("id") ?: "")
            }
            composable(Routes.STOCK_KEYS) { StockKeysScreen(nav) }
            composable(Routes.VOICES) { VoicesScreen(nav) }
        }
    }
}
