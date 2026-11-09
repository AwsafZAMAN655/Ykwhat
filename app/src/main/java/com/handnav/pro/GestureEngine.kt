package com.handnav.pro

import kotlin.math.sqrt

/**
 * Turns 21 hand landmarks per frame into pointer movement and actions.
 * Called only from the camera analysis thread (config setters are @Volatile).
 *
 * All coordinates sent to [Output] are normalized screen coordinates (0..1).
 */
class GestureEngine(private val out: Output) {

    interface Output {
        fun pointer(x: Float, y: Float, mode: Int)
        fun tap(x: Float, y: Float)
        fun strokeBegin(x: Float, y: Float, holdMs: Long)
        fun strokeMove(x: Float, y: Float)
        fun strokeEnd()
        fun back()
        fun pausedChanged(paused: Boolean)
        fun calibrated(cx: Float, cy: Float)
    }

    companion object {
        const val MODE_PAUSED = 0
        const val MODE_POINT = 1
        const val MODE_PINCH = 2
        const val MODE_DRAG = 3
        const val MODE_SCROLL = 4

        const val G_NONE = 0
        const val G_POINT = 1
        const val G_PINCH = 2
        const val G_SCROLL = 3
        const val G_FIST = 4
        const val G_PALM = 5
        val LABELS = arrayOf("NONE", "POINT", "PINCH", "SCROLL", "FIST", "PALM")

        // ---------------- Tunables (edit here if gestures feel off) ----------------
        private const val BETA = 6.0f              // One Euro speed coefficient
        private const val REWIND_MS = 160L         // click lands where the pointer was this long before the pinch
        private const val STABILIZE_MS = 130L      // pointer is frozen this long after pinch starts
        private const val DRAG_SLOP = 0.04f        // normalized movement that turns a pinch into a drag
        private const val TAP_MAX_MS = 500L        // pinch shorter than this = tap, longer = long-press
        private const val DRAG_HOLD_MS = 250L      // touch-down time before a drag starts moving
        private const val FIST_HOLD_MS = 350L
        private const val PALM_HOLD_MS = 700L
        private const val FIST_COOLDOWN_MS = 1200L
        private const val PALM_COOLDOWN_MS = 1500L
        private const val LOST_GRACE_MS = 250L     // short tracking drop-outs do not break a drag/scroll
        private const val OFFSET_DECAY = 0.85f
        private const val EXT_MARGIN = 1.12f       // finger counts as extended if tip is this much farther from wrist than PIP
        private const val FIST_MAX_R = 1.5f        // in a fist every fingertip is closer than this (in palm lengths) to the wrist
        private const val THUMB_OPEN = 0.6f        // open palm: thumb tip to index base, in palm lengths
        private const val SCROLL_MIN_PINCH = 0.45f
        private const val HIST = 16
    }

    // ---------------- Config (written from UI thread) ----------------
    @Volatile private var pinchOn = 0.28f
    @Volatile private var pinchOff = 0.43f
    @Volatile private var gain = 2.2f
    @Volatile private var minCutoff = 2.2f
    @Volatile private var invertScroll = false
    @Volatile private var centerX = 0.5f
    @Volatile private var centerY = 0.5f
    @Volatile var calibrateRequested = false
    @Volatile var paused = false
        private set

    fun applyConfig(s: AppSettings) {
        val sens = s.sensitivity / 100f
        pinchOn = 0.16f + 0.24f * sens
        pinchOff = pinchOn * 1.55f
        gain = 1.0f + 3.0f * (s.speed / 100f)
        minCutoff = 4.0f - 3.6f * (s.smoothing / 100f)
        invertScroll = s.invertScroll
        centerX = s.centerX
        centerY = s.centerY
    }

    // ---------------- State ----------------
    private val fx = OneEuroFilter()
    private val fy = OneEuroFilter()
    private var p = FloatArray(42)
    private var aspect = 1f

    private var sx = 0.5f          // filtered mapped pointer (absolute)
    private var sy = 0.5f
    private var offX = 0f          // offset that keeps the pointer where the click should be
    private var offY = 0f
    private var curPx = 0.5f       // last pointer shown
    private var curPy = 0.5f

    private var lastFrame = 0L
    private var lostSince = 0L

    private var stable = G_NONE
    private var cand = G_NONE
    private var candCount = 0
    private var gestureStart = 0L
    private var latched = false
    private var cooldownUntil = 0L

    // pinch / drag
    private var ancX = 0f
    private var ancY = 0f
    private var dragging = false

    // scroll
    private var scrollX = 0f
    private var scrollY0 = 0f
    private var handY0 = 0f
    private var handYF = 0f

    // pointer history for click rewind
    private val histT = LongArray(HIST)
    private val histX = FloatArray(HIST)
    private val histY = FloatArray(HIST)
    private var histHead = 0
    private var histCount = 0

    // finger features
    private var idxR = 0f
    private var midR = 0f
    private var ringR = 0f
    private var pnkR = 0f
    private var pinchR = 1f
    private var idxExt = false
    private var midExt = false
    private var ringExt = false
    private var pnkExt = false
    private var thumbOpen = false

    // ---------------- Frame entry points ----------------

    fun onNoHand(now: Long) {
        TrackingState.handVisible = false
        if (lostSince == 0L) lostSince = now
        if (stable != G_NONE && now - lostSince >= LOST_GRACE_MS) {
            setStable(G_NONE, now, true)
            cand = G_NONE
            candCount = 0
            histCount = 0
            TrackingState.gesture = G_NONE
        }
    }

    fun onFrame(pts: FloatArray, asp: Float, now: Long) {
        p = pts
        aspect = asp
        val palm = dist(0, 9)
        if (palm < 1e-4f) {
            onNoHand(now)
            return
        }
        lostSince = 0L
        TrackingState.handVisible = true
        computeFeatures(palm)

        // Map index fingertip (mirrored horizontally) to the screen.
        val rawX = 1f - p[16]
        val rawY = p[17]
        if (calibrateRequested) {
            calibrateRequested = false
            centerX = rawX
            centerY = rawY
            fx.reset()
            fy.reset()
            out.calibrated(rawX, rawY)
        }
        val mx = (0.5f + (rawX - centerX) * gain).coerceIn(0f, 1f)
        val my = (0.5f + (rawY - centerY) * gain).coerceIn(0f, 1f)
        if (lastFrame == 0L || now - lastFrame > 400L) {
            fx.reset()
            fy.reset()
        }
        val dt = if (lastFrame == 0L) 0.033f else ((now - lastFrame) / 1000f).coerceIn(0.005f, 0.1f)
        lastFrame = now
        sx = fx.filter(mx, dt, minCutoff, BETA)
        sy = fy.filter(my, dt, minCutoff, BETA)

        // Classify and debounce.
        val raw = classify()
        if (raw == stable) {
            cand = raw
            candCount = 0
        } else {
            if (raw == cand) candCount++ else {
                cand = raw
                candCount = 1
            }
            val need = if (stable == G_NONE && raw == G_POINT) 1 else framesNeeded(raw)
            if (candCount >= need) {
                setStable(raw, now, false)
                cand = raw
                candCount = 0
            }
        }
        TrackingState.gesture = stable

        runState(now)
    }

    // ---------------- Classification ----------------

    private fun dist(a: Int, b: Int): Float {
        val dx = (p[2 * a] - p[2 * b]) * aspect
        val dy = p[2 * a + 1] - p[2 * b + 1]
        return sqrt(dx * dx + dy * dy)
    }

    private fun computeFeatures(palm: Float) {
        val dIdx = dist(8, 0)
        val dMid = dist(12, 0)
        val dRing = dist(16, 0)
        val dPnk = dist(20, 0)
        idxR = dIdx / palm
        midR = dMid / palm
        ringR = dRing / palm
        pnkR = dPnk / palm
        idxExt = dIdx > dist(6, 0) * EXT_MARGIN
        midExt = dMid > dist(10, 0) * EXT_MARGIN
        ringExt = dRing > dist(14, 0) * EXT_MARGIN
        pnkExt = dPnk > dist(18, 0) * EXT_MARGIN
        thumbOpen = dist(4, 5) / palm > THUMB_OPEN
        pinchR = dist(4, 8) / palm

        TrackingState.idxR = idxR
        TrackingState.midR = midR
        TrackingState.ringR = ringR
        TrackingState.pnkR = pnkR
        TrackingState.pinchR = pinchR
    }

    private fun classify(): Int {
        if (stable == G_PINCH) {
            if (pinchR < pinchOff) return G_PINCH
        } else if (pinchR < pinchOn) {
            return G_PINCH
        }
        if (!idxExt && !midExt && !ringExt && !pnkExt &&
            idxR < FIST_MAX_R && midR < FIST_MAX_R && ringR < FIST_MAX_R && pnkR < FIST_MAX_R
        ) return G_FIST
        if (idxExt && midExt && ringExt && pnkExt && thumbOpen) return G_PALM
        if (idxExt && midExt && pinchR > SCROLL_MIN_PINCH &&
            (stable == G_SCROLL || (!ringExt && !pnkExt))
        ) return G_SCROLL
        return G_POINT
    }

    private fun framesNeeded(g: Int): Int = when (g) {
        G_PINCH -> 2
        G_SCROLL -> 4
        G_FIST, G_PALM -> 3
        G_POINT -> 2
        else -> 1
    }

    // ---------------- State transitions ----------------

    private fun setStable(g: Int, now: Long, lost: Boolean) {
        exitGesture(stable, now, lost)
        stable = g
        gestureStart = now
        enterGesture(g, now)
    }

    private fun enterGesture(g: Int, now: Long) {
        when (g) {
            G_PINCH -> if (!paused) {
                rewind(now)
                offX = ancX - sx
                offY = ancY - sy
                dragging = false
                curPx = ancX
                curPy = ancY
                out.pointer(ancX, ancY, MODE_PINCH)
            }
            G_SCROLL -> if (!paused) {
                scrollX = curPx
                scrollY0 = curPy.coerceIn(0.2f, 0.8f)
                handY0 = handY()
                handYF = handY0
                out.strokeBegin(scrollX, scrollY0, 20L)
                out.pointer(scrollX, scrollY0, MODE_SCROLL)
            }
            G_FIST, G_PALM -> latched = false
            G_NONE -> {
                offX = 0f
                offY = 0f
            }
        }
    }

    private fun exitGesture(g: Int, now: Long, lost: Boolean) {
        when (g) {
            G_PINCH -> {
                if (!paused) {
                    if (dragging) out.strokeEnd()
                    else if (!lost && now - gestureStart < TAP_MAX_MS) out.tap(ancX, ancY)
                }
                dragging = false
            }
            G_SCROLL -> {
                if (!paused) out.strokeEnd()
                offX = 0f
                offY = 0f
            }
            G_FIST, G_PALM -> latched = false
        }
    }

    // ---------------- Per-frame behaviour ----------------

    private fun runState(now: Long) {
        if (paused && stable != G_PALM) return

        when (stable) {
            G_POINT -> {
                offX *= OFFSET_DECAY
                offY *= OFFSET_DECAY
                val px = (sx + offX).coerceIn(0f, 1f)
                val py = (sy + offY).coerceIn(0f, 1f)
                curPx = px
                curPy = py
                pushHist(now, px, py)
                out.pointer(px, py, MODE_POINT)
            }
            G_PINCH -> {
                val elapsed = now - gestureStart
                if (elapsed < STABILIZE_MS) {
                    offX = ancX - sx
                    offY = ancY - sy
                }
                val px = (sx + offX).coerceIn(0f, 1f)
                val py = (sy + offY).coerceIn(0f, 1f)
                curPx = px
                curPy = py
                if (!dragging) {
                    val dx = px - ancX
                    val dy = py - ancY
                    if (elapsed >= STABILIZE_MS && dx * dx + dy * dy > DRAG_SLOP * DRAG_SLOP) {
                        dragging = true
                        out.strokeBegin(ancX, ancY, DRAG_HOLD_MS)
                        out.strokeMove(px, py)
                    } else if (elapsed >= TAP_MAX_MS) {
                        dragging = true   // pinch held without moving = long-press
                        out.strokeBegin(ancX, ancY, 50L)
                        out.strokeMove(px, py)
                    }
                } else {
                    out.strokeMove(px, py)
                }
                out.pointer(px, py, if (dragging) MODE_DRAG else MODE_PINCH)
            }
            G_SCROLL -> {
                val hy = handY()
                handYF += (hy - handYF) * 0.6f
                val dir = if (invertScroll) -1f else 1f
                val y = (scrollY0 + (handYF - handY0) * gain * dir).coerceIn(0.1f, 0.9f)
                out.strokeMove(scrollX, y)
                out.pointer(scrollX, y, MODE_SCROLL)
            }
            G_FIST -> {
                if (!latched && now - gestureStart >= FIST_HOLD_MS && now >= cooldownUntil) {
                    latched = true
                    cooldownUntil = now + FIST_COOLDOWN_MS
                    out.back()
                }
            }
            G_PALM -> {
                if (!latched && now - gestureStart >= PALM_HOLD_MS && now >= cooldownUntil) {
                    latched = true
                    cooldownUntil = now + PALM_COOLDOWN_MS
                    paused = !paused
                    TrackingState.paused = paused
                    out.pausedChanged(paused)
                    out.pointer(curPx, curPy, if (paused) MODE_PAUSED else MODE_POINT)
                }
            }
        }
    }

    private fun handY(): Float = (p[17] + p[25]) * 0.5f

    // ---------------- Pointer history (click rewind) ----------------

    private fun pushHist(now: Long, x: Float, y: Float) {
        histT[histHead] = now
        histX[histHead] = x
        histY[histHead] = y
        histHead = (histHead + 1) % HIST
        if (histCount < HIST) histCount++
    }

    private fun rewind(now: Long) {
        val target = now - REWIND_MS
        var found = -1
        var oldest = -1
        for (k in 0 until histCount) {
            val idx = (histHead - 1 - k + HIST) % HIST
            oldest = idx
            if (histT[idx] <= target) {
                found = idx
                break
            }
        }
        val use = if (found >= 0) found else oldest
        if (use >= 0) {
            ancX = histX[use]
            ancY = histY[use]
        } else {
            ancX = curPx
            ancY = curPy
        }
    }
}
