#pragma once

#include "unigis/geometry.hpp"

namespace unigis {

/**
 * Camera state lives in a flat float array owned by Kotlin, so there is no native handle
 * to leak. The layout is shared with `NativeCore.kt` (CAMERA_* constants): keep in sync.
 *
 * A world point `w` (meters) maps to the screen as `offset + rotate(w * scale, rotation)`.
 */
namespace cam {
enum Field : int {
    Scale = 0,
    Rotation,
    OffsetX,
    OffsetY,
    ViewportW,
    ViewportH,
    FitScale,
    PlanL,
    PlanT,
    PlanR,
    PlanB,
    // Animation from the current pose to a target pose, driven by t in [0, 1].
    AnimStartX,
    AnimStartY,
    AnimStartScaleLog,
    AnimEndScaleLog,
    AnimStartRotation,
    AnimRotationDelta,
    AnimTargetX,
    AnimTargetY,
    Count,
};
}  // namespace cam

class Camera {
public:
    explicit Camera(float* state) : s_(state) {}

    static void init(float* state, const Rect& plan);

    float scale() const { return s_[cam::Scale]; }
    float rotation() const { return s_[cam::Rotation]; }
    Vec2 offset() const { return {s_[cam::OffsetX], s_[cam::OffsetY]}; }

    Vec2 toScreen(Vec2 world) const { return offset() + (world * scale()).rotated(rotation()); }
    Vec2 toWorld(Vec2 screen) const { return (screen - offset()).rotated(-rotation()) / scale(); }

    void onViewportChanged(float width, float height);
    void onGesture(Vec2 centroid, Vec2 pan, float zoom, float rotationDelta);
    void fitToScreen();

    // Each prepare* stores an animation target; step(t) then moves the camera along it.
    void prepareFit();
    void prepareResetRotation();
    void prepareZoom(float factor, Vec2 pivot);
    void prepareZoomAtCenter(float factor) { prepareZoom(factor, viewportCenter()); }
    void prepareFocus(const Rect& target);
    void step(float t);

private:
    float* s_;

    Rect plan() const { return {s_[cam::PlanL], s_[cam::PlanT], s_[cam::PlanR], s_[cam::PlanB]}; }
    Vec2 viewportCenter() const { return {s_[cam::ViewportW] / 2.f, s_[cam::ViewportH] / 2.f}; }
    float minScale() const { return s_[cam::FitScale] * 0.5f; }
    float maxScale() const { return s_[cam::FitScale] * 12.f; }

    float computeFitScale(const Rect& bounds) const;
    void setCamera(Vec2 centerWorld, float scale, float rotation);
    void prepare(Vec2 targetCenter, float targetScale, float targetRotation);
};

}  // namespace unigis
