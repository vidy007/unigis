package com.unigismap.indoormap.core

/**
 * JNI entry points into libunigismap (app/src/main/cpp). All map logic lives in C++:
 * campus data, hit testing, search, routing and camera math. Kotlin only decodes the
 * packed arrays below into UI models; layouts are documented in jni_bridge.cpp.
 */
object NativeCore {
    init {
        System.loadLibrary("unigismap")
    }

    // Campus ---------------------------------------------------------------------------------

    /** Parses campus JSON; throws IllegalArgumentException on malformed data. */
    external fun loadCampus(json: String): Long
    external fun releaseCampus(handle: Long)
    external fun campusInfo(handle: Long): Array<String>
    external fun buildingCount(handle: Long): Int
    external fun buildingStrings(handle: Long, building: Int): Array<String>
    external fun buildingLevels(handle: Long, building: Int): IntArray
    external fun buildingOutline(handle: Long, building: Int): FloatArray
    external fun floorStrings(handle: Long, building: Int, floor: Int): Array<String>?
    external fun floorGeometry(handle: Long, building: Int, floor: Int): FloatArray?
    external fun roomAt(handle: Long, building: Int, floor: Int, x: Float, y: Float): Int
    external fun searchRooms(handle: Long, query: String, building: Int, floor: Int): IntArray
    external fun route(
        handle: Long,
        fromBuilding: Int, fromFloor: Int, fromRoom: Int,
        toBuilding: Int, toFloor: Int, toRoom: Int,
    ): FloatArray

    // Camera: state is a Kotlin-owned FloatArray, see cam::Field in unigismap.hpp for the layout -------

    const val CAMERA_SCALE = 0
    const val CAMERA_ROTATION = 1
    const val CAMERA_OFFSET_X = 2
    const val CAMERA_OFFSET_Y = 3

    external fun cameraStateSize(): Int
    external fun cameraInit(state: FloatArray, left: Float, top: Float, right: Float, bottom: Float)
    external fun cameraViewport(state: FloatArray, width: Float, height: Float)
    external fun cameraGesture(
        state: FloatArray, centroidX: Float, centroidY: Float, panX: Float, panY: Float, zoom: Float, rotation: Float,
    )
    external fun cameraFit(state: FloatArray)
    external fun cameraPrepareFit(state: FloatArray)
    external fun cameraPrepareResetRotation(state: FloatArray)
    /** NaN pivot = viewport center. */
    external fun cameraPrepareZoom(state: FloatArray, factor: Float, pivotX: Float, pivotY: Float)
    external fun cameraPrepareFocus(state: FloatArray, left: Float, top: Float, right: Float, bottom: Float)
    external fun cameraStep(state: FloatArray, t: Float)
    external fun cameraToScreen(state: FloatArray, x: Float, y: Float, out: FloatArray)
    external fun cameraToWorld(state: FloatArray, x: Float, y: Float, out: FloatArray)
}
