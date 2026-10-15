// Dependency-free checks for the C++ core. Usage: core_test path/to/campus.json
// Exit code 0 = all passed.

#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>

#include "unigis/camera.hpp"
#include "unigis/campus.hpp"
#include "unigis/json.hpp"
#include "unigis/router.hpp"
#include "unigis/search.hpp"

using namespace unigis;

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
}

static void testCampus(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    CHECK(in.good());
    if (!in) return;
    std::stringstream ss;
    ss << in.rdbuf();
    const Campus campus = loadCampus(ss.str());

    const int demo = campus.buildingIndex("demo");
    CHECK(demo >= 0);
    CHECK(campus.buildingIndex("k1") >= 0);  // from OpenStreetMap
    if (demo < 0) return;
    const Building& b = campus.buildings[demo];
    CHECK(b.floors.size() == 2);
    const Floor& f1 = b.floors[0];

    // Hit testing prefers rooms over the corridor.
    const int hit = f1.roomAt({3, 4});
    CHECK(hit >= 0 && f1.rooms[hit].id == "101");
    const int corr = f1.roomAt({10, 9});
    CHECK(corr >= 0 && f1.rooms[corr].category == RoomCategory::Corridor);
    CHECK(f1.roomAt({-1, -1}) == -1);

    // Search: Cyrillic case folding, id priority, corridors excluded.
    auto hits = searchRooms(campus, "КАБИНЕТ 10", demo, 0);
    CHECK(hits.size() == 2);
    hits = searchRooms(campus, "210");
    CHECK(!hits.empty() && campus.buildings[hits[0].building].floors[hits[0].floor].rooms[hits[0].room].id == "210");
    for (const auto& h : searchRooms(campus, "коридор")) {
        CHECK(campus.buildings[h.building].floors[h.floor].rooms[h.room].category != RoomCategory::Corridor);
    }
    CHECK(foldForSearch(" Ёлка ") == foldForSearch("елка"));

    // Routing: same floor, then up the stairs to the second floor.
    const Router router(campus);
    const auto room = [&](int floor, const char* id) {
        const auto& rooms = b.floors[floor].rooms;
        for (size_t i = 0; i < rooms.size(); ++i) {
            if (rooms[i].id == id) return Place{demo, floor, static_cast<int>(i)};
        }
        return Place{};
    };
    const Route same = router.route(room(0, "101"), room(0, "105"));
    CHECK(same.found);
    CHECK(same.legs.size() == 1);
    CHECK(same.length > 26.f && same.length < 45.f);

    const Route up = router.route(room(0, "lobby"), room(1, "201"));
    CHECK(up.found);
    CHECK(up.legs.size() == 2);
    if (up.legs.size() == 2) {
        CHECK(up.legs[0].floor == 0 && up.legs[1].floor == 1);
    }
    std::printf("route lobby -> 201: %.1f m, %.0f s, %zu legs; graph %zu nodes\n", up.length, up.seconds,
                up.legs.size(), router.nodeCount());
}

int main(int argc, char** argv) {
    testJson();
    testGeometry();
    testCamera();
    if (argc > 1) testCampus(argv[1]);
    else std::printf("campus.json not given, skipping data tests\n");
    std::printf(failures ? "%d check(s) failed\n" : "all checks passed\n", failures);
    return failures ? 1 : 0;
}
