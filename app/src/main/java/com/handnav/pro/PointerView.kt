package com.handnav.pro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen, touch-transparent overlay that draws the pointer and a click ripple.
 * setPointer() may be called from any thread: it only writes volatile fields and
 * requests a small dirty-rect redraw.
 */
class PointerView(context: Context) : View(context) {

    @Volatile private var nx = 0.5f
    @Volatile private var ny = 0.5f
    @Volatile private var mode = GestureEngine.MODE_POINT

    private val density = resources.displayMetrics.density
    private val dotRadius = 9f * density
    private val ringRadius = 13f * density
    private val rippleMax = 48f * density
    private val margin = (rippleMax + 6f * density).toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.WHITE
    }
    private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0xFF00BFA5.toInt()
    }

    private var rippleStart = 0L
    private var rippleX = 0f
    private var rippleY = 0f

    fun setPointer(x: Float, y: Float, m: Int) {
        val w = width
        val h = height
        val ox = nx * w
        val oy = ny * h
        nx = x
        ny = y
        mode = m
        val cx = x * w
        val cy = y * h
        postInvalidateOnAnimation(
            (min(ox, cx) - margin).toInt(),
            (min(oy, cy) - margin).toInt(),
            (max(ox, cx) + margin).toInt(),
            (max(oy, cy) + margin).toInt()
        )
    }

    /** Main thread only. */
    fun showRipple(x: Float, y: Float) {
        rippleX = x * width
        rippleY = y * height
        rippleStart = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = nx * width
        val cy = ny * height
        val now = SystemClock.uptimeMillis()

        if (rippleStart != 0L) {
            val t = (now - rippleStart) / 300f
            if (t >= 1f) {
                rippleStart = 0L
            } else {
                ripple.alpha = ((1f - t) * 220f).toInt()
                canvas.drawCircle(rippleX, rippleY, dotRadius + (rippleMax - dotRadius) * t, ripple)
                postInvalidateOnAnimation(
                    (rippleX - margin).toInt(), (rippleY - margin).toInt(),
                    (rippleX + margin).toInt(), (rippleY + margin).toInt()
                )
            }
        }

        val m = mode
        fill.color = when (m) {
            GestureEngine.MODE_PAUSED -> 0xFF9E9E9E.toInt()
            GestureEngine.MODE_PINCH -> 0xFFFF9800.toInt()
            GestureEngine.MODE_DRAG -> 0xFFFF5722.toInt()
            GestureEngine.MODE_SCROLL -> 0xFF2979FF.toInt()
            else -> 0xFF00BFA5.toInt()
        }
        fill.alpha = 230
        val r = if (m == GestureEngine.MODE_PINCH || m == GestureEngine.MODE_DRAG) dotRadius * 0.7f else dotRadius
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, ringRadius, ring)
    }
}
