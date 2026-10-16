package com.unigismap.indoormap.lite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.min

private const val WALL_WIDTH = 0.12f
private const val OUTER_WALL_WIDTH = 0.3f

/** Renders a [FloorPlan]; camera and gestures come from [MapView]. */
class FloorMapView(context: Context, private val plan: FloorPlan) : MapView(context, plan.bounds) {

    var selectedRoom: Room? = null
        set(value) { field = value; invalidate() }

    var onRoomClick: (Room?) -> Unit = {}

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun onTapWorld(wx: Float, wy: Float) = onRoomClick(plan.roomAt(wx, wy))

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

}
