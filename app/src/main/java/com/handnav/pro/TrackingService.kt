package com.handnav.pro

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class TrackingService : Service() {

    private var handAnalyzer: HandAnalyzer? = null
    private lateinit var gestureEngine: GestureEngine
    private lateinit var appSettings: AppSettings

    override fun onCreate() {
        super.onCreate()
        appSettings = AppSettings(this)
        gestureEngine = GestureEngine(this, appSettings)

        // Properly match the 3-argument constructor: (Context, AppSettings, Lambda)
        handAnalyzer = HandAnalyzer(this, appSettings) { result, timestamp ->
            gestureEngine.processResult(result, timestamp)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start foreground service implementation as needed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handAnalyzer?.close()
        handAnalyzer = null
    }
}
