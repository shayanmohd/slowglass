package com.mohdshayan.slowglass.ui.components

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens the system share sheet for one photo. */
fun sharePhoto(context: Context, uri: Uri, title: String = "Share photo") {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title))
}

/** Opens the share sheet for plain text, such as the camera check report. */
fun shareText(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, title))
}
