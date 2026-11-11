package com.motorola.pixelcamera.core

/** Progress of one photo capture, reported to the UI. */
sealed interface CaptureProgress {
    /** [done] of [total] frames are in. The phone has to stay still until they are equal. */
    data class Frames(val done: Int, val total: Int) : CaptureProgress

    /** All frames are in; merging and saving. The phone may move again. */
    object Processing : CaptureProgress
}
