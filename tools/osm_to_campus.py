"""Builds app/src/main/assets/campus.json from an OpenStreetMap extract of the NSTU campus.

Which objects go in, and with which badge, comes from the legend of the printed campus map
(data/campus_map.json): academic buildings, dormitories and campus facilities, plus the bus,
tram and metro stops around the campus. OSM gives their footprints, levels, entrances and the
walkways (ODbL: keep the attribution). Floor plans are not in OSM; a legend entry points to a
hand-made file with them ("floors": "k4_floors.json" for корпус 4).

Usage:
    curl -o nstu.osm "https://api.openstreetmap.org/api/0.6/map?bbox=82.8960,54.9810,82.9150,54.9910"
    python tools/osm_to_campus.py nstu.osm

Objects of the legend that are not found are listed at the end: give them an "osm" id in the
legend (the object's way/N or relation/N from openstreetmap.org) and run the script again.
"""

import argparse
import json
import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = ROOT / "app" / "src" / "main" / "assets" / "campus.json"
DEFAULT_LEGEND = ROOT / "tools" / "data" / "campus_map.json"
DEFAULT_EXTRA: list[Path] = []

# Origin of the local plane: the NSTU campus relation (osm relation/2164624).
ORIGIN_LAT = 54.9863816
ORIGIN_LON = 82.9057080

WALKABLE = {
    "footway", "path", "pedestrian", "steps", "service", "living_street",
    "residential", "unclassified", "tertiary", "secondary", "crossing", "corridor",
}
FOOTWAY_MARGIN_M = 80.0
STOP_MARGIN_M = 60.0
# Matches by tags are searched only this far from the campus origin (the extract is bigger).
SEARCH_RADIUS_M = 600.0
# OSM maps one stop as several objects (platform, stop position); closer ones are merged.
STOP_MERGE_M = 60.0


def project(lat: float, lon: float) -> list[float]:
    """Equirectangular projection around the origin: x east, y south, meters."""
    k = math.cos(math.radians(ORIGIN_LAT))
    x = math.radians(lon - ORIGIN_LON) * 6371008.8 * k
    y = -math.radians(lat - ORIGIN_LAT) * 6371008.8
    return [round(x, 2), round(y, 2)]


def polygon_area(ring: list[list[float]]) -> float:
    return abs(sum(p[0] * q[1] - q[0] * p[1] for p, q in zip(ring, ring[1:] + ring[:1]))) / 2


def contains(ring: list[list[float]], p: list[float]) -> bool:
    inside = False
    for (ax, ay), (bx, by) in zip(ring, ring[-1:] + ring[:-1]):
        if (ay > p[1]) != (by > p[1]) and p[0] < (bx - ax) * (p[1] - ay) / (by - ay) + ax:
            inside = not inside
    return inside


def join_rings(ways: list[list[str]]) -> list[list[str]]:
    """Joins the outer member ways of a multipolygon into closed rings of node refs."""
    rings, rest = [], [w[:] for w in ways if len(w) >= 2]
    while rest:
        ring = rest.pop(0)
        while ring[0] != ring[-1]:
            for i, w in enumerate(rest):
                if w[0] == ring[-1]:
                    ring += w[1:]
                elif w[-1] == ring[-1]:
                    ring += w[-2::-1]
                elif w[-1] == ring[0]:
                    ring = w[:-1] + ring
                elif w[0] == ring[0]:
                    ring = w[:0:-1] + ring
                else:
                    continue
                rest.pop(i)
                break
            else:
                break  # open ring: broken data, dropped below
        if ring[0] == ring[-1] and len(ring) >= 4:
            rings.append(ring)
    return rings


class Osm:
    """Polygons (closed ways, multipolygons), tagged nodes and walkways of an OSM extract."""

    def __init__(self, path: Path):
        root = ET.parse(path).getroot()
        tags_of = lambda e: {t.get("k"): t.get("v") for t in e.iter("tag")}
        self.nodes = {n.get("id"): (float(n.get("lat")), float(n.get("lon"))) for n in root.iter("node")}
        self.node_tags = {n.get("id"): t for n in root.iter("node") if (t := tags_of(n))}

        way_refs = {}
        self.polygons = []  # dicts: key ("way/1"), tags, refs (closed ring), outline (meters), area
        self.walkways = []
        for w in root.iter("way"):
            refs = [nd.get("ref") for nd in w.iter("nd") if nd.get("ref") in self.nodes]
            way_refs[w.get("id")] = refs
            tags = tags_of(w)
            if len(refs) >= 4 and refs[0] == refs[-1] and tags:
                self._add_polygon(f"way/{w.get('id')}", tags, refs)
            elif tags.get("highway") in WALKABLE and len(refs) >= 2:
                self.walkways.append([project(*self.nodes[r]) for r in refs])
        for r in root.iter("relation"):
            tags = tags_of(r)
            if tags.get("type") != "multipolygon":
                continue
            outer = [way_refs[m.get("ref")] for m in r.iter("member")
                     if m.get("type") == "way" and m.get("role") in ("outer", "") and m.get("ref") in way_refs]
            rings = join_rings(outer)
            if rings:
                largest = max(rings, key=lambda ring: polygon_area([project(*self.nodes[x]) for x in ring]))
                self._add_polygon(f"relation/{r.get('id')}", tags, largest)
        self.by_key = {p["key"]: p for p in self.polygons}

    def _add_polygon(self, key: str, tags: dict, refs: list[str]):
        outline = [project(*self.nodes[r]) for r in refs[:-1]]
        self.polygons.append({"key": key, "tags": tags, "refs": refs[:-1], "outline": outline,
                              "area": polygon_area(outline)})

    def node_pos(self, ref: str) -> list[float]:
        return project(*self.nodes[ref])


def tags_match(tags: dict, rule: dict) -> bool:
    return all(re.search(pattern, tags.get(key, "")) for key, pattern in rule.items())


def find(osm: Osm, entry: dict, used: set[str]) -> list[dict]:
    """Polygons for one legend entry: by explicit id, else by tags of the polygon or of a POI inside it."""
    if "osm" in entry:
        p = osm.by_key.get(entry["osm"])
        return [p] if p and p["key"] not in used else []
    rules = entry.get("match", [])
    rules = rules if isinstance(rules, list) else [rules]
    near = [p for p in osm.polygons if p["key"] not in used
            and math.dist(p["outline"][0], (0, 0)) <= SEARCH_RADIUS_M]
    hits = [p for p in near if any(tags_match(p["tags"], r) for r in rules)]
    for ref, tags in osm.node_tags.items():
        if any(tags_match(tags, r) for r in rules):
            pos = osm.node_pos(ref)
            # A POI node (library, clinic...) stands for the smallest building around it.
            around = [p for p in near if "building" in p["tags"] and contains(p["outline"], pos)]
            if around:
                hits.append(min(around, key=lambda p: p["area"]))
    hits = list({p["key"]: p for p in hits}.values())
    # Several candidates: prefer buildings, then the biggest footprint.
    hits.sort(key=lambda p: ("building" not in p["tags"], -p["area"]))
    return hits[: entry.get("count", 1)]


def building_json(osm: Osm, entry: dict, poly: dict, suffix: str, legend_dir: Path) -> dict:
    bid = entry["id"] + suffix
    entrances = []
    for r in dict.fromkeys(poly["refs"]):
        kind = osm.node_tags.get(r, {}).get("entrance")
        if kind:
            entrances.append({"id": f"{bid}-e{len(entrances) + 1}", "pos": osm.node_pos(r),
                              **({"main": True} if kind == "main" else {})})
    levels = poly["tags"].get("building:levels", "")
    return {
        "id": bid,
        "kind": entry["kind"],
        "number": entry["number"],
        "name": poly["tags"].get("name") or entry["name"],  # OSM names are often more specific
        "short": entry.get("short", entry["name"]),
        **({"levels": int(levels)} if levels.isdigit() else {}),
        "osm": poly["key"],
        "outline": poly["outline"],
        "entrances": entrances,
        **floor_plans(legend_dir, entry),
    }


def floor_plans(legend_dir: Path, entry: dict) -> dict:
    """Frame and floors of a building from the file named in its legend entry, if any."""
    if "floors" not in entry:
        return {"floors": []}
    plans = json.loads((legend_dir / entry["floors"]).read_text(encoding="utf-8"))
    return {k: v for k, v in plans.items() if k in ("frame", "floorHeight", "floors")}


def stop_kind(tags: dict) -> str | None:
    if tags.get("station") == "subway" or tags.get("railway") == "subway_entrance" or tags.get("subway") == "yes":
        return "metro"
    if tags.get("railway") == "tram_stop" or tags.get("tram") == "yes":
        return "tram"
    if tags.get("highway") == "bus_stop" or tags.get("bus") == "yes" or tags.get("trolleybus") == "yes":
        return "bus"
    return None


def find_stops(osm: Osm, buildings: list[dict]) -> list[dict]:
    """Stops and metro entrances next to the campus buildings, one per stop."""
    outlines = [p for b in buildings for p in b["outline"]]
    xs, ys = [p[0] for p in outlines], [p[1] for p in outlines]
    box = (min(xs) - STOP_MARGIN_M, min(ys) - STOP_MARGIN_M, max(xs) + STOP_MARGIN_M, max(ys) + STOP_MARGIN_M)
    stops = []
    for ref, tags in osm.node_tags.items():
        is_stop = tags.get("public_transport") in ("platform", "stop_position", "station") \
            or tags.get("highway") == "bus_stop" or tags.get("railway") in ("tram_stop", "subway_entrance", "station")
        kind = stop_kind(tags) if is_stop else None
        pos = osm.node_pos(ref)
        if not kind or not (box[0] <= pos[0] <= box[2] and box[1] <= pos[1] <= box[3]):
            continue
        name = tags.get("name", "")
        if any(s["kind"] == kind and s["name"] == name and math.dist(s["pos"], pos) < STOP_MERGE_M for s in stops):
            continue
        stops.append({"id": f"{kind}-{sum(s['kind'] == kind for s in stops) + 1}", "kind": kind, "name": name,
                      "pos": pos})
    return stops


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("osm", type=Path, help="OSM XML extract (API /map output)")
    ap.add_argument("-o", "--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--legend", type=Path, default=DEFAULT_LEGEND, help="objects of the printed campus map")
    ap.add_argument("--extra", type=Path, nargs="*", default=DEFAULT_EXTRA,
                    help="extra building JSON files (not in OSM) to put first")
    args = ap.parse_args()

    osm = Osm(args.osm)
    legend = json.loads(args.legend.read_text(encoding="utf-8"))

    buildings, missing, used = [], [], set()
    for entry in legend["objects"]:
        found = find(osm, entry, used)
        if not found and not entry.get("optional"):
            missing.append(entry)
        for i, poly in enumerate(found):
            used.add(poly["key"])
            suffix = f"-{i + 1}" if entry.get("count", 1) > 1 else ""
            buildings.append(building_json(osm, entry, poly, suffix, args.legend.parent))
    if not buildings:
        print("None of the legend objects found in the extract", file=sys.stderr)
        return 1

    # Keep walkways near the selected buildings only.
    xs = [p[0] for b in buildings for p in b["outline"]]
    ys = [p[1] for b in buildings for p in b["outline"]]
    box = (min(xs) - FOOTWAY_MARGIN_M, min(ys) - FOOTWAY_MARGIN_M,
           max(xs) + FOOTWAY_MARGIN_M, max(ys) + FOOTWAY_MARGIN_M)
    inside = lambda p: box[0] <= p[0] <= box[2] and box[1] <= p[1] <= box[3]
    footways = [w for w in osm.walkways if any(inside(p) for p in w)]
    stops = find_stops(osm, buildings)

    extra = [json.loads(p.read_text(encoding="utf-8")) for p in args.extra]
    campus = {
        "name": "НГТУ",
        "attribution": "Контуры зданий и дорожки: © участники OpenStreetMap, ODbL",
        "origin": {"lat": ORIGIN_LAT, "lon": ORIGIN_LON},
        "viewRotation": legend.get("viewRotation", 0),
        "buildings": extra + buildings,
        "footways": footways,
        "stops": stops,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(campus, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"{args.out}: {len(buildings)} buildings, {len(stops)} stops, {len(footways)} footways, {len(extra)} extra")
    for b in buildings:
        print(f"  {b['id']:6} {b['kind']:9} {b['number']:6} {b['name']:45} {b['osm']:18} "
              f"levels={b.get('levels', '?'):<3} entrances={len(b['entrances'])}")
    for s in stops:
        print(f"  {s['id']:8} {s['name']}")
    if missing:
        print(f"Not found in OSM ({len(missing)}): give them an \"osm\" id in {args.legend.name}", file=sys.stderr)
        for e in missing:
            print(f"  {e['id']:6} {e['name']}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
