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
import io.woowtech.odoo.ui.config.ProfileScreen
import io.woowtech.odoo.ui.config.SettingsScreen
import io.woowtech.odoo.ui.login.LoginScreen
import io.woowtech.odoo.ui.main.MainScreen

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Auth : Screen("auth")
    object Pin : Screen("pin")
    object Login : Screen("login")
    object Main : Screen("main")
    object Config : Screen("config")
    object Profile : Screen("profile")
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
                }
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
                onProfileClick = { navController.navigate(Screen.Profile.route) },
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

        composable(Screen.Profile.route) {
            ProfileScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
