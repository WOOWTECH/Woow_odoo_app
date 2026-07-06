package io.woowtech.odoo.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.woowtech.odoo.ui.auth.AuthViewModel
import io.woowtech.odoo.ui.auth.BiometricScreen
import io.woowtech.odoo.ui.auth.PinScreen
import io.woowtech.odoo.ui.config.ConfigScreen
import io.woowtech.odoo.ui.config.SettingsScreen
import io.woowtech.odoo.ui.login.LoginScreen
import io.woowtech.odoo.ui.main.MainScreen

// L8 NOTE: String-based routes are a known limitation (MASVS-PLATFORM-1). Migrating to
// type-safe @Serializable data objects (Nav 2.8+ API) would make saved-state restoration
// of the back-stack auditable at compile time, but requires upgrading
// androidx.navigation from 2.7.7 to 2.8+. Tracked as a backlog item — the L2 imperative
// LaunchedEffect guard (added in this commit) mitigates the exploitation path by clearing
// the back-stack and routing to Auth on any requiresAuth && !isAuthenticated state, making
// back-stack manipulation via string routes a non-exploitable path.
sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Auth : Screen("auth")
    object Pin : Screen("pin")
    object Login : Screen("login")
    object Main : Screen("main")
    object Config : Screen("config")
    object Settings : Screen("settings")
}

@Composable
fun WoowOdooNavHost(
    navController: NavHostController = rememberNavController(),
    authViewModel: AuthViewModel = hiltViewModel()
) {
    val hasActiveAccount by authViewModel.hasActiveAccount.collectAsState()
    val requiresAuth by authViewModel.requiresAuth.collectAsState()
    val isAuthenticated by authViewModel.isAuthenticated.collectAsState()

    val startDestination = when {
        hasActiveAccount == null -> Screen.Splash.route
        hasActiveAccount == false -> Screen.Login.route
        requiresAuth && !isAuthenticated -> Screen.Auth.route
        else -> Screen.Main.route
    }

    // L2: Imperative auth guard — navigates to Auth whenever the auth state transitions
    // to "requires authentication". This covers the process-restoration path where the
    // NavController saved-state can restore the back-stack to Screen.Main while
    // isAuthenticated is false (its in-memory initial value after process death).
    // The startDestination guard above only controls the *initial* destination; it cannot
    // pop a restored back-stack. This LaunchedEffect fires on every frame where
    // requiresAuth=true AND isAuthenticated=false, clearing the entire stack to Auth.
    LaunchedEffect(isAuthenticated, requiresAuth) {
        if (requiresAuth && !isAuthenticated) {
            navController.navigate(Screen.Auth.route) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Splash.route) {
            // Just a loading indicator while checking account status
            LaunchedEffect(hasActiveAccount, requiresAuth, isAuthenticated) {
                when {
                    hasActiveAccount == false -> navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                    hasActiveAccount == true && requiresAuth && !isAuthenticated -> {
                        navController.navigate(Screen.Auth.route) {
                            popUpTo(Screen.Splash.route) { inclusive = true }
                        }
                    }
                    hasActiveAccount == true && (!requiresAuth || isAuthenticated) -> {
                        navController.navigate(Screen.Main.route) {
                            popUpTo(Screen.Splash.route) { inclusive = true }
                        }
                    }
                }
            }
        }

        composable(Screen.Auth.route) {
            BiometricScreen(
                onAuthSuccess = {
                    authViewModel.setAuthenticated(true)
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Auth.route) { inclusive = true }
                    }
                },
                onUsePinClick = {
                    navController.navigate(Screen.Pin.route)
                },
            )
        }

        composable(Screen.Pin.route) {
            PinScreen(
                onPinVerified = {
                    authViewModel.setAuthenticated(true)
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Auth.route) { inclusive = true }
                    }
                },
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Main.route) {
            MainScreen(
                onMenuClick = {
                    navController.navigate(Screen.Config.route)
                }
            )
        }

        composable(Screen.Config.route) {
            ConfigScreen(
                onBackClick = { navController.popBackStack() },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onAddAccountClick = {
                    navController.navigate(Screen.Login.route)
                },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Main.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
