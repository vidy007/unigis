package com.unigis.indoormap.lite

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

private const val WALL_WIDTH = 0.12f
private const val OUTER_WALL_WIDTH = 0.3f

private fun normalizeDegrees(d: Float): Float {
    var r = d % 360f
    if (r > 180f) r -= 360f
    if (r < -180f) r += 360f
    return r
}

/**
 * Renders a [FloorPlan] and lets the user pan, pinch-zoom and two-finger-rotate it.
 *
 * A world point `w` (meters) maps to the screen as `offset + rotate(w * scale, rotation)`.
 */
class FloorMapView(context: Context, private val plan: FloorPlan) : View(context) {

    var palette: Palette = Palette.Light
        set(value) { field = value; invalidate() }

    var selectedRoom: Room? = null
        set(value) { field = value; invalidate() }

    var onRoomClick: (Room?) -> Unit = {}
    var onCameraChanged: () -> Unit = {}

    var scale = 1f
        private set
    var rotationDeg = 0f
        private set
    private var offX = 0f
    private var offY = 0f

    private var fitScale = 1f
    private val minScale get() = fitScale * 0.5f
    private val maxScale get() = fitScale * 12f
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

    private fun rot(x: Float, y: Float, degrees: Float): PointF {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return PointF(x * c - y * s, x * s + y * c)
    }

    fun toScreen(wx: Float, wy: Float): PointF {
        val p = rot(wx * scale, wy * scale, rotationDeg)
        return PointF(offX + p.x, offY + p.y)
    }

    fun toWorld(sx: Float, sy: Float): PointF {
        val p = rot(sx - offX, sy - offY, -rotationDeg)
        return PointF(p.x / scale, p.y / scale)
    }

    private val centerX get() = width / 2f
    private val centerY get() = height / 2f

    private fun computeFitScale(bounds: RectF): Float {
        if (width == 0 || height == 0) return 1f
        return min(width / bounds.width(), height / bounds.height()) * 0.9f
    }

    /** Places the world point ([cx], [cy]) in the middle of the view. */
    private fun setCamera(cx: Float, cy: Float, newScale: Float, newRotation: Float) {
        scale = newScale
        rotationDeg = newRotation
        val p = rot(cx * newScale, cy * newScale, newRotation)
        offX = centerX - p.x
        offY = centerY - p.y
        invalidate()
        onCameraChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val oldCenter = if (oldw > 0) toWorld(oldw / 2f, oldh / 2f) else null
        fitScale = computeFitScale(plan.bounds)
        if (oldCenter == null) {
            setCamera(plan.bounds.centerX(), plan.bounds.centerY(), fitScale, 0f)
        } else {
            setCamera(oldCenter.x, oldCenter.y, scale, rotationDeg)
        }
    }

    // ---- Animated camera moves ----

    private fun animateCamera(tx: Float, ty: Float, targetScale: Float, targetRotation: Float) {
        animator?.cancel()
        val start = toWorld(centerX, centerY)
        val s0 = ln(scale)
        val s1 = ln(targetScale)
        val r0 = rotationDeg
        val dr = normalizeDegrees(targetRotation - r0)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f) // M3 emphasized-ish
            addUpdateListener {
                val t = it.animatedValue as Float
                setCamera(
                    start.x + (tx - start.x) * t,
                    start.y + (ty - start.y) * t,
                    exp(s0 + (s1 - s0) * t),
                    normalizeDegrees(r0 + dr * t),
                )
            }
            start()
        }
    }

    fun animateFit() = animateCamera(plan.bounds.centerX(), plan.bounds.centerY(), fitScale, 0f)

    fun animateResetRotation() {
        val c = toWorld(centerX, centerY)
        animateCamera(c.x, c.y, scale, 0f)
    }

    fun animateZoom(factor: Float, px: Float = centerX, py: Float = centerY) {
        val target = (scale * factor).coerceIn(minScale, maxScale)
        val pivot = toWorld(px, py)
        val center = toWorld(centerX, centerY)
        val k = scale / target
        animateCamera(pivot.x + (center.x - pivot.x) * k, pivot.y + (center.y - pivot.y) * k, target, rotationDeg)
    }

    fun animateFocus(room: Room) {
        val r = RectF(room.bounds).apply { inset(-3f, -3f) }
        val target = maxOf(scale, computeFitScale(r)).coerceAtMost(maxScale)
        animateCamera(room.bounds.centerX(), room.bounds.centerY(), target, rotationDeg)
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
        val newScale = (scale * zoom).coerceIn(minScale, maxScale)
        val dz = newScale / scale
        val v = rot(cx - offX, cy - offY, rotDelta)
        offX = cx + panX - v.x * dz
        offY = cy + panY - v.y * dz
        scale = newScale
        rotationDeg = normalizeDegrees(rotationDeg + rotDelta)
        invalidate()
        onCameraChanged()
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
            val rotDelta = if (idx.size == 2) normalizeDegrees(angle - lastAngle) else 0f
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
