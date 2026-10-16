#include "unigis.hpp"

#include <queue>
#include <unordered_map>

namespace unigis {

namespace {

constexpr float kWalkSpeed = 1.3f;        // m/s
constexpr float kDoorSnap = 0.3f;         // a door belongs to a room if this close to its outline
constexpr float kStairsPerFloorM = 8.f;   // walked length of one flight with landing
constexpr float kStairsPerFloorS = 15.f;
constexpr float kElevatorWaitS = 30.f;
constexpr float kElevatorPerFloorS = 3.f;

long long pointKey(Vec2 p) {
    // Shared OSM vertices land on identical coordinates; centimeter grid absorbs float noise.
    const long long x = std::llround(p.x * 100.f);
    const long long y = std::llround(p.y * 100.f);
    return (x << 32) ^ (y & 0xffffffffLL);
}

}  // namespace

Router::Router(const Campus& campus) : campus_(campus) {
    const auto& buildings = campus_.buildings;
    roomNodes_.resize(buildings.size());
    std::vector<std::vector<std::pair<int, std::string>>> exteriorDoors;
    for (int b = 0; b < static_cast<int>(buildings.size()); ++b) exteriorDoors.push_back(buildBuilding(b));

    buildOutdoor();

    // Exterior doors -> named entrance, or the nearest one of the same building.
    size_t base = 0;  // first entrance of building b in entranceNodes_
    for (int b = 0; b < static_cast<int>(buildings.size()); ++b) {
        const auto& entrances = buildings[b].entrances;
        for (const auto& [doorNode, entranceId] : exteriorDoors[b]) {
            int target = -1;
            float best = INFINITY;
            for (size_t e = 0; e < entrances.size(); ++e) {
                const int en = entranceNodes_[base + e];
                if (!entranceId.empty()) {
                    if (entrances[e].id == entranceId) { target = en; break; }
                    continue;
                }
                const float d = distance(nodes_[doorNode].world, nodes_[en].world);
                if (d < best) { best = d; target = en; }
            }
            if (target < 0) {
                // Entrance not mapped (common in OSM): the door itself becomes one.
                const Vec2 p = nodes_[doorNode].world;
                target = addNode(p, p);
                attachOutdoor(target);
            }
            connectWalk(doorNode, target);
        }
        base += entrances.size();
    }
}

int Router::addNode(Vec2 local, Vec2 world, int building, int floor) {
    nodes_.push_back({local, world, building, floor});
    adj_.emplace_back();
    return static_cast<int>(nodes_.size()) - 1;
}

void Router::connect(int a, int b, float meters, float seconds) {
    if (a == b) return;
    adj_[a].push_back({b, meters, seconds});
    adj_[b].push_back({a, meters, seconds});
}

void Router::connectWalk(int a, int b) {
    const float m = distance(nodes_[a].world, nodes_[b].world);
    connect(a, b, m, m / kWalkSpeed);
}

std::vector<std::pair<int, std::string>> Router::buildBuilding(int b) {
    const Building& bld = campus_.buildings[b];
    std::vector<std::pair<int, std::string>> exteriorDoors;
    struct Shaft {
        bool elevator = false;
        std::vector<std::pair<int, int>> stops;  // (level, node)
    };
    std::unordered_map<std::string, Shaft> shafts;  // by connector group

    roomNodes_[b].resize(bld.floors.size());
    for (int f = 0; f < static_cast<int>(bld.floors.size()); ++f) {
        const Floor& floor = bld.floors[f];
        const auto add = [&](Vec2 local) { return addNode(local, bld.frame.toParent(local), b, f); };

        // A room's anchor is linked to its portals (doors and connectors) and they to each other.
        auto& anchors = roomNodes_[b][f];
        for (const Room& room : floor.rooms) anchors.push_back(add(room.anchor));
        std::vector<std::vector<int>> portals(floor.rooms.size());

        for (const Door& door : floor.doors) {
            const int node = add(door.mid());
            for (size_t r = 0; r < floor.rooms.size(); ++r) {
                if (floor.rooms[r].shape.distanceToEdge(door.mid()) <= kDoorSnap) portals[r].push_back(node);
            }
            // Placeholder buildings are not georeferenced, so they stay off the outdoor network.
            if (door.exterior && !bld.placeholder) exteriorDoors.emplace_back(node, door.entranceId);
        }

        for (const Connector& con : floor.connectors) {
            const int node = add(con.pos);
            int room = floor.roomAt(con.pos);
            if (room < 0) {  // slightly outside every room: take the closest one
                float best = INFINITY;
                for (size_t r = 0; r < floor.rooms.size(); ++r) {
                    const float d = floor.rooms[r].shape.distanceToEdge(con.pos);
                    if (d < best) { best = d; room = static_cast<int>(r); }
                }
            }
            if (room >= 0) portals[room].push_back(node);
            Shaft& shaft = shafts[con.group];
            shaft.elevator |= con.kind == ConnectorKind::Elevator;
            shaft.stops.emplace_back(floor.level, node);
        }

        for (size_t r = 0; r < floor.rooms.size(); ++r) {
            const auto& p = portals[r];
            for (size_t i = 0; i < p.size(); ++i) {
                connectWalk(anchors[r], p[i]);
                for (size_t j = i + 1; j < p.size(); ++j) connectWalk(p[i], p[j]);
            }
        }
    }

    // Vertical links between consecutive levels of each stairwell / elevator shaft.
    for (auto& [group, shaft] : shafts) {
        auto& stops = shaft.stops;
        std::sort(stops.begin(), stops.end());
        for (size_t i = 0; i + 1 < stops.size(); ++i) {
            const int floorsApart = stops[i + 1].first - stops[i].first;
            if (floorsApart <= 0) continue;
            if (shaft.elevator) {
                connect(stops[i].second, stops[i + 1].second, bld.floorHeight * floorsApart,
                        kElevatorWaitS + kElevatorPerFloorS * floorsApart);
            } else {
                connect(stops[i].second, stops[i + 1].second, kStairsPerFloorM * floorsApart,
                        kStairsPerFloorS * floorsApart);
            }
        }
    }
    return exteriorDoors;
}

void Router::buildOutdoor() {
    std::unordered_map<long long, int> vertexNode;
    const auto vertex = [&](Vec2 p) {
        const auto [it, added] = vertexNode.try_emplace(pointKey(p), -1);
        if (added) it->second = addNode(p, p);
        return it->second;
    };
    for (const Footway& fw : campus_.footways) {
        int prev = vertex(fw.points[0]);
        for (size_t i = 1; i < fw.points.size(); ++i) {
            const int cur = vertex(fw.points[i]);
            connectWalk(prev, cur);
            footwaySegments_.emplace_back(prev, cur);
            prev = cur;
        }
    }

    for (const Building& bld : campus_.buildings) {
        for (const Entrance& e : bld.entrances) {
            const int en = addNode(e.pos, e.pos);
            attachOutdoor(en);  // without footways this links to the entrances added so far
            entranceNodes_.push_back(en);
        }
    }
}

void Router::attachOutdoor(int node) {
    if (footwaySegments_.empty()) {
        // No walkway data: allow straight lines between all entrances.
        for (const int en : entranceNodes_) connectWalk(node, en);
        return;
    }
    const Vec2 p = nodes_[node].world;
    float best = INFINITY, bestT = 0.f;
    size_t bestSeg = 0;
    for (size_t s = 0; s < footwaySegments_.size(); ++s) {
        float t;
        const auto [a, b] = footwaySegments_[s];
        const float d = distanceToSegment(p, nodes_[a].world, nodes_[b].world, &t);
        if (d < best) { best = d; bestT = t; bestSeg = s; }
    }
    const auto [a, b] = footwaySegments_[bestSeg];
    const Vec2 proj = nodes_[a].world + (nodes_[b].world - nodes_[a].world) * bestT;
    const int pn = addNode(proj, proj);
    connectWalk(pn, a);
    connectWalk(pn, b);
    connectWalk(node, pn);
}

int Router::roomNode(const Place& p) const {
    if (p.building < 0 || p.building >= static_cast<int>(roomNodes_.size())) return -1;
    const auto& floors = roomNodes_[p.building];
    if (p.floor < 0 || p.floor >= static_cast<int>(floors.size())) return -1;
    const auto& rooms = floors[p.floor];
    if (p.room < 0 || p.room >= static_cast<int>(rooms.size())) return -1;
    return rooms[p.room];
}

Route Router::route(const Place& from, const Place& to) const {
    Route result;
    const int start = roomNode(from);
    const int goal = roomNode(to);
    if (start < 0 || goal < 0) return result;

    // A* on walking time; the heuristic (straight line at walking speed) never overestimates.
    const size_t n = nodes_.size();
    std::vector<float> cost(n, INFINITY);
    std::vector<const Edge*> via(n, nullptr);  // edge that reached the node
    std::vector<int> prev(n, -1);
    using Item = std::pair<float, int>;  // (f = g + h, node)
    std::priority_queue<Item, std::vector<Item>, std::greater<>> open;
    const auto h = [&](int v) { return distance(nodes_[v].world, nodes_[goal].world) / kWalkSpeed; };

    cost[start] = 0.f;
    open.emplace(h(start), start);
    while (!open.empty()) {
        const auto [f, v] = open.top();
        open.pop();
        if (v == goal) break;
        if (f > cost[v] + h(v) + 1e-3f) continue;  // stale entry
        for (const Edge& e : adj_[v]) {
            const float c = cost[v] + e.seconds;
            if (c < cost[e.to]) {
                cost[e.to] = c;
                prev[e.to] = v;
                via[e.to] = &e;
                open.emplace(c + h(e.to), e.to);
            }
        }
    }
    if (!std::isfinite(cost[goal])) return result;

    std::vector<int> path;
    for (int v = goal; v != -1; v = prev[v]) path.push_back(v);
    std::reverse(path.begin(), path.end());

    result.found = true;
    result.seconds = cost[goal];
    for (const int v : path) {
        const Node& node = nodes_[v];
        if (via[v]) result.length += via[v]->meters;
        if (result.legs.empty() || result.legs.back().building != node.building || result.legs.back().floor != node.floor) {
            result.legs.push_back({node.building, node.floor, {}});
        }
        result.legs.back().points.push_back(node.local);
    }
    return result;
}

}  // namespace unigis
