package com.mohdshayan.slowglass.save

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import com.mohdshayan.slowglass.gl.Pixels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The words and numbers written into each photo's EXIF. */
data class PhotoMeta(
    val takenAt: Long,
    val exposureRational: String,
    val comment: String,
)

/**
 * Turns read-back pixels into a JPEG in Pictures/Slowglass through MediaStore, which needs no storage
 * permission from Android 10. The image is flipped (GL rows are bottom first) and rotated to how the
 * phone was held, then EXIF is written before the file becomes visible to other apps.
 */
class PhotoSaver(private val context: Context) {

    suspend fun save(pixels: Pixels, rotation: Int, displayName: String, meta: PhotoMeta): Uri = withContext(Dispatchers.IO) {
        val raw = Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        pixels.rgba.rewind()
        raw.copyPixelsFromBuffer(pixels.rgba)
        val m = Matrix().apply {
            postScale(1f, -1f)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, false)
        raw.recycle()
        val tmp = File(context.cacheDir, "save-${System.nanoTime()}.jpg")
        try {
            tmp.outputStream().use { out ->
                if (!upright.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw IOException("compress failed")
            }
            upright.recycle()
            writeExif(tmp, meta)
            insert(tmp, displayName, meta.takenAt)
        } finally {
            tmp.delete()
        }
    }

    private fun writeExif(file: File, meta: PhotoMeta) {
        val exif = ExifInterface(file)
        val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(meta.takenAt))
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
        // ExifInterface takes ExposureTime as decimal seconds and stores it as a rational.
        val (num, den) = meta.exposureRational.split('/').map { it.toDouble() }
        exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (num / den).toString())
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, meta.comment)
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "ASCII\u0000\u0000\u0000" + meta.comment)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Slowglass")
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.saveAttributes()
    }

    private fun insert(file: File, displayName: String, takenAt: Long): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused the photo")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("no output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw IOException(e)
        }
        return uri
    }

    companion object {
        const val RELATIVE_PATH = "Pictures/Slowglass"

        fun stamp(millis: Long): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(millis))

        /** A downsampled bitmap for the screen, or null when the photo is gone. */
        suspend fun loadPreview(context: Context, uri: Uri, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.loadThumbnail(uri, Size(maxSide, maxSide), null)
            } catch (e: Exception) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                } catch (e2: Exception) {
                    null
                }
            }
        }
    }
}
