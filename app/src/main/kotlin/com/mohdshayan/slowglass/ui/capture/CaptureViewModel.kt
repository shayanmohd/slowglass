package com.mohdshayan.slowglass.ui.capture

import android.app.Application
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mohdshayan.slowglass.capture.CaptureConnection
import com.mohdshayan.slowglass.capture.EngineState
import com.mohdshayan.slowglass.capture.Phase
import com.mohdshayan.slowglass.capture.SavedResult
import com.mohdshayan.slowglass.core.stack.LightningSensitivity
import com.mohdshayan.slowglass.core.stack.NoiseSmoothing
import com.mohdshayan.slowglass.core.stack.StackMode
import com.mohdshayan.slowglass.core.timer.StopReason
import com.mohdshayan.slowglass.data.db.SessionEntity
import com.mohdshayan.slowglass.data.prefs.Settings
import com.mohdshayan.slowglass.di.ServiceLocator
import com.mohdshayan.slowglass.save.MediaDeleter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CaptureViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = ServiceLocator.appPrefs
    private val repo = ServiceLocator.sessions

    init {
        CaptureConnection.ensureBound(app)
    }

    val engine: StateFlow<EngineState> =
        CaptureConnection.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EngineState())
    val settings: StateFlow<Settings?> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val latest: StateFlow<SessionEntity?> =
        repo.observeLatest().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val service get() = CaptureConnection.service.value

    fun setVisible(visible: Boolean) = service?.setUiVisible(visible)
    fun onPermissionGranted() = service?.onPermissionGranted()
    fun retryCamera() = service?.retryCamera()
    fun setMode(m: StackMode) = service?.setMode(m)
    fun setZoom(z: Float) = service?.setZoom(z)
    fun setEv(ev: Float) = service?.setEv(ev)
    fun focusAt(u: Float, v: Float) = service?.focusAt(u, v)
    fun clearNotice() = service?.clearNotice()
    fun dismissInterrupted() = service?.dismissInterrupted()

    fun shutter() {
        val s = service ?: return
        when (s.state.value.phase) {
            Phase.Idle -> s.requestStart()
            is Phase.Countdown, is Phase.Running -> s.stopSession(StopReason.USER)
            Phase.Saving -> Unit
        }
    }

    fun retrySave() = service?.retrySave()
    fun discard() = service?.discardPending()
    fun doneWithSaved() = service?.clearOutcome()

    /** Deletes both photos and the entry. Returns Android's delete question when it needs one. */
    suspend fun deleteSaved(r: SavedResult): IntentSender? {
        val uris = listOfNotNull(r.stackUri, r.sharpestUri)
        val sender = MediaDeleter.delete(getApplication(), uris)
        repo.deleteEntry(r.sessionId)
        service?.clearOutcome()
        return sender
    }

    fun setPreset(ms: Long) = viewModelScope.launch { prefs.setBulbPreset(ms) }
    fun setDelay(s: Int) = viewModelScope.launch { prefs.setTripodDelay(s) }
    fun setNoise(n: NoiseSmoothing) = viewModelScope.launch { prefs.setNoiseSmoothing(n) }
    fun setKeepLights(v: Float) = viewModelScope.launch { prefs.setKeepLights(v) }
    fun setStarSensitivity(v: Int) = viewModelScope.launch { prefs.setStarSensitivity(v) }
    fun setLightning(v: LightningSensitivity) = viewModelScope.launch { prefs.setLightningSensitivity(v) }
    suspend fun cameraCheckDone(): Boolean = prefs.current().cameraCheckDone

    fun markNotificationAsked() = viewModelScope.launch { prefs.setNotificationAsked(true) }
}
