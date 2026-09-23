package com.mohdshayan.slowglass.data.repo

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import com.mohdshayan.slowglass.core.export.CameraCheckDto
import com.mohdshayan.slowglass.core.export.DecodeResult
import com.mohdshayan.slowglass.core.export.ExportFile
import com.mohdshayan.slowglass.core.export.SessionCodec
import com.mohdshayan.slowglass.core.export.SessionCsv
import com.mohdshayan.slowglass.core.export.SessionDto
import com.mohdshayan.slowglass.core.export.StrikeDto
import com.mohdshayan.slowglass.data.db.AppDatabase
import com.mohdshayan.slowglass.data.db.CameraCheckEntity
import com.mohdshayan.slowglass.data.db.SessionEntity
import com.mohdshayan.slowglass.data.db.StrikeEntity
import com.mohdshayan.slowglass.data.prefs.AppPrefs
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

sealed interface ImportOutcome {
    data class Imported(val added: Int, val skipped: Int, val missingPhotos: Int) : ImportOutcome
    data object NotSlowglass : ImportOutcome
}

class SessionRepository(
    private val db: AppDatabase,
    private val prefs: AppPrefs,
    private val resolver: ContentResolver,
) {
    private val sessions = db.sessionDao()
    private val checks = db.cameraCheckDao()

    fun observeAll() = sessions.observeAll()
    fun observe(id: Long) = sessions.observe(id)
    fun observeLatest() = sessions.observeLatest()
    fun observeStrikes(id: Long) = sessions.observeStrikes(id)
    fun observeLatestCheck() = checks.observeLatest()
    suspend fun latestCheck() = checks.latest()
    suspend fun saveCheck(c: CameraCheckEntity) = checks.insert(c)
    suspend fun count(): Int = sessions.all().size

    suspend fun insert(session: SessionEntity, strikes: List<StrikeEntity>): Long =
        sessions.insertWithStrikes(session, strikes)

    suspend fun deleteEntry(id: Long) = sessions.delete(id)

    /** True when the photo behind [uri] still exists on this phone. */
    suspend fun exists(uri: String?): Boolean = withContext(Dispatchers.IO) {
        if (uri == null || uri.isEmpty()) return@withContext false
        try {
            resolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun exportJson(): Pair<String, Int> = withContext(Dispatchers.IO) {
        val list = dtos()
        val file = ExportFile(
            prefs = prefs.exportMap(),
            sessions = list,
            cameraChecks = checks.all().map {
                CameraCheckDto(it.ranAt, it.cameraId, it.streamWidth, it.streamHeight, it.measuredFps, it.halfFloat, it.infinityFocus, it.holds4k, it.verdict)
            },
        )
        SessionCodec.encode(file) to list.size
    }

    suspend fun exportCsv(): Pair<String, Int> = withContext(Dispatchers.IO) {
        val list = dtos()
        val zone = TimeZone.getDefault()
        SessionCsv.encode(list) { SessionCsv.isoTime(it, zone) } to list.size
    }

    private suspend fun dtos(): List<SessionDto> {
        val strikes = sessions.allStrikes().groupBy { it.sessionId }
        return sessions.all().map { s ->
            SessionDto(
                startedAt = s.startedAt, endedAt = s.endedAt, mode = s.mode, durationMs = s.durationMs,
                frameCount = s.frameCount, droppedFrames = s.droppedFrames, gapsBridged = s.gapsBridged,
                gapLog = s.gapLog, streamWidth = s.streamWidth, streamHeight = s.streamHeight,
                precision = s.precision, rotationDegrees = s.rotationDegrees, settingsJson = s.settingsJson,
                stackUri = s.stackUri, sharpestUri = s.sharpestUri, note = s.note,
                strikes = strikes[s.id].orEmpty().map { StrikeDto(it.at, it.peakDelta, it.photoUri) },
            )
        }
    }

    /** Validates the file, then merges its sessions on (startedAt, mode) in one transaction. */
    suspend fun import(text: String): ImportOutcome = withContext(Dispatchers.IO) {
        val file = when (val r = SessionCodec.decode(text)) {
            is DecodeResult.Invalid -> return@withContext ImportOutcome.NotSlowglass
            is DecodeResult.Ok -> r.file
        }
        val existing = sessions.keys().map { it.startedAt to it.mode }.toSet()
        val fresh = SessionCodec.newSessions(file.sessions, existing)
        var missing = 0
        for (s in fresh) if (s.stackUri != null && !exists(s.stackUri)) missing++
        val knownChecks = checks.all().map { it.ranAt }.toSet()
        db.withTransaction {
            for (s in fresh) {
                sessions.insertWithStrikes(
                    SessionEntity(
                        startedAt = s.startedAt, endedAt = s.endedAt, mode = s.mode, durationMs = s.durationMs,
                        frameCount = s.frameCount, droppedFrames = s.droppedFrames, gapsBridged = s.gapsBridged,
                        gapLog = s.gapLog, streamWidth = s.streamWidth, streamHeight = s.streamHeight,
                        precision = s.precision, rotationDegrees = s.rotationDegrees, settingsJson = s.settingsJson,
                        stackUri = s.stackUri, sharpestUri = s.sharpestUri, note = s.note,
                    ),
                    s.strikes.map { StrikeEntity(sessionId = 0, at = it.at, peakDelta = it.peakDelta, photoUri = it.photoUri) },
                )
            }
            checks.insertAll(
                file.cameraChecks.filter { it.ranAt !in knownChecks }.map {
                    CameraCheckEntity(0, it.ranAt, it.cameraId, it.streamWidth, it.streamHeight, it.measuredFps, it.halfFloat, it.infinityFocus, it.holds4k, it.verdict)
                },
            )
        }
        if (file.prefs.isNotEmpty()) prefs.importMap(file.prefs)
        ImportOutcome.Imported(fresh.size, file.sessions.size - fresh.size, missing)
    }

    companion object {
        fun today(): String = SessionCodec.fileDate(System.currentTimeMillis(), TimeZone.getDefault())
    }
}
