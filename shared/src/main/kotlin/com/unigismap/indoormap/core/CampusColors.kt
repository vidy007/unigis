package com.unigismap.indoormap.core

/**
 * Badge colors of the printed NSTU campus map (ARGB), the same in light and dark themes so the
 * app's map reads like the paper one. Shared by the Compose app and the lite app.
 */
object CampusColors {
    val ACADEMIC = 0xFF4E8A5C.toInt()  // green: учебные корпуса
    val DORM = 0xFFE5A630.toInt()      // orange: общежития
    val FACILITY = 0xFF2E5C8F.toInt()  // blue: центр культуры, спорт, библиотека...
    val BUS = ACADEMIC
    val TRAM = DORM
    val METRO = 0xFF9C2A45.toInt()     // maroon: метро «Студенческая»
    val ON_BADGE = 0xFFFFFFFF.toInt()

    fun of(kind: BuildingKind): Int = when (kind) {
        BuildingKind.ACADEMIC -> ACADEMIC
        BuildingKind.DORM -> DORM
        BuildingKind.FACILITY -> FACILITY
    }

    fun of(kind: StopKind): Int = when (kind) {
        StopKind.BUS -> BUS
        StopKind.TRAM -> TRAM
        StopKind.METRO -> METRO
    }
}

/** One row of the campus map legend. */
data class LegendItem(val color: Int, val label: String)

/**
 * Legend for what is actually on the map, in the order of the printed one: only the kinds of
 * buildings and stops that are drawn (the app shows the academic block, without dormitories).
 */
fun legendOf(buildings: List<BuildingInfo>, stops: List<StopInfo>): List<LegendItem> {
    val drawn = buildings.filter { it.outline.isNotEmpty() }
    val items = ArrayList<LegendItem>()
    if (drawn.any { it.kind == BuildingKind.ACADEMIC }) items += LegendItem(CampusColors.ACADEMIC, "Учебные корпуса")
    if (drawn.any { it.kind == BuildingKind.DORM }) items += LegendItem(CampusColors.DORM, "Общежития")
    val facilities = drawn.filter { it.kind == BuildingKind.FACILITY }
    if (facilities.isNotEmpty()) {
        items += LegendItem(CampusColors.FACILITY, facilities.singleOrNull()?.shortName ?: "Объекты кампуса")
    }
    if (stops.any { it.kind == StopKind.BUS }) items += LegendItem(CampusColors.BUS, "Автобусная остановка")
    if (stops.any { it.kind == StopKind.TRAM }) items += LegendItem(CampusColors.TRAM, "Трамвайная остановка")
    stops.firstOrNull { it.kind == StopKind.METRO }?.let {
        items += LegendItem(CampusColors.METRO, if (it.name.isEmpty()) "Метро" else "Метро «${it.name}»")
    }
    return items
}
