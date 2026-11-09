package com.handnav.pro

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter: strong smoothing when the hand is slow (kills jitter),
 * little smoothing when the hand is fast (keeps latency low).
 */
class OneEuroFilter {
    private var has = false
    private var xPrev = 0f
    private var dxPrev = 0f

    fun reset() {
        has = false
    }

    fun filter(x: Float, dt: Float, minCutoff: Float, beta: Float): Float {
        if (!has) {
            has = true
            xPrev = x
            dxPrev = 0f
            return x
        }
        val dx = (x - xPrev) / dt
        val aD = alpha(dt, 1.0f)
        val dxHat = aD * dx + (1f - aD) * dxPrev
        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(dt, cutoff)
        val xHat = a * x + (1f - a) * xPrev
        xPrev = xHat
        dxPrev = dxHat
        return xHat
    }

    private fun alpha(dt: Float, cutoff: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}
