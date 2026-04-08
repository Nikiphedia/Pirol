package ch.etasystems.pirol.ui.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.ui.analysis.AnalysisScreen
import ch.etasystems.pirol.ui.live.LiveScreen
import ch.etasystems.pirol.ui.map.MapScreen
import ch.etasystems.pirol.ui.onboarding.OnboardingScreen
import ch.etasystems.pirol.ui.reference.ReferenceScreen
import ch.etasystems.pirol.ui.settings.SettingsScreen
import org.koin.compose.koinInject

@Composable
fun PirolNavHost(
    windowSizeClass: WindowSizeClass,
    appPreferences: AppPreferences = koinInject()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val widthSizeClass = windowSizeClass.widthSizeClass
    val useNavigationRail = widthSizeClass >= WindowWidthSizeClass.Medium

    // Start-Destination: Onboarding wenn noch nicht abgeschlossen
    val startDestination = if (appPreferences.onboardingCompleted) {
        Screen.Live.route
    } else {
        ONBOARDING_ROUTE
    }

    // Onboarding-Screen hat keine Tab-Bar
    val isOnboarding = currentDestination?.route == ONBOARDING_ROUTE

    val navigateToScreen: (Screen) -> Unit = { screen ->
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    if (isOnboarding) {
        // Onboarding: Fullscreen, keine Navigation
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(ONBOARDING_ROUTE) {
                OnboardingScreen(
                    onFinished = {
                        // Nach Onboarding: zu LiveScreen navigieren, Onboarding aus Backstack entfernen
                        navController.navigate(Screen.Live.route) {
                            popUpTo(ONBOARDING_ROUTE) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Live.route) { LiveScreen(widthSizeClass = widthSizeClass) }
            composable(Screen.Analysis.route) { AnalysisScreen() }
            composable(Screen.Reference.route) { ReferenceScreen() }
            composable(Screen.Map.route) { MapScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    } else if (useNavigationRail) {
        // Tablet: NavigationRail links + Content rechts
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail {
                Screen.entries.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == screen.route
                    } == true
                    NavigationRailItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = selected,
                        onClick = { navigateToScreen(screen) }
                    )
                }
            }
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.weight(1f)
            ) {
                composable(ONBOARDING_ROUTE) {
                    OnboardingScreen(
                        onFinished = {
                            navController.navigate(Screen.Live.route) {
                                popUpTo(ONBOARDING_ROUTE) { inclusive = true }
                            }
                        }
                    )
                }
                composable(Screen.Live.route) { LiveScreen(widthSizeClass = widthSizeClass) }
                composable(Screen.Analysis.route) { AnalysisScreen() }
                composable(Screen.Reference.route) { ReferenceScreen() }
                composable(Screen.Map.route) { MapScreen() }
                composable(Screen.Settings.route) { SettingsScreen() }
            }
        }
    } else {
        // Phone: Content + BottomNavigation
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Screen.entries.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == screen.route
                        } == true
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            selected = selected,
                            onClick = { navigateToScreen(screen) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(ONBOARDING_ROUTE) {
                    OnboardingScreen(
                        onFinished = {
                            navController.navigate(Screen.Live.route) {
                                popUpTo(ONBOARDING_ROUTE) { inclusive = true }
                            }
                        }
                    )
                }
                composable(Screen.Live.route) { LiveScreen(widthSizeClass = widthSizeClass) }
                composable(Screen.Analysis.route) { AnalysisScreen() }
                composable(Screen.Reference.route) { ReferenceScreen() }
                composable(Screen.Map.route) { MapScreen() }
                composable(Screen.Settings.route) { SettingsScreen() }
            }
        }
    }
}
