package com.mohdshayan.slowglass.capture

import android.content.Context

/** Release builds always use the camera. */
object ClipFeed {
    fun open(context: Context): ClipSource? = null
}
