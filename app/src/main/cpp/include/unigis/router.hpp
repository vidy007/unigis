#pragma once

#include <vector>

#include "unigis/campus.hpp"

namespace unigis {

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

    int addNode(Vec2 local, Vec2 world, int building, int floor);
    void connect(int a, int b, float meters, float seconds);
    void connectWalk(int a, int b);
    void buildIndoor();
    void buildOutdoor();
    /** Links an outdoor node to the closest point of the footway network (or to all entrances). */
    void attachOutdoor(int node);
};

}  // namespace unigis
