package com.mohdshayan.slowglass.capture

import android.view.Surface

/**
 * A recorded clip played into the stacking pipeline in place of the camera. Only debug builds ever
 * return one (see ClipFeed in src/debug); release builds have no clip code path at all.
 */
interface ClipSource {
    val width: Int
    val height: Int
    fun start(surface: Surface)
    fun stop()
    /** Restart from the first frame, so a session stacks the clip from its beginning. */
    fun rewind()
}
