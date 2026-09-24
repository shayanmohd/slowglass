package com.mohdshayan.slowglass.capture

import android.net.Uri
import com.mohdshayan.slowglass.core.check.CheckReport
import com.mohdshayan.slowglass.core.stack.StackMode

sealed interface Phase {
    data object Idle : Phase
    data class Countdown(val secondsLeft: Int) : Phase
    /** [startedAtRealtime] is SystemClock.elapsedRealtime() when stacking began. */
    data class Running(val startedAtRealtime: Long) : Phase
    data object Saving : Phase
}

/** A finished session as the saved sheet shows it. */
data class SavedResult(
    val sessionId: Long,
    val mode: StackMode,
    val stackUri: Uri,
    val sharpestUri: Uri?,
    val width: Int,
    val height: Int,
)

sealed interface SaveOutcome {
    data class Saved(val result: SavedResult) : SaveOutcome
    /** The stack is held in memory until the user taps Save again. */
    data object Failed : SaveOutcome
    /** Lightning stopped before any strike: nothing to save. */
    data object NothingCaught : SaveOutcome
}

sealed interface CheckProgress {
    data object Idle : CheckProgress
    data class Running(val step: String) : CheckProgress
    data class Done(val report: CheckReport) : CheckProgress
    data class Failed(val message: String) : CheckProgress
}

data class EngineState(
    val camera: CamStatus = CamStatus.STARTING,
    /** True once frames reach the renderer after the camera opened. */
    val streaming: Boolean = false,
    val phase: Phase = Phase.Idle,
    val mode: StackMode = StackMode.TRAILS,
    val presetMs: Long = 30_000,
    val frames: Int = 0,
    val strikes: Int = 0,
    val streamWidth: Int = 0,
    val streamHeight: Int = 0,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val zoomChips: List<Float> = listOf(1f),
    val zoom: Float = 1f,
    /** Brightness in EV, limited to -2..+2 and the camera's own range. */
    val ev: Float = 0f,
    val evMin: Float = 0f,
    val evMax: Float = 0f,
    val evStep: Float = 0.5f,
    val outcome: SaveOutcome? = null,
    /** One-line message such as "Strike 2 saved" or why a session stopped. */
    val notice: String? = null,
    val noticeId: Long = 0,
    val check: CheckProgress = CheckProgress.Idle,
    /** Set at launch when the last session was killed before it could save; shown until dismissed. */
    val interrupted: String? = null,
)
