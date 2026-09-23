package com.mohdshayan.slowglass.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.camera.core.SurfaceRequest
import com.mohdshayan.slowglass.core.lightning.SpikeDetector
import com.mohdshayan.slowglass.core.sharp.SharpestKeeper
import com.mohdshayan.slowglass.core.sharp.SharpnessScore
import com.mohdshayan.slowglass.core.stack.MeanTreeSchedule
import com.mohdshayan.slowglass.core.stack.StackMode
import com.mohdshayan.slowglass.core.stack.SubExposureCounter
import com.mohdshayan.slowglass.core.stars.GapBridge
import com.mohdshayan.slowglass.core.stars.GapTracker
import com.mohdshayan.slowglass.core.timer.SessionGuard
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** RGBA8888 pixels read back from the GPU, bottom row first. */
class Pixels(val rgba: ByteBuffer, val width: Int, val height: Int)

/** What the stacking pass needs to know when a session starts. */
data class StackConfig(
    val mode: StackMode,
    val framesPerSub: Int,
    val keepLights: Float,
    val lightningThreshold: Float,
    val pixelsPerDegree: Double,
    val preRollFrames: Int,
)

/** What came out of a session. */
class StackResult(
    val stack: Pixels?,
    val sharpest: Pixels?,
    val framesStacked: Int,
    val droppedFrames: Int,
    val gapsBridged: Int,
    val gapOffsetsMs: List<Long>,
    val precision: String,
)

/**
 * The GPU half of Slowglass. Camera frames arrive as an external OES texture on this renderer's own
 * thread and are blended into framebuffers that stay on the GPU for the whole session: max blending
 * for trails, stars and lightning, a three-level mean tree for water and motion blur. The viewfinder
 * shows the stack as it builds; with no viewfinder the same work continues on a pbuffer.
 */
class StackRenderer(private val listener: Listener) {

    interface Listener {
        /** Every camera frame, on the render thread. */
        fun onStreamFrame(timestampNs: Long)
        /** A lightning strike stack is ready: pre-roll plus the frames after the flash. */
        fun onStrike(pixels: Pixels, peakDelta: Float)
        /** The renderer could not start OpenGL ES 3. */
        fun onGlError(message: String)
    }

    private val thread = HandlerThread("slowglass-gl").apply { start() }
    val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    private val egl = EglCore()
    private lateinit var oes: GlProgram
    private lateinit var copy: GlProgram
    private lateinit var dilate: GlProgram
    private lateinit var resolve: GlProgram
    private var ready = false

    /** True when this GPU can render into RGBA16F framebuffers. Set once GL has started. */
    @Volatile var halfFloatSupported = false
        private set

    /** Quirk override: force the 8-bit path even where half float exists. */
    @Volatile var forceRgba8 = false

    // Camera stream state, render thread only.
    private var cameraTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var bufferW = 0
    private var bufferH = 0
    private var sensorRotation = 90
    private var hasCameraTransform = true
    private var extraRotation = 0
    private val stMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** Upright stream size, portrait on a phone. Read from any thread for the UI. */
    @Volatile var outWidth = 0
        private set
    @Volatile var outHeight = 0
        private set

    private var displayW = 0
    private var displayH = 0

    private var pipeline: Pipeline? = null
    /** Moves the dither pattern every frame; see Shaders.DITHER. */
    private var ditherSeed = 0f
    @Volatile var thermalStatus = 0
    @Volatile private var paused = false

    fun start() = handler.post {
        try {
            egl.init()
            Quad.init()
            oes = GlProgram(Shaders.VERTEX, Shaders.OES)
            copy = GlProgram(Shaders.VERTEX, Shaders.COPY)
            dilate = GlProgram(Shaders.VERTEX, Shaders.DILATE)
            resolve = GlProgram(Shaders.VERTEX, Shaders.RESOLVE)
            halfFloatSupported = probeHalfFloat()
            ready = true
        } catch (e: Throwable) {
            listener.onGlError(e.message ?: "OpenGL ES 3 is not available")
        }
    }

    private fun probeHalfFloat(): Boolean {
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        val version = GLES30.glGetString(GLES30.GL_VERSION) ?: ""
        val claimed = "GL_EXT_color_buffer_half_float" in ext || "GL_EXT_color_buffer_float" in ext ||
            version.contains("OpenGL ES 3.2")
        if (!claimed) return false
        return try {
            val f = Fbo(16, 16, halfFloat = true)
            val ok = f.complete && GLES30.glGetError() == GLES30.GL_NO_ERROR
            f.release()
            ok
        } catch (e: Throwable) {
            false
        }
    }

    val precisionHalf: Boolean get() = halfFloatSupported && !forceRgba8

    /** Called from CameraX on this renderer's executor. */
    fun provideSurface(request: SurfaceRequest, sensorRotationDegrees: Int) {
        if (!ready) {
            request.willNotProvideSurface()
            return
        }
        releaseCameraSurface()
        val size: Size = request.resolution
        bufferW = size.width
        bufferH = size.height
        sensorRotation = sensorRotationDegrees
        hasCameraTransform = true
        extraRotation = 0
        computeOutSize()
        cameraTex = Quad.newOesTexture()
        val st = SurfaceTexture(cameraTex)
        st.setDefaultBufferSize(bufferW, bufferH)
        st.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
        val surface = Surface(st)
        surfaceTexture = st
        cameraSurface = surface
        request.setTransformationInfoListener(executor) { info ->
            hasCameraTransform = info.hasCameraTransform()
            extraRotation = if (hasCameraTransform) {
                ((info.rotationDegrees - sensorRotation) % 360 + 360) % 360
            } else {
                info.rotationDegrees
            }
            computeOutSize()
        }
        request.provideSurface(surface, executor) {
            if (surfaceTexture === st) {
                surfaceTexture = null
                cameraSurface = null
                GLES30.glDeleteTextures(1, intArrayOf(cameraTex), 0)
                cameraTex = 0
            }
            surface.release()
            st.release()
        }
    }

    /** Debug clips: an input surface fed with upright frames instead of the camera. */
    fun provideClipSurface(width: Int, height: Int, onReady: (Surface) -> Unit) = handler.post {
        if (!ready) return@post
        releaseCameraSurface()
        bufferW = width
        bufferH = height
        hasCameraTransform = false
        extraRotation = 0
        computeOutSize()
        cameraTex = Quad.newOesTexture()
        val st = SurfaceTexture(cameraTex)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
        surfaceTexture = st
        val surface = Surface(st)
        cameraSurface = surface
        onReady(surface)
    }

    private fun computeOutSize() {
        var w = bufferW
        var h = bufferH
        if (hasCameraTransform && sensorRotation % 180 != 0) { w = bufferH; h = bufferW }
        if (extraRotation % 180 != 0) { val t = w; w = h; h = t }
        outWidth = w
        outHeight = h
    }

    private fun releaseCameraSurface() {
        surfaceTexture?.setOnFrameAvailableListener(null)
    }

    fun attachDisplay(surface: Surface, width: Int, height: Int) = handler.post {
        if (!ready) return@post
        displayW = width
        displayH = height
        egl.attachWindow(surface)
    }

    fun resizeDisplay(width: Int, height: Int) = handler.post {
        displayW = width
        displayH = height
    }

    /** Blocks until the window surface is released, as SurfaceHolder.Callback requires. */
    fun detachDisplay() {
        val latch = CountDownLatch(1)
        handler.post {
            if (ready) egl.detachWindow()
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    fun setPaused(value: Boolean) {
        paused = value
    }

    fun beginSession(config: StackConfig) = handler.post {
        pipeline?.release()
        pipeline = null
        if (!ready || outWidth == 0) return@post
        pipeline = try {
            Pipeline(config, outWidth, outHeight, if (config.mode == StackMode.LIGHTNING) false else precisionHalf)
        } catch (e: Throwable) {
            null
        }
    }

    /** Resolves the stack, reads it back and frees the buffers. [done] runs on the render thread. */
    fun finishSession(done: (StackResult) -> Unit) = handler.post {
        val p = pipeline
        pipeline = null
        if (p == null) {
            done(StackResult(null, null, 0, 0, 0, emptyList(), if (precisionHalf) "RGBA16F" else "RGBA8"))
            return@post
        }
        val result = try {
            p.finish()
        } catch (e: Throwable) {
            StackResult(null, null, p.stacked, p.gaps.droppedFrames, p.gapsBridged, p.gapLog, p.precision)
        }
        p.release()
        done(result)
    }

    fun cancelSession() = handler.post {
        pipeline?.release()
        pipeline = null
    }

    /** Runs the heaviest stacking load (motion blur) without a session, for the camera check. */
    fun beginBenchmark() = beginSession(StackConfig(StackMode.MOTION, 1, 0.5f, 1f, 30.0, 0))

    fun release() {
        handler.post {
            pipeline?.release()
            pipeline = null
            releaseCameraSurface()
            if (ready) {
                oes.release(); copy.release(); dilate.release(); resolve.release()
                Quad.release()
            }
            egl.release()
            ready = false
        }
        thread.quitSafely()
    }

    // ---- per frame ----

    private fun onFrameAvailable() {
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
        } catch (e: Throwable) {
            return
        }
        st.getTransformMatrix(stMatrix)
        ditherSeed = (ditherSeed + 37.13f) % 1009f
        val ts = st.timestamp
        listener.onStreamFrame(ts)
        val p = pipeline
        if (p != null && !paused) p.onFrame(ts)
        present(p)
    }

    private fun cameraUv(crop: FloatArray): FloatArray {
        val rot = rotationMatrix(extraRotation)
        val tmp = FloatArray(16)
        val out = FloatArray(16)
        Matrix.multiplyMM(tmp, 0, rot, 0, crop, 0)
        Matrix.multiplyMM(out, 0, stMatrix, 0, tmp, 0)
        return out
    }

    private fun drawOes(scale: Float, linear: Boolean, gammaOut: Boolean, dither: Boolean, uv: FloatArray) {
        oes.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex)
        oes.i("uTex", 0)
        oes.f("uLinear", if (linear) 1f else 0f)
        oes.f("uScale", scale)
        oes.f("uGammaOut", if (gammaOut) 1f else 0f)
        oes.f("uDither", if (dither) 1f else 0f)
        oes.f("uSeed", ditherSeed)
        oes.m4("uUv", uv)
        Quad.draw()
    }

    private fun drawCopy(src: Fbo, scale: Float, gammaOut: Boolean, dither: Boolean, uv: FloatArray = identity) {
        copy.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src.tex)
        copy.i("uTex", 0)
        copy.f("uScale", scale)
        copy.f("uGammaOut", if (gammaOut) 1f else 0f)
        copy.f("uDither", if (dither) 1f else 0f)
        copy.f("uSeed", ditherSeed)
        copy.m4("uUv", uv)
        Quad.draw()
    }

    private fun blendOff() = GLES30.glDisable(GLES30.GL_BLEND)

    private fun blendAdd() {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
    }

    private fun blendMax() {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_MAX)
    }

    /** dst = src + dst * (1 - w); the source pass scales itself by w. */
    private fun blendRunningMean(w: Float) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glBlendColor(0f, 0f, 0f, w)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_CONSTANT_ALPHA)
    }

    private fun present(p: Pipeline?) {
        if (!egl.hasWindow || displayW == 0 || outWidth == 0) return
        if (!egl.makeCurrentWindow()) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, displayW, displayH)
        blendOff()
        val crop = cropMatrix(outWidth.toFloat() / outHeight, displayW.toFloat() / displayH)
        if (p == null || !p.hasImage()) {
            drawOes(1f, linear = false, gammaOut = false, dither = false, uv = cameraUv(crop))
        } else {
            p.present(crop)
        }
        p?.presentLightning(crop)
        egl.swap()
    }

    /** Per-session buffers and counters. Render thread only. */
    private inner class Pipeline(val config: StackConfig, val w: Int, val h: Int, val hi: Boolean) {
        val mode = config.mode
        val precision = if (hi) "RGBA16F" else "RGBA8"
        val subCounter = SubExposureCounter(config.framesPerSub)
        val tree = MeanTreeSchedule()
        val gaps = GapTracker()
        var gapsBridged = 0
        val gapLog = ArrayList<Long>()
        var firstTs = -1L
        var stacked = 0
        var streamIndex = 0L
        private var pendingRadius = 0

        // Allocated per mode.
        val sub: Fbo? = if (mode == StackMode.TRAILS || mode == StackMode.STARS) Fbo(w, h, hi) else null
        val acc: Fbo? = if (mode == StackMode.TRAILS || mode == StackMode.STARS) Fbo(w, h, hi) else null
        val tmp: Fbo? = if (mode == StackMode.STARS) Fbo(w, h, hi) else null
        val a: Fbo? = if (mode == StackMode.WATER || mode == StackMode.MOTION) Fbo(w, h, hi) else null
        val s: Fbo? = if (mode == StackMode.WATER || mode == StackMode.MOTION) Fbo(w, h, hi) else null
        val b: Fbo? = if (mode == StackMode.WATER || mode == StackMode.MOTION) Fbo(w, h, hi) else null
        val mx: Fbo? = if (mode == StackMode.MOTION) Fbo(w, h, hi) else null
        val ring: List<Fbo> = if (mode == StackMode.LIGHTNING) List(config.preRollFrames.coerceAtLeast(1)) { Fbo(w, h, false) } else emptyList()
        val strikeAcc: Fbo? = if (mode == StackMode.LIGHTNING) Fbo(w, h, false) else null
        val storm: Fbo? = if (mode == StackMode.LIGHTNING) Fbo(w, h, false) else null
        val sharpest = Fbo(w, h, false)
        private val sharpW = if (w <= h) 180 else 320
        private val sharpH = if (w <= h) 320 else 180
        val sharpSmall = Fbo(sharpW, sharpH, false)
        private val detW = if (w <= h) 36 else 64
        private val detH = if (w <= h) 64 else 36
        val detSmall: Fbo? = if (mode == StackMode.LIGHTNING) Fbo(detW, detH, false) else null
        val detector = SpikeDetector(config.lightningThreshold, cols = if (w <= h) 4 else 8, rows = if (w <= h) 8 else 4)
        val keeper = SharpestKeeper()
        private var ringHead = 0
        private var ringFilled = 0
        private var strikeFramesAfter = -1
        private var strikePeak = 0f
        var strikes = 0
        private var stormHasContent = false
        private var hasSharpest = false

        init {
            val all = listOfNotNull(sub, acc, tmp, a, s, b, mx, strikeAcc, storm, sharpest, sharpSmall, detSmall) + ring
            check(all.all { it.complete }) { "framebuffer incomplete" }
        }

        fun hasImage(): Boolean = when (mode) {
            StackMode.TRAILS, StackMode.STARS -> subCounter.completedSubs > 0
            StackMode.WATER, StackMode.MOTION -> stacked > 0
            StackMode.LIGHTNING -> false // lightning draws the live frame, then the storm over it
        }

        fun onFrame(ts: Long) {
            streamIndex++
            if (!SessionGuard.stackThisFrame(streamIndex, thermalStatus)) return
            if (firstTs < 0) firstTs = ts
            val gap = gaps.onFrame(ts)
            if (gap > 0 && mode == StackMode.STARS) {
                val r = GapBridge.radiusPx(gap / 1e9, config.pixelsPerDegree)
                if (r > 0) {
                    pendingRadius = maxOf(pendingRadius, r)
                    gapsBridged++
                    gapLog += (ts - firstTs) / 1_000_000
                }
            }
            stacked++
            val uv = cameraUv(identity)
            when (mode) {
                StackMode.TRAILS, StackMode.STARS -> {
                    sub!!.bind(); blendAdd()
                    drawOes(subCounter.frameWeight, linear = hi, gammaOut = false, dither = !hi, uv = uv)
                    if (subCounter.onFrame()) foldSub()
                }
                StackMode.WATER, StackMode.MOTION -> {
                    // The same true-mean schedule on both paths. Half float holds linear light and
                    // scaled sums; the 8-bit path stays in gamma space and keeps a dithered running
                    // mean at every level, since an 8-bit sum of a few frames keeps too few levels.
                    val lo = !hi
                    for (step in tree.onFrame()) when (step) {
                        MeanTreeSchedule.Step.ADD_FRAME_TO_BLOCK -> {
                            b!!.bind()
                            if (hi) {
                                blendAdd()
                                drawOes(MeanTreeSchedule.FRAME_WEIGHT, linear = true, gammaOut = false, dither = false, uv = uv)
                            } else {
                                val wgt = tree.runningAddWeight
                                blendRunningMean(wgt)
                                drawOes(wgt, linear = false, gammaOut = false, dither = true, uv = uv)
                            }
                        }
                        MeanTreeSchedule.Step.FOLD_BLOCK_INTO_SUPER -> {
                            s!!.bind()
                            if (hi) {
                                blendAdd()
                                drawCopy(b!!, MeanTreeSchedule.FRAME_WEIGHT, gammaOut = false, dither = false)
                            } else {
                                val wgt = tree.runningBlockWeight
                                blendRunningMean(wgt)
                                drawCopy(b!!, wgt, gammaOut = false, dither = true)
                            }
                            b.clear()
                        }
                        MeanTreeSchedule.Step.FOLD_SUPER_INTO_ACC -> {
                            val wgt = tree.accFoldWeight()
                            a!!.bind(); blendRunningMean(wgt)
                            drawCopy(s!!, wgt, gammaOut = false, dither = lo)
                            s.clear()
                        }
                    }
                    if (mode == StackMode.MOTION) {
                        mx!!.bind(); blendMax()
                        drawOes(1f, linear = hi, gammaOut = false, dither = false, uv = uv)
                    }
                }
                StackMode.LIGHTNING -> lightningFrame(ts, uv)
            }
            blendOff()
            if (stacked % SHARP_EVERY == 1) scoreSharpness(uv)
        }

        private fun foldSub() {
            val src = if (pendingRadius > 0) {
                tmp!!.bind(); blendOff()
                dilate.use()
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sub!!.tex)
                dilate.i("uTex", 0)
                dilate.i("uR", pendingRadius)
                dilate.v2("uTexel", 1f / w, 1f / h)
                dilate.m4("uUv", identity)
                Quad.draw()
                pendingRadius = 0
                tmp
            } else {
                sub!!
            }
            acc!!.bind(); blendMax()
            drawCopy(src, 1f, gammaOut = false, dither = false)
            blendOff()
            sub!!.clear()
        }

        private fun lightningFrame(ts: Long, uv: FloatArray) {
            val slot = ring[ringHead]
            slot.bind(); blendOff()
            drawOes(1f, linear = false, gammaOut = false, dither = false, uv = uv)
            ringHead = (ringHead + 1) % ring.size
            if (ringFilled < ring.size) ringFilled++
            if (strikeFramesAfter >= 0) {
                strikeAcc!!.bind(); blendMax()
                drawCopy(slot, 1f, gammaOut = false, dither = false)
                blendOff()
                strikeFramesAfter++
                if (strikeFramesAfter >= FRAMES_AFTER_STRIKE) completeStrike()
            }
            // The trigger only watches while the viewfinder is on screen.
            if (!egl.hasWindow) return
            val det = detSmall!!
            det.bind(); blendOff()
            drawOes(1f, linear = false, gammaOut = false, dither = false, uv = uv)
            val luma = toLuma(det.readRgba(), det.width, det.height)
            if (detector.onFrame(luma, det.width, det.height, ts) && strikeFramesAfter < 0) {
                strikePeak = detector.lastPeak
                strikeAcc!!.clear()
                strikeAcc.bind(); blendMax()
                for (i in 0 until ringFilled) drawCopy(ring[i], 1f, gammaOut = false, dither = false)
                blendOff()
                strikeFramesAfter = 0
            }
        }

        private fun completeStrike() {
            strikeFramesAfter = -1
            strikes++
            listener.onStrike(Pixels(strikeAcc!!.readRgba(), w, h), strikePeak)
            storm!!.bind(); blendMax()
            drawCopy(strikeAcc, 1f, gammaOut = false, dither = false)
            blendOff()
            stormHasContent = true
        }

        private fun scoreSharpness(uv: FloatArray) {
            sharpSmall.bind(); blendOff()
            drawOes(1f, linear = false, gammaOut = false, dither = false, uv = uv)
            val luma = toLuma(sharpSmall.readRgba(), sharpSmall.width, sharpSmall.height)
            if (keeper.offer(SharpnessScore.score(luma, sharpSmall.width, sharpSmall.height))) {
                sharpest.bind(); blendOff()
                drawOes(1f, linear = false, gammaOut = false, dither = false, uv = uv)
                hasSharpest = true
            }
        }

        fun present(crop: FloatArray) {
            when (mode) {
                StackMode.TRAILS, StackMode.STARS -> drawCopy(acc!!, 1f, gammaOut = hi, dither = hi, uv = crop)
                StackMode.WATER, StackMode.MOTION -> drawResolve(crop)
                StackMode.LIGHTNING -> Unit
            }
        }

        fun presentLightning(crop: FloatArray) {
            if (mode == StackMode.LIGHTNING && stormHasContent) {
                blendMax()
                drawCopy(storm!!, 1f, gammaOut = false, dither = false, uv = crop)
                blendOff()
            }
        }

        private fun drawResolve(uv: FloatArray) {
            resolve.use()
            val texA = a!!.tex
            val texS = s?.tex ?: texA
            val texB = b?.tex ?: texA
            val texM = mx?.tex ?: texA
            val units = intArrayOf(texA, texS, texB, texM)
            val names = arrayOf("uA", "uS", "uB", "uM")
            for (i in 0 until 4) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, units[i])
                resolve.i(names[i], i)
            }
            val wts = if (hi) tree.resolveWeights() else tree.runningResolveWeights()
            resolve.v3("uW", wts[0], wts[1], wts[2])
            resolve.f("uKeep", config.keepLights)
            resolve.i("uMotion", if (mode == StackMode.MOTION) 1 else 0)
            resolve.f("uGammaOut", if (hi) 1f else 0f)
            resolve.f("uDither", if (hi) 1f else 0f)
            resolve.f("uSeed", ditherSeed)
            resolve.m4("uUv", uv)
            Quad.draw()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }

        fun finish(): StackResult {
            val out = Fbo(w, h, false)
            var stack: Pixels? = null
            try {
                when (mode) {
                    StackMode.TRAILS, StackMode.STARS -> {
                        if (subCounter.inSub > 0) {
                            // Fold the partial sub-exposure in at its true mean.
                            val scale = subCounter.framesPerSub.toFloat() / subCounter.inSub
                            acc!!.bind(); blendMax()
                            drawCopy(sub!!, scale, gammaOut = false, dither = false)
                            blendOff()
                        }
                        if (stacked > 0) {
                            out.bind(); blendOff()
                            drawCopy(acc!!, 1f, gammaOut = hi, dither = hi)
                            stack = Pixels(out.readRgba(), w, h)
                        }
                    }
                    StackMode.WATER, StackMode.MOTION -> if (stacked > 0) {
                        out.bind(); blendOff()
                        drawResolve(identity)
                        stack = Pixels(out.readRgba(), w, h)
                    }
                    StackMode.LIGHTNING -> {
                        if (strikeFramesAfter >= 0) completeStrike()
                        if (stormHasContent) stack = Pixels(storm!!.readRgba(), w, h)
                    }
                }
                val sharp = if (hasSharpest && stack != null) Pixels(sharpest.readRgba(), w, h) else null
                return StackResult(stack, sharp, stacked, gaps.droppedFrames, gapsBridged, gapLog.toList(), precision)
            } finally {
                out.release()
            }
        }

        fun release() {
            (listOfNotNull(sub, acc, tmp, a, s, b, mx, strikeAcc, storm, sharpest, sharpSmall, detSmall) + ring)
                .forEach { it.release() }
        }
    }

    private fun toLuma(buf: ByteBuffer, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (i in 0 until w * h) {
            val r = buf.get(i * 4).toInt() and 0xFF
            val g = buf.get(i * 4 + 1).toInt() and 0xFF
            val b = buf.get(i * 4 + 2).toInt() and 0xFF
            out[i] = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        }
        return out
    }

    companion object {
        const val FRAMES_AFTER_STRIKE = 12
        const val SHARP_EVERY = 10

        /** Centre crop so an image of aspect [imageAspect] fills a viewport of aspect [viewAspect]. */
        fun cropMatrix(imageAspect: Float, viewAspect: Float): FloatArray {
            var kx = 1f
            var ky = 1f
            if (viewAspect > imageAspect) ky = imageAspect / viewAspect else kx = viewAspect / imageAspect
            return floatArrayOf(
                kx, 0f, 0f, 0f,
                0f, ky, 0f, 0f,
                0f, 0f, 1f, 0f,
                0.5f - 0.5f * kx, 0.5f - 0.5f * ky, 0f, 1f,
            )
        }

        /** Maps upright output coordinates to the camera image rotated by [deg] clockwise. */
        fun rotationMatrix(deg: Int): FloatArray = when (((deg % 360) + 360) % 360) {
            90 -> floatArrayOf(0f, 1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)
            180 -> floatArrayOf(-1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
            270 -> floatArrayOf(0f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
            else -> FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        }
    }
}
