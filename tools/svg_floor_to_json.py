"""Converts a floor plan drawn in Inkscape/Figma (SVG) into a UniGISMap floor and puts it into campus.json.

One SVG file = one floor. Coordinates are SVG user units after all transforms (translate/scale/rotate/
matrix/skew on groups and elements), converted to meters; y points down like in SVG. The SVG origin is
the building-local origin, so draw all floors of a building on the same canvas (or set a <circle id="origin">
at the same physical point on every floor).

Drawing conventions:
  scale         <line> or <rect> (or a two-point <path>) with id="scale" and data-meters="N": its real
                length in meters (for a rect, the longer side). Required.
  rooms         <rect>, <polygon> or <path> made of straight segments (M/L/H/V/Z), with
                  id             room number ("201"); data-id overrides it (XML ids can't start with a digit
                                 in some editors, e.g. Inkscape)
                  data-name      shown name; else inkscape:label; else the id
                  data-category  office|meeting|common|service|corridor
                A shape is a room only if it has data-category, data-name or an inkscape:label, or sits in a
                group that gives a category, so walls, backgrounds and traced images are ignored.
  doors         <line> (or two-point <path>, which is what Inkscape's pen draws) with data-door or class "door";
                data-exterior="true" for doors to the street, data-entrance="<entrance id>" to pin the entrance.
                The door's midpoint must lie on the outlines (within 0.3 m) of the rooms it connects.
  stairs/lifts  <circle> or <ellipse> with data-connector="stairs|elevator" and data-group="stairs-A"
                (same group on every floor; default group is the element id). Connector id = "<group>-<level>".
  you are here  <circle id="you-are-here">. Optional (the app then uses the floor center).
  bounds        <rect id="bounds">. Optional (else the bounding box of everything above).
  origin        <circle id="origin">. Optional: its center becomes (0, 0).

data-* attributes are inherited from ancestor groups, so <g data-category="office"> or <g data-door="">
marks everything inside. Figma drops custom attributes but keeps layer names as ids, so a group (or Inkscape
layer) named after a category ("office", ...), "doors", "stairs" or "elevator" works the same way.
Hidden elements (display:none) and <defs> are skipped.

Usage:
    python tools/svg_floor_to_json.py floor.svg --building k1 --level 2 [--name "Этаж 2"]
        [--campus app/src/main/assets/campus.json] [--dry-run]
    python tools/svg_floor_to_json.py --check     # self-test on tools/data/sample_floor.svg
"""

import argparse
import json
import math
import re
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CAMPUS = ROOT / "app" / "src" / "main" / "assets" / "campus.json"
SAMPLE_SVG = ROOT / "tools" / "data" / "sample_floor.svg"

SVG_NS = "http://www.w3.org/2000/svg"
INKSCAPE_LABEL = "{http://www.inkscape.org/namespaces/inkscape}label"
CATEGORIES = {"office", "meeting", "common", "service", "corridor"}
CONNECTOR_KINDS = {"stairs", "elevator"}
SPECIAL_IDS = {"scale", "bounds", "origin", "you-are-here"}
SKIPPED_TAGS = {"defs", "clipPath", "mask", "symbol", "marker", "pattern", "metadata", "title", "desc",
                "style", "script", "linearGradient", "radialGradient", "filter", "namedview", "text"}
DOOR_SNAP_M = 0.3  # router.cpp kDoorSnap
DIGITS = 2         # centimeters


class PlanError(Exception):
    pass


# ---------------------------------------------------------------- transforms

Matrix = tuple[float, float, float, float, float, float]  # SVG matrix(a b c d e f)
IDENTITY: Matrix = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def mul(m: Matrix, n: Matrix) -> Matrix:
    """m then n applied to a point = m * n (n is applied first)."""
    a, b, c, d, e, f = m
    a2, b2, c2, d2, e2, f2 = n
    return (a * a2 + c * b2, b * a2 + d * b2, a * c2 + c * d2, b * c2 + d * d2,
            a * e2 + c * f2 + e, b * e2 + d * f2 + f)


def apply(m: Matrix, x: float, y: float) -> tuple[float, float]:
    a, b, c, d, e, f = m
    return a * x + c * y + e, b * x + d * y + f


NUMBER = r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?"


def parse_transform(text: str | None) -> Matrix:
    m = IDENTITY
    if not text:
        return m
    for name, args in re.findall(r"(\w+)\s*\(([^)]*)\)", text):
        v = [float(x) for x in re.findall(NUMBER, args)]
        if name == "matrix" and len(v) == 6:
            t = tuple(v)
        elif name == "translate" and v:
            t = (1, 0, 0, 1, v[0], v[1] if len(v) > 1 else 0)
        elif name == "scale" and v:
            t = (v[0], 0, 0, v[1] if len(v) > 1 else v[0], 0, 0)
        elif name == "rotate" and v:
            r = math.radians(v[0])
            t = (math.cos(r), math.sin(r), -math.sin(r), math.cos(r), 0, 0)
            if len(v) == 3:
                t = mul(mul((1, 0, 0, 1, v[1], v[2]), t), (1, 0, 0, 1, -v[1], -v[2]))
        elif name == "skewX" and v:
            t = (1, 0, math.tan(math.radians(v[0])), 1, 0, 0)
        elif name == "skewY" and v:
            t = (1, math.tan(math.radians(v[0])), 0, 1, 0, 0)
        else:
            raise PlanError(f"unsupported transform '{name}({args})'")
        m = mul(m, t)
    return m


# ---------------------------------------------------------------- element helpers

def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def length(el: ET.Element, attr: str, default: float | None = None) -> float:
    raw = el.get(attr)
    if raw is None:
        if default is None:
            raise PlanError(f"{describe(el)}: missing '{attr}'")
        return default
    raw = raw.strip()
    if raw.endswith("px"):
        raw = raw[:-2]
    try:
        return float(raw)
    except ValueError:
        raise PlanError(f"{describe(el)}: '{attr}={el.get(attr)}' must be a number in user units") from None


def describe(el: ET.Element) -> str:
    return f"<{local(el.tag)} id=\"{el.get('id', '')}\">"


def hidden(el: ET.Element) -> bool:
    style = (el.get("style") or "").replace(" ", "")
    return el.get("display") == "none" or "display:none" in style or el.get("visibility") == "hidden"


def group_hints(el: ET.Element) -> dict[str, str]:
    """data-* implied by a group's name (Figma layer names become ids)."""
    hints = {}
    for name in (el.get("id"), el.get(INKSCAPE_LABEL)):
        name = (name or "").strip().lower()
        if name in CATEGORIES:
            hints["data-category"] = name
        elif name in ("doors", "door"):
            hints["data-door"] = ""
        elif name in ("stairs", "elevator", "elevators"):
            hints["data-connector"] = "elevator" if name.startswith("elevator") else "stairs"
    return hints


def walk(el: ET.Element, m: Matrix, inherited: dict[str, str], out: list):
    """Collects (element, matrix, effective data-* attributes) for every visible drawable element."""
    tag = local(el.tag)
    if tag in SKIPPED_TAGS or hidden(el):
        return
    m = mul(m, parse_transform(el.get("transform")))
    attrs = dict(inherited)
    if tag in ("g", "a", "svg"):
        attrs.update(group_hints(el))
    attrs.update({k: v for k, v in el.attrib.items() if k.startswith("data-")})
    if "door" in (el.get("class") or "").split():
        attrs["data-door"] = ""
    if tag in ("g", "a", "svg"):
        for child in el:
            walk(child, m, attrs, out)
    else:
        out.append((el, m, attrs))


# ---------------------------------------------------------------- geometry

def path_points(el: ET.Element) -> list[tuple[float, float]]:
    d = el.get("d") or ""
    tokens = re.findall(r"[A-Za-z]|" + NUMBER, d)
    pts: list[tuple[float, float]] = []
    x = y = 0.0
    cmd = None
    i = 0
    closed = False

    def num() -> float:
        nonlocal i
        if i >= len(tokens) or tokens[i].isalpha():
            raise PlanError(f"{describe(el)}: malformed path data")
        i += 1
        return float(tokens[i - 1])

    while i < len(tokens):
        if tokens[i].isalpha():
            cmd = tokens[i]
            i += 1
            if cmd in "Zz":
                closed = True
                continue
            if closed:
                raise PlanError(f"{describe(el)}: several subpaths (holes) are not supported; draw one outline")
        elif cmd is None:
            raise PlanError(f"{describe(el)}: path must start with M")
        if cmd in "Mm":
            if pts and cmd in "Mm" and tokens[i - 1].isalpha():
                raise PlanError(f"{describe(el)}: several subpaths (holes) are not supported; draw one outline")
            dx, dy = num(), num()
            x, y = (x + dx, y + dy) if cmd == "m" else (dx, dy)
            cmd = "l" if cmd == "m" else "L"  # implicit lineto after moveto
        elif cmd in "Ll":
            dx, dy = num(), num()
            x, y = (x + dx, y + dy) if cmd == "l" else (dx, dy)
        elif cmd in "Hh":
            v = num()
            x = x + v if cmd == "h" else v
        elif cmd in "Vv":
            v = num()
            y = y + v if cmd == "v" else v
        else:
            raise PlanError(f"{describe(el)}: path command '{cmd}' (curve/arc) is not supported; "
                            "use straight segments only (Inkscape: Path > Object to Path, then remove curves)")
        pts.append((x, y))
    return pts


def dedupe(pts: list[tuple[float, float]]) -> list[tuple[float, float]]:
    out = []
    for p in pts:
        if not out or math.dist(p, out[-1]) > 1e-9:
            out.append(p)
    if len(out) > 1 and math.dist(out[0], out[-1]) <= 1e-9:
        out.pop()
    return out


def shape_points(el: ET.Element) -> list[tuple[float, float]]:
    """Outline / segment of an element in its own user units, before transforms."""
    tag = local(el.tag)
    if tag == "rect":
        x, y = length(el, "x", 0.0), length(el, "y", 0.0)
        w, h = length(el, "width"), length(el, "height")
        return [(x, y), (x + w, y), (x + w, y + h), (x, y + h)]
    if tag in ("polygon", "polyline"):
        v = [float(n) for n in re.findall(NUMBER, el.get("points") or "")]
        return list(zip(v[0::2], v[1::2]))
    if tag == "path":
        return path_points(el)
    if tag == "line":
        return [(length(el, "x1", 0.0), length(el, "y1", 0.0)), (length(el, "x2", 0.0), length(el, "y2", 0.0))]
    if tag in ("circle", "ellipse"):
        return [(length(el, "cx", 0.0), length(el, "cy", 0.0))]
    raise PlanError(f"{describe(el)}: unsupported element")


def segment_distance(p, a, b) -> float:
    ax, ay = a
    dx, dy = b[0] - ax, b[1] - ay
    len2 = dx * dx + dy * dy
    t = 0.0 if len2 == 0 else max(0.0, min(1.0, ((p[0] - ax) * dx + (p[1] - ay) * dy) / len2))
    return math.dist(p, (ax + t * dx, ay + t * dy))


def edge_distance(p, poly) -> float:
    return min(segment_distance(p, poly[i], poly[(i + 1) % len(poly)]) for i in range(len(poly)))


def rnd(v: float) -> float:
    v = round(v, DIGITS)
    return int(v) if v == int(v) else v + 0.0  # 3 instead of 3.0, no -0.0


def as_rect(poly) -> list[float] | None:
    """[l, t, r, b] if the polygon is an axis-aligned rectangle."""
    if len(poly) != 4:
        return None
    xs = sorted({p[0] for p in poly})
    ys = sorted({p[1] for p in poly})
    if len(xs) != 2 or len(ys) != 2:
        return None
    if {(x, y) for x in xs for y in ys} != set(poly):
        return None
    return [xs[0], ys[0], xs[1], ys[1]]


# ---------------------------------------------------------------- conversion

def convert(svg_text: str, level: int, name: str | None = None) -> tuple[dict, list[str]]:
    """Returns (floor dict, warnings)."""
    try:
        root = ET.fromstring(svg_text)
    except ET.ParseError as e:
        raise PlanError(f"SVG is not valid XML: {e}") from None
    if local(root.tag) != "svg":
        raise PlanError("root element is not <svg>")
    items: list = []
    for child in root:  # the root's viewBox maps to the page; user units are what we measure in
        walk(child, parse_transform(root.get("transform")), {}, items)

    warnings: list[str] = []
    by_id = {}
    for el, m, attrs in items:
        i = el.get("id")
        if i in SPECIAL_IDS:
            by_id[i] = (el, m, attrs)

    def world(el, m):
        return [apply(m, *p) for p in shape_points(el)]

    # Scale: meters per user unit.
    if "scale" not in by_id:
        raise PlanError('no scale reference: add <line id="scale" data-meters="N"> (or a rect)')
    el, m, _ = by_id["scale"]
    if el.get("data-meters") is None:
        raise PlanError('scale element needs data-meters="<real length in meters>"')
    meters = float(el.get("data-meters"))
    pts = world(el, m)
    if local(el.tag) == "rect":
        units = max(math.dist(pts[0], pts[1]), math.dist(pts[1], pts[2]))
    else:
        pts = dedupe(pts)
        if len(pts) != 2:
            raise PlanError("scale element must be a line, a rect or a two-point path")
        units = math.dist(*pts)
    if units <= 0 or meters <= 0:
        raise PlanError("scale element has zero length or data-meters <= 0")
    k = meters / units

    ox, oy = 0.0, 0.0
    if "origin" in by_id:
        ox, oy = world(*by_id["origin"][:2])[0]

    def to_m(p):
        return (rnd((p[0] - ox) * k), rnd((p[1] - oy) * k))

    rooms, doors, connectors = [], [], []
    room_shapes = []  # (id, polygon in meters) for door checks
    you_are_here = None
    for el, m, attrs in items:
        tag, eid = local(el.tag), el.get("id") or ""
        label = (el.get(INKSCAPE_LABEL) or "").strip()
        if eid in ("scale", "bounds", "origin"):
            continue
        if eid == "you-are-here" or label == "you-are-here":
            you_are_here = list(to_m(world(el, m)[0]))
            continue
        if tag in ("circle", "ellipse") and "data-connector" in attrs:
            kind = attrs["data-connector"].strip().lower()
            if kind not in CONNECTOR_KINDS:
                raise PlanError(f"{describe(el)}: data-connector must be stairs or elevator, not '{kind}'")
            group = (el.get("data-group") or attrs.get("data-group") or eid or label).strip()
            if not group:
                raise PlanError(f"{describe(el)}: connector needs data-group (e.g. stairs-A)")
            connectors.append({"id": f"{group}-{level}", "group": group, "kind": kind,
                               "pos": list(to_m(world(el, m)[0]))})
            continue
        if tag in ("line", "path", "polyline") and "data-door" in attrs:
            pts = dedupe(world(el, m))
            if len(pts) != 2:
                raise PlanError(f"{describe(el)}: a door must be a single straight segment")
            door = {"a": list(to_m(pts[0])), "b": list(to_m(pts[1]))}
            if attrs.get("data-exterior", "").strip().lower() in ("true", "1", "yes"):
                door["exterior"] = True
            if attrs.get("data-entrance"):
                door["entrance"] = attrs["data-entrance"].strip()
            doors.append(door)
            continue
        if tag in ("rect", "polygon", "path"):
            own_label = label if label and label != eid else ""
            if not ("data-category" in attrs or el.get("data-name") or own_label):
                continue  # walls, background, traced image frame...
            rid = (el.get("data-id") or eid).strip()
            if not rid:
                raise PlanError(f"{describe(el)} named '{el.get('data-name') or own_label}': room needs an id")
            category = attrs.get("data-category", "office").strip().lower()
            if category not in CATEGORIES:
                raise PlanError(f"room {rid}: data-category must be one of {sorted(CATEGORIES)}, not '{category}'")
            poly = dedupe([to_m(p) for p in world(el, m)])
            if len(poly) < 3:
                raise PlanError(f"room {rid}: outline needs at least 3 distinct points")
            room = {"id": rid, "name": (el.get("data-name") or own_label or rid).strip(), "category": category}
            rect = as_rect(poly)
            if rect:
                room["rect"] = rect
            else:
                room["polygon"] = [list(p) for p in poly]
            rooms.append(room)
            room_shapes.append((rid, poly))

    if not rooms:
        raise PlanError("no rooms found: give shapes data-category / data-name, or put them in a category group")
    seen = set()
    for r in rooms:
        if r["id"] in seen:
            raise PlanError(f"duplicate room id '{r['id']}'")
        seen.add(r["id"])
    ids = [c["id"] for c in connectors]
    if len(ids) != len(set(ids)):
        raise PlanError(f"duplicate connector group on one floor: {ids}")

    for d in doors:
        mid = ((d["a"][0] + d["b"][0]) / 2, (d["a"][1] + d["b"][1]) / 2)
        touching = [rid for rid, poly in room_shapes if edge_distance(mid, poly) <= DOOR_SNAP_M]
        need = 1 if d.get("exterior") else 2
        if len(touching) < need:
            warnings.append(f"door {d['a']}-{d['b']} touches {touching or 'no rooms'}; its midpoint must lie on "
                            f"the outline of {'a room' if need == 1 else 'both rooms it connects'} "
                            f"(within {DOOR_SNAP_M} m)")
    if not any(d.get("exterior") for d in doors) and not connectors:
        warnings.append("floor has no exterior door and no stairs/elevator: it is unreachable")

    if "bounds" in by_id:
        pts = [to_m(p) for p in world(*by_id["bounds"][:2])]
    else:
        pts = [p for _, poly in room_shapes for p in poly]
    bounds = [min(p[0] for p in pts), min(p[1] for p in pts), max(p[0] for p in pts), max(p[1] for p in pts)]

    floor = {"level": level, "name": name or f"Этаж {level}", "bounds": bounds}
    if you_are_here:
        floor["youAreHere"] = you_are_here
    floor.update({"rooms": rooms, "doors": doors, "connectors": connectors})
    return floor, warnings


def insert_floor(campus: dict, building_id: str, floor: dict) -> bool:
    """Inserts or replaces the floor with the same level. Returns True if it replaced one."""
    for b in campus.get("buildings", []):
        if b.get("id") == building_id:
            floors = [f for f in b.setdefault("floors", []) if f.get("level") != floor["level"]]
            replaced = len(floors) != len(b["floors"])
            floors.append(floor)
            floors.sort(key=lambda f: f.get("level", 1))
            b["floors"] = floors
            return replaced
    known = ", ".join(b.get("id", "?") for b in campus.get("buildings", []))
    raise PlanError(f"building '{building_id}' not found in campus file (have: {known})")


def write_campus(path: Path, campus: dict, original: str):
    # Keep the file's style: osm_to_campus.py writes compact JSON, hand-edited files are indented.
    if "\n " in original:
        text = json.dumps(campus, ensure_ascii=False, indent=2) + "\n"
    else:
        text = json.dumps(campus, ensure_ascii=False, separators=(",", ":"))
    path.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------- self-check

def check() -> int:
    """Converts tools/data/sample_floor.svg into a temp copy of campus.json and verifies the result."""
    with tempfile.TemporaryDirectory() as tmp:
        target = Path(tmp) / "campus.json"
        shutil.copyfile(DEFAULT_CAMPUS, target)
        before = json.loads(target.read_text(encoding="utf-8"))
        building = next(b["id"] for b in before["buildings"] if not b.get("floors"))
        for level in (3, 1):  # out of order, to check sorting
            rc = main([str(SAMPLE_SVG), "--building", building, "--level", str(level), "--campus", str(target)])
            assert rc == 0, f"conversion failed for level {level}"
        rc = main([str(SAMPLE_SVG), "--building", building, "--level", "3", "--name", "Третий",
                   "--campus", str(target)])
        assert rc == 0
        after = json.loads(target.read_text(encoding="utf-8"))

    b = next(b for b in after["buildings"] if b["id"] == building)
    assert [f["level"] for f in b["floors"]] == [1, 3], b["floors"]
    floor = b["floors"][1]
    assert floor["name"] == "Третий"
    rooms = {r["id"]: r for r in floor["rooms"]}
    # sample_floor.svg: 1 SVG unit = 0.1 m (scale line 100 units = 10 m), rooms group translated by (20, 20).
    expected = {
        "101": ("Кабинет 101", "office", [2, 2, 8, 8]),
        "102": ("Переговорная", "meeting", [8, 2, 14, 8]),
        "corridor": ("Коридор", "corridor", [2, 8, 20, 11]),
    }
    for rid, (name, cat, rect) in expected.items():
        r = rooms[rid]
        assert (r["name"], r["category"], r["rect"]) == (name, cat, rect), r
    poly = rooms["103"]
    assert poly["category"] == "service" and poly["name"] == "Санузел", poly
    assert poly["polygon"] == [[14, 2], [20, 2], [20, 8], [17, 8], [14, 5]], poly
    assert len(rooms) == 4, sorted(rooms)
    assert floor["bounds"] == [0, 0, 22, 13], floor["bounds"]
    assert floor["youAreHere"] == [3, 9.5], floor["youAreHere"]
    assert floor["doors"] == [{"a": [4.5, 8], "b": [5.5, 8]},
                              {"a": [2, 9], "b": [2, 10.5], "exterior": True}], floor["doors"]
    assert floor["connectors"] == [{"id": "stairs-A-3", "group": "stairs-A", "kind": "stairs",
                                    "pos": [18.5, 9.5]}], floor["connectors"]
    # Everything else is untouched.
    for key in ("name", "attribution", "origin", "footways"):
        assert after[key] == before[key], key
    others = lambda c: [x for x in c["buildings"] if x["id"] != building]
    assert others(after) == others(before)

    # Transforms: matrix/rotate/scale compose like SVG.
    m = mul(parse_transform("translate(10 0) rotate(90)"), parse_transform("scale(2)"))
    x, y = apply(m, 1, 0)
    assert abs(x - 10) < 1e-9 and abs(y - 2) < 1e-9, (x, y)
    print("check passed")
    return 0


# ---------------------------------------------------------------- CLI

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("svg", type=Path, nargs="?", help="floor plan SVG (one floor)")
    ap.add_argument("--building", help="building id in campus.json (k1, k2, ..., d1, f1...)")
    ap.add_argument("--level", type=int, help="floor level (1 = ground floor)")
    ap.add_argument("--name", help='floor name, default "Этаж <level>"')
    ap.add_argument("--campus", type=Path, default=DEFAULT_CAMPUS, help="campus.json to update in place")
    ap.add_argument("--dry-run", action="store_true", help="print the floor JSON instead of writing")
    ap.add_argument("--check", action="store_true", help="run the self-test on tools/data/sample_floor.svg")
    args = ap.parse_args(argv)
    if args.check:
        return check()
    if not args.svg or not args.building or args.level is None:
        ap.error("svg, --building and --level are required")

    try:
        floor, warnings = convert(args.svg.read_text(encoding="utf-8"), args.level, args.name)
        for w in warnings:
            print(f"warning: {w}", file=sys.stderr)
        if args.dry_run:
            sys.stdout.reconfigure(encoding="utf-8")
            print(json.dumps(floor, ensure_ascii=False, indent=2))
            return 0
        original = args.campus.read_text(encoding="utf-8")
        campus = json.loads(original)
        replaced = insert_floor(campus, args.building, floor)
        write_campus(args.campus, campus, original)
    except PlanError as e:
        print(f"{args.svg}: {e}", file=sys.stderr)
        return 1
    print(f"{args.campus}: {'replaced' if replaced else 'added'} {args.building} level {args.level}: "
          f"{len(floor['rooms'])} rooms, {len(floor['doors'])} doors, {len(floor['connectors'])} connectors")
    return 0


if __name__ == "__main__":
    sys.exit(main())
