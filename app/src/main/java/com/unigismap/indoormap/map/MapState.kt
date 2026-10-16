package com.unigismap.indoormap.map

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
import androidx.compose.ui.geometry.Size
import com.unigismap.indoormap.core.NativeCore

/**
 * Camera over the floor plan. The math lives in C++ (unigismap::Camera); this class owns the
 * packed state array and mirrors scale/rotation/offset into Compose state for recomposition.
 * A world point `w` (meters) maps to the screen as `offset + rotate(w * scale, rotation)`.
 */
@Stable
class MapState(plan: FloorPlan) {
    private val native = FloatArray(NativeCore.cameraStateSize())
    private val point = FloatArray(2)

    var scale by mutableFloatStateOf(1f)
        private set
    var rotation by mutableFloatStateOf(0f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    init {
        with(plan.bounds) { NativeCore.cameraInit(native, left, top, right, bottom) }
    }

    private fun sync() {
        scale = native[NativeCore.CAMERA_SCALE]
        rotation = native[NativeCore.CAMERA_ROTATION]
        offset = Offset(native[NativeCore.CAMERA_OFFSET_X], native[NativeCore.CAMERA_OFFSET_Y])
    }

    fun toScreen(world: Offset): Offset {
        NativeCore.cameraToScreen(native, world.x, world.y, point)
        return Offset(point[0], point[1])
    }

    fun toWorld(screen: Offset): Offset {
        NativeCore.cameraToWorld(native, screen.x, screen.y, point)
        return Offset(point[0], point[1])
    }

    fun onViewportChanged(size: Size) {
        NativeCore.cameraViewport(native, size.width, size.height)
        sync()
    }

    fun onGesture(centroid: Offset, pan: Offset, zoom: Float, rotationDelta: Float) {
        NativeCore.cameraGesture(native, centroid.x, centroid.y, pan.x, pan.y, zoom, rotationDelta)
        sync()
    }

    fun fitToScreen() {
        NativeCore.cameraFit(native)
        sync()
    }

    suspend fun animateFit() = animateCamera { NativeCore.cameraPrepareFit(native) }

    suspend fun animateResetRotation() = animateCamera { NativeCore.cameraPrepareResetRotation(native) }

    suspend fun animateZoom(factor: Float, pivot: Offset? = null) = animateCamera {
        NativeCore.cameraPrepareZoom(native, factor, pivot?.x ?: Float.NaN, pivot?.y ?: Float.NaN)
    }

    suspend fun animateFocus(room: Room) = animateCamera {
        with(room.bounds) { NativeCore.cameraPrepareFocus(native, left, top, right, bottom) }
    }

    /** Native code computes the start/target poses; Compose only drives the eased progress. */
    private suspend fun animateCamera(prepare: () -> Unit) {
        prepare()
        animate(0f, 1f, animationSpec = tween(450, easing = FastOutSlowInEasing)) { t, _ ->
            NativeCore.cameraStep(native, t)
            sync()
        }
    }
}

@Composable
fun rememberMapState(plan: FloorPlan): MapState = remember(plan) { MapState(plan) }
