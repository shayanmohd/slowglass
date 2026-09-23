package com.mohdshayan.slowglass.ui.nav

import android.app.Activity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.mohdshayan.slowglass.ui.capture.CaptureScreen
import com.mohdshayan.slowglass.ui.check.CameraCheckScreen
import com.mohdshayan.slowglass.ui.info.LicencesScreen
import com.mohdshayan.slowglass.ui.info.PrivacyScreen
import com.mohdshayan.slowglass.ui.library.LibraryScreen
import com.mohdshayan.slowglass.ui.session.SessionScreen
import com.mohdshayan.slowglass.ui.settings.SettingsScreen
import com.mohdshayan.slowglass.ui.theme.AppTheme
import com.mohdshayan.slowglass.ui.theme.rememberReducedMotion
import kotlinx.serialization.Serializable

@Serializable object CaptureRoute
@Serializable object LibraryRoute
@Serializable data class SessionRoute(val id: Long)
@Serializable data class CheckRoute(val firstRun: Boolean = false)
@Serializable object SettingsRoute
@Serializable object PrivacyRoute
@Serializable object LicencesRoute

/**
 * Capture is the start destination and has no bottom bar: Library opens from the last-photo
 * thumbnail, Settings from the gear. Capture is always dark; the rest follow the theme setting.
 */
@Composable
fun AppNav(darkTheme: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val onCapture = entry?.destination?.hasRoute(CaptureRoute::class) ?: true
    SystemBars(dark = onCapture || darkTheme)
    val reduced = rememberReducedMotion()
    val enter: EnterTransition = if (reduced) EnterTransition.None else fadeIn(tween(180))
    val exit: ExitTransition = if (reduced) ExitTransition.None else fadeOut(tween(180))
    val back: () -> Unit = { nav.popBackStack() }

    NavHost(
        navController = nav,
        startDestination = CaptureRoute,
        enterTransition = { enter },
        exitTransition = { exit },
        popEnterTransition = { enter },
        popExitTransition = { exit },
    ) {
        composable<CaptureRoute> {
            AppTheme(darkTheme = true) {
                CaptureScreen(
                    onOpenLibrary = { nav.navigate(LibraryRoute) },
                    onOpenSettings = { nav.navigate(SettingsRoute) },
                    onOpenSession = { nav.navigate(SessionRoute(it)) },
                    onFirstRunCheck = { nav.navigate(CheckRoute(firstRun = true)) { launchSingleTop = true } },
                )
            }
        }
        composable<LibraryRoute> {
            AppTheme(darkTheme) {
                LibraryScreen(onBack = back, onOpen = { nav.navigate(SessionRoute(it)) }, onOpenCamera = back)
            }
        }
        composable<SessionRoute> { e ->
            AppTheme(darkTheme) {
                SessionScreen(
                    id = e.toRoute<SessionRoute>().id,
                    onBack = back,
                    onOpenCamera = { nav.popBackStack(CaptureRoute, inclusive = false) },
                )
            }
        }
        composable<CheckRoute> { e ->
            AppTheme(darkTheme) {
                CameraCheckScreen(firstRun = e.toRoute<CheckRoute>().firstRun, onBack = back, onDone = { nav.popBackStack(CaptureRoute, inclusive = false) })
            }
        }
        composable<SettingsRoute> {
            AppTheme(darkTheme) {
                SettingsScreen(
                    onBack = back,
                    onCameraCheck = { nav.navigate(CheckRoute(firstRun = false)) },
                    onPrivacy = { nav.navigate(PrivacyRoute) },
                    onLicences = { nav.navigate(LicencesRoute) },
                )
            }
        }
        composable<PrivacyRoute> { AppTheme(darkTheme) { PrivacyScreen(onBack = back) } }
        composable<LicencesRoute> { AppTheme(darkTheme) { LicencesScreen(onBack = back) } }
    }
}

@Composable
private fun SystemBars(dark: Boolean) {
    val view = LocalView.current
    LaunchedEffect(dark) {
        if (view.isInEditMode) return@LaunchedEffect
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
