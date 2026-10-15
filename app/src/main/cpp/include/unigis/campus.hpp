#pragma once

#include <string>
#include <string_view>
#include <vector>

#include "unigis/geometry.hpp"

namespace unigis {

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

}  // namespace unigis
