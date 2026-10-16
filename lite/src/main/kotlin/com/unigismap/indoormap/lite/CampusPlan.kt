package com.unigismap.indoormap.lite

import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import com.unigismap.indoormap.core.BuildingInfo
import com.unigismap.indoormap.core.CampusColors
import com.unigismap.indoormap.core.CampusCore
import com.unigismap.indoormap.core.MapPoint
import com.unigismap.indoormap.core.StopKind

/**
 * android.graphics-side view of the campus map from the shared core layer. Mirrors
 * app/.../map/CampusPlan.kt, which does the same for Compose types.
 * Coordinates are campus meters (x east, y south); [viewRotation] turns the map the way the
 * printed NSTU map is drawn.
 */
class CampusBuilding(val info: BuildingInfo, val outline: Path, val bounds: RectF, val label: PointF) {
    val color = CampusColors.of(info.kind)
    /** Only buildings with floor plans can be opened; the rest are just drawn. */
    val hasPlans: Boolean get() = info.floors.isNotEmpty()
    val entrances: List<PointF> = info.entrances.map { it.pos.toPointF() }
}

class CampusStop(val name: String, val kind: StopKind, val pos: PointF) {
    val color = CampusColors.of(kind)
}

class CampusPlan(
    val name: String,
    val attribution: String,
    val bounds: RectF,
    val viewRotation: Float,
    /** Buildings that are on the map (georeferenced), in data order. */
    val buildings: List<CampusBuilding>,
    val stops: List<CampusStop>,
    val footways: List<Path>,
    private val campus: CampusCore,
) {
    /** Building with floor plans under ([x], [y]); taps on the other buildings do nothing. */
    fun buildingAt(x: Float, y: Float): CampusBuilding? =
        campus.buildingAt(x, y)
            ?.let { info -> buildings.firstOrNull { it.info.index == info.index } }
            ?.takeIf { it.hasPlans }

    companion object {
        fun of(campus: CampusCore): CampusPlan {
            val map = campus.map
            val buildings = campus.buildings.filter { it.outline.size >= 3 }.map { b ->
                val path = b.outline.toPath(close = true)
                val bounds = RectF().also { path.computeBounds(it, true) }
                CampusBuilding(b, path, bounds, b.labelPos.toPointF())
            }
            return CampusPlan(
                name = campus.name,
                attribution = campus.attribution,
                bounds = with(map.bounds) { RectF(left, top, right, bottom) },
                viewRotation = map.viewRotation,
                buildings = buildings,
                stops = map.stops.map { CampusStop(it.name, it.kind, it.pos.toPointF()) },
                footways = map.footways.map { it.toPath(close = false) },
                campus = campus,
            )
        }
    }
}

private fun MapPoint.toPointF() = PointF(x, y)

private fun List<MapPoint>.toPath(close: Boolean) = Path().apply {
    forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    if (close) close()
}
