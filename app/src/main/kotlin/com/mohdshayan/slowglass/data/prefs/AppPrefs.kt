package com.mohdshayan.slowglass.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mohdshayan.slowglass.core.stack.LightningSensitivity
import com.mohdshayan.slowglass.core.stack.NoiseSmoothing
import com.mohdshayan.slowglass.core.stack.StackMode
import com.mohdshayan.slowglass.core.stack.StarSensitivity
import com.mohdshayan.slowglass.core.timer.BulbPreset
import com.mohdshayan.slowglass.core.timer.TripodDelay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "Follow the phone"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** Everything the user can set, read as one snapshot. */
data class Settings(
    val defaultMode: StackMode = StackMode.TRAILS,
    val bulbPresetMs: Long = BulbPreset.DEFAULT_MS,
    val tripodDelayS: Int = TripodDelay.DEFAULT,
    val noiseSmoothing: NoiseSmoothing = NoiseSmoothing.LOW,
    val keepLights: Float = 0.5f,
    val starSensitivity: Int = StarSensitivity.DEFAULT,
    val lightningSensitivity: LightningSensitivity = LightningSensitivity.MEDIUM,
    val dimDuringSession: Boolean = true,
    val volumeKeysShutter: Boolean = true,
    val saveSharpest: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val cameraCheckDone: Boolean = false,
    val notificationAsked: Boolean = false,
    val usageCountsOptIn: Boolean = false,
    val countSessions: Int = 0,
    val countSaves: Int = 0,
    val countStrikes: Int = 0,
)

class AppPrefs(private val context: Context) {

    private object Keys {
        val DEFAULT_MODE = stringPreferencesKey("default_mode")
        val BULB_PRESET_MS = longPreferencesKey("bulb_preset_ms")
        val TRIPOD_DELAY_S = intPreferencesKey("tripod_delay_s")
        val NOISE_SMOOTHING = stringPreferencesKey("noise_smoothing")
        val KEEP_LIGHTS = floatPreferencesKey("keep_lights")
        val STAR_SENSITIVITY = intPreferencesKey("star_sensitivity")
        val LIGHTNING_SENSITIVITY = stringPreferencesKey("lightning_sensitivity")
        val DIM_DURING_SESSION = booleanPreferencesKey("dim_during_session")
        val VOLUME_KEYS_SHUTTER = booleanPreferencesKey("volume_keys_shutter")
        val SAVE_SHARPEST = booleanPreferencesKey("save_sharpest")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CAMERA_CHECK_DONE = booleanPreferencesKey("camera_check_done")
        val SUCCESS_COUNT = intPreferencesKey("success_count")
        val NOTIFICATION_ASKED = booleanPreferencesKey("notification_asked")
        val USAGE_COUNTS_OPT_IN = booleanPreferencesKey("usage_counts_opt_in")
        val COUNT_SESSIONS = intPreferencesKey("count_sessions")
        val COUNT_SAVES = intPreferencesKey("count_saves")
        val COUNT_STRIKES = intPreferencesKey("count_strikes")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            defaultMode = StackMode.fromId(p[Keys.DEFAULT_MODE]),
            bulbPresetMs = BulbPreset.normalise(p[Keys.BULB_PRESET_MS] ?: BulbPreset.DEFAULT_MS),
            tripodDelayS = TripodDelay.normalise(p[Keys.TRIPOD_DELAY_S] ?: TripodDelay.DEFAULT),
            noiseSmoothing = NoiseSmoothing.fromId(p[Keys.NOISE_SMOOTHING]),
            keepLights = (p[Keys.KEEP_LIGHTS] ?: 0.5f).coerceIn(0f, 1f),
            starSensitivity = StarSensitivity.clamp(p[Keys.STAR_SENSITIVITY] ?: StarSensitivity.DEFAULT),
            lightningSensitivity = LightningSensitivity.fromId(p[Keys.LIGHTNING_SENSITIVITY]),
            dimDuringSession = p[Keys.DIM_DURING_SESSION] ?: true,
            volumeKeysShutter = p[Keys.VOLUME_KEYS_SHUTTER] ?: true,
            saveSharpest = p[Keys.SAVE_SHARPEST] ?: true,
            themeMode = ThemeMode.fromId(p[Keys.THEME_MODE]),
            cameraCheckDone = p[Keys.CAMERA_CHECK_DONE] ?: false,
            notificationAsked = p[Keys.NOTIFICATION_ASKED] ?: false,
            usageCountsOptIn = p[Keys.USAGE_COUNTS_OPT_IN] ?: false,
            countSessions = p[Keys.COUNT_SESSIONS] ?: 0,
            countSaves = p[Keys.COUNT_SAVES] ?: 0,
            countStrikes = p[Keys.COUNT_STRIKES] ?: 0,
        )
    }

    suspend fun current(): Settings = settings.first()

    private suspend fun set(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    suspend fun setDefaultMode(m: StackMode) = set { it[Keys.DEFAULT_MODE] = m.id }
    suspend fun setBulbPreset(ms: Long) = set { it[Keys.BULB_PRESET_MS] = ms }
    suspend fun setTripodDelay(s: Int) = set { it[Keys.TRIPOD_DELAY_S] = s }
    suspend fun setNoiseSmoothing(n: NoiseSmoothing) = set { it[Keys.NOISE_SMOOTHING] = n.id }
    suspend fun setKeepLights(v: Float) = set { it[Keys.KEEP_LIGHTS] = v.coerceIn(0f, 1f) }
    suspend fun setStarSensitivity(v: Int) = set { it[Keys.STAR_SENSITIVITY] = StarSensitivity.clamp(v) }
    suspend fun setLightningSensitivity(v: LightningSensitivity) = set { it[Keys.LIGHTNING_SENSITIVITY] = v.id }
    suspend fun setDimDuringSession(v: Boolean) = set { it[Keys.DIM_DURING_SESSION] = v }
    suspend fun setVolumeKeysShutter(v: Boolean) = set { it[Keys.VOLUME_KEYS_SHUTTER] = v }
    suspend fun setSaveSharpest(v: Boolean) = set { it[Keys.SAVE_SHARPEST] = v }
    suspend fun setThemeMode(v: ThemeMode) = set { it[Keys.THEME_MODE] = v.id }
    suspend fun setCameraCheckDone(v: Boolean) = set { it[Keys.CAMERA_CHECK_DONE] = v }
    suspend fun setNotificationAsked(v: Boolean) = set { it[Keys.NOTIFICATION_ASKED] = v }
    suspend fun setUsageCountsOptIn(v: Boolean) = set { it[Keys.USAGE_COUNTS_OPT_IN] = v }

    /** Local counts, kept only when the user opted in. They never leave the phone. */
    suspend fun recordSession(saved: Boolean, strikes: Int) = set {
        it[Keys.SUCCESS_COUNT] = (it[Keys.SUCCESS_COUNT] ?: 0) + if (saved) 1 else 0
        if (it[Keys.USAGE_COUNTS_OPT_IN] == true) {
            it[Keys.COUNT_SESSIONS] = (it[Keys.COUNT_SESSIONS] ?: 0) + 1
            if (saved) it[Keys.COUNT_SAVES] = (it[Keys.COUNT_SAVES] ?: 0) + 1
            it[Keys.COUNT_STRIKES] = (it[Keys.COUNT_STRIKES] ?: 0) + strikes
        }
    }

    suspend fun clearCounts() = set {
        it[Keys.COUNT_SESSIONS] = 0; it[Keys.COUNT_SAVES] = 0; it[Keys.COUNT_STRIKES] = 0
    }

    /** The settings that travel in the export file, as strings. */
    suspend fun exportMap(): Map<String, String> {
        val s = current()
        return mapOf(
            "default_mode" to s.defaultMode.id,
            "bulb_preset_ms" to s.bulbPresetMs.toString(),
            "tripod_delay_s" to s.tripodDelayS.toString(),
            "noise_smoothing" to s.noiseSmoothing.id,
            "keep_lights" to s.keepLights.toString(),
            "star_sensitivity" to s.starSensitivity.toString(),
            "lightning_sensitivity" to s.lightningSensitivity.id,
            "dim_during_session" to s.dimDuringSession.toString(),
            "volume_keys_shutter" to s.volumeKeysShutter.toString(),
            "save_sharpest" to s.saveSharpest.toString(),
            "theme_mode" to s.themeMode.id,
        )
    }

    suspend fun importMap(m: Map<String, String>) = set { p ->
        m["default_mode"]?.let { p[Keys.DEFAULT_MODE] = StackMode.fromId(it).id }
        m["bulb_preset_ms"]?.toLongOrNull()?.let { p[Keys.BULB_PRESET_MS] = BulbPreset.normalise(it) }
        m["tripod_delay_s"]?.toIntOrNull()?.let { p[Keys.TRIPOD_DELAY_S] = TripodDelay.normalise(it) }
        m["noise_smoothing"]?.let { p[Keys.NOISE_SMOOTHING] = NoiseSmoothing.fromId(it).id }
        m["keep_lights"]?.toFloatOrNull()?.let { p[Keys.KEEP_LIGHTS] = it.coerceIn(0f, 1f) }
        m["star_sensitivity"]?.toIntOrNull()?.let { p[Keys.STAR_SENSITIVITY] = StarSensitivity.clamp(it) }
        m["lightning_sensitivity"]?.let { p[Keys.LIGHTNING_SENSITIVITY] = LightningSensitivity.fromId(it).id }
        m["dim_during_session"]?.toBooleanStrictOrNull()?.let { p[Keys.DIM_DURING_SESSION] = it }
        m["volume_keys_shutter"]?.toBooleanStrictOrNull()?.let { p[Keys.VOLUME_KEYS_SHUTTER] = it }
        m["save_sharpest"]?.toBooleanStrictOrNull()?.let { p[Keys.SAVE_SHARPEST] = it }
        m["theme_mode"]?.let { p[Keys.THEME_MODE] = ThemeMode.fromId(it).id }
    }
}
