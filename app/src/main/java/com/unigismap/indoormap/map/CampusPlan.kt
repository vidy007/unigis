package com.unigismap.indoormap.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.unigismap.indoormap.core.BuildingInfo
import com.unigismap.indoormap.core.CampusColors
import com.unigismap.indoormap.core.CampusCore
import com.unigismap.indoormap.core.LegendItem
import com.unigismap.indoormap.core.MapPoint
import com.unigismap.indoormap.core.StopKind

/**
 * Compose-side view of the campus map from the shared core layer: every building of the printed
 * NSTU map with its badge, the stops and the walkways. Coordinates are campus meters
 * (x east, y south); [viewRotation] turns the map the way the printed one is drawn.
 */
class CampusBuilding(val info: BuildingInfo, val outline: Path, val bounds: Rect, val label: Offset) {
    val color = Color(CampusColors.of(info.kind))
    /** Only buildings with floor plans can be opened; the rest are just drawn. */
    val hasPlans: Boolean get() = info.floors.isNotEmpty()
    val entrances: List<Offset> = info.entrances.map { it.pos.toOffset() }
}

class CampusStop(val name: String, val kind: StopKind, val pos: Offset) {
    val color = Color(CampusColors.of(kind))
}

class CampusPlan(
    val name: String,
    val attribution: String,
    val bounds: Rect,
    val viewRotation: Float,
    /** Buildings that are on the map (georeferenced), in data order. */
    val buildings: List<CampusBuilding>,
    val stops: List<CampusStop>,
    val footways: List<Path>,
    /** Only the kinds of buildings and stops that are on the map. */
    val legend: List<LegendItem>,
    private val campus: CampusCore,
) {
    /** Building with floor plans under [point]; taps on the other buildings do nothing. */
    fun buildingAt(point: Offset): CampusBuilding? =
        campus.buildingAt(point.x, point.y)
            ?.let { info -> buildings.firstOrNull { it.info.index == info.index } }
            ?.takeIf { it.hasPlans }

    companion object {
        fun of(campus: CampusCore): CampusPlan {
            val map = campus.map
            val buildings = campus.buildings.filter { it.outline.size >= 3 }.map { b ->
                val path = b.outline.toPath(close = true)
                CampusBuilding(b, path, path.getBounds(), b.labelPos.toOffset())
            }
            return CampusPlan(
                name = campus.name,
                attribution = campus.attribution,
                bounds = with(map.bounds) { Rect(left, top, right, bottom) },
                viewRotation = map.viewRotation,
                buildings = buildings,
                stops = map.stops.map { CampusStop(it.name, it.kind, it.pos.toOffset()) },
                footways = map.footways.map { it.toPath(close = false) },
                legend = campus.legend,
                campus = campus,
            )
        }
    }
}

private fun MapPoint.toOffset() = Offset(x, y)

private fun List<MapPoint>.toPath(close: Boolean) = Path().apply {
    forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    if (close) close()
}
