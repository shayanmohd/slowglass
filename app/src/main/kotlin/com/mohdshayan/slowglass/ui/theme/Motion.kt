package com.mohdshayan.slowglass.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * True when the user has turned system animations off. Compose has no
 * prefers-reduced-motion, so the animator duration scale is the Android
 * equivalent. Every animation reads LocalReducedMotion.current and snaps
 * to its end state instead of animating when it is true.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
