package com.mohdshayan.slowglass.core.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Serializable
data class StrikeDto(
    val at: Long,
    val peakDelta: Float,
    val photoUri: String,
)

@Serializable
data class SessionDto(
    val startedAt: Long,
    val endedAt: Long,
    val mode: String,
    val durationMs: Long,
    val frameCount: Int,
    val droppedFrames: Int = 0,
    val gapsBridged: Int = 0,
    val gapLog: String = "[]",
    val streamWidth: Int,
    val streamHeight: Int,
    val precision: String = "RGBA16F",
    val rotationDegrees: Int = 0,
    val settingsJson: String = "{}",
    val stackUri: String? = null,
    val sharpestUri: String? = null,
    val note: String = "",
    val strikes: List<StrikeDto> = emptyList(),
)

@Serializable
data class CameraCheckDto(
    val ranAt: Long,
    val cameraId: String,
    val streamWidth: Int,
    val streamHeight: Int,
    val measuredFps: Float,
    val halfFloat: Boolean,
    val infinityFocus: Boolean,
    val holds4k: Boolean,
    val verdict: String,
)

@Serializable
data class ExportFile(
    val format: String = FORMAT,
    val schema: Int = SCHEMA,
    val prefs: Map<String, String> = emptyMap(),
    val sessions: List<SessionDto> = emptyList(),
    val cameraChecks: List<CameraCheckDto> = emptyList(),
) {
    companion object {
        const val FORMAT = "slowglass-sessions"
        const val SCHEMA = 1
    }
}

sealed interface DecodeResult {
    data class Ok(val file: ExportFile) : DecodeResult
    data class Invalid(val reason: String) : DecodeResult
}

/** The session log file: JSON out, validated JSON in, and the merge rule for importing it. */
object SessionCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(file: ExportFile): String = json.encodeToString(ExportFile.serializer(), file)

    fun decode(text: String): DecodeResult {
        val file = try {
            // The format field must be present, not filled in from the default.
            // A byte order mark from a text editor is not part of the JSON.
            val root = json.parseToJsonElement(text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')).jsonObject
            if (root["format"]?.jsonPrimitive?.contentOrNull != ExportFile.FORMAT) return DecodeResult.Invalid("format")
            json.decodeFromJsonElement(ExportFile.serializer(), root)
        } catch (e: Exception) {
            return DecodeResult.Invalid("not json")
        }
        if (file.schema > ExportFile.SCHEMA || file.schema < 1) return DecodeResult.Invalid("schema")
        val known = setOf("trails", "water", "motion", "stars", "lightning")
        val bad = file.sessions.firstOrNull {
            it.mode !in known || it.durationMs < 0 || it.frameCount < 0 || it.droppedFrames < 0 ||
                it.gapsBridged < 0 || it.streamWidth < 0 || it.streamHeight < 0
        }
        if (bad != null) return DecodeResult.Invalid("session")
        return DecodeResult.Ok(file)
    }

    /** A session is the same one when it started at the same instant in the same mode. */
    fun key(s: SessionDto): Pair<Long, String> = s.startedAt to s.mode

    /** Sessions from [incoming] not already present in [existingKeys], in their original order. */
    fun newSessions(incoming: List<SessionDto>, existingKeys: Set<Pair<Long, String>>): List<SessionDto> {
        val seen = HashSet(existingKeys)
        return incoming.filter { seen.add(key(it)) }
    }

    fun fileName(yyyyMmDd: String, ext: String): String = "slowglass-sessions-$yyyyMmDd.$ext"

    /** The local calendar date of [millis] in [zone], for the export file name. */
    fun fileDate(millis: Long, zone: TimeZone): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.format(Date(millis))
}

/** The flat CSV view of the same log, for a spreadsheet. */
object SessionCsv {
    const val HEADER = "started,mode,length_s,frames,dropped,gaps_bridged,width,height,precision,strikes,stack_file"

    fun encode(sessions: List<SessionDto>, isoTime: (Long) -> String): String = buildString {
        append(HEADER).append('\n')
        for (s in sessions) {
            val fields = listOf(
                isoTime(s.startedAt),
                s.mode,
                (s.durationMs / 1000.0).let { if (it % 1.0 == 0.0) it.toLong().toString() else "%.1f".format(Locale.US, it) },
                s.frameCount.toString(),
                s.droppedFrames.toString(),
                s.gapsBridged.toString(),
                s.streamWidth.toString(),
                s.streamHeight.toString(),
                s.precision,
                s.strikes.size.toString(),
                s.stackUri ?: "",
            )
            append(fields.joinToString(",") { escape(it) }).append('\n')
        }
    }

    /** ISO 8601 local time with its UTC offset, so a row stays unambiguous across daylight saving. */
    fun isoTime(millis: Long, zone: TimeZone): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = zone }.format(Date(millis))

    fun escape(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
