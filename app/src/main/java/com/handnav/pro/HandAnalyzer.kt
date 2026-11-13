package com.handnav.pro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.nio.ByteBuffer

/**
 * Runs on the single camera-analysis thread. The HandLandmarker is created lazily on that
 * same thread (required for the GPU delegate) and used synchronously in VIDEO mode.
 * CameraX drops stale frames for us (STRATEGY_KEEP_ONLY_LATEST), so no queue builds up.
 */
class HandAnalyzer(
    private val appContext: Context,
    private val settings: AppSettings,
    private val engine: GestureEngine,
    private val onFatal: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private companion object {
        const val TAG = "HandAnalyzer"
        const val MODEL = "hand_landmarker.task"
    }

    private var landmarker: HandLandmarker? = null
    private var failed = false
    private var errorCount = 0

    // Reused buffers (no per-frame allocation in the normal path)
    private var frameBmp: Bitmap? = null
    private var frameCanvas: Canvas? = null
    private var padBmp: Bitmap? = null
    private var padBuf: ByteBuffer? = null
    private var rotBmp: Bitmap? = null
    private var rotCanvas: Canvas? = null
    private val rotMatrix = Matrix()
    private val pts = FloatArray(42)
    private var lastTs = 0L
    private var lastFrameNs = 0L
    private var fpsEma = 0f
    private var infEma = 0f


    override fun analyze(image: ImageProxy) {
        try {
            if (failed) return
            val lm = landmarker ?: createLandmarker().also { landmarker = it } ?: return

            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val src = plane.buffer
            src.rewind()
            val rowStride = plane.rowStride

            var frame = frameBmp
            if (frame == null || frame.width != w || frame.height != h) {
                frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                frameBmp = frame
                frameCanvas = Canvas(frame)
            }
            if (rowStride == w * 4) {
                // Tightly packed RGBA (what CameraX normally delivers)
                frame.copyPixelsFromBuffer(src)
            } else {
                // Padded rows: copy into a wider bitmap, then crop by drawing it into the frame bitmap.
                val pw = rowStride / 4
                var pad = padBmp
                if (pad == null || pad.width != pw || pad.height != h) {
                    pad = Bitmap.createBitmap(pw, h, Bitmap.Config.ARGB_8888)
                    padBmp = pad
                }
                val need = rowStride * h
                var pb = padBuf
                if (pb == null || pb.capacity() != need) {
                    pb = ByteBuffer.allocateDirect(need)
                    padBuf = pb
                }
                pb.clear()
                pb.put(src)            // last row may be shorter than rowStride, rest stays 0
                pb.rewind()
                pad.copyPixelsFromBuffer(pb)
                frameCanvas!!.drawBitmap(pad, 0f, 0f, null)
            }

            // Rotate clockwise by rotationDegrees so the hand is upright (same as Google's sample).
            val rot = image.imageInfo.rotationDegrees
            val upW = if (rot == 90 || rot == 270) h else w
            val upH = if (rot == 90 || rot == 270) w else h
            val upright: Bitmap
            if (rot == 0) {
                upright = frame
            } else {
                var rb = rotBmp
                if (rb == null || rb.width != upW || rb.height != upH) {
                    rb = Bitmap.createBitmap(upW, upH, Bitmap.Config.ARGB_8888)
                    rotBmp = rb
                    rotCanvas = Canvas(rb)
                }
                rotMatrix.reset()
                rotMatrix.postRotate(rot.toFloat())
                when (rot) {
                    90 -> rotMatrix.postTranslate(h.toFloat(), 0f)
                    180 -> rotMatrix.postTranslate(w.toFloat(), h.toFloat())
                    270 -> rotMatrix.postTranslate(0f, w.toFloat())
                }
                rotCanvas!!.drawBitmap(frame, rotMatrix, null)
                upright = rb
            }
            val aspect = upW.toFloat() / upH.toFloat()

            var ts = SystemClock.uptimeMillis()
            if (ts <= lastTs) ts = lastTs + 1
            lastTs = ts

            val t0 = System.nanoTime()
            val result = lm.detectForVideo(BitmapImageBuilder(upright).build(), ts)
            val t1 = System.nanoTime()

            val hands = result.landmarks()
            if (hands.isEmpty() || hands[0].size < 21) {
                engine.onNoHand(ts)
            } else {
                val l = hands[0]
                for (i in 0 until 21) {
                    pts[2 * i] = l[i].x()
                    pts[2 * i + 1] = l[i].y()
                }
                engine.onFrame(pts, aspect, ts)
            }

            // stats
            val infMs = (t1 - t0) / 1_000_000f
            infEma = if (infEma == 0f) infMs else infEma * 0.9f + infMs * 0.1f
            if (lastFrameNs != 0L) {
                val inst = 1_000_000_000f / (t1 - lastFrameNs).coerceAtLeast(1L)
                fpsEma = if (fpsEma == 0f) inst else fpsEma * 0.9f + inst * 0.1f
            }
            lastFrameNs = t1
            TrackingState.fps = fpsEma
            TrackingState.inferenceMs = infEma
            errorCount = 0
        } catch (e: Exception) {
            Log.e(TAG, "frame failed", e)
            errorCount++
            if (errorCount > 30 && !failed) {
                failed = true
                onFatal("Hand detection keeps failing: ${e.message}")
            }
        } finally {
            image.close()
        }
    }

    private fun createLandmarker(): HandLandmarker? {
        if (settings.useGpu) {
            try {
                val lm = build(Delegate.GPU)
                TrackingState.delegate = "GPU"
                return lm
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate failed, falling back to CPU", e)
            }
        }
        return try {
            val lm = build(Delegate.CPU)
            TrackingState.delegate = "CPU"
            lm
        } catch (e: Exception) {
            Log.e(TAG, "Cannot create HandLandmarker", e)
            failed = true
            onFatal("Cannot load hand model (is hand_landmarker.task in the APK?): ${e.message}")
            null
        }
    }

    private fun build(delegate: Delegate): HandLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL)
            .setDelegate(delegate)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        return HandLandmarker.createFromOptions(appContext, options)
    }

    /** Must be called on the analysis thread. */
    fun close() {
        try {
            landmarker?.close()
        } catch (_: Exception) {
        }
        landmarker = null
    }
}
