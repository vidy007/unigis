package com.unigismap.indoormap.map

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

private const val WALL_WIDTH = 0.12f
private const val OUTER_WALL_WIDTH = 0.3f

@Composable
fun FloorMap(
    plan: FloorPlan,
    state: MapState,
    selectedRoom: Room?,
    onRoomClick: (Room?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val selectedLabelStyle = labelStyle.copy(fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)

    fun fillFor(room: Room): Color = when {
        room == selectedRoom -> colors.primaryContainer
        else -> when (room.category) {
            RoomCategory.OFFICE -> colors.secondaryContainer
            RoomCategory.MEETING -> colors.tertiaryContainer
            RoomCategory.COMMON -> colors.surfaceContainerHigh
            RoomCategory.SERVICE -> colors.surfaceContainerHighest
            RoomCategory.CORRIDOR -> colors.surfaceContainerLow
        }
    }

    Canvas(modifier.mapInput(state) { onRoomClick(plan.roomAt(it)) }) {
        drawRect(colors.surface)

        val corridorFill = plan.rooms.firstOrNull { it.category == RoomCategory.CORRIDOR }
            ?.let(::fillFor) ?: colors.surfaceContainerLow

        withTransform({
            translate(state.offset.x, state.offset.y)
            rotate(state.rotation, pivot = Offset.Zero)
            scale(state.scale, state.scale, pivot = Offset.Zero)
        }) {
            plan.rooms.forEach { room ->
                drawRect(fillFor(room), room.bounds.topLeft, room.bounds.size)
            }
            plan.rooms.forEach { room ->
                drawRect(colors.outline, room.bounds.topLeft, room.bounds.size, style = Stroke(WALL_WIDTH))
            }
            drawRect(
                colors.onSurface,
                plan.bounds.topLeft,
                plan.bounds.size,
                style = Stroke(OUTER_WALL_WIDTH),
            )
            // Door openings: erase the wall segment.
            plan.doors.forEach { door ->
                drawLine(
                    color = if (door.exterior) colors.surface else corridorFill,
                    start = door.start,
                    end = door.end,
                    strokeWidth = OUTER_WALL_WIDTH * 1.4f,
                )
            }
            selectedRoom?.let { room ->
                drawRect(colors.primary, room.bounds.topLeft, room.bounds.size, style = Stroke(WALL_WIDTH * 2.5f))
            }
        }

        // Labels are drawn in screen space so they always stay upright and readable.
        val minLabelSize = 44.dp.toPx()
        plan.rooms.forEach { room ->
            val onScreen = minOf(room.bounds.width, room.bounds.height) * state.scale
            if (onScreen < minLabelSize && room != selectedRoom) return@forEach
            val layout = textMeasurer.measure(
                room.name,
                if (room == selectedRoom) selectedLabelStyle else labelStyle.copy(color = colors.onSurface),
            )
            val center = state.toScreen(room.bounds.center)
            drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f))
        }

        // "You are here" marker at the entrance.
        val here = state.toScreen(plan.entrance)
        drawCircle(colors.primary.copy(alpha = 0.2f), radius = 16.dp.toPx(), center = here)
        drawCircle(colors.surface, radius = 8.dp.toPx(), center = here)
        drawCircle(colors.primary, radius = 6.dp.toPx(), center = here)
    }
}
