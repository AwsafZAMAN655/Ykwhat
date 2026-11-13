package com.handnav.pro

import android.content.Context
import android.graphics.Bitmap
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
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.nio.ByteBuffer

class HandAnalyzer(
    context: Context,
    private val settings: AppSettings,
    private val onResult: (HandLandmarkerResult?, Long) -> Unit
) : ImageAnalysis.Analyzer {

    private var landmarker: HandLandmarker? = null
    private var padBmp: Bitmap? = null
    private var padBuf: ByteBuffer? = null
    private var rotBmp: Bitmap? = null
    private var lastRotation = -1
    private val rotMatrix = Matrix()

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(Delegate.GPU)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(settings.detectionConfidence)
            .setMinHandPresenceConfidence(settings.presenceConfidence)
            .setMinTrackingConfidence(settings.trackingConfidence)
            .setResultListener { result, _ ->
                onResult(result, SystemClock.uptimeMillis())
            }
            .setErrorListener { e ->
                Log.e("HandAnalyzer", "MediaPipe error", e)
            }
            .build()

        landmarker = try {
            HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.w("HandAnalyzer", "GPU delegate failed, falling back to CPU", e)
            val cpuBase = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .setDelegate(Delegate.CPU)
                .build()
            HandLandmarker.createFromOptions(
                context,
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(cpuBase)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(settings.detectionConfidence)
                    .setMinHandPresenceConfidence(settings.presenceConfidence)
                    .setMinTrackingConfidence(settings.trackingConfidence)
                    .setResultListener { result, _ ->
                        onResult(result, SystemClock.uptimeMillis())
                    }
                    .build()
            )
        }
    }

    override fun analyze(image: ImageProxy) {
        val lm = landmarker ?: run {
            image.close()
            return
        }

        try {
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val padW = rowStride / 4

            val pad = padBmp?.takeIf { it.width == padW && it.height == h }
                ?: Bitmap.createBitmap(padW, h, Bitmap.Config.ARGB_8888).also { padBmp = it }

            val need = rowStride * h
            var pb = padBuf
            if (pb == null || pb.capacity() != need) {
                pb = ByteBuffer.allocateDirect(need)
                padBuf = pb
            }

            val src = plane.buffer
            pb?.let { buffer ->
                buffer.clear()
                src?.let { s ->
                    s.rewind()
                    buffer.put(s)
                }
                buffer.rewind()
                pad.copyPixelsFromBuffer(buffer)
            }

            val rot = image.imageInfo.rotationDegrees
            if (rot != lastRotation) {
                rotMatrix.reset()
                rotMatrix.postRotate(rot.toFloat())
                if (settings.mirrorX) {
                    rotMatrix.postScale(-1f, 1f)
                }
                lastRotation = rot
            }

            val cropped = Bitmap.createBitmap(pad, 0, 0, w, h, rotMatrix, false)
            val mpImage = BitmapImageBuilder(cropped).build()
            lm.detectAsync(mpImage, SystemClock.uptimeMillis())
        } catch (e: Exception) {
            Log.e("HandAnalyzer", "Frame processing failed", e)
        } finally {
            image.close()
        }
    }

    fun close() {
        landmarker?.close()
        landmarker = null
    }
}
