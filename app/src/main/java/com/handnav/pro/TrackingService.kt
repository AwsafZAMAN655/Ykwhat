package com.handnav.pro

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.util.Size
import android.view.Display
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service (type "camera") that owns the camera and the hand analysis.
 * It only runs after the user taps Start, shows a persistent notification with a Stop action,
 * and stops itself on any fatal error.
 */
class TrackingService : LifecycleService(), GestureEngine.Output {

    companion object {
        const val ACTION_STOP = "com.handnav.pro.action.STOP"
        private const val CHANNEL_ID = "hand_nav_tracking"
        private const val NOTIF_ID = 1001
        private const val TAG = "TrackingService"

        @Volatile
        var instance: TrackingService? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }

    private lateinit var settings: AppSettings
    private lateinit var engine: GestureEngine
    private var analyzer: HandAnalyzer? = null
    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var displayManager: DisplayManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var started = false
    private var shuttingDown = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        engine.applyConfig(settings)
        NavAccessibilityService.instance?.setPointerVisible(settings.pointerVisible)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) updateRotation()
        }
    }

    // ---------------- Lifecycle ----------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
        engine = GestureEngine(this)
        engine.applyConfig(settings)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            startTracking()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // ---------------- Start / stop ----------------

    private fun startTracking() {
        TrackingState.error = null
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            fail("Cannot start camera service: ${e.message}")
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail("Camera permission is not granted")
            return
        }
        if (NavAccessibilityService.instance == null) {
            fail("Accessibility service is not enabled")
            return
        }

        NavAccessibilityService.instance?.setPointerVisible(settings.pointerVisible)
        NavAccessibilityService.instance?.setActive(true)
        settings.prefs.registerOnSharedPreferenceChangeListener(prefListener)

        val ex = Executors.newSingleThreadExecutor { r ->
            Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                } catch (_: Exception) {
                }
                r.run()
            }, "hand-analysis")
        }
        executor = ex
        analyzer = HandAnalyzer(applicationContext, settings, engine) { msg -> mainHandler.post { fail(msg) } }

        TrackingState.running = true
        TrackingState.paused = false
        bindCamera(ex)
    }

    private fun bindCamera(ex: ExecutorService) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                if (shuttingDown) return@addListener
                val p = future.get()
                provider = p
                if (!p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    fail("No front camera found")
                    return@addListener
                }
                val resolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
                val ia = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetRotation(currentRotation())
                    .build()
                ia.setAnalyzer(ex, analyzer!!)
                analysis = ia

                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, ia)

                val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
                dm.registerDisplayListener(displayListener, mainHandler)
                displayManager = dm
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                fail("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun currentRotation(): Int {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
    }

    private fun updateRotation() {
        analysis?.targetRotation = currentRotation()
    }

    private fun fail(message: String) {
        TrackingState.error = message
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        stopSelf()
    }

    private fun shutdown() {
        if (shuttingDown) return
        shuttingDown = true
        try { analysis?.clearAnalyzer() } catch (_: Exception) {}
        try { provider?.unbindAll() } catch (_: Exception) {}
        try { displayManager?.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        try { settings.prefs.unregisterOnSharedPreferenceChangeListener(prefListener) } catch (_: Exception) {}
        val a = analyzer
        val ex = executor
        try {
            ex?.execute { a?.close() }
            ex?.shutdown()
        } catch (_: Exception) {}
        NavAccessibilityService.instance?.let {
            it.strokeEnd()
            it.setActive(false)
        }
        TrackingState.running = false
        TrackingState.handVisible = false
        TrackingState.gesture = GestureEngine.G_NONE
        instance = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    // ---------------- Calibration ----------------

    fun startCalibration() {
        Toast.makeText(
            applicationContext,
            "Hold your index finger at the comfortable CENTER of your hand range. Calibrating in 3 seconds...",
            Toast.LENGTH_LONG
        ).show()
        mainHandler.postDelayed({ engine.calibrateRequested = true }, 3000L)
    }

    // ---------------- Notification ----------------

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle(getString(R.string.notif_title))
        .setContentText(getString(R.string.notif_text))
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.notif_stop),
            PendingIntent.getService(
                this, 1,
                Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    // ---------------- GestureEngine.Output -> accessibility service ----------------

    override fun pointer(x: Float, y: Float, mode: Int) {
        NavAccessibilityService.instance?.updatePointer(x, y, mode)
    }

    override fun tap(x: Float, y: Float) {
        NavAccessibilityService.instance?.tapAt(x, y)
    }

    override fun strokeBegin(x: Float, y: Float, holdMs: Long) {
        NavAccessibilityService.instance?.strokeBegin(x, y, holdMs)
    }

    override fun strokeMove(x: Float, y: Float) {
        NavAccessibilityService.instance?.strokeMove(x, y)
    }

    override fun strokeEnd() {
        NavAccessibilityService.instance?.strokeEnd()
    }

    override fun back() {
        NavAccessibilityService.instance?.goBack()
    }

    override fun pausedChanged(paused: Boolean) {
        TrackingState.paused = paused
    }

    override fun calibrated(cx: Float, cy: Float) {
        settings.centerX = cx
        settings.centerY = cy
        mainHandler.post {
            Toast.makeText(applicationContext, "Pointer center calibrated", Toast.LENGTH_SHORT).show()
        }
    }
}
