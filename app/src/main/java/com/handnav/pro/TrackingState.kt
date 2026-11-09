package com.handnav.pro

/** Process-wide status that the UI polls. Only simple volatile fields, no allocation per frame. */
object TrackingState {
    @Volatile var running = false
    @Volatile var handVisible = false
    @Volatile var gesture = GestureEngine.G_NONE
    @Volatile var paused = false
    @Volatile var fps = 0f
    @Volatile var inferenceMs = 0f
    @Volatile var delegate = "CPU"
    @Volatile var error: String? = null

    // Debug values that help when tuning thresholds
    @Volatile var idxR = 0f
    @Volatile var midR = 0f
    @Volatile var ringR = 0f
    @Volatile var pnkR = 0f
    @Volatile var pinchR = 0f
}
