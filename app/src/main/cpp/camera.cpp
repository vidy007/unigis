#include "unigismap.hpp"

namespace unigismap {

void Camera::init(float* state, const Rect& plan, float homeRotation) {
    std::fill(state, state + cam::Count, 0.f);
    state[cam::Scale] = 1.f;
    state[cam::FitScale] = 1.f;
    state[cam::PlanL] = plan.left;
    state[cam::PlanT] = plan.top;
    state[cam::PlanR] = plan.right;
    state[cam::PlanB] = plan.bottom;
    state[cam::HomeRotation] = normalizeDegrees(homeRotation);
    state[cam::Rotation] = state[cam::HomeRotation];
}

float Camera::computeFitScale(const Rect& bounds, float rotation) const {
    const float w = s_[cam::ViewportW];
    const float h = s_[cam::ViewportH];
    if (w <= 0.f || h <= 0.f || bounds.empty()) return 1.f;
    // Extent of the rotated rectangle on screen.
    const float rad = rotation * static_cast<float>(M_PI) / 180.f;
    const float c = std::fabs(std::cos(rad)), s = std::fabs(std::sin(rad));
    const float bw = bounds.width() * c + bounds.height() * s;
    const float bh = bounds.width() * s + bounds.height() * c;
    return std::min(w / bw, h / bh) * 0.9f;
}

void Camera::setCamera(Vec2 centerWorld, float scale, float rotation) {
    s_[cam::Scale] = scale;
    s_[cam::Rotation] = rotation;
    set(cam::OffsetX, cam::OffsetY, viewportCenter() - (centerWorld * scale).rotated(rotation));
}

void Camera::onViewportChanged(float width, float height) {
    const bool first = s_[cam::ViewportW] == 0.f && s_[cam::ViewportH] == 0.f;
    set(cam::ViewportW, cam::ViewportH, {width, height});
    s_[cam::FitScale] = computeFitScale(plan(), homeRotation());
    if (first) fitToScreen();
    else setCamera(plan().center(), scale(), rotation());
}

void Camera::onGesture(Vec2 centroid, Vec2 pan, float zoom, float rotationDelta) {
    const float newScale = std::clamp(scale() * zoom, minScale(), maxScale());
    const float dz = newScale / scale();
    set(cam::OffsetX, cam::OffsetY, centroid + pan - (centroid - offset()).rotated(rotationDelta) * dz);
    s_[cam::Scale] = newScale;
    s_[cam::Rotation] = normalizeDegrees(rotation() + rotationDelta);
}

void Camera::fitToScreen() { setCamera(plan().center(), s_[cam::FitScale], homeRotation()); }

void Camera::prepare(Vec2 targetCenter, float targetScale, float targetRotation) {
    set(cam::AnimStartX, cam::AnimStartY, toWorld(viewportCenter()));
    set(cam::AnimTargetX, cam::AnimTargetY, targetCenter);
    s_[cam::AnimStartScaleLog] = std::log(scale());
    s_[cam::AnimEndScaleLog] = std::log(targetScale);
    s_[cam::AnimStartRotation] = rotation();
    s_[cam::AnimRotationDelta] = normalizeDegrees(targetRotation - rotation());
}

void Camera::step(float t) {
    const Vec2 start = get(cam::AnimStartX, cam::AnimStartY);
    const Vec2 target = get(cam::AnimTargetX, cam::AnimTargetY);
    // Scale is interpolated in log space so zooming feels uniform.
    const float logScale = s_[cam::AnimStartScaleLog] + (s_[cam::AnimEndScaleLog] - s_[cam::AnimStartScaleLog]) * t;
    setCamera(start + (target - start) * t, std::exp(logScale),
              normalizeDegrees(s_[cam::AnimStartRotation] + s_[cam::AnimRotationDelta] * t));
}

void Camera::prepareFit() { prepare(plan().center(), s_[cam::FitScale], homeRotation()); }

void Camera::prepareResetRotation() { prepare(toWorld(viewportCenter()), scale(), homeRotation()); }

void Camera::prepareZoom(float factor, Vec2 pivot) {
    const float target = std::clamp(scale() * factor, minScale(), maxScale());
    // Keep the world point under the pivot fixed while zooming.
    const Vec2 pivotWorld = toWorld(pivot);
    const Vec2 centerWorld = toWorld(viewportCenter());
    prepare(pivotWorld + (centerWorld - pivotWorld) * (scale() / target), target, rotation());
}

void Camera::prepareFocus(const Rect& target) {
    const float fit = std::min(std::max(scale(), computeFitScale(target.inflated(3.f), rotation())), maxScale());
    prepare(target.center(), fit, rotation());
}

}  // namespace unigismap
