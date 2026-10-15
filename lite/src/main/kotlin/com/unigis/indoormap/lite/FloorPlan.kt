package com.unigis.indoormap.lite

import android.graphics.PointF
import android.graphics.RectF

/** All coordinates are in meters, origin at the top-left corner of the building. */
enum class RoomCategory(val title: String) {
    OFFICE("Кабинет"),
    MEETING("Переговорная"),
    COMMON("Общая зона"),
    SERVICE("Служебное"),
    CORRIDOR("Коридор"),
}

class Room(val id: String, val name: String, val category: RoomCategory, val bounds: RectF) {
    val area: Float get() = bounds.width() * bounds.height()
}

/** A door is an opening in a wall between [start] and [end]. */
class Door(val start: PointF, val end: PointF, val exterior: Boolean = false)

class FloorPlan(
    val name: String,
    val bounds: RectF,
    val rooms: List<Room>,
    val doors: List<Door>,
    val entrance: PointF,
) {
    fun roomAt(x: Float, y: Float): Room? =
        rooms.lastOrNull { it.category != RoomCategory.CORRIDOR && it.bounds.contains(x, y) }
            ?: rooms.lastOrNull { it.bounds.contains(x, y) }
}

private fun rect(l: Float, t: Float, r: Float, b: Float) = RectF(l, t, r, b)
private fun door(x1: Float, y1: Float, x2: Float, y2: Float, exterior: Boolean = false) =
    Door(PointF(x1, y1), PointF(x2, y2), exterior)

/** Demo single-storey office building, 32 × 20 m. Same layout as the Compose app. */
val DemoFloorPlan = FloorPlan(
    name = "Этаж 1",
    bounds = rect(0f, 0f, 32f, 20f),
    rooms = listOf(
        Room("101", "Кабинет 101", RoomCategory.OFFICE, rect(0f, 0f, 6f, 8f)),
        Room("102", "Кабинет 102", RoomCategory.OFFICE, rect(6f, 0f, 12f, 8f)),
        Room("103", "Опенспейс", RoomCategory.OFFICE, rect(12f, 0f, 22f, 8f)),
        Room("104", "Переговорная", RoomCategory.MEETING, rect(22f, 0f, 28f, 8f)),
        Room("105", "Серверная", RoomCategory.SERVICE, rect(28f, 0f, 32f, 8f)),
        Room("corridor", "Коридор", RoomCategory.CORRIDOR, rect(0f, 8f, 32f, 11f)),
        Room("lobby", "Холл", RoomCategory.COMMON, rect(0f, 11f, 8f, 20f)),
        Room("110", "Ресепшн", RoomCategory.COMMON, rect(8f, 11f, 13f, 20f)),
        Room("111", "Кухня", RoomCategory.COMMON, rect(13f, 11f, 19f, 20f)),
        Room("wc", "Санузел", RoomCategory.SERVICE, rect(19f, 11f, 22f, 20f)),
        Room("stairs", "Лестница", RoomCategory.SERVICE, rect(22f, 11f, 26f, 20f)),
        Room("112", "Склад", RoomCategory.SERVICE, rect(26f, 11f, 32f, 20f)),
    ),
    doors = listOf(
        door(2f, 8f, 3f, 8f),
        door(8.5f, 8f, 9.5f, 8f),
        door(16f, 8f, 18f, 8f),
        door(24.5f, 8f, 25.5f, 8f),
        door(29.5f, 8f, 30.5f, 8f),
        door(2f, 11f, 6f, 11f),
        door(10f, 11f, 11f, 11f),
        door(15f, 11f, 17f, 11f),
        door(20f, 11f, 21f, 11f),
        door(23f, 11f, 25f, 11f),
        door(28.5f, 11f, 29.5f, 11f),
        door(8f, 15f, 8f, 17f),
        door(3f, 20f, 5f, 20f, exterior = true),
    ),
    entrance = PointF(4f, 18.8f),
)
