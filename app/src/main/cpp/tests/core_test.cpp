// Dependency-free checks for the C++ core. Usage: core_test path/to/campus.json
// Exit code 0 = all passed.

#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>

#include "../unigismap.hpp"

using namespace unigismap;

static int failures = 0;

#define CHECK(cond)                                                       \
    do {                                                                  \
        if (!(cond)) {                                                    \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                   \
        }                                                                 \
    } while (0)

static bool near(float a, float b, float eps = 1e-3f) { return std::fabs(a - b) <= eps; }

static void testJson() {
    const auto v = json::parse(R"({"a":[1,2.5,-3e2],"s":"Корпус","t":true,"n":null})");
    CHECK(v["a"].size() == 3);
    CHECK(near(v["a"][2].asFloat(), -300.f));
    CHECK(v["s"].asString() == "Корпус");
    CHECK(v["t"].asBool());
    CHECK(v["n"].isNull());
    CHECK(v["missing"]["deeper"].isNull());
    bool threw = false;
    try { json::parse("{\"a\":}"); } catch (const json::ParseError&) { threw = true; }
    CHECK(threw);
}

static void testGeometry() {
    const Polygon square = Polygon::fromRect({0, 0, 4, 3});
    CHECK(near(square.area(), 12.f));
    CHECK(square.contains({1, 1}));
    CHECK(!square.contains({5, 1}));
    CHECK(near(square.distanceToEdge({2, 1}), 1.f));
    const Vec2 r = Vec2{1, 0}.rotated(90.f);
    CHECK(near(r.x, 0.f) && near(r.y, 1.f));
    const Frame f{{10, 20}, 90.f};
    const Vec2 back = f.toLocal(f.toParent({3, 4}));
    CHECK(near(back.x, 3.f) && near(back.y, 4.f));
}

static void testCamera() {
    float s[cam::Count];
    Camera::init(s, {0, 0, 32, 20});
    Camera c(s);
    c.onViewportChanged(1000, 500);
    // Fit: 0.9 * min(1000/32, 500/20) = 22.5, plan centered.
    CHECK(near(c.scale(), 22.5f));
    const Vec2 center = c.toScreen({16, 10});
    CHECK(near(center.x, 500.f, 0.01f) && near(center.y, 250.f, 0.01f));
    const Vec2 w = c.toWorld(c.toScreen({3, 7}));
    CHECK(near(w.x, 3.f) && near(w.y, 7.f));
    c.onGesture({500, 250}, {0, 0}, 2.f, 30.f);
    CHECK(near(c.scale(), 45.f) && near(c.rotation(), 30.f));
    const Vec2 still = c.toScreen({16, 10});  // gesture centroid stays fixed
    CHECK(near(still.x, 500.f, 0.01f) && near(still.y, 250.f, 0.01f));
    c.prepareFit();
    c.step(1.f);
    CHECK(near(c.scale(), 22.5f, 1e-2f) && near(c.rotation(), 0.f));
    c.onGesture({0, 0}, {0, 0}, 1000.f, 0.f);  // clamped to 12x fit
    CHECK(near(c.scale(), 22.5f * 12.f, 0.05f));

    // Home rotation: a 32x20 plan shown rotated by 90 degrees fits as 20x32.
    Camera::init(s, {0, 0, 32, 20}, 90.f);
    c.onViewportChanged(1000, 500);
    CHECK(near(c.rotation(), 90.f) && near(c.scale(), 0.9f * 500.f / 32.f));
    const Vec2 mid = c.toScreen({16, 10});
    CHECK(near(mid.x, 500.f, 0.01f) && near(mid.y, 250.f, 0.01f));
    c.onGesture({500, 250}, {0, 0}, 1.f, 45.f);
    c.prepareResetRotation();
    c.step(1.f);
    CHECK(near(c.rotation(), 90.f));

    // Cover: home view fills the viewport (31.25 = 1000/32), yet zooming out reaches the whole plan (22.5).
    Camera::init(s, {0, 0, 32, 20}, 0.f, true);
    c.onViewportChanged(1000, 500);
    CHECK(near(c.scale(), 31.25f));
    c.onGesture({500, 250}, {0, 0}, 0.01f, 0.f);
    CHECK(near(c.scale(), 22.5f));
    c.prepareFit();
    c.step(1.f);
    CHECK(near(c.scale(), 31.25f, 1e-2f));
}

static void testCampusMap() {
    const Campus c = loadCampus(R"({
        "buildings": [
            {"id": "k1", "kind": "academic", "number": "1", "outline": [[0,0],[20,0],[20,10],[0,10]]},
            {"id": "f4", "kind": "facility", "number": "4", "outline": [[2,2],[6,2],[6,6],[2,6]]},
            {"id": "d1", "kind": "dorm", "number": "1", "outline": [[30,0],[40,0],[40,8],[30,8],[30,0]]},
            {"id": "plan", "placeholder": true}
        ],
        "stops": [{"id": "m", "kind": "metro", "name": "Студенческая", "pos": [-5, 12]}]
    })");
    CHECK(c.buildings[0].kind == BuildingKind::Academic && c.buildings[0].number == "1");
    CHECK(c.buildings[2].kind == BuildingKind::Dorm);
    CHECK(c.buildings[3].kind == BuildingKind::Academic && c.buildings[3].number.empty());
    CHECK(c.buildings[2].outline.contains(c.buildings[2].labelPos));
    CHECK(c.buildingAt({3, 3}) == 1);  // nested: the smaller outline wins
    CHECK(c.buildingAt({10, 5}) == 0);
    CHECK(c.buildingAt({35, 4}) == 2);
    CHECK(c.buildingAt({25, 4}) == -1);
    CHECK(c.stops.size() == 1 && c.stops[0].kind == StopKind::Metro && c.stops[0].name == "Студенческая");
    const Rect b = c.bounds();
    CHECK(near(b.left, -5) && near(b.top, 0) && near(b.right, 40) && near(b.bottom, 12));
    bool threw = false;
    try { loadCampus(R"({"buildings": [{"id": "x", "kind": "castle"}]})"); } catch (const std::runtime_error&) { threw = true; }
    CHECK(threw);
}

static void testCampus(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    CHECK(in.good());
    if (!in) return;
    std::stringstream ss;
    ss << in.rdbuf();
    const Campus campus = loadCampus(ss.str());

    const int k4 = campus.buildingIndex("k4");
    CHECK(k4 >= 0);
    CHECK(campus.buildingIndex("k1") >= 0);  // from OpenStreetMap
    if (k4 < 0) return;
    const Building& b = campus.buildings[k4];
    CHECK(b.floors.size() == 4);
    CHECK(b.number == "4" && b.kind == BuildingKind::Academic);
    const auto find = [&](int floor, const char* id) {
        const auto& rooms = b.floors[floor].rooms;
        for (size_t i = 0; i < rooms.size(); ++i) {
            if (rooms[i].id == id) return Place{k4, floor, static_cast<int>(i)};
        }
        return Place{};
    };
    const auto& f1 = b.floors[0];

    // Floor plans sit on the OSM footprint: the anchor of room 102 is inside the outline.
    const Place r102 = find(0, "102");
    CHECK(r102.room >= 0);
    if (r102.room < 0) return;
    CHECK(b.outline.contains(b.frame.toParent(f1.rooms[r102.room].anchor)));

    // Hit testing prefers rooms over the corridor.
    const int hit = f1.roomAt(f1.rooms[r102.room].anchor);
    CHECK(hit == r102.room);
    const Rect corridor = f1.rooms[0].bounds;
    CHECK(f1.rooms[0].category == RoomCategory::Corridor);
    const int corr = f1.roomAt(corridor.center());
    CHECK(corr >= 0 && f1.rooms[corr].category == RoomCategory::Corridor);
    CHECK(f1.roomAt({-1, -1}) == -1);

    // Search: id priority, case folding, corridors excluded.
    auto hits = searchRooms(campus, "210");
    CHECK(!hits.empty() && campus.buildings[hits[0].building].floors[hits[0].floor].rooms[hits[0].room].id == "210");
    hits = searchRooms(campus, "БУФЕТ", k4);
    CHECK(hits.size() == 2);  // floors 2 and 3
    for (const auto& h : searchRooms(campus, "коридор")) {
        CHECK(campus.buildings[h.building].floors[h.floor].rooms[h.room].category != RoomCategory::Corridor);
    }
    CHECK(foldForSearch(" Ёлка ") == foldForSearch("елка"));

    // Routing: along the first floor, then up to the fourth by stairs or elevator.
    const Router router(campus);
    const Route same = router.route(r102, find(0, "130"));
    CHECK(same.found);
    CHECK(same.legs.size() == 1);
    CHECK(same.length > 150.f && same.length < 200.f);  // the building is 183 m long

    const Route up = router.route(find(0, "102"), find(3, "402"));
    CHECK(up.found);
    CHECK(up.legs.size() >= 2);
    if (up.legs.size() >= 2) CHECK(up.legs.front().floor == 0 && up.legs.back().floor == 3);
    std::printf("route 102 -> 402: %.1f m, %.0f s, %zu legs; graph %zu nodes\n", up.length, up.seconds,
                up.legs.size(), router.nodeCount());

    // Out of the main entrance to the street network.
    const int hall = find(0, "1-вестибюль").room;
    CHECK(hall >= 0);
}

int main(int argc, char** argv) {
    testJson();
    testGeometry();
    testCamera();
    testCampusMap();
    if (argc > 1) testCampus(argv[1]);
    else std::printf("campus.json not given, skipping data tests\n");
    std::printf(failures ? "%d check(s) failed\n" : "all checks passed\n", failures);
    return failures ? 1 : 0;
}
