package com.mohdshayan.slowglass.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * One binding to the capture service for the whole process. Screens read the engine state from here;
 * the camera itself opens only while a screen says it is visible or a session runs.
 */
object CaptureConnection {
    private val _service = MutableStateFlow<CaptureService?>(null)
    val service: StateFlow<CaptureService?> = _service.asStateFlow()
    private var bound = false

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<EngineState> = _service.flatMapLatest { it?.state ?: flowOf(EngineState()) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            _service.value = (binder as? CaptureService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _service.value = null
        }
    }

    fun ensureBound(context: Context) {
        if (bound) return
        val app = context.applicationContext
        bound = app.bindService(Intent(app, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    /** Volume keys act as the shutter while the Capture screen is on top and the setting is on. */
    @Volatile var volumeKeysActive = false
    private val _volumePresses = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val volumePresses = _volumePresses.asSharedFlow()
    fun onVolumeKey(): Boolean {
        if (!volumeKeysActive) return false
        _volumePresses.tryEmit(Unit)
        return true
    }
}
