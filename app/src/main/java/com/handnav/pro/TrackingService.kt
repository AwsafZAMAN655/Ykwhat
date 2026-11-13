package com.handnav.pro

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TrackingService : AccessibilityService(), GestureEngine.Output {

    companion object {
        private const val TAG = "TrackingService"
        var isRunning = false
            private set
    }

    private lateinit var appSettings: AppSettings
    private lateinit var gestureEngine: GestureEngine
    private var handAnalyzer: HandAnalyzer? = null
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate() {
        super.onCreate()
        appSettings = AppSettings(this)
        gestureEngine = GestureEngine(this).apply {
            applyConfig(appSettings)
        }
        cameraExecutor = Executors.newSingleThreadExecutor()
        isRunning = true

        startCameraAnalysis()
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
                    this as LifecycleService,
                    cameraSelector,
                    analysisUseCase
                )
                Log.d(TAG, "Camera analysis bound successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
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
        
        // Pass flattened landmarks and aspect ratio to engine
        gestureEngine.onFrame(pts, 1.0f, timestamp)
    }

    // ---------------- GestureEngine.Output Implementations ----------------

    override fun pointer(x: Float, y: Float, mode: Int) {
        // Update floating pointer overlay position and state
    }

    override fun tap(x: Float, y: Float) {
        // Perform click action via accessibility injection or gesture dispatch
    }

    override fun strokeBegin(x: Float, y: Float, holdMs: Long) {
        // Start drag/scroll stroke
    }

    override fun strokeMove(x: Float, y: Float) {
        // Update drag/scroll stroke position
    }

    override fun strokeEnd() {
        // Finish drag/scroll stroke
    }

    override fun back() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    override fun pausedChanged(paused: Boolean) {
        Log.d(TAG, "Paused state changed: $paused")
    }

    override fun calibrated(cx: Float, cy: Float) {
        Log.d(TAG, "Calibrated at cx=$cx, cy=$cy")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handAnalyzer?.close()
        handAnalyzer = null
        cameraExecutor.shutdown()
        Log.d(TAG, "TrackingService destroyed")
    }
}
