package com.mohdshayan.slowglass.di

import android.content.Context
import android.os.Build
import com.mohdshayan.slowglass.core.quirks.DeviceInfo
import com.mohdshayan.slowglass.core.quirks.QuirkRules
import com.mohdshayan.slowglass.core.quirks.Quirks
import com.mohdshayan.slowglass.data.db.AppDatabase
import com.mohdshayan.slowglass.data.prefs.AppPrefs
import com.mohdshayan.slowglass.data.repo.SessionRepository

/**
 * Manual dependency container, initialised in App.onCreate and idempotent so the capture service
 * may call init() again with whatever context it has.
 */
object ServiceLocator {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) appContext = context.applicationContext
            }
        }
    }

    private fun ctx(): Context =
        appContext ?: error("ServiceLocator.init() must be called before use")

    val appPrefs: AppPrefs by lazy { AppPrefs(ctx()) }

    val database: AppDatabase by lazy { AppDatabase.get(ctx()) }

    val sessions: SessionRepository by lazy { SessionRepository(database, appPrefs, ctx().contentResolver) }

    /** Device overrides from assets/device_quirks.json for this phone. */
    val quirks: Quirks by lazy {
        val text = runCatching { ctx().assets.open("device_quirks.json").bufferedReader().use { it.readText() } }.getOrDefault("")
        QuirkRules.resolve(
            QuirkRules.parse(text),
            DeviceInfo(Build.MANUFACTURER, Build.MODEL, Build.HARDWARE, Build.VERSION.SDK_INT),
        )
    }
}
