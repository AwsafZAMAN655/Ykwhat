package com.handnav.pro

import android.content.Context
import android.content.SharedPreferences

/** Tiny wrapper around SharedPreferences. All values are plain primitives. */
class AppSettings(context: Context) {

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("hand_nav_pro", Context.MODE_PRIVATE)

    var sensitivity: Int
        get() = prefs.getInt("sensitivity", 50)
        set(v) = prefs.edit().putInt("sensitivity", v).apply()

    var speed: Int
        get() = prefs.getInt("speed", 40)
        set(v) = prefs.edit().putInt("speed", v).apply()

    var smoothing: Int
        get() = prefs.getInt("smoothing", 50)
        set(v) = prefs.edit().putInt("smoothing", v).apply()

    var pointerVisible: Boolean
        get() = prefs.getBoolean("pointer_visible", true)
        set(v) = prefs.edit().putBoolean("pointer_visible", v).apply()

    var useGpu: Boolean
        get() = prefs.getBoolean("use_gpu", false)
        set(v) = prefs.edit().putBoolean("use_gpu", v).apply()

    var invertScroll: Boolean
        get() = prefs.getBoolean("invert_scroll", false)
        set(v) = prefs.edit().putBoolean("invert_scroll", v).apply()

    var centerX: Float
        get() = prefs.getFloat("center_x", 0.5f)
        set(v) = prefs.edit().putFloat("center_x", v).apply()

    var centerY: Float
        get() = prefs.getFloat("center_y", 0.5f)
        set(v) = prefs.edit().putFloat("center_y", v).apply()

    /** MediaPipe min hand detection confidence (0..1). */
    var detectionConfidence: Float
        get() = prefs.getFloat("detection_confidence", 0.5f)
        set(v) = prefs.edit().putFloat("detection_confidence", v).apply()

    /** MediaPipe min hand presence confidence (0..1). */
    var presenceConfidence: Float
        get() = prefs.getFloat("presence_confidence", 0.5f)
        set(v) = prefs.edit().putFloat("presence_confidence", v).apply()

    /** MediaPipe min tracking confidence (0..1). */
    var trackingConfidence: Float
        get() = prefs.getFloat("tracking_confidence", 0.5f)
        set(v) = prefs.edit().putFloat("tracking_confidence", v).apply()

    /**
     * Mirror the camera image horizontally.
     * Front camera frames are usually already mirrored for a “selfie” feel;
     * keep this true unless you need the opposite.
     */
    var mirrorX: Boolean
        get() = prefs.getBoolean("mirror_x", true)
        set(v) = prefs.edit().putBoolean("mirror_x", v).apply()
}
