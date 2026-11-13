package com.handnav.pro

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

class AppSettings(context: Context) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    var detectionConfidence: Float
        get() = prefs.getFloat(PREF_DETECTION_CONFIDENCE, 0.5f)
        set(value) = prefs.edit().putFloat(PREF_DETECTION_CONFIDENCE, value).apply()

    var presenceConfidence: Float
        get() = prefs.getFloat(PREF_PRESENCE_CONFIDENCE, 0.5f)
        set(value) = prefs.edit().putFloat(PREF_PRESENCE_CONFIDENCE, value).apply()

    var trackingConfidence: Float
        get() = prefs.getFloat(PREF_TRACKING_CONFIDENCE, 0.5f)
        set(value) = prefs.edit().putFloat(PREF_TRACKING_CONFIDENCE, value).apply()

    var mirrorX: Boolean
        get() = prefs.getBoolean(PREF_MIRROR_X, false)
        set(value) = prefs.edit().putBoolean(PREF_MIRROR_X, value).apply()

    companion object {
        private const val PREF_DETECTION_CONFIDENCE = "detection_confidence"
        private const val PREF_PRESENCE_CONFIDENCE = "presence_confidence"
        private const val PREF_TRACKING_CONFIDENCE = "tracking_confidence"
        private const val PREF_MIRROR_X = "mirror_x"
    }
}
