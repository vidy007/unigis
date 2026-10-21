package com.unigismap.indoormap.map

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unigismap.indoormap.core.CampusColors
import com.unigismap.indoormap.core.StopKind

/**
 * The campus as on the printed NSTU map: gray footprints with building numbers, entrances,
 * bus/tram stops and the metro. Numbers and markers are drawn in screen space so they keep
 * their size and stay upright while the map is zoomed and rotated.
 */
@Composable
fun CampusMap(
    plan: CampusPlan,
    state: MapState,
    selected: CampusBuilding?,
    onBuildingClick: (CampusBuilding?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val numberStyle = TextStyle(color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    val metroStyle = numberStyle.copy(color = Color(CampusColors.ON_BADGE))

    Canvas(modifier.mapInput(state) { onBuildingClick(plan.buildingAt(it)) }) {
        drawRect(colors.surface)

        // World space: meters, rotated and scaled by the camera.
        withTransform({
            translate(state.offset.x, state.offset.y)
            rotate(state.rotation, pivot = Offset.Zero)
            scale(state.scale, state.scale, pivot = Offset.Zero)
        }) {
            val px = 1f / state.scale  // one screen pixel in meters
            val walkway = Stroke(1.dp.toPx() * px, cap = StrokeCap.Round, join = StrokeJoin.Round)
            plan.footways.forEach { drawPath(it, colors.outlineVariant, style = walkway) }
            plan.buildings.forEach { b ->
                val fill = when {
                    b == selected -> colors.primaryContainer
                    b.hasPlans -> colors.secondaryContainer  // can be opened
                    else -> colors.surfaceContainerHighest
                }
                drawPath(b.outline, fill)
                drawPath(b.outline, colors.outline, style = Stroke(1.dp.toPx() * px))
            }
            selected?.let { drawPath(it.outline, colors.primary, style = Stroke(2.5.dp.toPx() * px)) }
        }

        // Screen space.
        val entranceColor = colors.onSurfaceVariant
        plan.buildings.forEach { b -> b.entrances.forEach { drawEntrance(state.toScreen(it), entranceColor) } }
        plan.stops.forEach { drawStop(it, state.toScreen(it.pos), textMeasurer, metroStyle) }
        plan.buildings.forEach { b ->
            if (b.info.number.isNotEmpty()) {
                val layout = textMeasurer.measure(b.info.number, numberStyle)
                drawText(layout, topLeft = state.toScreen(b.label) - Offset(layout.size.width / 2f, layout.size.height / 2f))
            }
        }
    }
}

private fun DrawScope.drawStop(
    stop: CampusStop,
    center: Offset,
    textMeasurer: TextMeasurer,
    style: TextStyle,
) {
    val w = 22.dp.toPx()
    val h = if (stop.kind == StopKind.METRO) 16.dp.toPx() else 10.dp.toPx()
    val topLeft = center - Offset(w / 2f, h / 2f)
    drawRoundRect(stop.color, topLeft, Size(w, h), CornerRadius(2.dp.toPx()))
    if (stop.kind == StopKind.METRO) {
        val layout = textMeasurer.measure("М", style.copy(fontSize = 11.sp))
        drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f))
    }
}

/** Small upright triangle, as the "Вход" marks of the printed map. */
private fun DrawScope.drawEntrance(at: Offset, color: Color) {
    val r = 4.dp.toPx()
    val path = Path().apply {
        moveTo(at.x, at.y - r)
        lineTo(at.x + r, at.y + r * 0.8f)
        lineTo(at.x - r, at.y + r * 0.8f)
        close()
    }
    drawPath(path, color)
}
