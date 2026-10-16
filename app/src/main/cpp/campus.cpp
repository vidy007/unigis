#include "unigismap.hpp"

namespace unigismap {

namespace {

using json::Value;

[[noreturn]] void bad(const std::string& what) { throw std::runtime_error("campus: " + what); }

Vec2 readPoint(const Value& v, const std::string& where) {
    if (!v.isArray() || v.size() != 2 || !v[0].isNumber() || !v[1].isNumber()) bad(where + ": expected [x, y]");
    return {v[0].asFloat(), v[1].asFloat()};
}

Rect readRect(const Value& v, const std::string& where) {
    if (!v.isArray() || v.size() != 4) bad(where + ": expected [left, top, right, bottom]");
    Rect r{v[0].asFloat(), v[1].asFloat(), v[2].asFloat(), v[3].asFloat()};
    if (r.empty()) bad(where + ": empty rectangle");
    return r;
}

Polygon readPolygon(const Value& v, const std::string& where) {
    if (!v.isArray() || v.size() < 3) bad(where + ": polygon needs at least 3 points");
    Polygon p;
    for (const auto& pt : v.items()) p.points.push_back(readPoint(pt, where));
    // Accept closed rings (first == last) as produced by GeoJSON/OSM exports.
    if (p.points.size() > 3 && p.points.front() == p.points.back()) p.points.pop_back();
    return p;
}

RoomCategory readCategory(const std::string& s) {
    if (s == "office") return RoomCategory::Office;
    if (s == "meeting") return RoomCategory::Meeting;
    if (s == "common") return RoomCategory::Common;
    if (s == "service") return RoomCategory::Service;
    if (s == "corridor") return RoomCategory::Corridor;
    bad("unknown room category '" + s + "'");
}

/** Visual centroid is overkill for now: bounds center if inside the shape, else the first vertex nudged inward. */
Vec2 pickAnchor(const Polygon& shape, const Rect& bounds) {
    const Vec2 c = bounds.center();
    if (shape.contains(c)) return c;
    Vec2 sum;
    for (const auto& p : shape.points) sum = sum + p;
    const Vec2 mean = sum / static_cast<float>(shape.points.size());
    if (shape.contains(mean)) return mean;
    return shape.points.front() + (mean - shape.points.front()) * 0.1f;
}

Room readRoom(const Value& v, const std::string& where) {
    Room r;
    r.id = v["id"].asString();
    if (r.id.empty()) bad(where + ": room without id");
    r.name = v["name"].asString(r.id);
    r.category = readCategory(v["category"].asString("office"));
    const std::string here = where + " room " + r.id;
    if (v.has("polygon")) r.shape = readPolygon(v["polygon"], here);
    else r.shape = Polygon::fromRect(readRect(v["rect"], here));
    r.bounds = r.shape.bounds();
    r.area = r.shape.area();
    r.anchor = v.has("anchor") ? readPoint(v["anchor"], here) : pickAnchor(r.shape, r.bounds);
    return r;
}

Floor readFloor(const Value& v, const std::string& where) {
    Floor f;
    f.level = v["level"].asInt(1);
    f.name = v["name"].asString("Этаж " + std::to_string(f.level));
    const std::string here = where + " floor " + std::to_string(f.level);
    for (const auto& r : v["rooms"].items()) f.rooms.push_back(readRoom(r, here));
    if (v.has("bounds")) {
        f.bounds = readRect(v["bounds"], here);
    } else if (!f.rooms.empty()) {
        f.bounds = f.rooms[0].bounds;
        for (const auto& r : f.rooms) f.bounds = f.bounds.united(r.bounds);
    }
    for (const auto& d : v["doors"].items()) {
        Door door;
        door.a = readPoint(d["a"], here + " door");
        door.b = readPoint(d["b"], here + " door");
        door.exterior = d["exterior"].asBool(false);
        door.entranceId = d["entrance"].asString("");
        f.doors.push_back(std::move(door));
    }
    for (const auto& c : v["connectors"].items()) {
        Connector con;
        con.id = c["id"].asString();
        con.group = c["group"].asString(con.id);
        con.kind = c["kind"].asString("stairs") == "elevator" ? ConnectorKind::Elevator : ConnectorKind::Stairs;
        con.pos = readPoint(c["pos"], here + " connector " + con.id);
        f.connectors.push_back(std::move(con));
    }
    f.youAreHere = v.has("youAreHere") ? readPoint(v["youAreHere"], here) : f.bounds.center();
    return f;
}

Building readBuilding(const Value& v) {
    Building b;
    b.id = v["id"].asString();
    if (b.id.empty()) bad("building without id");
    b.name = v["name"].asString(b.id);
    b.shortName = v["short"].asString(b.name);
    b.levels = v["levels"].asInt(0);
    b.floorHeight = v["floorHeight"].asFloat(3.6f);
    b.placeholder = v["placeholder"].asBool(false);
    const std::string here = "building " + b.id;
    if (v.has("frame")) {
        b.frame.origin = readPoint(v["frame"]["origin"], here + " frame");
        b.frame.rotation = v["frame"]["rotation"].asFloat(0.f);
    }
    if (v.has("outline")) b.outline = readPolygon(v["outline"], here + " outline");
    for (const auto& e : v["entrances"].items()) {
        Entrance en;
        en.id = e["id"].asString();
        en.pos = readPoint(e["pos"], here + " entrance");
        en.main = e["main"].asBool(false);
        b.entrances.push_back(std::move(en));
    }
    for (const auto& f : v["floors"].items()) b.floors.push_back(readFloor(f, here));
    std::stable_sort(b.floors.begin(), b.floors.end(), [](const Floor& x, const Floor& y) { return x.level < y.level; });
    if (b.levels == 0) b.levels = static_cast<int>(b.floors.size());
    return b;
}

}  // namespace

int Floor::roomAt(Vec2 p) const {
    for (int i = static_cast<int>(rooms.size()) - 1; i >= 0; --i) {
        if (rooms[i].category != RoomCategory::Corridor && rooms[i].shape.contains(p)) return i;
    }
    for (int i = static_cast<int>(rooms.size()) - 1; i >= 0; --i) {
        if (rooms[i].shape.contains(p)) return i;
    }
    return -1;
}

int Building::floorIndex(int level) const {
    for (size_t i = 0; i < floors.size(); ++i) {
        if (floors[i].level == level) return static_cast<int>(i);
    }
    return -1;
}

int Campus::buildingIndex(std::string_view id) const {
    for (size_t i = 0; i < buildings.size(); ++i) {
        if (buildings[i].id == id) return static_cast<int>(i);
    }
    return -1;
}

Campus loadCampus(std::string_view text) {
    const Value root = json::parse(text);
    if (!root.isObject()) bad("root must be an object");
    Campus c;
    c.name = root["name"].asString("");
    c.attribution = root["attribution"].asString("");
    c.originLat = root["origin"]["lat"].asNumber();
    c.originLon = root["origin"]["lon"].asNumber();
    for (const auto& b : root["buildings"].items()) c.buildings.push_back(readBuilding(b));
    for (const auto& w : root["footways"].items()) {
        Footway fw;
        for (const auto& p : w.items()) fw.points.push_back(readPoint(p, "footway"));
        if (fw.points.size() >= 2) c.footways.push_back(std::move(fw));
    }
    return c;
}

}  // namespace unigismap
