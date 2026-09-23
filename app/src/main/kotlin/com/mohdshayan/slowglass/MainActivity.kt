package com.mohdshayan.slowglass

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohdshayan.slowglass.capture.CaptureConnection
import com.mohdshayan.slowglass.data.prefs.Settings
import com.mohdshayan.slowglass.data.prefs.ThemeMode
import com.mohdshayan.slowglass.di.ServiceLocator
import com.mohdshayan.slowglass.ui.nav.AppNav

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CaptureConnection.ensureBound(this)
        setContent {
            val settings by ServiceLocator.appPrefs.settings.collectAsStateWithLifecycle(initialValue = Settings())
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            AppNav(darkTheme = dark)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            event?.repeatCount == 0 && CaptureConnection.onVolumeKey()
        ) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && CaptureConnection.volumeKeysActive) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
