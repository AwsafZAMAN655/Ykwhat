package com.handnav.pro

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

class AppSettings(context: Context) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    val detectionConfidence: Float = 0.5f
    val presenceConfidence: Float = 0.5f
    val trackingConfidence: Float = 0.5f
    val mirrorX: Boolean = prefs.getBoolean("mirror_x", false)
}
