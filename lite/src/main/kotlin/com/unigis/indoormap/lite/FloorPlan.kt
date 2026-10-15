package com.unigis.indoormap.lite

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import com.unigis.indoormap.core.NativeCore

/**
 * UI-side view of a floor decoded from the shared C++ core (app/src/main/cpp), which owns the
 * campus data (assets/campus.json), hit testing and search. Coordinates are in meters.
 */
enum class RoomCategory(val title: String) {
    // Order must match unigis::RoomCategory in campus.hpp: the ordinal crosses JNI.
    OFFICE("Кабинет"),
    MEETING("Переговорная"),
    COMMON("Общая зона"),
    SERVICE("Служебное"),
    CORRIDOR("Коридор"),
}

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
    private val campus: Campus,
    private val building: Int,
    private val floor: Int,
) {
    fun roomAt(x: Float, y: Float): Room? =
        NativeCore.roomAt(campus.handle, building, floor, x, y).takeIf { it >= 0 }?.let(rooms::get)

    /** Rooms on this floor matching [query] by number or name, best match first. */
    fun search(query: String): List<Room> {
        val hits = NativeCore.searchRooms(campus.handle, query, building, floor)
        return List(hits.size / 3) { rooms[hits[3 * it + 2]] }
    }
}

/** Process-wide native campus; its memory is intentionally never released. */
class Campus private constructor(val handle: Long) {

    /** First floor of the first building that has floor plans. */
    fun defaultFloorPlan(): FloorPlan {
        for (b in 0 until NativeCore.buildingCount(handle)) {
            // [levels, placeholder, floor levels...]
            if (NativeCore.buildingLevels(handle, b).size > 2) return floorPlan(b, 0)
        }
        error("campus.json has no floor plans")
    }

    fun floorPlan(building: Int, floor: Int): FloorPlan {
        // Packed layouts are documented in app/src/main/cpp/jni/jni_bridge.cpp.
        val strings = requireNotNull(NativeCore.floorStrings(handle, building, floor))
        val g = requireNotNull(NativeCore.floorGeometry(handle, building, floor))
        var i = 0
        fun next() = g[i++]
        val bounds = RectF(next(), next(), next(), next())
        val youAreHere = PointF(next(), next())
        val rooms = List(next().toInt()) { r ->
            val category = RoomCategory.values()[next().toInt()]
            val roomBounds = RectF(next(), next(), next(), next())
            val area = next()
            i += 2 // anchor, used natively for routing
            Room(strings[1 + 2 * r], strings[2 + 2 * r], category, roomBounds, area)
        }
        val doors = List(next().toInt()) {
            val start = PointF(next(), next())
            val end = PointF(next(), next())
            Door(start, end, exterior = next() != 0f)
        }
        val shortName = NativeCore.buildingStrings(handle, building)[2]
        return FloorPlan("$shortName · ${strings[0]}", bounds, rooms, doors, youAreHere, this, building, floor)
    }

    companion object {
        @Volatile
        private var instance: Campus? = null

        fun get(context: Context): Campus =
            instance ?: synchronized(this) {
                instance ?: run {
                    val json = context.applicationContext.assets.open("campus.json")
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    Campus(NativeCore.loadCampus(json)).also { instance = it }
                }
            }
    }
}
