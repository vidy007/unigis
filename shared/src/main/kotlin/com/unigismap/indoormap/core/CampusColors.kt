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
