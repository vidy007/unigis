package com.unigis.indoormap.lite

import android.graphics.PointF
import android.graphics.RectF
import com.unigis.indoormap.core.CampusCore
import com.unigis.indoormap.core.FloorRef
import com.unigis.indoormap.core.MapPoint
import com.unigis.indoormap.core.MapRect

/**
 * android.graphics-side view of a floor from the shared core layer (shared/, backed by the
 * C++ core). Mirrors app/.../map/FloorPlan.kt, which does the same for Compose types.
 * All coordinates are in meters, origin at the top-left corner of the floor plan.
 */
typealias RoomCategory = com.unigis.indoormap.core.RoomCategory

/** [area] is in square meters, computed natively from the room polygon. */
class Room(val id: String, val name: String, val category: RoomCategory, val bounds: RectF, val area: Float)

/** A door is an opening in a wall between [start] and [end]. */
class Door(val start: PointF, val end: PointF, val exterior: Boolean = false)

class FloorPlan(
    val name: String,
    val bounds: RectF,
    val rooms: List<Room>,
    val doors: List<Door>,
    val entrance: PointF,
    val ref: FloorRef,
    private val campus: CampusCore,
) {
    fun roomAt(x: Float, y: Float): Room? = campus.roomAt(ref, x, y).takeIf { it >= 0 }?.let(rooms::get)

    /** Rooms on this floor matching [query] by number or name, best match first. */
    fun search(query: String): List<Room> = campus.search(query, ref).map { rooms[it.room] }

    companion object {
        fun of(campus: CampusCore, ref: FloorRef): FloorPlan {
            val floor = campus.floor(ref)
            return FloorPlan(
                name = floor.title,
                bounds = floor.bounds.toRectF(),
                rooms = floor.rooms.map { Room(it.id, it.name, it.category, it.bounds.toRectF(), it.area) },
                doors = floor.doors.map { Door(it.start.toPointF(), it.end.toPointF(), it.exterior) },
                entrance = floor.youAreHere.toPointF(),
                ref = ref,
                campus = campus,
            )
        }
    }
}

private fun MapRect.toRectF() = RectF(left, top, right, bottom)
private fun MapPoint.toPointF() = PointF(x, y)
