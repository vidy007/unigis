package com.unigis.indoormap.map

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/** Rotates a vector clockwise (screen coordinates, y down) by [degrees]. */
fun Offset.rotateBy(degrees: Float): Offset {
    val rad = Math.toRadians(degrees.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    return Offset(x * c - y * s, x * s + y * c)
}

private fun normalizeDegrees(d: Float): Float {
    var r = d % 360f
    if (r > 180f) r -= 360f
    if (r < -180f) r += 360f
    return r
}

/**
 * Camera over the floor plan. A world point `w` (meters) maps to the screen as
 * `offset + rotate(w * scale, rotation)`.
 */
@Stable
class MapState(private val plan: FloorPlan) {
    var scale by mutableFloatStateOf(1f)
        private set
    var rotation by mutableFloatStateOf(0f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    private var viewport = Size.Zero
    private var fitScale = 1f
    private val minScale get() = fitScale * 0.5f
    private val maxScale get() = fitScale * 12f

    private val viewportCenter get() = Offset(viewport.width / 2f, viewport.height / 2f)

    fun toScreen(world: Offset): Offset = offset + (world * scale).rotateBy(rotation)

    fun toWorld(screen: Offset): Offset = (screen - offset).rotateBy(-rotation) / scale

    fun onViewportChanged(size: Size) {
        val first = viewport == Size.Zero
        viewport = size
        fitScale = computeFitScale(plan.bounds)
        if (first) fitToScreen() else setCamera(plan.bounds.center, scale, rotation)
    }

    fun onGesture(centroid: Offset, pan: Offset, zoom: Float, rotationDelta: Float) {
        val newScale = (scale * zoom).coerceIn(minScale, maxScale)
        val dz = newScale / scale
        offset = centroid + pan - (centroid - offset).rotateBy(rotationDelta) * dz
        scale = newScale
        rotation = normalizeDegrees(rotation + rotationDelta)
    }

    fun fitToScreen() = setCamera(plan.bounds.center, fitScale, 0f)

    suspend fun animateFit() = animateCamera(plan.bounds.center, fitScale, 0f)

    suspend fun animateResetRotation() = animateCamera(toWorld(viewportCenter), scale, 0f)

    suspend fun animateZoom(factor: Float, pivot: Offset = viewportCenter) {
        val target = (scale * factor).coerceIn(minScale, maxScale)
        // Keep the world point under the pivot fixed while zooming.
        val pivotWorld = toWorld(pivot)
        val centerWorld = toWorld(viewportCenter)
        val newCenter = pivotWorld + (centerWorld - pivotWorld) * (scale / target)
        animateCamera(newCenter, target, rotation)
    }

    suspend fun animateFocus(room: Room) {
        val target = maxOf(scale, computeFitScale(room.bounds.inflate(3f))).coerceAtMost(maxScale)
        animateCamera(room.bounds.center, target, rotation)
    }

    private fun computeFitScale(bounds: Rect): Float {
        if (viewport.width <= 0f || viewport.height <= 0f) return 1f
        return min(viewport.width / bounds.width, viewport.height / bounds.height) * 0.9f
    }

    /** Places [centerWorld] in the middle of the viewport. */
    private fun setCamera(centerWorld: Offset, newScale: Float, newRotation: Float) {
        scale = newScale
        rotation = newRotation
        offset = viewportCenter - (centerWorld * newScale).rotateBy(newRotation)
    }

    private suspend fun animateCamera(targetCenter: Offset, targetScale: Float, targetRotation: Float) {
        val startCenter = toWorld(viewportCenter)
        val startScaleLog = ln(scale)
        val endScaleLog = ln(targetScale)
        val startRotation = rotation
        val rotationDelta = normalizeDegrees(targetRotation - startRotation)
        animate(0f, 1f, animationSpec = tween(450, easing = FastOutSlowInEasing)) { t, _ ->
            setCamera(
                centerWorld = startCenter + (targetCenter - startCenter) * t,
                newScale = exp(startScaleLog + (endScaleLog - startScaleLog) * t),
                newRotation = normalizeDegrees(startRotation + rotationDelta * t),
            )
        }
    }
}

@Composable
fun rememberMapState(plan: FloorPlan): MapState = remember(plan) { MapState(plan) }
