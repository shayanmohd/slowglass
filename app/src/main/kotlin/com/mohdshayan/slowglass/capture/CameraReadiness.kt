package com.mohdshayan.slowglass.capture

/**
 * Decides whether the shutter may start a session. CameraX's public camera state is one input, but
 * not the only one: on a Samsung S22+ a rebind from 1080p to 4K published CLOSING and OPENING and
 * never OPEN, while frames were flowing. So frames arriving after the latest change count as open,
 * and a start that shows neither OPEN nor frames for [stallMs] becomes STALLED, which the screen
 * shows with a Retry. Not thread safe; the controller serialises calls.
 */
class CameraReadiness(
    private val stallMs: Long = 6_000,
    private val framesToTrust: Int = 3,
) {
    var status: CamStatus = CamStatus.STARTING
        private set

    private var startingSince = 0L
    private var framesSinceChange = 0
    /** Set by a failure that frames cannot clear (no camera, a bind or GL error) until the next bind. */
    private var sticky = false

    /** A new bind: whatever came before no longer counts. */
    fun onBind(nowMs: Long): CamStatus {
        sticky = false
        framesSinceChange = 0
        status = CamStatus.STARTING
        startingSince = nowMs
        return status
    }

    /**
     * CameraX published a state. [open] is CameraState.Type.OPEN; [error] is the status its error
     * maps to (IN_USE, DISABLED or FAILED), or null.
     */
    fun onPublished(open: Boolean, error: CamStatus?, nowMs: Long): CamStatus {
        if (sticky) return status
        framesSinceChange = 0
        val next = when {
            open -> CamStatus.READY
            error != null -> error
            else -> CamStatus.STARTING
        }
        if (next == CamStatus.STARTING) {
            // A stalled camera stays stalled (with its Retry) until OPEN or frames arrive, and
            // repeated OPENING or CLOSING keeps the first start time, so a camera that cycles still stalls.
            if (status == CamStatus.STALLED) return status
            if (status != CamStatus.STARTING) startingSince = nowMs
        }
        status = next
        return status
    }

    /** A camera frame reached the renderer. A few in a row prove the camera is open. */
    fun onFrame(): CamStatus {
        if (sticky || status == CamStatus.READY) return status
        framesSinceChange++
        if (framesSinceChange >= framesToTrust) status = CamStatus.READY
        return status
    }

    /** Checked while the camera should be open; [wantOpen] false restarts the wait. */
    fun onTick(nowMs: Long, wantOpen: Boolean): CamStatus {
        if (!wantOpen) {
            startingSince = nowMs
            return status
        }
        if (status == CamStatus.STARTING && nowMs - startingSince >= stallMs) status = CamStatus.STALLED
        return status
    }

    /** No back camera, a failed bind or a GL error: frames do not clear it, only a new bind. */
    fun onFailure(failure: CamStatus): CamStatus {
        sticky = true
        status = failure
        return status
    }
}
