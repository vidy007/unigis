package com.unigis.indoormap.core

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.unigis.indoormap.map.Door
import com.unigis.indoormap.map.FloorPlan
import com.unigis.indoormap.map.Room
import com.unigis.indoormap.map.RoomCategory

/** Identifies a floor inside the native campus. */
data class FloorRef(val building: Int, val floor: Int)

/** Identifies a room inside the native campus. */
data class RoomRef(val building: Int, val floor: Int, val room: Int)

data class BuildingInfo(
    val index: Int,
    val id: String,
    val name: String,
    val shortName: String,
    val levels: Int,
    /** Floor data is a stand-in, not a surveyed plan. */
    val placeholder: Boolean,
    /** Levels that have floor plans, ascending. */
    val floorLevels: List<Int>,
    /** Footprint in campus meters (x east, y south). */
    val outline: List<Offset>,
)

/** One map segment of a route: a building floor, or outdoors when [floor] is null. */
data class RouteLeg(val floor: FloorRef?, val points: List<Offset>)

data class Route(val meters: Float, val seconds: Float, val legs: List<RouteLeg>)

/**
 * Kotlin handle to the C++ campus model. One instance lives for the whole process (see [get]),
 * so the native memory is intentionally never released.
 */
class Campus private constructor(private val handle: Long) {
    val name: String
    val attribution: String

    init {
        val info = NativeCore.campusInfo(handle)
        name = info[0]
        attribution = info[1]
    }

    val buildings: List<BuildingInfo> by lazy {
        List(NativeCore.buildingCount(handle)) { b ->
            val s = NativeCore.buildingStrings(handle, b)
            val levels = NativeCore.buildingLevels(handle, b)
            val outline = NativeCore.buildingOutline(handle, b)
            BuildingInfo(
                index = b,
                id = s[0],
                name = s[1],
                shortName = s[2],
                levels = levels[0],
                placeholder = levels[1] != 0,
                floorLevels = levels.drop(2),
                outline = List(outline.size / 2) { Offset(outline[2 * it], outline[2 * it + 1]) },
            )
        }
    }

    private val plans = HashMap<FloorRef, FloorPlan>()

    /** First floor of the first building that has floor plans. */
    fun defaultFloor(): FloorRef? =
        buildings.firstOrNull { it.floorLevels.isNotEmpty() }?.let { FloorRef(it.index, 0) }

    fun floorPlan(ref: FloorRef): FloorPlan = plans.getOrPut(ref) { decodeFloor(ref) }

    fun roomAt(ref: FloorRef, point: Offset): Int = NativeCore.roomAt(handle, ref.building, ref.floor, point.x, point.y)

    /** Rooms matching [query], best first; restricted to [scope] if given. */
    fun search(query: String, scope: FloorRef? = null): List<RoomRef> {
        val hits = NativeCore.searchRooms(handle, query, scope?.building ?: -1, scope?.floor ?: -1)
        return List(hits.size / 3) { RoomRef(hits[3 * it], hits[3 * it + 1], hits[3 * it + 2]) }
    }

    fun room(ref: RoomRef): Room = floorPlan(FloorRef(ref.building, ref.floor)).rooms[ref.room]

    /** Fastest walking route between two rooms, possibly across floors and buildings. */
    fun route(from: RoomRef, to: RoomRef): Route? {
        val a = NativeCore.route(handle, from.building, from.floor, from.room, to.building, to.floor, to.room)
        if (a[0] == 0f) return null
        var i = 4
        val legs = List(a[3].toInt()) {
            val building = a[i++].toInt()
            val floor = a[i++].toInt()
            val count = a[i++].toInt()
            val points = List(count) { Offset(a[i + 2 * it], a[i + 2 * it + 1]) }
            i += 2 * count
            RouteLeg(if (building < 0) null else FloorRef(building, floor), points)
        }
        return Route(meters = a[1], seconds = a[2], legs = legs)
    }

    private fun decodeFloor(ref: FloorRef): FloorPlan {
        val strings = requireNotNull(NativeCore.floorStrings(handle, ref.building, ref.floor)) { "No floor $ref" }
        val g = requireNotNull(NativeCore.floorGeometry(handle, ref.building, ref.floor)) { "No floor $ref" }
        var i = 0
        fun next() = g[i++]
        val bounds = Rect(next(), next(), next(), next())
        val youAreHere = Offset(next(), next())
        val rooms = List(next().toInt()) { r ->
            val category = RoomCategory.entries[next().toInt()]
            val roomBounds = Rect(next(), next(), next(), next())
            val area = next()
            i += 2 // anchor, used natively for routing
            Room(strings[1 + 2 * r], strings[2 + 2 * r], category, roomBounds, area)
        }
        val doors = List(next().toInt()) {
            val start = Offset(next(), next())
            val end = Offset(next(), next())
            Door(start, end, exterior = next() != 0f)
        }
        val building = buildings[ref.building]
        return FloorPlan(
            name = "${building.shortName} · ${strings[0]}",
            bounds = bounds,
            rooms = rooms,
            doors = doors,
            entrance = youAreHere,
            ref = ref,
            campus = this,
        )
    }

    companion object {
        const val ASSET = "campus.json"

        @Volatile
        private var instance: Campus? = null

        /** Process-wide campus, parsed from assets on first use. */
        fun get(context: Context): Campus =
            instance ?: synchronized(this) {
                instance ?: load(context.applicationContext).also { instance = it }
            }

        private fun load(context: Context): Campus {
            val json = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return Campus(NativeCore.loadCampus(json))
        }
    }
}
