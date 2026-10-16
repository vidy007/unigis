package com.unigismap.indoormap.core

import android.content.Context

/*
 * UI-independent layer shared by the Compose app (app/) and the dependency-free lite app (lite/).
 * It only uses the Kotlin stdlib and the Android framework (no AndroidX), decodes the packed
 * arrays coming from the C++ core (layouts in app/src/main/cpp/jni_bridge.cpp) and leaves
 * conversion to Compose or android.graphics types to each UI.
 */

/** Order must match unigismap::RoomCategory in unigismap.hpp: the ordinal crosses JNI. */
enum class RoomCategory(val title: String) {
    OFFICE("Кабинет"),
    MEETING("Переговорная"),
    COMMON("Общая зона"),
    SERVICE("Служебное"),
    CORRIDOR("Коридор"),
}

/** Point in meters. */
data class MapPoint(val x: Float, val y: Float)

/** Axis-aligned rectangle in meters. */
data class MapRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Identifies a floor inside the native campus. */
data class FloorRef(val building: Int, val floor: Int)

/** Identifies a room inside the native campus. */
data class RoomRef(val building: Int, val floor: Int, val room: Int)

data class RoomInfo(
    val id: String,
    val name: String,
    val category: RoomCategory,
    val bounds: MapRect,
    /** Square meters, computed natively from the room polygon. */
    val area: Float,
)

data class DoorInfo(val start: MapPoint, val end: MapPoint, val exterior: Boolean)

data class FloorInfo(
    val ref: FloorRef,
    /** "<building short name> · <floor name>", as shown under the app title. */
    val title: String,
    val bounds: MapRect,
    val youAreHere: MapPoint,
    val rooms: List<RoomInfo>,
    val doors: List<DoorInfo>,
)

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
    val outline: List<MapPoint>,
)

/** One map segment of a route: a building floor, or outdoors when [floor] is null. */
data class RouteLeg(val floor: FloorRef?, val points: List<MapPoint>)

data class Route(val meters: Float, val seconds: Float, val legs: List<RouteLeg>)

/**
 * Kotlin handle to the C++ campus model (the parsed campus plus its walking graph).
 * Screens share one instance through [acquire]/[release]; the native memory is freed by [close]
 * once the last screen is gone. Calls after [close] throw IllegalStateException instead of
 * reaching freed native memory. Like the UI, use it from the main thread.
 */
class CampusCore private constructor(handle: Long) : AutoCloseable {
    /** Native Session pointer; 0 once closed. */
    @Volatile
    private var nativeHandle: Long = handle

    private val handle: Long
        get() = nativeHandle.also { check(it != 0L) { "CampusCore is closed" } }

    val name: String
    val attribution: String

    init {
        val info = NativeCore.campusInfo(handle)
        name = info[0]
        attribution = info[1]
    }

    /** Frees the native campus and walking graph. Safe to call more than once. */
    override fun close() {
        synchronized(this) {
            if (nativeHandle != 0L) {
                NativeCore.releaseCampus(nativeHandle)
                nativeHandle = 0L
            }
        }
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
                outline = List(outline.size / 2) { MapPoint(outline[2 * it], outline[2 * it + 1]) },
            )
        }
    }

    private val floors = HashMap<FloorRef, FloorInfo>()

    /** First floor of the first building that has floor plans. */
    fun defaultFloor(): FloorRef =
        requireNotNull(buildings.firstOrNull { it.floorLevels.isNotEmpty() }) { "campus.json has no floor plans" }
            .let { FloorRef(it.index, 0) }

    fun floor(ref: FloorRef): FloorInfo = synchronized(floors) { floors.getOrPut(ref) { decodeFloor(ref) } }

    /** Index of the room under ([x], [y]) meters, preferring rooms over corridors; -1 if none. */
    fun roomAt(ref: FloorRef, x: Float, y: Float): Int = NativeCore.roomAt(handle, ref.building, ref.floor, x, y)

    /** Rooms matching [query] by number or name, best first; restricted to [scope] if given. */
    fun search(query: String, scope: FloorRef? = null): List<RoomRef> {
        val hits = NativeCore.searchRooms(handle, query, scope?.building ?: -1, scope?.floor ?: -1)
        return List(hits.size / 3) { RoomRef(hits[3 * it], hits[3 * it + 1], hits[3 * it + 2]) }
    }

    /** Fastest walking route between two rooms, possibly across floors and buildings. */
    fun route(from: RoomRef, to: RoomRef): Route? {
        val a = NativeCore.route(handle, from.building, from.floor, from.room, to.building, to.floor, to.room)
        if (a[0] == 0f) return null
        var i = 4
        val legs = List(a[3].toInt()) {
            val building = a[i++].toInt()
            val floor = a[i++].toInt()
            val count = a[i++].toInt()
            val points = List(count) { MapPoint(a[i + 2 * it], a[i + 2 * it + 1]) }
            i += 2 * count
            RouteLeg(if (building < 0) null else FloorRef(building, floor), points)
        }
        return Route(meters = a[1], seconds = a[2], legs = legs)
    }

    private fun decodeFloor(ref: FloorRef): FloorInfo {
        val strings = requireNotNull(NativeCore.floorStrings(handle, ref.building, ref.floor)) { "No floor $ref" }
        val g = requireNotNull(NativeCore.floorGeometry(handle, ref.building, ref.floor)) { "No floor $ref" }
        var i = 0
        fun next() = g[i++]
        val bounds = MapRect(next(), next(), next(), next())
        val youAreHere = MapPoint(next(), next())
        val rooms = List(next().toInt()) { r ->
            val category = RoomCategory.entries[next().toInt()]
            val roomBounds = MapRect(next(), next(), next(), next())
            val area = next()
            i += 2 // anchor, used natively for routing
            RoomInfo(strings[1 + 2 * r], strings[2 + 2 * r], category, roomBounds, area)
        }
        val doors = List(next().toInt()) {
            val start = MapPoint(next(), next())
            val end = MapPoint(next(), next())
            DoorInfo(start, end, exterior = next() != 0f)
        }
        return FloorInfo(ref, "${buildings[ref.building].shortName} · ${strings[0]}", bounds, youAreHere, rooms, doors)
    }

    companion object {
        const val ASSET = "campus.json"

        private var instance: CampusCore? = null
        private var users = 0

        /**
         * Process-wide campus, parsed from the APK assets on first use (works offline).
         * Every call must be paired with [release], typically onCreate/onDestroy of an activity.
         */
        fun acquire(context: Context): CampusCore = synchronized(this) {
            val campus = instance ?: run {
                val json = context.applicationContext.assets.open(ASSET)
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                CampusCore(NativeCore.loadCampus(json)).also { instance = it }
            }
            users++
            campus
        }

        /**
         * Drops one [acquire]. When nobody uses the campus any more it is closed, unless
         * [changingConfigurations] says the activity is only being recreated (rotation, theme...)
         * and is about to acquire it again; then the parsed data is kept for the new instance.
         */
        fun release(changingConfigurations: Boolean = false) = synchronized(this) {
            check(users > 0) { "CampusCore.release() without acquire()" }
            users--
            if (users == 0 && !changingConfigurations) {
                instance?.close()
                instance = null
            }
        }
    }
}
