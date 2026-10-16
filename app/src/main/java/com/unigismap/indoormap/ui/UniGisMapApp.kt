package com.unigismap.indoormap.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.unigismap.indoormap.core.CampusCore
import com.unigismap.indoormap.core.FloorRef
import com.unigismap.indoormap.map.CampusPlan
import com.unigismap.indoormap.map.FloorPlan

/** Campus map first; a building with floor plans opens on top of it, Back returns to the campus. */
@Composable
fun UniGisMapApp(campus: CampusCore) {
    val campusPlan = remember(campus) { CampusPlan.of(campus) }
    var building by rememberSaveable { mutableIntStateOf(-1) }
    var floor by rememberSaveable { mutableIntStateOf(0) }

    val info = campus.buildings.getOrNull(building)?.takeIf { it.floors.isNotEmpty() }
    if (info == null) {
        CampusScreen(campusPlan, onOpenFloors = { building = it.index; floor = 0 })
        return
    }
    val index = floor.coerceIn(info.floors.indices)
    val plan = remember(campus, info.index, index) { FloorPlan.of(campus, FloorRef(info.index, index)) }
    BackHandler { building = -1 }
    MapScreen(
        plan = plan,
        floors = info.floorLevels.map { "$it этаж" },
        floorIndex = index,
        onFloorSelected = { floor = it },
        onBack = { building = -1 },
    )
}
