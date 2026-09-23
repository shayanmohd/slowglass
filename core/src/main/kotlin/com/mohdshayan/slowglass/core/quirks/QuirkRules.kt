package com.mohdshayan.slowglass.core.quirks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a device rule can switch off. Everything defaults to the normal path. */
data class Quirks(
    val forceRgba8: Boolean = false,
    val cap1080p: Boolean = false,
    val skipFpsRequest: Boolean = false,
) {
    fun merge(o: Quirks) = Quirks(forceRgba8 || o.forceRgba8, cap1080p || o.cap1080p, skipFpsRequest || o.skipFpsRequest)
}

@Serializable
data class QuirkRule(
    val manufacturer: String? = null,
    val modelPrefix: String? = null,
    val hardware: String? = null,
    val minSdk: Int? = null,
    val maxSdk: Int? = null,
    val forceRgba8: Boolean = false,
    val cap1080p: Boolean = false,
    val skipFpsRequest: Boolean = false,
    val note: String = "",
)

@Serializable
data class QuirkFile(val schema: Int = 1, val rules: List<QuirkRule> = emptyList())

data class DeviceInfo(val manufacturer: String, val model: String, val hardware: String, val sdk: Int)

/**
 * Device overrides bundled as assets/device_quirks.json. A rule matches when every field it names
 * matches (case-insensitive); matching rules combine. A file that fails to parse means no overrides,
 * never a crash on launch.
 */
object QuirkRules {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): QuirkFile = runCatching { json.decodeFromString(QuirkFile.serializer(), text) }
        .getOrDefault(QuirkFile())

    fun matches(rule: QuirkRule, d: DeviceInfo): Boolean {
        if (rule.manufacturer == null && rule.modelPrefix == null && rule.hardware == null) return false
        if (rule.manufacturer != null && !rule.manufacturer.equals(d.manufacturer, ignoreCase = true)) return false
        if (rule.modelPrefix != null && !d.model.startsWith(rule.modelPrefix, ignoreCase = true)) return false
        if (rule.hardware != null && !rule.hardware.equals(d.hardware, ignoreCase = true)) return false
        if (rule.minSdk != null && d.sdk < rule.minSdk) return false
        if (rule.maxSdk != null && d.sdk > rule.maxSdk) return false
        return true
    }

    fun resolve(file: QuirkFile, d: DeviceInfo): Quirks =
        file.rules.filter { matches(it, d) }
            .fold(Quirks()) { acc, r -> acc.merge(Quirks(r.forceRgba8, r.cap1080p, r.skipFpsRequest)) }
}
