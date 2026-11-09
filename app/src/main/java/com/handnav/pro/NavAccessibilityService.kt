package com.handnav.pro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Hosts the pointer overlay (TYPE_ACCESSIBILITY_OVERLAY, so no "draw over other apps"
 * permission is needed) and performs taps, drags, scrolls and Back on behalf of the user.
 * It does not read any screen content (canRetrieveWindowContent is not requested).
 */
class NavAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NavA11y"

        @Volatile
        var instance: NavAccessibilityService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var overlay: PointerView? = null
    private var windowManager: WindowManager? = null
    private val loc = IntArray(2)

    private var active = false
    private var pointerWanted = true

    // ----- continuous stroke state (main thread only, except the two volatile targets) -----
    private var stroke: GestureDescription.StrokeDescription? = null
    private var strokeId = 0
    private var curX = 0f
    private var curY = 0f
    private var inFlight = false
    private var ending = false
    private var finalSent = false

    // A new touch requested while the previous one is still being released waits here.
    private var hasPendingBegin = false
    private var pendNx = 0f
    private var pendNy = 0f
    private var pendHold = 0L

    @Volatile private var targetNx = 0.5f
    @Volatile private var targetNy = 0.5f
    private val pumpQueued = AtomicBoolean(false)
    private val pumpRunnable = Runnable {
        pumpQueued.set(false)
        pump()
    }

    // ---------------- Lifecycle ----------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        pointerWanted = AppSettings(this).pointerVisible
        addOverlay()
        updateVisibility()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used. We never inspect screen content.
    }

    override fun onInterrupt() {
        main.post {
            hasPendingBegin = false
            resetStroke()
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        removeOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        removeOverlay()
        super.onDestroy()
    }

    private fun addOverlay() {
        if (overlay != null) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val view = PointerView(this)
            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            view.visibility = View.GONE
            wm.addView(view, lp)
            windowManager = wm
            overlay = view
        } catch (e: Exception) {
            Log.e(TAG, "Cannot add overlay", e)
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try {
            windowManager?.removeView(v)
        } catch (_: Exception) {
        }
        overlay = null
    }

    private fun updateVisibility() {
        overlay?.visibility = if (active && pointerWanted) View.VISIBLE else View.GONE
    }

    // ---------------- API used by TrackingService ----------------

    fun setActive(isActive: Boolean) {
        main.post {
            active = isActive
            if (!isActive) {
                hasPendingBegin = false
                resetStroke()
            }
            updateVisibility()
        }
    }

    fun setPointerVisible(visible: Boolean) {
        main.post {
            pointerWanted = visible
            updateVisibility()
        }
    }

    fun updatePointer(nx: Float, ny: Float, mode: Int) {
        overlay?.setPointer(nx, ny, mode)
    }

    fun goBack() {
        main.post { performGlobalAction(GLOBAL_ACTION_BACK) }
    }

    fun tapAt(nx: Float, ny: Float) {
        main.post { doTap(nx, ny, 0) }
    }

    private fun doTap(nx: Float, ny: Float, retry: Int) {
        // Never start a tap while a drag/scroll touch is still down or being released:
        // a second gesture would cancel it.
        if (stroke != null && retry < 10) {
            main.postDelayed({ doTap(nx, ny, retry + 1) }, 30L)
            return
        }
        val v = overlay ?: return
        if (v.width == 0) return
        v.getLocationOnScreen(loc)
        val x = loc[0] + nx * v.width
        val y = loc[1] + ny * v.height
        v.showRipple(nx, ny)
        val path = Path()
        path.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 40L))
            .build()
        dispatchGesture(g, null, null)
    }

    // ----- continuous touch (drag and scroll) -----

    fun strokeBegin(nx: Float, ny: Float, holdMs: Long) {
        main.post {
            if (stroke != null) {
                // Previous touch is still down or its release is in flight:
                // release it first, then start the new one (see finishStroke()).
                pendNx = nx
                pendNy = ny
                pendHold = holdMs
                hasPendingBegin = true
                ending = true
                pump()
            } else {
                beginNow(nx, ny, holdMs)
            }
        }
    }

    private fun beginNow(nx: Float, ny: Float, holdMs: Long) {
        val v = overlay ?: return
        if (v.width == 0) return
        v.getLocationOnScreen(loc)
        curX = loc[0] + nx * v.width
        curY = loc[1] + ny * v.height
        targetNx = nx
        targetNy = ny
        ending = false
        finalSent = false
        val path = Path()
        path.moveTo(curX, curY)   // zero-length path = touch that does not move
        val s = GestureDescription.StrokeDescription(path, 0L, holdMs.coerceAtLeast(1L), true)
        val id = ++strokeId
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(s).build(),
            callbackFor(id),
            null
        )
        if (ok) {
            stroke = s
            inFlight = true
        }
    }

    /** Touch fully released (or cancelled): clear state, then start a queued touch if any. */
    private fun finishStroke() {
        resetStroke()
        if (hasPendingBegin) {
            hasPendingBegin = false
            beginNow(pendNx, pendNy, pendHold)
        }
    }

    fun strokeMove(nx: Float, ny: Float) {
        targetNx = nx
        targetNy = ny
        if (pumpQueued.compareAndSet(false, true)) main.post(pumpRunnable)
    }

    fun strokeEnd() {
        main.post {
            if (hasPendingBegin) {
                // The queued touch was released before it even started: just drop it.
                hasPendingBegin = false
                return@post
            }
            if (stroke == null) return@post
            ending = true
            pump()
        }
    }

    private fun callbackFor(id: Int) = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            if (id != strokeId) return
            inFlight = false
            if (finalSent) finishStroke() else pump()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            if (id != strokeId) return
            inFlight = false
            finishStroke()
        }
    }

    private fun pump() {
        val s = stroke ?: return
        if (inFlight || finalSent) return
        val v = overlay ?: return
        if (v.width == 0) return
        v.getLocationOnScreen(loc)
        val tx = loc[0] + targetNx * v.width
        val ty = loc[1] + targetNy * v.height
        val moving = !hasPendingBegin && (abs(tx - curX) >= 1f || abs(ty - curY) >= 1f)
        if (!moving && !ending) return

        val path = Path()
        path.moveTo(curX, curY)
        if (moving) path.lineTo(tx, ty)
        val next = try {
            s.continueStroke(path, 0L, if (ending) 20L else 35L, !ending)
        } catch (e: Exception) {
            Log.e(TAG, "continueStroke failed", e)
            finishStroke()
            return
        }
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(next).build(),
            callbackFor(strokeId),
            null
        )
        if (!ok) {
            finishStroke()
            return
        }
        stroke = next
        inFlight = true
        if (moving) {
            curX = tx
            curY = ty
        }
        if (ending) finalSent = true
    }

    private fun resetStroke() {
        strokeId++            // invalidates callbacks of any older stroke
        stroke = null
        inFlight = false
        ending = false
        finalSent = false
    }
}
