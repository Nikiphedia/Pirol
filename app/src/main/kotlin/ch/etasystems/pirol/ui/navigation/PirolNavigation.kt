package ch.etasystems.pirol.ui.navigation

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    appPreferences: AppPreferences = koinInject(),
    playback: ch.etasystems.pirol.audio.PlaybackController = koinInject()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    // T173: Beim Wechsel/Verlassen eines Tabs laeuft keine Wiedergabe weiter
    androidx.compose.runtime.LaunchedEffect(currentDestination?.route) { playback.stop() }
    val widthSizeClass = windowSizeClass.widthSizeClass
    val useNavigationRail = widthSizeClass >= WindowWidthSizeClass.Medium
    // T174: Telefon quer (Fensterhoehe < 480 dp): schmale Rail ohne Labels, kompaktes Live-Layout
    val compactHeight = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact

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
            composable(Screen.Live.route) { LiveScreen(widthSizeClass = widthSizeClass, compactHeight = compactHeight) }
            composable(Screen.Analysis.route) { AnalysisScreen() }
            composable(Screen.Reference.route) { ReferenceScreen() }
            composable(Screen.Map.route) { MapScreen() }
            composable(Screen.Settings.route) { SettingsScreen(onStartSetup = { navController.navigate(ONBOARDING_ROUTE) }) }
        }
    } else if (useNavigationRail) {
        // Tablet: NavigationRail links + Content rechts
        // T114: Edge-to-Edge ohne Scaffold → System-Leisten selbst freihalten
        // (sonst lag z.B. der Benchmark-Knopf unter der Navigationsleiste)
        Row(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (compactHeight) {
                // T174: schmale Leiste nur mit Icons (48-dp-Tap-Targets), contentDescription bleibt
                androidx.compose.material3.Surface(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.fillMaxHeight().width(56.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Screen.entries.forEach { screen ->
                            val selected = currentDestination?.hierarchy?.any {
                                it.route == screen.route
                            } == true
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer
                                        else Color.Transparent
                                    )
                                    .selectable(
                                        selected = selected,
                                        role = Role.Tab,
                                        onClick = { navigateToScreen(screen) }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    screen.icon,
                                    contentDescription = stringResource(screen.labelRes),
                                    tint = if (selected) androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer
                                    else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
            NavigationRail {
                Screen.entries.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == screen.route
                    } == true
                    NavigationRailItem(
                        icon = { Icon(screen.icon, contentDescription = stringResource(screen.labelRes)) },
                        label = { Text(stringResource(screen.labelRes)) },
                        selected = selected,
                        onClick = { navigateToScreen(screen) }
                    )
                }
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
                composable(Screen.Live.route) { LiveScreen(widthSizeClass = widthSizeClass, compactHeight = compactHeight) }
                composable(Screen.Analysis.route) { AnalysisScreen() }
                composable(Screen.Reference.route) { ReferenceScreen() }
                composable(Screen.Map.route) { MapScreen() }
                composable(Screen.Settings.route) { SettingsScreen(onStartSetup = { navController.navigate(ONBOARDING_ROUTE) }) }
            }
        }
    } else {
        // Phone: Content + BottomNavigation
        Scaffold(
            bottomBar = {
                // T164: niedrige Leiste (48 dp) ohne Labels statt 80-dp-NavigationBar
                androidx.compose.material3.Surface(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Screen.entries.forEach { screen ->
                            val selected = currentDestination?.hierarchy?.any {
                                it.route == screen.route
                            } == true
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .selectable(
                                        selected = selected,
                                        role = Role.Tab,
                                        onClick = { navigateToScreen(screen) }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .height(32.dp)
                                        .width(56.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (selected) androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer
                                            else Color.Transparent
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        screen.icon,
                                        contentDescription = stringResource(screen.labelRes),
                                        tint = if (selected) androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer
                                        else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
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
                composable(Screen.Live.route) { LiveScreen(widthSizeClass = widthSizeClass, compactHeight = compactHeight) }
                composable(Screen.Analysis.route) { AnalysisScreen() }
                composable(Screen.Reference.route) { ReferenceScreen() }
                composable(Screen.Map.route) { MapScreen() }
                composable(Screen.Settings.route) { SettingsScreen(onStartSetup = { navController.navigate(ONBOARDING_ROUTE) }) }
            }
        }
    }
}
