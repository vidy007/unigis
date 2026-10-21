package com.unigismap.indoormap.lite

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import com.unigismap.indoormap.core.NativeCore
import kotlin.math.atan2
import kotlin.math.hypot

/** Wraps an angle delta between touch events into [-180, 180]; plain touch bookkeeping. */
private fun wrapDegrees(d: Float): Float {
    var r = d % 360f
    if (r > 180f) r -= 360f
    if (r < -180f) r += 360f
    return r
}

/**
 * A map in meters (a floor plan or the campus) that the user can pan, pinch-zoom and
 * two-finger-rotate. Camera math lives in the shared C++ core (unigismap::Camera); this view
 * owns its packed state. A world point `w` maps to the screen as `offset + rotate(w * scale, rotation)`.
 * [homeRotation] is the default view angle: fit and reset-rotation return to it. With [cover] the
 * home view fills the screen with the map; zooming out still reaches the whole map.
 */
abstract class MapView(context: Context, bounds: RectF, homeRotation: Float = 0f, cover: Boolean = false) : View(context) {

    var palette: Palette = Palette.Light
        set(value) { field = value; invalidate() }

    var onCameraChanged: () -> Unit = {}

    private val camera = FloatArray(NativeCore.cameraStateSize()).also {
        with(bounds) { NativeCore.cameraInit(it, left, top, right, bottom, homeRotation, cover) }
    }
    private val point = FloatArray(2)

    val scale get() = camera[NativeCore.CAMERA_SCALE]
    val rotationDeg get() = camera[NativeCore.CAMERA_ROTATION]
    protected val offX get() = camera[NativeCore.CAMERA_OFFSET_X]
    protected val offY get() = camera[NativeCore.CAMERA_OFFSET_Y]

    private var animator: ValueAnimator? = null
    protected val density = resources.displayMetrics.density

    /** A tap at ([wx], [wy]) world meters. */
    protected abstract fun onTapWorld(wx: Float, wy: Float)

    // ---- Coordinate transforms ----

    fun toScreen(wx: Float, wy: Float): PointF {
        NativeCore.cameraToScreen(camera, wx, wy, point)
        return PointF(point[0], point[1])
    }

    fun toWorld(sx: Float, sy: Float): PointF {
        NativeCore.cameraToWorld(camera, sx, sy, point)
        return PointF(point[0], point[1])
    }

    private fun cameraChanged() {
        invalidate()
        onCameraChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        NativeCore.cameraViewport(camera, w.toFloat(), h.toFloat())
        cameraChanged()
    }

    // ---- Animated camera moves ----

    /** Native code computes the start/target poses; the animator only drives eased progress. */
    private fun animateCamera(prepare: () -> Unit) {
        animator?.cancel()
        prepare()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f) // M3 emphasized-ish
            addUpdateListener {
                NativeCore.cameraStep(camera, it.animatedValue as Float)
                cameraChanged()
            }
            start()
        }
    }

    fun animateFit() = animateCamera { NativeCore.cameraPrepareFit(camera) }

    fun animateResetRotation() = animateCamera { NativeCore.cameraPrepareResetRotation(camera) }

    fun animateZoom(factor: Float, px: Float = Float.NaN, py: Float = Float.NaN) =
        animateCamera { NativeCore.cameraPrepareZoom(camera, factor, px, py) }

    fun animateFocus(target: RectF) = animateCamera {
        with(target) { NativeCore.cameraPrepareFocus(camera, left, top, right, bottom) }
    }

    // ---- Gestures ----

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val w = toWorld(e.x, e.y)
            onTapWorld(w.x, w.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateZoom(2f, e.x, e.y)
            return true
        }
    })

    private var lastCx = 0f
    private var lastCy = 0f
    private var lastDist = 0f
    private var lastAngle = 0f
    private var lastCount = 0

    private fun applyGesture(cx: Float, cy: Float, panX: Float, panY: Float, zoom: Float, rotDelta: Float) {
        NativeCore.cameraGesture(camera, cx, cy, panX, panY, zoom, rotDelta)
        cameraChanged()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        tapDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) animator?.cancel()

        // Ignore the pointer that is going up so the remaining ones continue smoothly.
        val upIndex = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val idx = (0 until event.pointerCount).filter { it != upIndex }.take(2)
        if (idx.isEmpty()) {
            lastCount = 0
            return true
        }
        val cx = idx.map { event.getX(it) }.average().toFloat()
        val cy = idx.map { event.getY(it) }.average().toFloat()
        var dist = 0f
        var angle = 0f
        if (idx.size == 2) {
            val dx = event.getX(idx[1]) - event.getX(idx[0])
            val dy = event.getY(idx[1]) - event.getY(idx[0])
            dist = hypot(dx, dy)
            angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        }

        if (event.actionMasked == MotionEvent.ACTION_MOVE && lastCount == idx.size) {
            val zoom = if (idx.size == 2 && lastDist > 0f) dist / lastDist else 1f
            val rotDelta = if (idx.size == 2) wrapDegrees(angle - lastAngle) else 0f
            applyGesture(lastCx, lastCy, cx - lastCx, cy - lastCy, zoom, rotDelta)
        }

        lastCx = cx; lastCy = cy; lastDist = dist; lastAngle = angle
        lastCount = when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 0
            else -> idx.size
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }
}
