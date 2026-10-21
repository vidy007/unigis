package com.unigismap.indoormap.lite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import com.unigismap.indoormap.core.CampusColors
import com.unigismap.indoormap.core.StopKind

/**
 * The campus as on the printed NSTU map: gray footprints with building numbers, entrances,
 * bus/tram stops and the metro. Mirrors app/.../map/CampusMap.kt. Numbers and markers are drawn
 * in screen space so they keep their size and stay upright while the map is zoomed and rotated.
 */
class CampusMapView(context: Context, private val plan: CampusPlan) :
    MapView(context, plan.bounds, plan.viewRotation, cover = true) {

    var selected: CampusBuilding? = null
        set(value) { field = value; invalidate() }

    var onBuildingClick: (CampusBuilding?) -> Unit = {}

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val numberText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 13f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val metroText = Paint(numberText).apply { color = CampusColors.ON_BADGE }
    private val rect = RectF()
    private val triangle = Path()

    override fun onTapWorld(wx: Float, wy: Float) = onBuildingClick(plan.buildingAt(wx, wy))

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.surface)

        // World space: meters, rotated and scaled by the camera.
        canvas.save()
        canvas.translate(offX, offY)
        canvas.rotate(rotationDeg)
        canvas.scale(scale, scale)
        val px = 1f / scale  // one screen pixel in meters
        stroke.strokeWidth = density * px
        stroke.color = palette.outlineVariant
        for (fw in plan.footways) canvas.drawPath(fw, stroke)
        fill.style = Paint.Style.FILL
        stroke.strokeWidth = density * px
        stroke.color = palette.outline
        for (b in plan.buildings) {
            fill.color = when {
                b === selected -> palette.primaryContainer
                b.hasPlans -> palette.secondaryContainer  // can be opened
                else -> palette.surfaceContainerHighest
            }
            canvas.drawPath(b.outline, fill)
            canvas.drawPath(b.outline, stroke)
        }
        selected?.let {
            stroke.strokeWidth = 2.5f * density * px
            stroke.color = palette.primary
            canvas.drawPath(it.outline, stroke)
        }
        canvas.restore()

        // Screen space.
        for (b in plan.buildings) for (e in b.entrances) drawEntrance(canvas, toScreen(e.x, e.y))
        for (s in plan.stops) drawStop(canvas, s)
        for (b in plan.buildings) {
            if (b.info.number.isEmpty()) continue
            val c = toScreen(b.label.x, b.label.y)
            numberText.color = palette.primary
            canvas.drawText(b.info.number, c.x, c.y - (numberText.ascent() + numberText.descent()) / 2f, numberText)
        }
    }

    private fun drawStop(canvas: Canvas, stop: CampusStop) {
        val c = toScreen(stop.pos.x, stop.pos.y)
        val w = 22f * density
        val h = (if (stop.kind == StopKind.METRO) 16f else 10f) * density
        rect.set(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
        fill.color = stop.color
        canvas.drawRoundRect(rect, 2f * density, 2f * density, fill)
        if (stop.kind == StopKind.METRO) {
            canvas.drawText("М", c.x, c.y - (metroText.ascent() + metroText.descent()) / 2f, metroText)
        }
    }

    /** Small upright triangle, as the "Вход" marks of the printed map. */
    private fun drawEntrance(canvas: Canvas, at: PointF) {
        val (x, y) = at.x to at.y
        val r = 4f * density
        triangle.rewind()
        triangle.moveTo(x, y - r)
        triangle.lineTo(x + r, y + r * 0.8f)
        triangle.lineTo(x - r, y + r * 0.8f)
        triangle.close()
        fill.color = palette.onSurfaceVariant
        canvas.drawPath(triangle, fill)
    }
}
