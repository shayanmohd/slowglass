package com.mohdshayan.slowglass.capture

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import com.mohdshayan.slowglass.gl.StackRenderer

/** What this phone's back camera says about itself, read once per bind. */
data class CameraFacts(
    val cameraId: String,
    val sensorRotation: Int,
    val fpsRanges: List<Range<Int>>,
    val infinityFocus: Boolean,
    val focalMm: Double?,
    val sensorWidthMm: Double?,
    val offers4k: Boolean,
    val zoomMin: Float,
    val zoomMax: Float,
    val evMin: Int,
    val evMax: Int,
    val evStep: Float,
) {
    /** The range with the lowest top rate, for star trails: longer frames gather more starlight. */
    fun lowFpsRange(): Range<Int>? =
        fpsRanges.filter { it.upper < 30 }.minWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })

    /** A fixed 30 frames a second for lightning, so the pre-roll covers a known time. */
    fun lightningRange(): Range<Int>? =
        fpsRanges.filter { it.upper == 30 }.maxByOrNull { it.lower }
}

/** STALLED: bound and meant to be open, but neither OPEN nor frames came; the screen offers Retry. */
enum class CamStatus { STARTING, READY, IN_USE, DISABLED, NO_CAMERA, FAILED, STALLED }

/**
 * CameraX, owned by the capture service. It has its own lifecycle so the camera opens when the
 * viewfinder is on screen or a session runs, and closes otherwise, whatever the service is doing.
 * The Preview stream goes straight into the renderer's SurfaceTexture.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(
    private val context: Context,
    private val renderer: StackRenderer,
    private val onStatus: (CamStatus) -> Unit,
) : LifecycleOwner {

    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = registry

    private var provider: ProcessCameraProvider? = null
    var camera: Camera? = null
        private set
    var facts: CameraFacts? = null
        private set
    var boundTarget: Size? = null
        private set
    private var stateObserver: Observer<CameraState>? = null

    private val readiness = CameraReadiness()
    @Volatile private var ready = false
    // Frames count only from the surface served for the latest bind, never from a stream being torn down.
    @Volatile private var bindGen = 0
    @Volatile private var servedGen = -1
    private val main = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            val s = emit { onTick(SystemClock.elapsedRealtime(), isOpen) }
            if (s == CamStatus.STARTING && isOpen) main.postDelayed(this, 1000)
        }
    }

    /** Runs one readiness step and reports its result. Any thread; frames arrive on the render thread. */
    private inline fun emit(step: CameraReadiness.() -> CamStatus): CamStatus = synchronized(readiness) {
        val s = readiness.step()
        ready = s == CamStatus.READY
        onStatus(s)
        s
    }

    /** Main thread: while the camera should be open and is still starting, check it once a second. */
    private fun armWatchdog() {
        main.removeCallbacks(watchdog)
        val starting = synchronized(readiness) { readiness.status == CamStatus.STARTING }
        if (isOpen && starting) main.postDelayed(watchdog, 1000)
    }

    /** A camera frame reached the renderer (render thread). Cheap once the camera is ready. */
    fun onFrame() {
        if (!ready && servedGen == bindGen) emit { onFrame() }
    }

    /** A failure only a new bind clears, such as the renderer's GL failing. */
    fun fail() {
        emit { onFailure(CamStatus.FAILED) }
    }

    fun init(onReady: () -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            provider = try {
                future.get()
            } catch (e: Exception) {
                null
            }
            if (provider == null) emit { onFailure(CamStatus.FAILED) }
            onReady()
        }, ContextCompat.getMainExecutor(context))
    }

    /** CameraX finished initialising (it can take a few seconds on a cold start). */
    val isReady: Boolean get() = provider != null

    val hasBackCamera: Boolean
        get() = try {
            provider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) == true
        } catch (e: Exception) {
            false
        }

    /** Binds the Preview stream at the size closest to [target]. Main thread only. */
    fun bind(target: Size) {
        val p = provider ?: return
        if (!hasBackCamera) {
            emit { onFailure(CamStatus.NO_CAMERA) }
            return
        }
        unbind()
        val gen = ++bindGen
        emit { onBind(SystemClock.elapsedRealtime()) }
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(selector)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
        try {
            val cam = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            camera = cam
            boundTarget = target
            facts = readFacts(cam)
            val rotation = facts?.sensorRotation ?: 90
            preview.setSurfaceProvider(renderer.executor) { request ->
                renderer.provideSurface(request, rotation)
                servedGen = gen
            }
            val obs = Observer<CameraState> { s ->
                val error = s.error?.let { err ->
                    when (err.code) {
                        CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> CamStatus.IN_USE
                        CameraState.ERROR_CAMERA_DISABLED, CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> CamStatus.DISABLED
                        else -> CamStatus.FAILED
                    }
                }
                emit { onPublished(s.type == CameraState.Type.OPEN, error, SystemClock.elapsedRealtime()) }
                armWatchdog()
            }
            cam.cameraInfo.cameraState.observeForever(obs)
            stateObserver = obs
            armWatchdog()
        } catch (e: Exception) {
            emit { onFailure(CamStatus.FAILED) }
        }
    }

    fun unbind() {
        stateObserver?.let { camera?.cameraInfo?.cameraState?.removeObserver(it) }
        stateObserver = null
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            // Nothing bound.
        }
        camera = null
    }

    /** Opens or closes the camera without unbinding. Main thread only. */
    fun setOpen(open: Boolean) {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        // Opening restarts the stall wait, so time spent closed never counts as a stall.
        if (open && !isOpen) emit { onTick(SystemClock.elapsedRealtime(), wantOpen = false) }
        registry.currentState = if (open) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        if (open) armWatchdog() else main.removeCallbacks(watchdog)
    }

    val isOpen: Boolean get() = registry.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun release() {
        main.removeCallbacks(watchdog)
        unbind()
        registry.currentState = Lifecycle.State.DESTROYED
    }

    private fun readFacts(cam: Camera): CameraFacts {
        val c2 = Camera2CameraInfo.from(cam.cameraInfo)
        val ranges = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        val minFocus = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val afModes = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
        val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val physical = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val map = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val zoom = cam.cameraInfo.zoomState.value
        val exp = cam.cameraInfo.exposureState
        return CameraFacts(
            cameraId = c2.cameraId,
            sensorRotation = cam.cameraInfo.sensorRotationDegrees,
            fpsRanges = ranges,
            infinityFocus = minFocus > 0f && afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF),
            focalMm = focal?.toDouble(),
            sensorWidthMm = physical?.width?.toDouble(),
            offers4k = sizes.any { it.width == 3840 && it.height == 2160 },
            zoomMin = zoom?.minZoomRatio ?: 1f,
            zoomMax = zoom?.maxZoomRatio ?: 1f,
            evMin = if (exp.isExposureCompensationSupported) exp.exposureCompensationRange.lower else 0,
            evMax = if (exp.isExposureCompensationSupported) exp.exposureCompensationRange.upper else 0,
            evStep = if (exp.isExposureCompensationSupported) exp.exposureCompensationStep.toFloat() else 1f,
        )
    }

    /** Camera2 options on top of CameraX: frame rate, exposure and white balance lock, infinity focus. */
    fun applyOptions(fps: Range<Int>?, lock: Boolean, infinity: Boolean) {
        val cam = camera ?: return
        val b = CaptureRequestOptions.Builder()
        if (fps != null) b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
        if (lock) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
        if (infinity) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
        }
        try {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        } catch (e: Exception) {
            // The camera closed between the check and the call; the next bind applies options again.
        }
    }

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    /** [index] is in exposure compensation steps, clamped to the camera's range. */
    fun setEvIndex(index: Int) {
        val f = facts ?: return
        camera?.cameraControl?.setExposureCompensationIndex(index.coerceIn(f.evMin, f.evMax))
    }

    /** Focus at a point given in the upright image, 0..1 from the top left. */
    fun focusAt(u: Float, v: Float) {
        val cam = camera ?: return
        val display: Display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val factory = DisplayOrientedMeteringPointFactory(display, cam.cameraInfo, 1f, 1f)
        val action = FocusMeteringAction.Builder(factory.createPoint(u, v), FocusMeteringAction.FLAG_AF)
            .disableAutoCancel()
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun cancelFocus() {
        camera?.cameraControl?.cancelFocusAndMetering()
    }
}
