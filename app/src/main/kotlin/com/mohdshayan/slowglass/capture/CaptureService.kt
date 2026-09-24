package com.mohdshayan.slowglass.capture

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mohdshayan.slowglass.MainActivity
import com.mohdshayan.slowglass.R
import com.mohdshayan.slowglass.core.check.CheckMeasurements
import com.mohdshayan.slowglass.core.check.CheckVerdict
import com.mohdshayan.slowglass.core.exif.ExifText
import com.mohdshayan.slowglass.core.orient.OrientationLatch
import com.mohdshayan.slowglass.core.orient.OrientationMapper
import com.mohdshayan.slowglass.core.stack.StackMode
import com.mohdshayan.slowglass.core.stars.GapBridge
import com.mohdshayan.slowglass.core.timer.ExposureClock
import com.mohdshayan.slowglass.core.timer.InProgressSession
import com.mohdshayan.slowglass.core.timer.SessionGuard
import com.mohdshayan.slowglass.core.timer.SessionPulse
import com.mohdshayan.slowglass.core.timer.StopReason
import com.mohdshayan.slowglass.data.db.CameraCheckEntity
import com.mohdshayan.slowglass.data.db.SessionEntity
import com.mohdshayan.slowglass.data.db.StrikeEntity
import com.mohdshayan.slowglass.data.prefs.Settings
import com.mohdshayan.slowglass.di.ServiceLocator
import com.mohdshayan.slowglass.gl.Pixels
import com.mohdshayan.slowglass.gl.StackConfig
import com.mohdshayan.slowglass.gl.StackRenderer
import com.mohdshayan.slowglass.gl.StackResult
import com.mohdshayan.slowglass.save.PhotoMeta
import com.mohdshayan.slowglass.save.PhotoSaver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Owns the camera, the GL renderer and every session. The Capture screen binds to it for the
 * viewfinder; a shutter tap also starts it as a camera foreground service, so a star session keeps
 * stacking while the screen is dimmed or off, or the user is in another app.
 */
class CaptureService : LifecycleService(), StackRenderer.Listener {

    inner class LocalBinder : Binder() {
        val service: CaptureService get() = this@CaptureService
    }

    private val binder = LocalBinder()
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private lateinit var renderer: StackRenderer
    private lateinit var camera: CameraController
    private lateinit var saver: PhotoSaver
    private val prefs by lazy { ServiceLocator.appPrefs }
    private val repo by lazy { ServiceLocator.sessions }
    private val quirks by lazy { ServiceLocator.quirks }

    private var settings = Settings()
    private var uiVisible = false
    private var checkRunning = false
    private var sessionActive = false
    @Volatile private var cameraReady = false
    private var holds4k = false
    private var tappedFocus = false
    private var evUser = 0f

    private val orientation = OrientationLatch()
    private var orientationListener: OrientationEventListener? = null
    private var orientationOn = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var thermal = 0
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private var sessionJob: Job? = null
    private var startedWall = 0L
    private var startedRealtime = 0L
    private var sessionRotation = 0
    private var sessionMode = StackMode.TRAILS
    private var sessionSettings = Settings()
    private val runningFrames = AtomicInteger(0)
    // First and latest camera timestamps while stacking, for the real frame duration.
    private val runFirstNs = AtomicLong(0)
    private val runLastNs = AtomicLong(0)
    private val strikes = mutableListOf<StrikeEntity>()
    private var pending: PendingSave? = null
    private var clip: ClipSource? = null

    // Frame-rate measurement for the camera check, written on the render thread.
    @Volatile private var measuring = false
    private val measureCount = AtomicInteger(0)
    private val measureFirst = AtomicLong(0)
    private val measureLast = AtomicLong(0)
    private val streamFrames = AtomicLong(0)

    private class PendingSave(
        val result: StackResult,
        val mode: StackMode,
        val durationMs: Long,
        /** Average camera frame interval while stacking, or 0 when fewer than two frames came. */
        val frameNs: Long,
        val rotation: Int,
        val settings: Settings,
        val strikes: List<StrikeEntity>,
    )

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        saver = PhotoSaver(this)
        renderer = StackRenderer(this)
        renderer.forceRgba8 = quirks.forceRgba8
        renderer.start()
        camera = CameraController(this, renderer) { s ->
            cameraReady = s == CamStatus.READY
            _state.update { it.copy(camera = s) }
        }
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(angle: Int) {
                orientation.onAngle(if (angle == ORIENTATION_UNKNOWN) -1 else angle)
            }
        }
        createChannel(this)
        lifecycleScope.launch {
            // A session still recorded as running belonged to a process that was killed mid exposure.
            val lost = prefs.inProgress()
            if (lost != null && !sessionActive) _state.update { it.copy(interrupted = lost.message()) }
        }
        lifecycleScope.launch {
            holds4k = repo.latestCheck()?.holds4k == true
            prefs.settings.collect { s ->
                val modeChanged = s.defaultMode != settings.defaultMode || !camera.isOpen
                settings = s
                if (!sessionActive) {
                    _state.update { it.copy(mode = s.defaultMode, presetMs = s.bulbPresetMs) }
                    if (modeChanged) rebindIfNeeded()
                }
            }
        }
        camera.init { rebindIfNeeded(); updateCameraOpen() }
        val pm = getSystemService(PowerManager::class.java)
        thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
            thermal = status
            renderer.thermalStatus = status
        }.also { pm.addThermalStatusListener(ContextCompat.getMainExecutor(this), it) }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                if (sessionJob == null && _state.value.phase == Phase.Idle) runSession()
            }
            ACTION_STOP -> stopSession(StopReason.USER)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sessionJob?.cancel()
        orientationListener?.disable()
        releaseWakeLock()
        thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        clip?.stop()
        camera.release()
        renderer.release()
        super.onDestroy()
    }

    // ---- viewfinder and controls ----

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        renderer.setPaused(false)
        updateCameraOpen()
    }

    fun attachViewfinder(surface: Surface, width: Int, height: Int) = renderer.attachDisplay(surface, width, height)
    fun resizeViewfinder(width: Int, height: Int) = renderer.resizeDisplay(width, height)
    fun detachViewfinder() = renderer.detachDisplay()

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Rebinds when the mode needs a different stream size, or nothing is bound yet. */
    fun rebindIfNeeded() {
        if (clip != null) return
        ClipFeed.open(this)?.let { c ->
            // Debug builds with a clip pushed: play it into the pipeline instead of the camera.
            clip = c
            renderer.provideClipSurface(c.width, c.height) { s -> c.start(s) }
            cameraReady = true
            _state.update { it.copy(camera = CamStatus.READY, zoomChips = listOf(1f)) }
            return
        }
        if (!hasCameraPermission()) return
        val target = targetSize(_state.value.mode)
        if (camera.camera == null || camera.boundTarget != target) {
            camera.bind(target)
            afterBind()
        }
    }

    private fun targetSize(mode: StackMode): Size =
        if (mode != StackMode.LIGHTNING && holds4k && !quirks.cap1080p) Size(3840, 2160) else Size(1920, 1080)

    private fun afterBind() {
        val f = camera.facts ?: return
        val chips = buildList {
            if (f.zoomMin < 0.95f) add((f.zoomMin * 10).roundToInt() / 10f)
            add(1f)
            if (f.zoomMax >= 2f) add(2f)
        }
        val step = f.evStep.takeIf { it > 0f } ?: 1f
        _state.update {
            it.copy(
                streaming = false,
                zoomChips = chips,
                zoom = 1f,
                evMin = maxOf(-2f, f.evMin * step),
                evMax = minOf(2f, f.evMax * step),
                evStep = if (step >= 0.5f) step else 0.5f,
            )
        }
        tappedFocus = false
        applyModeOptions(lock = false)
    }

    private fun updateCameraOpen() {
        val open = hasCameraPermission() && (uiVisible || checkRunning || sessionActive)
        if (open && camera.camera == null) rebindIfNeeded()
        if (!open) _state.update { it.copy(streaming = false) }
        camera.setOpen(open)
        setOrientationListening(open)
    }

    /** The orientation listener runs while the camera is open, so a session has a fresh reading. */
    private fun setOrientationListening(on: Boolean) {
        if (on == orientationOn) return
        orientationOn = on
        val l = orientationListener ?: return
        if (on) {
            if (l.canDetectOrientation()) l.enable()
        } else {
            l.disable()
            orientation.forget()
        }
    }

    private fun displayRotationDegrees(): Int {
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return 0
        return d.rotation * 90
    }

    fun onPermissionGranted() {
        rebindIfNeeded()
        updateCameraOpen()
    }

    fun retryCamera() {
        camera.unbind()
        rebindIfNeeded()
        updateCameraOpen()
    }

    fun setMode(mode: StackMode) {
        if (sessionActive) return
        _state.update { it.copy(mode = mode) }
        lifecycleScope.launch { prefs.setDefaultMode(mode) }
        rebindIfNeeded()
        tappedFocus = false
        applyModeOptions(lock = false)
    }

    fun setZoom(ratio: Float) {
        camera.setZoom(ratio)
        _state.update { it.copy(zoom = ratio) }
    }

    fun setEv(ev: Float) {
        val s = _state.value
        val v = ev.coerceIn(s.evMin, s.evMax)
        evUser = v
        _state.update { it.copy(ev = v) }
        applyEv(s.mode)
    }

    private fun applyEv(mode: StackMode) {
        val f = camera.facts ?: return
        val step = f.evStep.takeIf { it > 0f } ?: return
        val ev = evUser + if (mode == StackMode.LIGHTNING) -1f else 0f
        camera.setEvIndex((ev / step).roundToInt())
    }

    fun focusAt(u: Float, v: Float) {
        if (!sessionActive || _state.value.mode == StackMode.STARS) {
            tappedFocus = true
            applyModeOptions(lock = sessionActive && _state.value.phase is Phase.Running)
            camera.focusAt(u, v)
        }
    }

    private fun applyModeOptions(lock: Boolean) {
        val f = camera.facts ?: return
        val mode = _state.value.mode
        val fps = if (quirks.skipFpsRequest) null else when (mode) {
            StackMode.STARS -> f.lowFpsRange()
            StackMode.LIGHTNING -> f.lightningRange()
            else -> null
        }
        val infinity = mode == StackMode.STARS && f.infinityFocus && !tappedFocus
        camera.applyOptions(fps, lock, infinity)
        applyEv(mode)
    }

    fun clearOutcome() = _state.update { it.copy(outcome = null) }

    private fun notice(text: String) = _state.update { it.copy(notice = text, noticeId = it.noticeId + 1) }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** The user read that the last exposure was interrupted. */
    fun dismissInterrupted() {
        _state.update { it.copy(interrupted = null) }
        lifecycleScope.launch { prefs.clearInProgress() }
    }

    // ---- sessions ----

    /** Called from the shutter. Starts this service in the foreground, then counts down and stacks. */
    fun requestStart() {
        if (sessionActive || _state.value.phase != Phase.Idle || !cameraReady) return
        sessionActive = true
        updateCameraOpen()
        try {
            ContextCompat.startForegroundService(this, Intent(this, CaptureService::class.java).setAction(ACTION_START))
        } catch (e: Exception) {
            // Android refused to start the service; run the session while Slowglass stays on screen.
            runSession()
        }
    }

    private fun goForeground() {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Starting", "", null), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } catch (e: Exception) {
            // Android refused the foreground start; the session still runs while Slowglass is on screen.
        }
    }

    private fun runSession() {
        sessionActive = true
        sessionMode = _state.value.mode
        sessionSettings = settings
        strikes.clear()
        runningFrames.set(0)
        _state.update { it.copy(outcome = null, strikes = 0, frames = 0, notice = null, interrupted = null) }
        sessionJob = lifecycleScope.launch {
            for (n in sessionSettings.tripodDelayS downTo 1) {
                _state.update { it.copy(phase = Phase.Countdown(n)) }
                notify(sessionMode.label, "Starting in $n", null)
                delay(1000)
            }
            applyModeOptions(lock = true)
            val facts = camera.facts
            val longSide = maxOf(renderer.outWidth, renderer.outHeight)
            val ppd = GapBridge.pixelsPerDegree(facts?.focalMm, facts?.sensorWidthMm, longSide, _state.value.zoom.toDouble())
            // Latched once: a phone lying flat reports no angle, so this falls back to the last
            // reliable reading while the viewfinder was open, then to the display's orientation.
            sessionRotation = orientation.latch(displayRotationDegrees())
            clip?.rewind()
            val lowRam = getSystemService(ActivityManager::class.java).isLowRamDevice
            renderer.beginSession(
                StackConfig(
                    mode = sessionMode,
                    framesPerSub = when (sessionMode) {
                        StackMode.TRAILS -> sessionSettings.noiseSmoothing.framesPerSub
                        StackMode.STARS -> sessionSettings.starSensitivity
                        else -> 1
                    },
                    keepLights = sessionSettings.keepLights,
                    lightningThreshold = sessionSettings.lightningSensitivity.threshold,
                    pixelsPerDegree = ppd,
                    preRollFrames = if (lowRam) 3 else 6,
                ),
            )
            runFirstNs.set(0)
            runLastNs.set(0)
            startedWall = System.currentTimeMillis()
            startedRealtime = SystemClock.elapsedRealtime()
            _state.update {
                it.copy(phase = Phase.Running(startedRealtime), outputWidth = renderer.outWidth, outputHeight = renderer.outHeight)
            }
            acquireWakeLock()
            var lastPulse: Long? = null
            var strikesPosted = 0
            while (true) {
                delay(250)
                val elapsed = SystemClock.elapsedRealtime() - startedRealtime
                _state.update { it.copy(frames = runningFrames.get()) }
                if (ExposureClock.isDone(elapsed, sessionSettings.bulbPresetMs)) {
                    stopSession(StopReason.PRESET)
                    break
                }
                val (battery, charging) = battery()
                val reason = SessionGuard.stopReason(battery, charging, thermal)
                if (reason != null) {
                    stopSession(reason)
                    break
                }
                // The notification clock is a chronometer; only the count and the record wait for a pulse.
                if (SessionPulse.due(elapsed, lastPulse, strikesPosted, strikes.size)) {
                    lastPulse = elapsed
                    strikesPosted = strikes.size
                    notify(sessionMode.label, progressText(), startedWall)
                    prefs.markInProgress(InProgressSession(sessionMode.id, startedWall, System.currentTimeMillis(), strikesPosted))
                }
            }
        }
    }

    private fun progressText(): String =
        if (sessionMode == StackMode.LIGHTNING) {
            val n = strikes.size
            if (n == 1) "1 strike saved" else "$n strikes saved"
        } else {
            // The first pulse comes as stacking starts, often at a single frame.
            val n = runningFrames.get()
            if (n == 1) "1 frame stacked" else String.format(Locale.US, "%,d frames stacked", n)
        }

    fun stopSession(reason: StopReason) {
        val phase = _state.value.phase
        if (phase is Phase.Countdown || (phase == Phase.Idle && sessionActive)) {
            sessionJob?.cancel()
            sessionJob = null
            endSession()
            _state.update { it.copy(phase = Phase.Idle) }
            return
        }
        if (phase !is Phase.Running) return
        sessionJob?.cancel()
        sessionJob = null
        val duration = SystemClock.elapsedRealtime() - startedRealtime
        val frameNs = runningFrames.get().let { n -> if (n >= 2) (runLastNs.get() - runFirstNs.get()) / (n - 1) else 0L }
        val mode = sessionMode
        val rotation = sessionRotation
        val s = sessionSettings
        _state.update { it.copy(phase = Phase.Saving) }
        reason.message?.let { notice(it) }
        renderer.finishSession { result ->
            lifecycleScope.launch {
                // Strike photos may still be writing; give them a moment to land in the list.
                delay(300)
                endSession()
                if (result.stack == null) {
                    _state.update {
                        it.copy(
                            phase = Phase.Idle,
                            outcome = if (mode == StackMode.LIGHTNING) SaveOutcome.NothingCaught else null,
                        )
                    }
                    if (mode != StackMode.LIGHTNING) notice("No frames reached the stack. Check the camera is free, then try again.")
                    prefs.recordSession(false, 0)
                    return@launch
                }
                pending = PendingSave(result, mode, duration, frameNs, rotation, s, strikes.toList())
                persist()
            }
        }
    }

    /** Saves the held stack. Called again from the saved sheet's Save button after a failure. */
    fun retrySave() {
        if (pending == null) return
        lifecycleScope.launch { persist() }
    }

    private suspend fun persist() {
        val p = pending ?: return
        val stack = p.result.stack ?: return
        _state.update { it.copy(phase = Phase.Saving) }
        try {
            val stem = ExifText.fileStem(PhotoSaver.stamp(startedWall), p.mode.id)
            val (w, h) = OrientationMapper.rotatedSize(stack.width, stack.height, p.rotation)
            val comment = ExifText.userComment(p.mode.label, p.durationMs, p.result.framesStacked, w, h)
            val meta = PhotoMeta(startedWall, ExifText.exposureTimeRational(p.durationMs), comment)
            val stackUri = saver.save(stack, p.rotation, "$stem.jpg", meta)
            val sharp = p.result.sharpest
            val sharpUri = if (p.settings.saveSharpest && sharp != null) {
                val frameTime = ExifText.frameExposureRational(p.frameNs)
                saver.save(sharp, p.rotation, "${stem}_sharpest.jpg", PhotoMeta(startedWall, frameTime, "Slowglass ${p.mode.label}, sharpest single frame"))
            } else {
                null
            }
            val settingsJson = buildJsonObject {
                put("ev", JsonPrimitive(evUser))
                put("zoom", JsonPrimitive(_state.value.zoom))
                when (p.mode) {
                    StackMode.TRAILS -> put("noiseSmoothing", p.settings.noiseSmoothing.id)
                    StackMode.MOTION -> put("keepLights", p.settings.keepLights)
                    StackMode.STARS -> put("starSensitivity", p.settings.starSensitivity)
                    StackMode.LIGHTNING -> put("lightningSensitivity", p.settings.lightningSensitivity.id)
                    StackMode.WATER -> Unit
                }
                put("presetMs", p.settings.bulbPresetMs)
                put("tripodDelayS", p.settings.tripodDelayS)
            }.toString()
            val id = repo.insert(
                SessionEntity(
                    startedAt = startedWall,
                    endedAt = startedWall + p.durationMs,
                    mode = p.mode.id,
                    durationMs = p.durationMs,
                    frameCount = p.result.framesStacked,
                    droppedFrames = p.result.droppedFrames,
                    gapsBridged = p.result.gapsBridged,
                    gapLog = p.result.gapOffsetsMs.joinToString(",", "[", "]"),
                    streamWidth = w,
                    streamHeight = h,
                    precision = p.result.precision,
                    rotationDegrees = p.rotation,
                    settingsJson = settingsJson,
                    stackUri = stackUri.toString(),
                    sharpestUri = sharpUri?.toString(),
                    note = "",
                ),
                p.strikes,
            )
            prefs.recordSession(true, p.strikes.size)
            pending = null
            _state.update {
                it.copy(phase = Phase.Idle, outcome = SaveOutcome.Saved(SavedResult(id, p.mode, stackUri, sharpUri, w, h)))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A full disk, MediaStore refusing the file or the database failing: the stack stays in
            // memory and the saved sheet offers Save again.
            _state.update { it.copy(phase = Phase.Idle, outcome = SaveOutcome.Failed) }
        }
    }

    /** Drops a stack the user chose not to keep after a failed save. */
    fun discardPending() {
        pending = null
        clearOutcome()
    }

    private fun endSession() {
        sessionActive = false
        lifecycleScope.launch { prefs.clearInProgress() }
        releaseWakeLock()
        tappedFocus = false
        applyModeOptions(lock = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        updateCameraOpen()
    }

    private fun battery(): Pair<Int, Boolean> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 100 to true
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val pct = if (level < 0 || scale <= 0) 100 else level * 100 / scale
        return pct to plugged
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Slowglass:session")
            .apply { acquire(12 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ---- renderer callbacks, on the render thread ----

    override fun onStreamFrame(timestampNs: Long) {
        streamFrames.incrementAndGet()
        // Frames reaching the renderer prove the camera open even when CameraX never publishes OPEN.
        if (clip == null) camera.onFrame()
        if (!_state.value.streaming) {
            _state.update { it.copy(streaming = true, outputWidth = renderer.outWidth, outputHeight = renderer.outHeight) }
        }
        if (_state.value.phase is Phase.Running) {
            if (runningFrames.incrementAndGet() == 1) runFirstNs.set(timestampNs)
            runLastNs.set(timestampNs)
        }
        if (measuring) {
            if (measureCount.getAndIncrement() == 0) measureFirst.set(timestampNs)
            measureLast.set(timestampNs)
        }
    }

    override fun onStrike(pixels: Pixels, peakDelta: Float) {
        val at = System.currentTimeMillis()
        lifecycleScope.launch {
            val n = strikes.size + 1
            try {
                val stem = ExifText.fileStem(PhotoSaver.stamp(startedWall), sessionMode.id)
                val uri: Uri = saver.save(
                    pixels, sessionRotation, "${stem}_strike$n.jpg",
                    PhotoMeta(at, "12/30", "Slowglass Lightning, strike $n"),
                )
                strikes += StrikeEntity(sessionId = 0, at = at, peakDelta = peakDelta, photoUri = uri.toString())
                _state.update { it.copy(strikes = strikes.size) }
                notice("Strike $n saved")
            } catch (e: Exception) {
                notice("Strike $n could not be saved. Free some space on the phone.")
            }
        }
    }

    override fun onGlError(message: String) {
        camera.fail()
    }

    // ---- camera check ----

    fun runCheck() {
        if (checkRunning || sessionActive) return
        checkRunning = true
        lifecycleScope.launch {
            try {
                _state.update { it.copy(check = CheckProgress.Running("Opening the camera")) }
                if (!hasCameraPermission()) {
                    _state.update { it.copy(check = CheckProgress.Failed("Slowglass needs the camera to run the check.")) }
                    return@launch
                }
                val readyBy = SystemClock.elapsedRealtime() + 8000
                while (!camera.isReady && SystemClock.elapsedRealtime() < readyBy) delay(100)
                if (camera.boundTarget != Size(1920, 1080) || camera.camera == null) {
                    camera.bind(Size(1920, 1080)); afterBind()
                }
                updateCameraOpen()
                if (!waitForFrames(6000)) {
                    _state.update {
                        it.copy(check = CheckProgress.Failed("The camera did not start. Close other camera apps and run the check again."))
                    }
                    return@launch
                }
                val facts = camera.facts
                val w = renderer.outWidth
                val h = renderer.outHeight
                _state.update { it.copy(check = CheckProgress.Running("Measuring the frame rate while stacking")) }
                renderer.beginBenchmark()
                val fps = measure(2000)
                renderer.cancelSession()

                var lowOk = false
                val low = facts?.lowFpsRange()
                if (low != null && !quirks.skipFpsRequest) {
                    _state.update { it.copy(check = CheckProgress.Running("Trying a low frame rate for stars")) }
                    camera.applyOptions(low, lock = false, infinity = false)
                    delay(500)
                    val lowFps = measure(1300)
                    lowOk = lowFps > 0f && lowFps <= low.upper + 1.5f
                    camera.applyOptions(null, lock = false, infinity = false)
                }

                val ramMb = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem / (1024 * 1024)
                var fps4k: Float? = null
                val offers4k = facts?.offers4k == true && !quirks.cap1080p
                if (offers4k && renderer.precisionHalf && ramMb >= CheckVerdict.MIN_4K_RAM_MB) {
                    _state.update { it.copy(check = CheckProgress.Running("Trying 4K")) }
                    camera.bind(Size(3840, 2160)); afterBind()
                    updateCameraOpen()
                    if (waitForFrames(3000) && maxOf(renderer.outWidth, renderer.outHeight) >= 3840) {
                        renderer.beginBenchmark()
                        fps4k = measure(1500)
                        renderer.cancelSession()
                    }
                    camera.bind(Size(1920, 1080)); afterBind()
                    updateCameraOpen()
                }
                val m = CheckMeasurements(
                    streamWidth = w, streamHeight = h, measuredFps = fps,
                    halfFloat = renderer.precisionHalf, lowFpsAccepted = lowOk,
                    infinityFocus = facts?.infinityFocus == true,
                    offers4k = offers4k, fps4k = fps4k, totalRamMb = ramMb,
                )
                val report = CheckVerdict.build(m)
                repo.saveCheck(
                    CameraCheckEntity(
                        ranAt = System.currentTimeMillis(), cameraId = facts?.cameraId ?: "0",
                        streamWidth = w, streamHeight = h, measuredFps = fps, halfFloat = m.halfFloat,
                        infinityFocus = m.infinityFocus, holds4k = report.holds4k, verdict = report.verdict,
                    ),
                )
                holds4k = report.holds4k
                prefs.setCameraCheckDone(true)
                _state.update { it.copy(check = CheckProgress.Done(report)) }
            } finally {
                measuring = false
                checkRunning = false
                rebindIfNeeded()
                applyModeOptions(lock = false)
                updateCameraOpen()
            }
        }
    }

    fun resetCheck() = _state.update { it.copy(check = CheckProgress.Idle) }

    private suspend fun waitForFrames(timeoutMs: Long): Boolean {
        val start = streamFrames.get()
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            if (streamFrames.get() - start >= 5 && renderer.outWidth > 0) return true
            delay(100)
        }
        return false
    }

    private suspend fun measure(ms: Long): Float {
        measureCount.set(0)
        measuring = true
        delay(ms)
        measuring = false
        return CheckVerdict.measureFps(measureFirst.get(), measureLast.get(), measureCount.get())
    }

    // ---- notification ----

    private fun notify(title: String, text: String, chronometerFrom: Long?) {
        if (!sessionActive) return
        val nm = getSystemService(NotificationManager::class.java)
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(title, text, chronometerFrom))
        } catch (e: SecurityException) {
            // Notifications are off; the session runs regardless.
        }
    }

    /** [chronometerFrom] is the wall-clock start: the system then runs the elapsed time without re-posts. */
    private fun buildNotification(title: String, text: String, chronometerFrom: Long?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_slowglass)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stat_stop, "Stop", stop)
            .apply {
                if (chronometerFrom != null) {
                    setWhen(chronometerFrom)
                    setShowWhen(true)
                    setUsesChronometer(true)
                } else {
                    setShowWhen(false)
                }
            }
            .build()
    }

    companion object {
        const val ACTION_START = "com.mohdshayan.slowglass.action.START"
        const val ACTION_STOP = "com.mohdshayan.slowglass.action.STOP"
        private const val CHANNEL_ID = "sessions"
        private const val NOTIFICATION_ID = 7

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Long exposures", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shows the time and a Stop button while a long exposure runs."
                        setShowBadge(false)
                    },
                )
            }
        }
    }
}
