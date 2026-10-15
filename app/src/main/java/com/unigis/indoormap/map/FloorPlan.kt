package com.unigis.indoormap.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.unigis.indoormap.core.Campus
import com.unigis.indoormap.core.FloorRef

/**
 * UI-side view of a floor decoded from the native campus model (app/src/main/cpp).
 * All coordinates are in meters, origin at the top-left corner of the floor plan.
 * Floor data itself lives in assets/campus.json.
 */
enum class RoomCategory(val title: String) {
    // Order must match unigis::RoomCategory in campus.hpp: the ordinal crosses JNI.
    OFFICE("Кабинет"),
    MEETING("Переговорная"),
    COMMON("Общая зона"),
    SERVICE("Служебное"),
    CORRIDOR("Коридор"),
}

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
    private val campus: Campus,
) {
    fun roomAt(point: Offset): Room? = campus.roomAt(ref, point).takeIf { it >= 0 }?.let(rooms::get)

    /** Rooms on this floor matching [query] by number or name, best match first. */
    fun search(query: String): List<Room> = campus.search(query, ref).map { rooms[it.room] }
}
