package com.unigismap.indoormap.lite

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import com.unigismap.indoormap.core.NativeCore
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

private const val WALL_WIDTH = 0.12f
private const val OUTER_WALL_WIDTH = 0.3f

/** Wraps an angle delta between touch events into [-180, 180]; plain touch bookkeeping. */
private fun wrapDegrees(d: Float): Float {
    var r = d % 360f
    if (r > 180f) r -= 360f
    if (r < -180f) r += 360f
    return r
}

/**
 * Renders a [FloorPlan] and lets the user pan, pinch-zoom and two-finger-rotate it.
 *
 * Camera math lives in the shared C++ core (unigismap::Camera); this view owns its packed state.
 * A world point `w` (meters) maps to the screen as `offset + rotate(w * scale, rotation)`.
 */
class FloorMapView(context: Context, private val plan: FloorPlan) : View(context) {

    var palette: Palette = Palette.Light
        set(value) { field = value; invalidate() }

    var selectedRoom: Room? = null
        set(value) { field = value; invalidate() }

    var onRoomClick: (Room?) -> Unit = {}
    var onCameraChanged: () -> Unit = {}

    private val camera = FloatArray(NativeCore.cameraStateSize()).also {
        with(plan.bounds) { NativeCore.cameraInit(it, left, top, right, bottom) }
    }
    private val point = FloatArray(2)

    val scale get() = camera[NativeCore.CAMERA_SCALE]
    val rotationDeg get() = camera[NativeCore.CAMERA_ROTATION]
    private val offX get() = camera[NativeCore.CAMERA_OFFSET_X]
    private val offY get() = camera[NativeCore.CAMERA_OFFSET_Y]

    private var animator: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

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

    fun animateFocus(room: Room) = animateCamera {
        with(room.bounds) { NativeCore.cameraPrepareFocus(camera, left, top, right, bottom) }
    }

    // ---- Gestures ----

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val w = toWorld(e.x, e.y)
            onRoomClick(plan.roomAt(w.x, w.y))
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

    // ---- Drawing ----

    private fun fillFor(room: Room): Int = when {
        room === selectedRoom -> palette.primaryContainer
        else -> when (room.category) {
            RoomCategory.OFFICE -> palette.secondaryContainer
            RoomCategory.MEETING -> palette.tertiaryContainer
            RoomCategory.COMMON -> palette.surfaceContainerHigh
            RoomCategory.SERVICE -> palette.surfaceContainerHighest
            RoomCategory.CORRIDOR -> palette.surfaceContainerLow
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.surface)

        canvas.save()
        canvas.translate(offX, offY)
        canvas.rotate(rotationDeg)
        canvas.scale(scale, scale)

        fill.style = Paint.Style.FILL
        for (room in plan.rooms) {
            fill.color = fillFor(room)
            canvas.drawRect(room.bounds, fill)
        }
        stroke.strokeWidth = WALL_WIDTH
        stroke.color = palette.outline
        for (room in plan.rooms) canvas.drawRect(room.bounds, stroke)
        stroke.strokeWidth = OUTER_WALL_WIDTH
        stroke.color = palette.onSurface
        canvas.drawRect(plan.bounds, stroke)

        // Door openings: erase the wall segment.
        val corridor = plan.rooms.firstOrNull { it.category == RoomCategory.CORRIDOR }
        stroke.strokeWidth = OUTER_WALL_WIDTH * 1.4f
        for (door in plan.doors) {
            stroke.color = if (door.exterior || corridor == null) palette.surface else fillFor(corridor)
            canvas.drawLine(door.start.x, door.start.y, door.end.x, door.end.y, stroke)
        }

        selectedRoom?.let {
            stroke.strokeWidth = WALL_WIDTH * 2.5f
            stroke.color = palette.primary
            canvas.drawRect(it.bounds, stroke)
        }
        canvas.restore()

        // Labels are drawn in screen space so they always stay upright and readable.
        val minLabelSize = 44f * density
        for (room in plan.rooms) {
            val onScreen = min(room.bounds.width(), room.bounds.height()) * scale
            val selected = room === selectedRoom
            if (onScreen < minLabelSize && !selected) continue
            label.color = if (selected) palette.onPrimaryContainer else palette.onSurface
            label.isFakeBoldText = selected
            val c = toScreen(room.bounds.centerX(), room.bounds.centerY())
            canvas.drawText(room.name, c.x, c.y - (label.ascent() + label.descent()) / 2f, label)
        }

        // "You are here" marker at the entrance.
        val here = toScreen(plan.entrance.x, plan.entrance.y)
        fill.color = (palette.primary and 0x00FFFFFF) or 0x33000000
        canvas.drawCircle(here.x, here.y, 16f * density, fill)
        fill.color = palette.surface
        canvas.drawCircle(here.x, here.y, 8f * density, fill)
        fill.color = palette.primary
        canvas.drawCircle(here.x, here.y, 6f * density, fill)
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }
}
