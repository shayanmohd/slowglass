package com.mohdshayan.slowglass.save

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deletes Slowglass photos from MediaStore. Photos this install saved delete straight away; photos
 * from before a reinstall belong to no app, so Android asks the user first and this returns the
 * IntentSender for that question.
 */
object MediaDeleter {
    suspend fun delete(context: Context, uris: List<Uri>): IntentSender? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val needConsent = mutableListOf<Uri>()
        var recoverable: RecoverableSecurityException? = null
        for (u in uris) {
            try {
                resolver.delete(u, null, null)
            } catch (e: RecoverableSecurityException) {
                needConsent += u
                recoverable = e
            } catch (e: SecurityException) {
                needConsent += u
            } catch (e: Exception) {
                // Already gone: nothing to delete.
            }
        }
        when {
            needConsent.isEmpty() -> null
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> MediaStore.createDeleteRequest(resolver, needConsent).intentSender
            else -> recoverable?.userAction?.actionIntent?.intentSender
        }
    }
}
