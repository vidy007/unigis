"""Builds app/src/main/assets/campus.json from an OpenStreetMap extract of the NSTU campus.

OSM gives building footprints, levels, entrances and walkways (ODbL: keep the attribution).
Floor plans are not in OSM; they come from hand-made files such as data/demo_building.json.

Usage:
    curl -o nstu.osm "https://api.openstreetmap.org/api/0.6/map?bbox=82.8960,54.9810,82.9150,54.9910"
    python tools/osm_to_campus.py nstu.osm
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
DEFAULT_EXTRA = [ROOT / "tools" / "data" / "demo_building.json"]

# Origin of the local plane: the NSTU campus relation (osm relation/2164624).
ORIGIN_LAT = 54.9863816
ORIGIN_LON = 82.9057080

MAIN_STREET = "проспект Карла Маркса"
WALKABLE = {
    "footway", "path", "pedestrian", "steps", "service", "living_street",
    "residential", "unclassified", "tertiary", "secondary", "crossing", "corridor",
}
MARGIN_M = 80.0


def project(lat: float, lon: float) -> list[float]:
    """Equirectangular projection around the origin: x east, y south, meters."""
    k = math.cos(math.radians(ORIGIN_LAT))
    x = math.radians(lon - ORIGIN_LON) * 6371008.8 * k
    y = -math.radians(lat - ORIGIN_LAT) * 6371008.8
    return [round(x, 2), round(y, 2)]


def classify(tags: dict) -> tuple[str, str, str] | None:
    """Returns (id, name, short name) for NSTU academic buildings (корпуса), None for everything else.

    Only numbered корпуса at пр. К. Маркса, 20 are taken; dormitories and unnumbered
    buildings are left out on purpose.
    """
    street = tags.get("addr:street", "")
    number = tags.get("addr:housenumber", "")
    m = re.fullmatch(r"20\s*к\s*(\d+)", number) if street == MAIN_STREET else None
    if not m:
        return None
    n = m.group(1)
    return f"k{n}", tags.get("name") or f"Корпус {n}", f"Корпус {n}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("osm", type=Path, help="OSM XML extract (API /map output)")
    ap.add_argument("-o", "--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--extra", type=Path, nargs="*", default=DEFAULT_EXTRA,
                    help="building JSON files with floor plans to put first")
    args = ap.parse_args()

    root = ET.parse(args.osm).getroot()
    nodes = {}
    node_tags = {}
    for n in root.iter("node"):
        nodes[n.get("id")] = (float(n.get("lat")), float(n.get("lon")))
        tags = {t.get("k"): t.get("v") for t in n.iter("tag")}
        if tags:
            node_tags[n.get("id")] = tags

    buildings = []
    walk_ways = []
    for w in root.iter("way"):
        tags = {t.get("k"): t.get("v") for t in w.iter("tag")}
        refs = [nd.get("ref") for nd in w.iter("nd") if nd.get("ref") in nodes]
        if "building" in tags:
            cls = classify(tags)
            if not cls or len(refs) < 4:
                continue
            bid, name, short = cls
            outline = [project(*nodes[r]) for r in refs]
            entrances = []
            for r in dict.fromkeys(refs):
                kind = node_tags.get(r, {}).get("entrance")
                if kind:
                    entrances.append({
                        "id": f"{bid}-e{len(entrances) + 1}",
                        "pos": project(*nodes[r]),
                        **({"main": True} if kind == "main" else {}),
                    })
            levels = tags.get("building:levels", "")
            buildings.append({
                "id": bid,
                "name": name,
                "short": short,
                **({"levels": int(levels)} if levels.isdigit() else {}),
                "osm": f"way/{w.get('id')}",
                "outline": outline,
                "entrances": entrances,
                "floors": [],
            })
        elif tags.get("highway") in WALKABLE and len(refs) >= 2:
            walk_ways.append([project(*nodes[r]) for r in refs])

    if not buildings:
        print("No NSTU buildings found in the extract", file=sys.stderr)
        return 1
    buildings.sort(key=lambda b: int(b["id"][1:]))

    # Keep walkways near the selected buildings only.
    xs = [p[0] for b in buildings for p in b["outline"]]
    ys = [p[1] for b in buildings for p in b["outline"]]
    box = (min(xs) - MARGIN_M, min(ys) - MARGIN_M, max(xs) + MARGIN_M, max(ys) + MARGIN_M)
    inside = lambda p: box[0] <= p[0] <= box[2] and box[1] <= p[1] <= box[3]
    footways = [w for w in walk_ways if any(inside(p) for p in w)]

    extra = [json.loads(p.read_text(encoding="utf-8")) for p in args.extra]
    campus = {
        "name": "НГТУ",
        "attribution": "Контуры зданий и дорожки: © участники OpenStreetMap, ODbL",
        "origin": {"lat": ORIGIN_LAT, "lon": ORIGIN_LON},
        "buildings": extra + buildings,
        "footways": footways,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(campus, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"{args.out}: {len(buildings)} NSTU buildings, {len(footways)} footways, {len(extra)} extra")
    for b in buildings:
        print(f"  {b['id']:7} {b['name']:45} levels={b.get('levels', '?'):<3} entrances={len(b['entrances'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
