package com.mohdshayan.slowglass.capture

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import android.view.Surface
import java.io.File

/**
 * Debug builds only: plays a folder of JPEG frames into the renderer's input surface, exactly where
 * camera frames would arrive, so the whole stacking pipeline can be exercised on an emulator with
 * recorded footage. Push frames with
 *
 *   adb push night/ /sdcard/Android/data/com.mohdshayan.slowglass.debug/files/clips/night
 *   adb shell 'echo night > /sdcard/Android/data/com.mohdshayan.slowglass.debug/files/clips/active'
 *
 * Delete the "active" file to go back to the camera. Nothing here is compiled into release builds.
 */
object ClipFeed {
    fun open(context: Context): ClipSource? {
        val root = File(context.getExternalFilesDir(null), "clips")
        val name = File(root, "active").takeIf { it.exists() }?.readText()?.trim().orEmpty()
        if (name.isEmpty()) return null
        val fps = File(root, "$name.fps").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 30
        val frames = File(root, name).listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }.orEmpty()
        if (frames.isEmpty()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(frames[0].path, opts)
        return Player(frames, opts.outWidth, opts.outHeight, fps)
    }

    private class Player(
        private val frames: List<File>,
        override val width: Int,
        override val height: Int,
        private val fps: Int,
    ) : ClipSource {
        @Volatile private var running = false
        @Volatile private var restart = false

        override fun rewind() {
            restart = true
        }
        private var thread: Thread? = null

        override fun start(surface: Surface) {
            stop()
            running = true
            thread = Thread({
                val frameNs = 1_000_000_000L / fps
                var i = 0
                val dst = Rect(0, 0, width, height)
                while (running && surface.isValid) {
                    if (restart) {
                        restart = false
                        i = 0
                    }
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val bmp = BitmapFactory.decodeFile(frames[i].path)
                    if (bmp != null) {
                        try {
                            val c = surface.lockHardwareCanvas()
                            c.drawBitmap(bmp, null, dst, null)
                            surface.unlockCanvasAndPost(c)
                        } catch (e: Exception) {
                            running = false
                        }
                        bmp.recycle()
                    }
                    i = (i + 1) % frames.size
                    val wait = frameNs - (SystemClock.elapsedRealtimeNanos() - t0)
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                }
            }, "slowglass-clip").apply { start() }
        }

        override fun stop() {
            running = false
            thread?.join(500)
            thread = null
        }
    }
}
