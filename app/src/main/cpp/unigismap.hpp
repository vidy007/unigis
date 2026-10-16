#pragma once

// The whole public API of the C++ core in one header. Sections, in dependency order:
//   1. Geometry   Vec2, Rect, Polygon, Frame                         (header-only)
//   2. JSON       json::Value, json::parse                           json.cpp
//   3. Campus     Campus > Building > Floor > Room/Door/Connector    campus.cpp
//   4. Search     searchRooms, foldForSearch                         search.cpp
//   5. Router     Place, Route, Router (A* over the walking graph)   router.cpp
//   6. Camera     cam::Field, Camera (map pan/zoom/rotate)           camera.cpp
// The JNI surface for Kotlin (NativeCore.kt) is in jni_bridge.cpp.

#include <algorithm>
#include <cmath>
#include <map>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace unigismap {

// ==== 1. Geometry ===========================================================

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

/** Distance from [p] to segment [a, b]; [t] receives the projection parameter in [0, 1]. */
inline float distanceToSegment(Vec2 p, Vec2 a, Vec2 b, float* t = nullptr) {
    const Vec2 ab = b - a;
    const float len2 = ab.x * ab.x + ab.y * ab.y;
    float k = len2 > 0.f ? ((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2 : 0.f;
    k = std::clamp(k, 0.f, 1.f);
    if (t) *t = k;
    return distance(p, a + ab * k);
}

/** Wraps an angle into [-180, 180]. */
inline float normalizeDegrees(float d) {
    float r = std::fmod(d, 360.f);
    if (r > 180.f) r -= 360.f;
    if (r < -180.f) r += 360.f;
    return r;
}

struct Rect {
    float left = 0.f, top = 0.f, right = 0.f, bottom = 0.f;

    float width() const { return right - left; }
    float height() const { return bottom - top; }
    Vec2 center() const { return {(left + right) / 2.f, (top + bottom) / 2.f}; }
    bool empty() const { return width() <= 0.f || height() <= 0.f; }

    bool contains(Vec2 p) const { return p.x >= left && p.x < right && p.y >= top && p.y < bottom; }

    Rect inflated(float d) const { return {left - d, top - d, right + d, bottom + d}; }

    /** Smallest rectangle containing both. */
    Rect united(const Rect& o) const {
        return {std::min(left, o.left), std::min(top, o.top), std::max(right, o.right), std::max(bottom, o.bottom)};
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
        for (const Vec2& p : points) r = r.united({p.x, p.y, p.x, p.y});
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
    float distanceToEdge(Vec2 p) const {
        float best = INFINITY;
        for (size_t i = 0, n = points.size(); i < n; ++i) {
            best = std::min(best, distanceToSegment(p, points[i], points[(i + 1) % n]));
        }
        return best;
    }
};

/** Rigid transform: local -> parent = origin + rotate(local, rotation). */
struct Frame {
    Vec2 origin;
    float rotation = 0.f;

    Vec2 toParent(Vec2 local) const { return origin + local.rotated(rotation); }
    Vec2 toLocal(Vec2 parent) const { return (parent - origin).rotated(-rotation); }
};

// ==== 2. JSON ===============================================================

namespace json {

struct ParseError : std::runtime_error {
    using std::runtime_error::runtime_error;
};

/** Minimal read-only JSON DOM: enough for the campus data files. */
class Value {
public:
    using Array = std::vector<Value>;
    using Object = std::map<std::string, Value, std::less<>>;
    enum class Type { Null, Bool, Number, String, Array, Object };

    Value() = default;
    explicit Value(bool b) : type_(Type::Bool), bool_(b) {}
    explicit Value(double n) : type_(Type::Number), number_(n) {}
    explicit Value(std::string s) : type_(Type::String), string_(std::move(s)) {}
    explicit Value(Array a) : type_(Type::Array), array_(std::move(a)) {}
    explicit Value(Object o) : type_(Type::Object), object_(std::move(o)) {}

    Type type() const { return type_; }
    bool isNull() const { return type_ == Type::Null; }
    bool isNumber() const { return type_ == Type::Number; }
    bool isString() const { return type_ == Type::String; }
    bool isArray() const { return type_ == Type::Array; }
    bool isObject() const { return type_ == Type::Object; }

    // Accessors never throw: a value of another type yields the fallback.
    double asNumber(double fallback = 0.0) const { return isNumber() ? number_ : fallback; }
    float asFloat(float fallback = 0.f) const { return static_cast<float>(asNumber(fallback)); }
    int asInt(int fallback = 0) const { return isNumber() ? static_cast<int>(number_) : fallback; }
    bool asBool(bool fallback = false) const { return type_ == Type::Bool ? bool_ : fallback; }
    std::string asString(const std::string& fallback = {}) const { return isString() ? string_ : fallback; }

    /** Array elements; empty for non-arrays. */
    const Array& items() const;
    size_t size() const { return isArray() ? array_.size() : isObject() ? object_.size() : 0; }
    const Value& operator[](size_t i) const;

    /** Missing keys (or non-objects) yield a shared null value, so lookups can be chained. */
    const Value& operator[](std::string_view key) const;
    bool has(std::string_view key) const { return isObject() && object_.find(key) != object_.end(); }

private:
    Type type_ = Type::Null;
    bool bool_ = false;
    double number_ = 0.0;
    std::string string_;
    Array array_;
    Object object_;
};

/** Throws ParseError with a byte offset on malformed input. */
Value parse(std::string_view text);

}  // namespace json

// ==== 3. Campus model =======================================================

/**
 * Keep the order in sync with the Kotlin `RoomCategory` enum: the ordinal crosses JNI.
 * New kinds of rooms (auditoriums, labs, ...) should be added on both sides together.
 */
enum class RoomCategory : int {
    Office = 0,
    Meeting = 1,
    Common = 2,
    Service = 3,
    Corridor = 4,
};

struct Room {
    std::string id;
    std::string name;
    RoomCategory category = RoomCategory::Office;
    Polygon shape;  // floor-local meters
    Rect bounds;
    float area = 0.f;
    Vec2 anchor;  // where routes start/end inside the room
};

/** An opening in a wall between [a] and [b]. Exterior doors lead outside to a building entrance. */
struct Door {
    Vec2 a, b;
    bool exterior = false;
    std::string entranceId;  // optional, exterior doors only; nearest entrance if empty

    Vec2 mid() const { return (a + b) / 2.f; }
};

enum class ConnectorKind : int { Stairs = 0, Elevator = 1 };

/** Stairs or elevator shaft. Connectors with the same [group] link adjacent floors of a building. */
struct Connector {
    std::string id;
    std::string group;
    ConnectorKind kind = ConnectorKind::Stairs;
    Vec2 pos;  // floor-local meters
};

struct Floor {
    int level = 1;
    std::string name;
    Rect bounds;      // floor-local meters
    Vec2 youAreHere;  // default "you are here" marker
    std::vector<Room> rooms;
    std::vector<Door> doors;
    std::vector<Connector> connectors;

    /** Index of the room under [p], preferring rooms over corridors; -1 if none. */
    int roomAt(Vec2 p) const;
};

/** Building entrance in campus meters. */
struct Entrance {
    std::string id;
    Vec2 pos;
    bool main = false;
};

struct Building {
    std::string id;
    std::string name;
    std::string shortName;
    int levels = 0;
    float floorHeight = 3.6f;
    bool placeholder = false;  // floor data is a stand-in, not a surveyed plan
    Frame frame;               // floor-local -> campus meters
    Polygon outline;           // campus meters
    std::vector<Entrance> entrances;
    std::vector<Floor> floors;  // sorted by level

    int floorIndex(int level) const;
};

/** Outdoor walkway polyline in campus meters. */
struct Footway {
    std::vector<Vec2> points;
};

/**
 * Campus in a local tangent plane: x = east, y = south, meters from (originLat, originLon).
 * Buildings carry their own frames so floor plans can be drawn in convenient local axes.
 */
struct Campus {
    std::string name;
    std::string attribution;
    double originLat = 0.0;
    double originLon = 0.0;
    std::vector<Building> buildings;
    std::vector<Footway> footways;

    int buildingIndex(std::string_view id) const;
};

/** Parses the campus JSON format (see assets/campus.json). Throws std::runtime_error on bad data. */
Campus loadCampus(std::string_view json);

// ==== 4. Search =============================================================

/** Lowercases ASCII and Cyrillic, folds ё -> е, trims spaces. Invalid UTF-8 bytes are dropped. */
std::u32string foldForSearch(std::string_view utf8);

struct SearchHit {
    int building = -1;
    int floor = -1;
    int room = -1;
    int score = 0;  // lower is better
};

/**
 * Finds rooms by number or name. Corridors are never returned. An empty query lists every room.
 * Pass [building]/[floor] >= 0 to restrict the scope. Results are ordered by match quality,
 * then by their order in the data.
 */
std::vector<SearchHit> searchRooms(const Campus& campus, std::string_view query, int building = -1, int floor = -1,
                                   size_t limit = 200);

// ==== 5. Router =============================================================

/** A room on a specific floor of a building (indices into Campus). */
struct Place {
    int building = -1;
    int floor = -1;
    int room = -1;
};

/** Part of a route on one map: a floor of a building, or outdoors (building == -1). */
struct RouteLeg {
    int building = -1;
    int floor = -1;
    std::vector<Vec2> points;  // floor-local meters, or campus meters when outdoors
};

struct Route {
    bool found = false;
    float length = 0.f;   // walking meters, stairs counted along the flight
    float seconds = 0.f;  // estimated walking time
    std::vector<RouteLeg> legs;
};

/**
 * Walking graph over the whole campus:
 *  - indoors: room anchors, door openings and stairs/elevators per floor;
 *  - vertically: connectors of the same group on adjacent floors;
 *  - outdoors: footways, building entrances and exterior doors.
 * Rooms are assumed convex (straight lines between their doors stay inside);
 * non-convex halls should be split into convex parts in the data.
 */
class Router {
public:
    explicit Router(const Campus& campus);

    Route route(const Place& from, const Place& to) const;

    size_t nodeCount() const { return nodes_.size(); }

private:
    struct Node {
        Vec2 local;  // floor-local, or campus for outdoor nodes
        Vec2 world;  // campus meters, for the A* heuristic
        int building = -1;
        int floor = -1;
    };
    struct Edge {
        int to;
        float meters;
        float seconds;
    };

    const Campus& campus_;
    std::vector<Node> nodes_;
    std::vector<std::vector<Edge>> adj_;
    std::vector<std::vector<std::vector<int>>> roomNodes_;  // [building][floor][room] -> node
    std::vector<std::pair<int, int>> footwaySegments_;
    std::vector<int> entranceNodes_;  // all entrances, building by building

    int addNode(Vec2 local, Vec2 world, int building = -1, int floor = -1);
    void connect(int a, int b, float meters, float seconds);
    void connectWalk(int a, int b);
    /** Builds one building's floors and stairs; returns its exterior doors as (node, entrance id). */
    std::vector<std::pair<int, std::string>> buildBuilding(int b);
    void buildOutdoor();
    /** Links an outdoor node to the closest point of the footway network (or to all entrances). */
    void attachOutdoor(int node);
    int roomNode(const Place& p) const;
};

// ==== 6. Camera =============================================================

/**
 * Camera state lives in a flat float array owned by Kotlin, so there is no native handle
 * to leak. The layout is shared with `NativeCore.kt` (CAMERA_* constants): keep in sync.
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

/** A view over a camera state array. A world point `w` (meters) maps to the screen as `offset + rotate(w * scale, rotation)`. */
class Camera {
public:
    explicit Camera(float* state) : s_(state) {}

    static void init(float* state, const Rect& plan);

    float scale() const { return s_[cam::Scale]; }
    float rotation() const { return s_[cam::Rotation]; }
    Vec2 offset() const { return get(cam::OffsetX, cam::OffsetY); }

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

    Vec2 get(int x, int y) const { return {s_[x], s_[y]}; }
    void set(int x, int y, Vec2 v) { s_[x] = v.x; s_[y] = v.y; }

    Rect plan() const { return {s_[cam::PlanL], s_[cam::PlanT], s_[cam::PlanR], s_[cam::PlanB]}; }
    Vec2 viewportCenter() const { return get(cam::ViewportW, cam::ViewportH) / 2.f; }
    float minScale() const { return s_[cam::FitScale] * 0.5f; }
    float maxScale() const { return s_[cam::FitScale] * 12.f; }

    float computeFitScale(const Rect& bounds) const;
    void setCamera(Vec2 centerWorld, float scale, float rotation);
    void prepare(Vec2 targetCenter, float targetScale, float targetRotation);
};

}  // namespace unigismap
