package com.unigismap.indoormap.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.unigismap.indoormap.core.CampusCore
import com.unigismap.indoormap.core.FloorRef
import com.unigismap.indoormap.core.MapPoint
import com.unigismap.indoormap.core.MapRect

/**
 * Compose-side view of a floor from the shared core layer (shared/, backed by the C++ core).
 * Only converts geometry to Compose types; the lite app has the same adapter for android.graphics.
 * All coordinates are in meters, origin at the top-left corner of the floor plan.
 */
typealias RoomCategory = com.unigismap.indoormap.core.RoomCategory

data class Room(
    val id: String,
    val name: String,
    val category: RoomCategory,
    val bounds: Rect,
    /** Square meters, computed natively from the room polygon. */
    val area: Float,
)

/** A door is an opening in a wall between [start] and [end]. */
data class Door(
    val start: Offset,
    val end: Offset,
    val exterior: Boolean = false,
)

data class FloorPlan(
    val name: String,
    val bounds: Rect,
    val rooms: List<Room>,
    val doors: List<Door>,
    val entrance: Offset,
    val ref: FloorRef,
    private val campus: CampusCore,
) {
    fun roomAt(point: Offset): Room? = campus.roomAt(ref, point.x, point.y).takeIf { it >= 0 }?.let(rooms::get)

    /** Rooms on this floor matching [query] by number or name, best match first. */
    fun search(query: String): List<Room> = campus.search(query, ref).map { rooms[it.room] }

    companion object {
        fun of(campus: CampusCore, ref: FloorRef): FloorPlan {
            val floor = campus.floor(ref)
            return FloorPlan(
                name = floor.title,
                bounds = floor.bounds.toRect(),
                rooms = floor.rooms.map { Room(it.id, it.name, it.category, it.bounds.toRect(), it.area) },
                doors = floor.doors.map { Door(it.start.toOffset(), it.end.toOffset(), it.exterior) },
                entrance = floor.youAreHere.toOffset(),
                ref = ref,
                campus = campus,
            )
        }
    }
}

private fun MapRect.toRect() = Rect(left, top, right, bottom)
private fun MapPoint.toOffset() = Offset(x, y)
