package com.unigis.indoormap.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** All coordinates are in meters, origin at the top-left corner of the building. */
enum class RoomCategory(val title: String) {
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
) {
    val area: Float get() = bounds.width * bounds.height
}

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
) {
    fun roomAt(point: Offset): Room? =
        rooms.lastOrNull { it.category != RoomCategory.CORRIDOR && it.bounds.contains(point) }
            ?: rooms.lastOrNull { it.bounds.contains(point) }
}

private fun rect(l: Float, t: Float, r: Float, b: Float) = Rect(l, t, r, b)

/** Demo single-storey office building, 32 × 20 m. */
val DemoFloorPlan = FloorPlan(
    name = "Этаж 1",
    bounds = rect(0f, 0f, 32f, 20f),
    rooms = listOf(
        // North row
        Room("101", "Кабинет 101", RoomCategory.OFFICE, rect(0f, 0f, 6f, 8f)),
        Room("102", "Кабинет 102", RoomCategory.OFFICE, rect(6f, 0f, 12f, 8f)),
        Room("103", "Опенспейс", RoomCategory.OFFICE, rect(12f, 0f, 22f, 8f)),
        Room("104", "Переговорная", RoomCategory.MEETING, rect(22f, 0f, 28f, 8f)),
        Room("105", "Серверная", RoomCategory.SERVICE, rect(28f, 0f, 32f, 8f)),
        // Corridor
        Room("corridor", "Коридор", RoomCategory.CORRIDOR, rect(0f, 8f, 32f, 11f)),
        // South row
        Room("lobby", "Холл", RoomCategory.COMMON, rect(0f, 11f, 8f, 20f)),
        Room("110", "Ресепшн", RoomCategory.COMMON, rect(8f, 11f, 13f, 20f)),
        Room("111", "Кухня", RoomCategory.COMMON, rect(13f, 11f, 19f, 20f)),
        Room("wc", "Санузел", RoomCategory.SERVICE, rect(19f, 11f, 22f, 20f)),
        Room("stairs", "Лестница", RoomCategory.SERVICE, rect(22f, 11f, 26f, 20f)),
        Room("112", "Склад", RoomCategory.SERVICE, rect(26f, 11f, 32f, 20f)),
    ),
    doors = listOf(
        Door(Offset(2f, 8f), Offset(3f, 8f)),
        Door(Offset(8.5f, 8f), Offset(9.5f, 8f)),
        Door(Offset(16f, 8f), Offset(18f, 8f)),
        Door(Offset(24.5f, 8f), Offset(25.5f, 8f)),
        Door(Offset(29.5f, 8f), Offset(30.5f, 8f)),
        Door(Offset(2f, 11f), Offset(6f, 11f)),
        Door(Offset(10f, 11f), Offset(11f, 11f)),
        Door(Offset(15f, 11f), Offset(17f, 11f)),
        Door(Offset(20f, 11f), Offset(21f, 11f)),
        Door(Offset(23f, 11f), Offset(25f, 11f)),
        Door(Offset(28.5f, 11f), Offset(29.5f, 11f)),
        Door(Offset(8f, 15f), Offset(8f, 17f)),
        Door(Offset(3f, 20f), Offset(5f, 20f), exterior = true),
    ),
    entrance = Offset(4f, 18.8f),
)
