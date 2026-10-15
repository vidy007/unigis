#pragma once

#include <algorithm>
#include <cmath>
#include <vector>

namespace unigis {

/** 2D vector in meters. Screen-like axes: x to the right (east), y down (south). */
struct Vec2 {
    float x = 0.f;
    float y = 0.f;

    constexpr Vec2() = default;
    constexpr Vec2(float x_, float y_) : x(x_), y(y_) {}

    constexpr Vec2 operator+(Vec2 o) const { return {x + o.x, y + o.y}; }
    constexpr Vec2 operator-(Vec2 o) const { return {x - o.x, y - o.y}; }
    constexpr Vec2 operator*(float k) const { return {x * k, y * k}; }
    constexpr Vec2 operator/(float k) const { return {x / k, y / k}; }
    constexpr bool operator==(Vec2 o) const { return x == o.x && y == o.y; }

    float length() const { return std::hypot(x, y); }

    /** Rotates clockwise (screen coordinates, y down) by [degrees]. */
    Vec2 rotated(float degrees) const {
        const float rad = degrees * static_cast<float>(M_PI) / 180.f;
        const float c = std::cos(rad);
        const float s = std::sin(rad);
        return {x * c - y * s, x * s + y * c};
    }
};

inline float distance(Vec2 a, Vec2 b) { return (a - b).length(); }

struct Rect {
    float left = 0.f, top = 0.f, right = 0.f, bottom = 0.f;

    float width() const { return right - left; }
    float height() const { return bottom - top; }
    Vec2 center() const { return {(left + right) / 2.f, (top + bottom) / 2.f}; }
    bool empty() const { return width() <= 0.f || height() <= 0.f; }

    bool contains(Vec2 p) const { return p.x >= left && p.x < right && p.y >= top && p.y < bottom; }

    Rect inflated(float d) const { return {left - d, top - d, right + d, bottom + d}; }

    void include(Vec2 p) {
        if (empty() && left == 0.f && right == 0.f && top == 0.f && bottom == 0.f) {
            *this = {p.x, p.y, p.x, p.y};
            return;
        }
        left = std::min(left, p.x);
        top = std::min(top, p.y);
        right = std::max(right, p.x);
        bottom = std::max(bottom, p.y);
    }
};

/** Simple (non self-intersecting) polygon, vertices in order, implicitly closed. */
struct Polygon {
    std::vector<Vec2> points;

    static Polygon fromRect(const Rect& r) {
        return Polygon{{{r.left, r.top}, {r.right, r.top}, {r.right, r.bottom}, {r.left, r.bottom}}};
    }

    Rect bounds() const {
        if (points.empty()) return {};
        Rect r{points[0].x, points[0].y, points[0].x, points[0].y};
        for (const auto& p : points) r.include(p);
        return r;
    }

    float area() const {
        float a = 0.f;
        for (size_t i = 0, n = points.size(); i < n; ++i) {
            const Vec2& p = points[i];
            const Vec2& q = points[(i + 1) % n];
            a += p.x * q.y - q.x * p.y;
        }
        return std::fabs(a) / 2.f;
    }

    /** Even-odd ray casting. Half-open like Rect::contains for shared edges. */
    bool contains(Vec2 p) const {
        bool inside = false;
        for (size_t i = 0, n = points.size(), j = n - 1; i < n; j = i++) {
            const Vec2& a = points[i];
            const Vec2& b = points[j];
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Distance from [p] to the polygon outline. */
    float distanceToEdge(Vec2 p) const;
};

/** Distance from [p] to segment [a, b]; [t] receives the projection parameter in [0, 1]. */
inline float distanceToSegment(Vec2 p, Vec2 a, Vec2 b, float* t = nullptr) {
    const Vec2 ab = b - a;
    const float len2 = ab.x * ab.x + ab.y * ab.y;
    float k = len2 > 0.f ? ((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2 : 0.f;
    k = std::clamp(k, 0.f, 1.f);
    if (t) *t = k;
    return distance(p, a + ab * k);
}

inline float Polygon::distanceToEdge(Vec2 p) const {
    float best = INFINITY;
    for (size_t i = 0, n = points.size(); i < n; ++i) {
        best = std::min(best, distanceToSegment(p, points[i], points[(i + 1) % n]));
    }
    return best;
}

/** Rigid transform: local -> parent = origin + rotate(local, rotation). */
struct Frame {
    Vec2 origin;
    float rotation = 0.f;

    Vec2 toParent(Vec2 local) const { return origin + local.rotated(rotation); }
    Vec2 toLocal(Vec2 parent) const { return (parent - origin).rotated(-rotation); }
};

inline float normalizeDegrees(float d) {
    float r = std::fmod(d, 360.f);
    if (r > 180.f) r -= 360.f;
    if (r < -180.f) r += 360.f;
    return r;
}

}  // namespace unigis
