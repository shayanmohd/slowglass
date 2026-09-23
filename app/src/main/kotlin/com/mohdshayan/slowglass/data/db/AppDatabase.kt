package com.mohdshayan.slowglass.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** One long exposure: what was stacked, how, and where its photos are. */
@Entity(tableName = "sessions", indices = [Index(value = ["startedAt", "mode"], unique = true)])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val mode: String,
    val durationMs: Long,
    val frameCount: Int,
    val droppedFrames: Int,
    val gapsBridged: Int,
    /** JSON array of millisecond offsets where a stream gap was bridged. */
    val gapLog: String,
    val streamWidth: Int,
    val streamHeight: Int,
    val precision: String,
    val rotationDegrees: Int,
    /** JSON: EV, zoom and the mode's own settings. */
    val settingsJson: String,
    val stackUri: String?,
    val sharpestUri: String?,
    val note: String,
)

@Entity(
    tableName = "strikes",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class StrikeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val at: Long,
    val peakDelta: Float,
    val photoUri: String,
)

@Entity(tableName = "camera_checks")
data class CameraCheckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    suspend fun all(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT 1")
    fun observeLatest(): Flow<SessionEntity?>

    @Query("SELECT startedAt, mode FROM sessions")
    suspend fun keys(): List<SessionKey>

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM strikes WHERE sessionId = :sessionId ORDER BY at")
    fun observeStrikes(sessionId: Long): Flow<List<StrikeEntity>>

    @Query("SELECT * FROM strikes ORDER BY at")
    suspend fun allStrikes(): List<StrikeEntity>

    @Insert
    suspend fun insertStrikes(strikes: List<StrikeEntity>)

    @Transaction
    suspend fun insertWithStrikes(session: SessionEntity, strikes: List<StrikeEntity>): Long {
        val id = insert(session)
        if (strikes.isNotEmpty()) insertStrikes(strikes.map { it.copy(sessionId = id) })
        return id
    }
}

data class SessionKey(val startedAt: Long, val mode: String)

@Dao
interface CameraCheckDao {
    @Query("SELECT * FROM camera_checks ORDER BY ranAt DESC LIMIT 1")
    fun observeLatest(): Flow<CameraCheckEntity?>

    @Query("SELECT * FROM camera_checks ORDER BY ranAt DESC LIMIT 1")
    suspend fun latest(): CameraCheckEntity?

    @Query("SELECT * FROM camera_checks ORDER BY ranAt")
    suspend fun all(): List<CameraCheckEntity>

    @Insert
    suspend fun insert(check: CameraCheckEntity): Long

    @Insert
    suspend fun insertAll(checks: List<CameraCheckEntity>)
}

@Database(
    entities = [SessionEntity::class, StrikeEntity::class, CameraCheckEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun cameraCheckDao(): CameraCheckDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "slowglass.db",
                ).build().also { instance = it }
            }
    }
}
