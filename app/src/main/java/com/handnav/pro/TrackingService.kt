package com.handnav.pro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera + MediaPipe + GestureEngine foreground service.
 * Gesture actions are forwarded to [NavAccessibilityService] (the real AccessibilityService).
 */
class TrackingService : LifecycleService(), GestureEngine.Output {

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "hand_nav_tracking"
        private const val NOTIF_ID = 42

        @Volatile
        var instance: TrackingService? = null
            private set

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackingService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var appSettings: AppSettings
    private lateinit var gestureEngine: GestureEngine
    private var handAnalyzer: HandAnalyzer? = null
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate() {
        super.onCreate()
        instance = this
        appSettings = AppSettings(this)
        gestureEngine = GestureEngine(this).apply {
            applyConfig(appSettings)
        }
        cameraExecutor = Executors.newSingleThreadExecutor()
        isRunning = true
        TrackingState.running = true
        TrackingState.error = null

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        startCameraAnalysis()
    }

    fun startCalibration() {
        gestureEngine.calibrateRequested = true
    }

    fun reloadSettings() {
        appSettings = AppSettings(this)
        gestureEngine.applyConfig(appSettings)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hand Navigation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Camera is running for hand tracking"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackingService::class.java).setAction("STOP"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hand Navigation PRO")
            .setContentText("Tracking is running — tap Stop to end")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startCameraAnalysis() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                handAnalyzer = HandAnalyzer(this, appSettings) { result, timestamp ->
                    handleLandmarkerResult(result, timestamp)
                }

                val analysisUseCase = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, handAnalyzer!!)
                    }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    analysisUseCase
                )
                Log.d(TAG, "Camera analysis bound successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
                TrackingState.error = e.localizedMessage
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleLandmarkerResult(result: HandLandmarkerResult?, timestamp: Long) {
        if (result == null || result.landmarks().isEmpty()) {
            gestureEngine.onNoHand(timestamp)
            return
        }

        val landmarks = result.landmarks()[0]
        val pts = FloatArray(42)
        for (i in landmarks.indices) {
            if (i < 21) {
                pts[2 * i] = landmarks[i].x()
                pts[2 * i + 1] = landmarks[i].y()
            }
        }
        gestureEngine.onFrame(pts, 1.0f, timestamp)
    }

    private fun a11y(): NavAccessibilityService? = NavAccessibilityService.instance

    override fun pointer(x: Float, y: Float, mode: Int) {
        a11y()?.updatePointer(x, y, mode)
    }

    override fun tap(x: Float, y: Float) {
        a11y()?.tapAt(x, y)
    }

    override fun strokeBegin(x: Float, y: Float, holdMs: Long) {
        a11y()?.strokeBegin(x, y, holdMs)
    }

    override fun strokeMove(x: Float, y: Float) {
        a11y()?.strokeMove(x, y)
    }

    override fun strokeEnd() {
        a11y()?.strokeEnd()
    }

    override fun back() {
        a11y()?.goBack()
    }

    override fun pausedChanged(paused: Boolean) {
        TrackingState.paused = paused
        a11y()?.setActive(!paused && isRunning)
        Log.d(TAG, "Paused state changed: $paused")
    }

    override fun calibrated(cx: Float, cy: Float) {
        appSettings.centerX = cx
        appSettings.centerY = cy
        Log.d(TAG, "Calibrated at cx=$cx, cy=$cy")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        TrackingState.running = false
        handAnalyzer?.close()
        handAnalyzer = null
        cameraExecutor.shutdown()
        a11y()?.setActive(false)
        Log.d(TAG, "TrackingService destroyed")
    }
}
