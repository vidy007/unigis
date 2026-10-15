#include "unigis/router.hpp"

#include <algorithm>
#include <cmath>
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
    buildIndoor();
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

void Router::buildIndoor() {
    const auto& buildings = campus_.buildings;
    roomNodes_.resize(buildings.size());

    // Exterior doors per building, linked to entrances once outdoor nodes exist.
    std::vector<std::vector<std::pair<int, std::string>>> exteriorDoors(buildings.size());

    for (int b = 0; b < static_cast<int>(buildings.size()); ++b) {
        const Building& bld = buildings[b];
        roomNodes_[b].resize(bld.floors.size());
        std::unordered_map<std::string, std::vector<std::pair<int, int>>> connectorsByGroup;  // group -> (level, node)

        for (int f = 0; f < static_cast<int>(bld.floors.size()); ++f) {
            const Floor& floor = bld.floors[f];
            const auto world = [&](Vec2 local) { return bld.frame.toParent(local); };

            std::vector<std::vector<int>> portals(floor.rooms.size());
            auto& anchors = roomNodes_[b][f];
            anchors.resize(floor.rooms.size());
            for (size_t r = 0; r < floor.rooms.size(); ++r) {
                anchors[r] = addNode(floor.rooms[r].anchor, world(floor.rooms[r].anchor), b, f);
            }

            for (const Door& door : floor.doors) {
                const Vec2 mid = door.mid();
                const int node = addNode(mid, world(mid), b, f);
                for (size_t r = 0; r < floor.rooms.size(); ++r) {
                    if (floor.rooms[r].shape.distanceToEdge(mid) <= kDoorSnap) portals[r].push_back(node);
                }
                // Placeholder buildings are not georeferenced, so they stay off the outdoor network.
                if (door.exterior && !bld.placeholder) exteriorDoors[b].emplace_back(node, door.entranceId);
            }

            for (const Connector& con : floor.connectors) {
                const int node = addNode(con.pos, world(con.pos), b, f);
                int room = floor.roomAt(con.pos);
                if (room < 0) {
                    float best = INFINITY;
                    for (size_t r = 0; r < floor.rooms.size(); ++r) {
                        const float d = floor.rooms[r].shape.distanceToEdge(con.pos);
                        if (d < best) { best = d; room = static_cast<int>(r); }
                    }
                }
                if (room >= 0) portals[room].push_back(node);
                connectorsByGroup[con.group].emplace_back(floor.level, node);
                (void)con.kind;
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
        for (auto& [group, list] : connectorsByGroup) {
            std::sort(list.begin(), list.end());
            bool elevator = false;
            for (const Floor& fl : bld.floors) {
                for (const Connector& c : fl.connectors) {
                    if (c.group == group && c.kind == ConnectorKind::Elevator) elevator = true;
                }
            }
            for (size_t i = 0; i + 1 < list.size(); ++i) {
                const int floorsApart = list[i + 1].first - list[i].first;
                if (floorsApart <= 0) continue;
                if (elevator) {
                    connect(list[i].second, list[i + 1].second, bld.floorHeight * floorsApart,
                            kElevatorWaitS + kElevatorPerFloorS * floorsApart);
                } else {
                    connect(list[i].second, list[i + 1].second, kStairsPerFloorM * floorsApart,
                            kStairsPerFloorS * floorsApart);
                }
            }
        }
    }

    buildOutdoor();

    // Exterior doors -> named entrance, or the nearest one of the same building.
    size_t base = 0;
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
                target = addNode(p, p, -1, -1);
                attachOutdoor(target);
            }
            connectWalk(doorNode, target);
        }
        base += entrances.size();
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
    const int pn = addNode(proj, proj, -1, -1);
    connectWalk(pn, a);
    connectWalk(pn, b);
    connectWalk(node, pn);
}

void Router::buildOutdoor() {
    std::unordered_map<long long, int> vertexNode;
    auto& segments = footwaySegments_;
    const auto vertex = [&](Vec2 p) {
        const long long key = pointKey(p);
        auto it = vertexNode.find(key);
        if (it != vertexNode.end()) return it->second;
        const int n = addNode(p, p, -1, -1);
        vertexNode.emplace(key, n);
        return n;
    };
    for (const Footway& fw : campus_.footways) {
        int prev = vertex(fw.points[0]);
        for (size_t i = 1; i < fw.points.size(); ++i) {
            const int cur = vertex(fw.points[i]);
            connectWalk(prev, cur);
            segments.emplace_back(prev, cur);
            prev = cur;
        }
    }

    for (const Building& bld : campus_.buildings) {
        for (const Entrance& e : bld.entrances) {
            const int en = addNode(e.pos, e.pos, -1, -1);
            attachOutdoor(en);  // without footways this links to the entrances added so far
            entranceNodes_.push_back(en);
        }
    }
}

Route Router::route(const Place& from, const Place& to) const {
    Route result;
    const auto nodeOf = [&](const Place& p) -> int {
        if (p.building < 0 || p.building >= static_cast<int>(roomNodes_.size())) return -1;
        const auto& floors = roomNodes_[p.building];
        if (p.floor < 0 || p.floor >= static_cast<int>(floors.size())) return -1;
        const auto& rooms = floors[p.floor];
        if (p.room < 0 || p.room >= static_cast<int>(rooms.size())) return -1;
        return rooms[p.room];
    };
    const int start = nodeOf(from);
    const int goal = nodeOf(to);
    if (start < 0 || goal < 0) return result;

    const size_t n = nodes_.size();
    std::vector<float> cost(n, INFINITY);
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
    for (size_t i = 0; i < path.size(); ++i) {
        const Node& node = nodes_[path[i]];
        if (i > 0) {
            for (const Edge& e : adj_[path[i - 1]]) {
                if (e.to == path[i]) { result.length += e.meters; break; }
            }
        }
        if (result.legs.empty() || result.legs.back().building != node.building || result.legs.back().floor != node.floor) {
            result.legs.push_back({node.building, node.floor, {}});
        }
        result.legs.back().points.push_back(node.local);
    }
    return result;
}

}  // namespace unigis
