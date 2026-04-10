/*
 * Copyright (C) 2026 Flashcards Everywhere contributors
 * Licensed under the GNU GPL v3+.
 *
 * Top-level navigation: a NavHost wrapping the three real destinations
 *   - onboarding (only shown until SettingsRepository.onboardingDone == true)
 *   - review     (the existing chrome-free reviewer)
 *   - settings   (deck picker, daily goal, pacing, quiet hours, sync, redo onboarding)
 *
 * Onboarding lives outside the Scaffold so it occupies the entire screen
 * with no NavigationBar; the two main destinations live inside a Scaffold
 * with a Material 3 NavigationBar at the bottom.
 */
package com.flashcardseverywhere.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.flashcardseverywhere.ui.onboarding.OnboardingRoute
import com.flashcardseverywhere.ui.reviewer.ReviewerRoute
import com.flashcardseverywhere.ui.settings.SettingsScreen

private object Routes {
    const val ONBOARDING = "onboarding"
    const val REVIEW = "review"
    const val SETTINGS = "settings"
}

private data class BottomDest(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val bottomDestinations = listOf(
    BottomDest(Routes.REVIEW, "Review", Icons.Outlined.Style),
    BottomDest(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
)

@Composable
fun AppNavHost(rootVm: AppRootViewModel = hiltViewModel()) {
    val rootState by rootVm.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()

    val startDestination = if (rootState.onboardingDone) Routes.REVIEW else Routes.ONBOARDING

    // We rebuild the NavHost when the start destination flips so that
    // "Run onboarding again" can re-route the user back to the onboarding
    // screen without having to manage the back stack manually.
    NavHost(
        navController = nav,
        startDestination = startDestination,
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingRoute(
                onDone = { nav.navigate(Routes.REVIEW) { popUpTo(Routes.ONBOARDING) { inclusive = true } } },
            )
        }
        composable(Routes.REVIEW) {
            MainScaffold(nav = nav, currentRoute = Routes.REVIEW) {
                ReviewerRoute(
                    onNavigateToSettings = {
                        nav.navigate(Routes.SETTINGS) {
                            popUpTo(nav.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
        composable(Routes.SETTINGS) {
            MainScaffold(nav = nav, currentRoute = Routes.SETTINGS) {
                SettingsScreen(
                    onRunOnboardingAgain = {
                        // Flip the pref + jump straight back to the onboarding
                        // route. The next NavHost recomposition will treat this
                        // as the new start destination.
                        rootVm.runOnboardingAgain()
                        nav.navigate(Routes.ONBOARDING) {
                            popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MainScaffold(
    nav: NavHostController,
    currentRoute: String,
    content: @Composable () -> Unit,
) {
    val backStackEntry by nav.currentBackStackEntryAsState()
    val activeRoute = backStackEntry?.destination?.route ?: currentRoute

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                bottomDestinations.forEach { dest ->
                    val selected = backStackEntry?.destination?.hierarchy
                        ?.any { it.route == dest.route } == true || activeRoute == dest.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                nav.navigate(dest.route) {
                                    popUpTo(nav.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                            selectedIconColor = MaterialTheme.colorScheme.onBackground,
                            selectedTextColor = MaterialTheme.colorScheme.onBackground,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            content()
        }
    }
}
