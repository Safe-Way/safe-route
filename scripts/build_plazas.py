# -*- coding: utf-8 -*-
"""보행자 광장(OSM에서 면으로 그린 곳)을 가로지르는 길을 만든다 (2026-11-10, A파트).

OSM은 광장을 선이 아니라 면으로 그린다(highway=pedestrian/footway + area=yes, 또는 여러 조각을 묶은 multipolygon).
load_road_network.py 는 면의 테두리만 걷는 길이 되는 것을 막으려고 area=yes 를 빼서, 광장 안을 가로지르는 길이 없었다.
그래서 문화전당 앞 광장·5·18민주광장 같은 곳을 2~4배 돌아갔다(137m → 589m 등).

만드는 법
  1. 광주 안의 보행자 광장 면을 받는다 (면 길 + multipolygon 관계, 바깥 테두리·안쪽 구멍).
  2. 입구 = 광장 테두리 점 중 도로망(road_edge)이 쓰는 점(같은 OSM 점 번호) + 광장 안에 있는 도로망 점
     + 광장 테두리에서 ENTRY_M 안의 도로망 점(둘레 보도가 테두리와 점을 같이 쓰지 않고 나란히 그려진 곳이 많아서).
  3. 입구끼리 곧게 잇되, 양 끝 ENTRY_M 을 뺀 가운데가 2m마다 모두 광장 안이고 안쪽 구멍(건물·화단) 밖인 선만 남긴다.
     찻길(CAR_ROADS)과 엇갈리는 선은 버린다(횡단보도 없이 찻길을 건너지 않게).
  4. 엔진이 지금 읽는 road_edge 가 아니라 road_edge_excluded(reason = 'plaza')에 넣는다. 넣는 순간 모든 길찾기가
     바뀌므로, 원일님이 시연 길을 비교한 뒤 엔진에서 읽게 한다. 휠체어는 높이 차이(계단·경사)를 확인하기 전까지
     쓰지 않게 tags.wheelchair = 'unknown' 으로 둔다.
  5. 면 하나 안에서만 잇기 때문에, 붙어 있는 광장 면 사이를 넘어가는 길은 생기지 않는다. 그런 곳 중 확인한 곳은
     MANUAL_LINKS 에 손으로 적어(입구 두 점 + 그 사이 꺾이는 점) 같은 표에 넣는다.
  6. 넓은 광장 가운데를 지나는 긴 선은 SPINES 에 뼈대로 적는다(11-16, 5·18민주광장). 뼈대에서 가까운 길 점·역 출구를
     가지로 붙이고, 가지 끝 두 개마다 '점 → 뼈대 → 점' 꺾인 선 하나를 넣는다. 현장에서 평지로 본 뼈대는
     tags.wheelchair = 'yes' 라 휠체어도 쓴다(원일 #266).
새 점은 만들지 않는다(입구는 모두 이미 road_node 에 있음. 꺾이는 점은 선 모양일 뿐 길찾기 점이 아님).

쓰는 법
  python3 scripts/build_plazas.py            # 받고, SQL 만들고, DB에 넣고, 시연 지역 거리 비교
  python3 scripts/build_plazas.py --dry-run  # DB에 넣지 않음 (비교는 함)
  --refresh: 받아 둔 OSM 응답(data/raw/osm/plazas_gwangju.json, spine_buildings_*.json) 대신 새로 받음
다시 돌려도 된다(plaza 줄을 지우고 새로 넣음).
"""
import heapq
import json
import math
import os
import re
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "scripts", "plaza_edges.sql")
REASON = "plaza"
BBOX = (35.0508, 126.6447, 35.2588, 127.0223)  # 광주 (load_road_network.AREA_BBOX)
OVERPASS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"]
# 11-10 확인한 문화전당 둘레 광장 7곳 (OSM)
ACC = {"way/1034349957", "way/1034349958", "way/1385124846", "way/1385128464",
       "relation/19102230#0", "relation/19108957#0", "relation/19121402#0"}
# 시연 지역에서 비교할 광장 가로지르기 (11-10 학교 서버에서 잰 지금 길이)
CHECKS = [
    ("문화전당역 쪽 광장", (35.14696, 126.92088), (35.14602, 126.91990), 589),
    ("5·18민주광장", (35.14767, 126.92069), (35.14795, 126.91889), 265),
    ("문화전당 앞 큰 광장", (35.14760, 126.92055), (35.14607, 126.91905), 561),
]
# 붙어 있는 광장 면 사이를 넘어가는 길 (손으로 확인, 5번). from·to 는 road_node 점, via 는 그 사이 꺾이는 점 (위도, 경도)
MANUAL_LINKS = [
    # 2026-11-13 원일 제안: 5·18민주광장(relation/19102230) 동쪽 끝 → 상무관 옆 북쪽 보행로 띠(way/1034349957) 가운데 →
    # 하늘마당 동쪽 입구 면(way/1034349958) → 제봉로 보도. 띠 안에 도로망 점이 없어 면마다 잇는 방식으로는 선이 없었다.
    # 띠 양 끝 가운데를 지나고 건물(OSM building)·찻길과 겹치지 않음을 확인. 163m.
    # 단순 최단 거리: 문화전당역 → 장동교차로 719 → 701m, 4번 출구 → 장동교차로 734 → 708m
    {"from": 7179003402, "to": 13052475511, "via": [(35.147759, 126.9206315), (35.148111, 126.921380)],
     "osm": "way/1034349957", "name": None},
]
# 뼈대 선 (6번). head: 손으로 그린 뼈대 꺾이는 점 (위도, 경도). 첫 점 → 둘째 점 방향을 서쪽으로 곧게 늘려
# road 선(road_edge id)과 만나는 곳이 뼈대 서쪽 끝이고, road 의 두 끝 점(road_ends)은 그 길을 따라 뼈대에 붙는다.
# east: 뼈대 동쪽 끝 도로망 점. entries: 뼈대에서 멀어도(SPINE_R_ENTRY) 붙일 광장 입구 점.
SPINES = [
    # 2026-11-16 재휘: 원일 그림(11-13)의 가운데 긴 초록 선. 서석로 쪽 광장 가장자리(3·4번 출구 북쪽) →
    # 5·18민주광장 → 상무관 옆 북쪽 보행로 띠 → 하늘마당 동쪽 입구(제봉로 보도). 뼈대 307m, 가지 끝 9개, 선 36개.
    # 휠체어 금남로 → 하늘마당 동쪽 363m (성인과 같음, 학교 서버 11-16)
    {"way_id": -399002, "spine": "5.18민주광장", "osm": "relation/19102230",
     "head": [(35.14716, 126.91912), (35.148111, 126.921380)], "east": 13052475511,
     "road": 119462, "road_ends": (13050545588, 824103424),  # 서석로 (광장 서쪽 가장자리를 따라감)
     "entries": [824103424, 13056485379, 436486786, 6175977287, 12819746050, 7179003402],
     "box": (35.1455, 126.9180, 35.1495, 126.9235),
     "max_s": 250,  # 뼈대 동쪽 끝 57m(하늘마당 동쪽)는 광장 선이 이미 촘촘해 가지를 붙이지 않음
     "wheelchair": "yes", "checked": "2026-11-16 재휘 (5·18민주광장·북쪽 보행로 평지, 현장 확인 필요)"},
]
SPINE_R, SPINE_R_ENTRY = 35, 75  # 가지 최대 길이 (길 점·출구, 광장 입구)
SPINE_LAT0 = 35.147


def path_m(pts):
    """꺾인 선 길이 (m)"""
    return sum(meters(a, b) for a, b in zip(pts, pts[1:]))


def read_env():
    env = {}
    with open(os.path.join(ROOT, ".env"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def overpass(query):
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        f.write(query)
        qfile = f.name
    for url in OVERPASS:
        out = subprocess.run(["curl", "-s", "-m", "200", "-A", "safe-route-student-project/0.1",
                              "-H", "Accept: application/json", "--data-urlencode", "data@" + qfile, url],
                             capture_output=True, text=True).stdout
        if out.startswith("{"):
            try:
                return json.loads(out)
            except json.JSONDecodeError:
                pass
        print("Overpass 서버가 바빠서 다음 서버로:", url, file=sys.stderr)
    sys.exit("Overpass 서버가 모두 바쁩니다. 잠시 뒤 다시 실행하세요.")


CACHE = os.path.join(ROOT, "data", "raw", "osm", "plazas_gwangju.json")


def fetch():
    """받아 둔 응답이 있으면 그것을 쓴다(--refresh 면 새로 받음). Overpass 가 바쁘면 쉬었다가 3번까지"""
    if os.path.exists(CACHE) and "--refresh" not in sys.argv:
        print("받아 둔 OSM 응답을 씀:", os.path.relpath(CACHE, ROOT))
        return json.load(open(CACHE, encoding="utf-8"))
    for k in range(3):
        try:
            data = fetch_once()
            break
        except SystemExit:
            if k == 2:
                raise
            print(f"  {60 * (k + 1)}초 쉬고 다시 ({k + 2}/3)", file=sys.stderr)
            time.sleep(60 * (k + 1))
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    json.dump(data, open(CACHE, "w", encoding="utf-8"), ensure_ascii=False)
    return data


def fetch_once():
    s, w, n, e = BBOX
    b = f"{s},{w},{n},{e}"
    return overpass(f"""[out:json][timeout:180];
way["highway"~"^(pedestrian|footway)$"]["area"="yes"]({b});
out body geom;
rel["highway"="pedestrian"]["type"="multipolygon"]({b});
out body;
way(r);
out body geom;""")


# ---------- 평면 계산 (광장 하나 안에서는 위도·경도를 m로 바꿔도 충분) ----------

def to_xy(lat, lon, lat0):
    return ((lon) * 111320 * math.cos(math.radians(lat0)), lat * 110540)


def meters(a, b):
    lat = math.radians((a[0] + b[0]) / 2)
    return math.hypot((b[1] - a[1]) * 111320 * math.cos(lat), (b[0] - a[0]) * 110540)


def inside(p, ring):
    """점 p(x, y)가 고리 안에 있는지 (반직선)"""
    x, y = p
    c = False
    for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]):
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            c = not c
    return c


def crosses(a, b, c, d):
    """선분 ab와 cd가 끝점이 아닌 곳에서 엇갈려 지나는지"""
    def orient(p, q, r):
        v = (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0])
        return 0 if abs(v) < 1e-6 else (1 if v > 0 else -1)
    o1, o2, o3, o4 = orient(a, b, c), orient(a, b, d), orient(c, d, a), orient(c, d, b)
    return o1 * o2 < 0 and o3 * o4 < 0


ENTRY_M = 25  # 광장 테두리에서 이 거리 안의 도로망 점은 입구 (--entry-m 으로 바꿔 볼 수 있음)
# 입구를 넓히면 찻길 건너편 보도 점도 잡히므로, 이런 찻길과 엇갈리는 선은 버린다 (횡단보도 없이 찻길을 건너지 않게)
CAR_ROADS = ("primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link", "residential",
             "unclassified", "service", "trunk", "trunk_link", "living_street")
if "--entry-m" in sys.argv:
    ENTRY_M = float(sys.argv[sys.argv.index("--entry-m") + 1])


def seg_dist(p, a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    t = 0.0 if dx == dy == 0 else max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy)))
    return math.hypot(a[0] + t * dx - p[0], a[1] + t * dy - p[1])


def ring_dist(p, ring):
    return min(seg_dist(p, c, d) for c, d in zip(ring, ring[1:] + ring[:1]))


def in_plaza(p, outer, inners):
    return inside(p, outer) and not any(inside(p, h) for h in inners)


def segment_ok(a, b, outer, inners):
    """양 끝 ENTRY_M 은 테두리 밖이어도 되고(입구가 테두리 옆 보도), 그 사이는 2m마다 광장 안·구멍 밖이어야 함"""
    length = math.hypot(b[0] - a[0], b[1] - a[1])
    if length < 3:
        return False
    k = 0.0
    while k <= length:
        if ENTRY_M <= k <= length - ENTRY_M or length < 2 * ENTRY_M:
            t = k / length
            p = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
            if not in_plaza(p, outer, inners):
                # 짧은 선은 테두리 옆(ENTRY_M 안)을 지나도 됨
                if not (length < 2 * ENTRY_M and ring_dist(p, outer) <= ENTRY_M and not any(inside(p, h) for h in inners)):
                    return False
        k += 2.0
    return True


def join_rings(ways):
    """관계의 조각 길들(점 번호 목록)을 끝점끼리 이어 닫힌 고리들로"""
    parts = [list(w) for w in ways if len(w) >= 2]
    rings = []
    while parts:
        cur = parts.pop(0)
        changed = True
        while cur[0] != cur[-1] and changed:
            changed = False
            for i, p in enumerate(parts):
                if p[0] == cur[-1]:
                    cur += p[1:]
                elif p[-1] == cur[-1]:
                    cur += p[::-1][1:]
                elif p[-1] == cur[0]:
                    cur = p[:-1] + cur
                elif p[0] == cur[0]:
                    cur = p[::-1][:-1] + cur
                else:
                    continue
                parts.pop(i)
                changed = True
                break
        if cur[0] == cur[-1] and len(cur) >= 4:
            rings.append(cur[:-1])
    return rings


def plazas(data):
    """[(이름, OSM 표시, 바깥 고리 점 번호들, 안쪽 고리들, 점 좌표 사전, tags)]"""
    coords, ways, out = {}, {}, []
    for e in data["elements"]:
        if e["type"] == "way" and "geometry" in e:
            for nid, g in zip(e["nodes"], e["geometry"]):
                coords[nid] = (g["lat"], g["lon"])
            ways[e["id"]] = e
    for e in data["elements"]:
        t = e.get("tags", {})
        if e["type"] == "way" and t.get("area") == "yes" and e["nodes"][0] == e["nodes"][-1]:
            if t.get("access") in ("no", "private") or t.get("foot") == "no":
                continue
            out.append((t.get("name"), f"way/{e['id']}", e["nodes"][:-1], [], t))
        elif e["type"] == "relation":
            if t.get("access") in ("no", "private") or t.get("foot") == "no":
                continue
            outer = [ways[m["ref"]]["nodes"] for m in e["members"] if m["type"] == "way" and m["role"] == "outer" and m["ref"] in ways]
            inner = [ways[m["ref"]]["nodes"] for m in e["members"] if m["type"] == "way" and m["role"] == "inner" and m["ref"] in ways]
            holes = join_rings(inner)
            for k, ring in enumerate(join_rings(outer)):
                out.append((t.get("name"), f"relation/{e['id']}#{k}", ring, holes, t))
    return out, coords


# ---------- DB ----------

def db_cmd(env, cnf):
    m = re.match(r"jdbc:mysql://([^:/?]+)(?::(\d+))?/([^?]+)", env["DB_URL"])
    with open(cnf, "w") as f:
        f.write("[client]\nuser=%s\npassword=%s\nhost=%s\nport=%s\n"
                % (env["DB_USERNAME"], env["DB_PASSWORD"], m.group(1), m.group(2) or "3306"))
    os.chmod(cnf, 0o600)
    return ["mysql", "--defaults-extra-file=" + cnf, "--default-character-set=utf8mb4", m.group(3)]


def query(cmd, sql):
    r = subprocess.run(cmd + ["-B", "-N", "-e", sql], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("DB 조회 실패: " + r.stderr.strip())
    return [line.split("\t") for line in r.stdout.splitlines() if line]


def car_lines_in(cmd, s, w, n, e, lat0, highways=CAR_ROADS):
    """범위 안 찻길 선들 (평면 좌표 점 목록)"""
    box = f"ST_GeomFromText('POLYGON(({w} {s},{e} {s},{e} {n},{w} {n},{w} {s}))', 0)"
    rows = query(cmd, f"""SELECT ST_AsText(geom) FROM road_edge WHERE MBRIntersects({box}, geom)
        AND highway IN ({",".join(q(h) for h in highways)})""")
    out = []
    for (wkt,) in rows:
        pts = [tuple(map(float, xy.split())) for xy in re.findall(r"[-\d.]+ [-\d.]+", wkt)]
        out.append([to_xy(lat, lon, lat0) for lon, lat in pts])
    return out


def crosses_car(a, b, cars):
    for line in cars:
        for c, d in zip(line, line[1:]):
            if crosses(a, b, c, d):
                return True
    return False


def network_nodes_in(cmd, s, w, n, e):
    """범위 안에서 도로망(road_edge)이 쓰는 점 {id: (lat, lon)}"""
    box = f"ST_GeomFromText('POLYGON(({w} {s},{e} {s},{e} {n},{w} {n},{w} {s}))', 0)"
    rows = query(cmd, f"""SELECT n.id, n.lat, n.lon FROM road_node n
        WHERE MBRIntersects({box}, n.geom)
          AND EXISTS (SELECT 1 FROM road_edge x WHERE x.from_node = n.id OR x.to_node = n.id)""")
    return {int(r[0]): (float(r[1]), float(r[2])) for r in rows}


def q(v):
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


# ---------- 뼈대 선 (6번) ----------

def spine_buildings(sp):
    """뼈대 범위의 건물 고리들 (OSM building 길, 평면 좌표). 받아 둔 응답이 있으면 그것을 씀"""
    path = os.path.join(ROOT, "data", "raw", "osm", f"spine_buildings_{-sp['way_id']}.json")
    if os.path.exists(path) and "--refresh" not in sys.argv:
        data = json.load(open(path, encoding="utf-8"))
    else:
        s, w, n, e = sp["box"]
        data = overpass(f'[out:json][timeout:60];way["building"]({s},{w},{n},{e});out geom;')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        json.dump(data, open(path, "w", encoding="utf-8"), ensure_ascii=False)
    return [[to_xy(p["lat"], p["lon"], SPINE_LAT0) for p in el["geometry"]]
            for el in data["elements"] if el["type"] == "way" and el.get("geometry")]


def spine_edges(cmd, sp):
    """뼈대 하나의 선들. 가지 끝 점 두 개마다 '점 → (가지) → 뼈대 → (가지) → 점' 꺾인 선 하나"""
    X = lambda ll: to_xy(ll[0], ll[1], SPINE_LAT0)  # noqa: E731
    s, w, n, e = sp["box"]
    box = f"ST_GeomFromText('POLYGON(({w} {s},{e} {s},{e} {n},{w} {n},{w} {s}))', 0)"
    # 붙일 후보: 찻길이 아닌 길이 쓰는 점
    nodes = {int(a): (float(b), float(c)) for a, b, c in query(cmd, f"""SELECT n.id, n.lat, n.lon FROM road_node n
        WHERE MBRIntersects({box}, n.geom) AND EXISTS (SELECT 1 FROM road_edge x
          WHERE (x.from_node = n.id OR x.to_node = n.id) AND x.highway NOT IN ({",".join(q(h) for h in CAR_ROADS)}))""")}
    known = dict(nodes)
    ids = ",".join(str(i) for i in [*sp["entries"], sp["east"], *sp["road_ends"]])
    known.update({int(a): (float(b), float(c)) for a, b, c in query(cmd, f"SELECT id, lat, lon FROM road_node WHERE id IN ({ids})")})
    for k in [*sp["entries"], sp["east"], *sp["road_ends"]]:
        if k not in known:
            sys.exit(f"SPINES {sp['spine']}: road_node 에 없는 점 {k}")
    # 지하 역 출구 (road_node_excluded, tags.exit). 엘리베이터 출구가 뼈대 옆이면 가지로 붙임
    exits = {int(a): ((float(b), float(c)), nm) for a, b, c, nm in query(cmd, f"""SELECT id, lat, lon,
        JSON_UNQUOTE(JSON_EXTRACT(tags, '$.name')) FROM road_node_excluded
        WHERE JSON_EXTRACT(tags, '$.exit') IS NOT NULL AND MBRIntersects({box}, geom)""")}
    # 광장 안 차량 통로(service)는 걸어서 건너는 곳이라 막는 찻길에서 뺌
    cars = car_lines_in(cmd, s, w, n, e, SPINE_LAT0, [h for h in CAR_ROADS if h != "service"])
    bld = spine_buildings(sp)

    # 서쪽 끝: head 를 서쪽으로 곧게 늘려 road 선과 처음 만나는 점
    wkt, frm = query(cmd, f"SELECT ST_AsText(geom), from_node FROM road_edge WHERE id = {sp['road']}")[0]
    road = [tuple(map(float, p.split()))[::-1] for p in re.findall(r"[-\d.]+ [-\d.]+", wkt)]
    if int(frm) != sp["road_ends"][0]:
        road = road[::-1]
    e0, s0 = X(sp["head"][1]), X(sp["head"][0])
    dx, dy = s0[0] - e0[0], s0[1] - e0[1]
    meet = None
    for k, (a, b) in enumerate(zip(road, road[1:])):
        pa, pb = X(a), X(b)
        rx, ry = pb[0] - pa[0], pb[1] - pa[1]
        den = dx * ry - dy * rx
        if abs(den) < 1e-9:
            continue
        t = ((pa[0] - e0[0]) * ry - (pa[1] - e0[1]) * rx) / den
        u = ((pa[0] - e0[0]) * dy - (pa[1] - e0[1]) * dx) / den
        if t > 0 and 0 <= u <= 1 and (meet is None or t < meet[0]):
            meet = (t, k, (a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u))
    if meet is None:
        sys.exit(f"SPINES {sp['spine']}: 뼈대를 늘려도 road {sp['road']} 와 만나지 않음")
    _, seg, west = meet
    spine = [west, *sp["head"][1:], known[sp["east"]]]
    segs = list(zip(spine, spine[1:]))
    cum = [0.0]
    for a, b in segs:
        cum.append(cum[-1] + meters(a, b))

    def blocked(a, b):
        """a → b 곧은 선이 건물 안(1m마다)을 지나거나 찻길과 엇갈리는지"""
        xa, xb = X(a), X(b)
        length = math.hypot(xb[0] - xa[0], xb[1] - xa[1])
        k = 1.0
        while k < length - 1:
            t = k / length
            p = (xa[0] + (xb[0] - xa[0]) * t, xa[1] + (xb[1] - xa[1]) * t)
            if any(inside(p, h) for h in bld):
                return True
            k += 1.0
        return crosses_car(xa, xb, cars)

    for a, b in segs:
        if blocked(a, b):
            sys.exit(f"SPINES {sp['spine']}: 뼈대가 건물·찻길을 지남 {a} → {b}")

    # 가지 끝: road 두 끝 점(길을 따라 서쪽 끝으로), 동쪽 끝 점, 그리고 뼈대에서 가까운 길 점·출구
    a_end, b_end = sp["road_ends"]
    ends = [{"id": k, "ll": known[k], "via": v, "q": west, "i": 0, "s": 0.0, "name": None}
            for k, v in ((a_end, road[1:seg + 1]), (b_end, list(reversed(road[seg + 1:-1]))))]
    ends.append({"id": sp["east"], "ll": known[sp["east"]], "via": [], "q": known[sp["east"]], "i": len(segs) - 1,
                 "s": cum[-1], "name": None})
    samples = []  # 뼈대 위 1m마다 점 (가지를 붙일 자리 후보)
    for i, (a, b) in enumerate(segs):
        length, k = meters(a, b), 0.0
        while k <= length:
            t = k / length
            samples.append(((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t), i, cum[i] + k))
            k += 1.0
    taken = {x["id"] for x in ends}
    pool = [(k, known[k], None) for k in sorted((set(nodes) | set(sp["entries"])) - taken)]
    pool += [(k, ll, nm) for k, (ll, nm) in sorted(exits.items())]
    for k, ll, nm in pool:
        lim = SPINE_R_ENTRY if k in sp["entries"] else SPINE_R
        # 가장 가까운, 건물·찻길을 지나지 않는 뼈대 자리 (수직이 막히면 비스듬히)
        hit = None
        for d, qq, i, sv in sorted(((meters(ll, qq), qq, i, sv) for qq, i, sv in samples), key=lambda r: r[0]):
            if d > lim:
                break
            if not blocked(ll, qq):
                hit = (d, qq, i, sv)
                break
        if not hit:
            continue
        d, qq, i, sv = hit
        # 동쪽 끝은 하늘마당 동쪽 광장 선이 촘촘함, 서쪽 끝 바깥(찻길 건너) 보도는 뺌 (출구는 붙임)
        if sv > sp["max_s"] or (sv < 1 and not nm):
            continue
        ends.append({"id": k, "ll": ll, "via": [], "q": qq, "i": i, "s": sv, "name": nm})
    ends.sort(key=lambda x: x["s"])

    def line(p, r):
        a, b = (p, r) if p["s"] <= r["s"] else (r, p)
        pts = [a["ll"], *a["via"], a["q"], *[spine[j + 1] for j in range(a["i"], b["i"])], b["q"],
               *reversed(b["via"]), b["ll"]]
        out = [pts[0]]
        for x in pts[1:]:
            if meters(out[-1], x) > 0.5:
                out.append(x)
        return out

    tags = {"area": "plaza", "osm": sp["osm"], "wheelchair": sp["wheelchair"], "source": "manual",
            "spine": sp["spine"], "checked": sp["checked"]}
    edges = []
    for i in range(len(ends)):
        for j in range(i + 1, len(ends)):
            edges.append({"way_id": sp["way_id"], "seq": len(edges), "from": ends[i]["id"], "to": ends[j]["id"],
                          "pts": line(ends[i], ends[j]), "name": None, "osm": sp["osm"], "tags": tags})
    print(f"뼈대 {sp['spine']}: {cum[-1]:.0f}m, 가지 끝 {len(ends)}개, 선 {len(edges)}개 (wheelchair {sp['wheelchair']})")
    for x in ends:
        print(f"  {x['id']} {x['name'] or ''} 뼈대 {x['s']:.0f}m 지점")
    return edges


def main():
    dry = "--dry-run" in sys.argv
    env = read_env()
    data = fetch()
    areas, coords = plazas(data)
    fd, cnf = tempfile.mkstemp(suffix=".cnf")
    os.close(fd)
    try:
        cmd = db_cmd(env, cnf)
        edges, report, acc_entries = [], [], []
        for idx, (name, osm, outer_ids, holes_ids, tags) in enumerate(areas, start=1):
            pts = [coords[i] for i in outer_ids if i in coords]
            if len(pts) < 3:
                continue
            lats = [p[0] for p in pts]
            lons = [p[1] for p in pts]
            lat0 = sum(lats) / len(lats)
            outer = [to_xy(*coords[i], lat0) for i in outer_ids]
            inners = [[to_xy(*coords[i], lat0) for i in h if i in coords] for h in holes_ids]
            nodes = network_nodes_in(cmd, min(lats) - 0.0003, min(lons) - 0.0003, max(lats) + 0.0003, max(lons) + 0.0003)
            ring_set = set(outer_ids) | {i for h in holes_ids for i in h}
            entries = {}
            for nid, ll in nodes.items():
                xy = to_xy(*ll, lat0)
                if nid in ring_set or in_plaza(xy, outer, inners) or ring_dist(xy, outer) <= ENTRY_M:
                    entries[nid] = ll
            ids = sorted(entries)
            cars = car_lines_in(cmd, min(lats) - 0.0004, min(lons) - 0.0004, max(lats) + 0.0004, max(lons) + 0.0004, lat0)
            made = 0
            for a in range(len(ids)):
                for b in range(a + 1, len(ids)):
                    pa, pb = entries[ids[a]], entries[ids[b]]
                    xa, xb = to_xy(*pa, lat0), to_xy(*pb, lat0)
                    if segment_ok(xa, xb, outer, inners) and not crosses_car(xa, xb, cars):
                        edges.append({"way_id": -(300_000 + idx), "seq": made, "from": ids[a], "to": ids[b],
                                      "pts": [pa, pb], "name": name, "osm": osm})
                        made += 1
            report.append((name or "(이름 없음)", osm, len(ids), made))
            if osm in ACC:
                acc_entries.append((name, osm, entries))

        # 손으로 확인한 면 사이 길 (5번). 두 끝이 도로망 점인지 확인하고 넣는다
        if MANUAL_LINKS:
            ids = ",".join(str(int(x)) for m in MANUAL_LINKS for x in (m["from"], m["to"]))
            known = {int(r[0]): (float(r[1]), float(r[2])) for r in query(cmd, f"""SELECT n.id, n.lat, n.lon FROM road_node n
                WHERE n.id IN ({ids}) AND EXISTS (SELECT 1 FROM road_edge x WHERE x.from_node = n.id OR x.to_node = n.id)""")}
            for k, m in enumerate(MANUAL_LINKS):
                if m["from"] not in known or m["to"] not in known:
                    sys.exit(f"MANUAL_LINKS {k}: 도로망에 없는 점 {m['from']}·{m['to']}")
                edges.append({"way_id": -399001, "seq": k, "from": m["from"], "to": m["to"],
                              "pts": [known[m["from"]], *m["via"], known[m["to"]]], "name": m["name"], "osm": m["osm"]})
            print(f"손으로 확인한 면 사이 길 {len(MANUAL_LINKS)}개")

        # 뼈대 선 (6번)
        for sp in SPINES:
            edges += spine_edges(cmd, sp)

        with open(OUT, "w", encoding="utf-8") as f:
            f.write("-- scripts/build_plazas.py 가 만든 파일. 손으로 고치지 말고 스크립트를 다시 돌린다.\n")
            f.write("-- 보행자 광장(OSM 면)을 가로지르는 길 + 손으로 확인한 면 사이 길(-399001) + 뼈대 선(-399002~) (reason plaza)\n")
            f.write("SET NAMES utf8mb4;\n")
            f.write(f"DELETE FROM road_edge_excluded WHERE reason = {q(REASON)};\n")
            for i in range(0, len(edges), 500):
                chunk = edges[i:i + 500]
                f.write("INSERT INTO road_edge_excluded (way_id, seq, from_node, to_node, length_m, highway, name, footway,"
                        " steps, covered, bridge, surface, tags, geom, reason) VALUES\n")
                f.write(",\n".join(
                    f"({e['way_id']},{e['seq']},{e['from']},{e['to']},{path_m(e['pts']):.2f},'pedestrian',"
                    f"{q(e['name']) if e['name'] else 'NULL'},NULL,0,0,0,NULL,"
                    f"{q(json.dumps(e.get('tags') or {'area': 'plaza', 'osm': e['osm'], 'wheelchair': 'unknown', 'source': 'manual'}, ensure_ascii=False))},"
                    "ST_GeomFromText('LINESTRING(" + ",".join(f"{p[1]:.7f} {p[0]:.7f}" for p in e["pts"])
                    + f")', 0),{q(REASON)})" for e in chunk) + ";\n")
            f.write(f"""INSERT INTO data_source (type, source, title, api, license, total_count, loaded_count,
                    skipped_count, complete, loaded_at)
VALUES ('road_edge_plaza', 'osm', {q('보행자 광장 가로지르기 (OSM 면 → 입구끼리 곧은 길 + 뼈대 선, build_plazas.py)')},
        'Overpass API', 'ODbL (출처 표시)', {len(edges)}, {len(edges)}, 0, 1, NOW())
ON DUPLICATE KEY UPDATE title = VALUES(title), total_count = VALUES(total_count), loaded_count = VALUES(loaded_count),
  complete = 1, loaded_at = NOW();
""")
        with_entries = [r for r in report if r[2] >= 2]
        for r in report:
            if r[1] in ACC:
                print(f"  문화전당 둘레 {r[0]} ({r[1]}): 입구 {r[2]}개, 가로지르는 길 {r[3]}개")
        print(f"광장 {len(report)}곳 (입구 2개 이상 {len(with_entries)}곳) · 가로지르는 길 {len(edges)}개")
        print("SQL:", os.path.relpath(OUT, ROOT))
        compare(cmd, edges, acc_entries)
        if dry:
            return
        with open(OUT, encoding="utf-8") as f:
            r = subprocess.run(cmd, stdin=f, capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit("DB에 넣다가 실패: " + r.stderr.strip())
        print("DB에 넣음 (road_edge_excluded, reason = plaza)")
    finally:
        os.remove(cnf)


def compare(cmd, edges, acc_entries):
    """문화전당 둘레 광장마다 입구끼리 도로망 최단 거리를 광장 길 없이·있게 비교 (엔진이 아니라 단순 최단 거리)"""
    s, w, n, e = 35.140, 126.912, 35.153, 126.928
    box = f"ST_GeomFromText('POLYGON(({w} {s},{e} {s},{e} {n},{w} {n},{w} {s}))', 0)"
    rows = query(cmd, f"SELECT from_node, to_node, length_m FROM road_edge WHERE MBRIntersects({box}, geom)")
    g = {}
    for a, b, l in rows:
        a, b, l = int(a), int(b), float(l)
        g.setdefault(a, []).append((b, l))
        g.setdefault(b, []).append((a, l))
    g2 = {k: list(v) for k, v in g.items()}
    for ed in edges:
        l = path_m(ed["pts"])
        g2.setdefault(ed["from"], []).append((ed["to"], l))
        g2.setdefault(ed["to"], []).append((ed["from"], l))

    def dists(graph, a):
        best, h = {a: 0.0}, [(0.0, a)]
        while h:
            d, u = heapq.heappop(h)
            if d > best.get(u, 1e18):
                continue
            for v, l in graph.get(u, []):
                if d + l < best.get(v, 1e18):
                    best[v] = d + l
                    heapq.heappush(h, (d + l, v))
        return best

    print("문화전당 둘레 광장: 입구끼리 도로망 거리 (지금 → 광장 길 넣으면, 단순 최단 거리)")
    for name, osm, ents in acc_entries:
        ids = sorted(ents)
        best = None
        saved = []
        for i, a in enumerate(ids):
            d1, d2 = dists(g, a), dists(g2, a)
            for b in ids[i + 1:]:
                if b in d1 and b in d2 and d2[b] < d1[b] - 1:
                    gain = d1[b] - d2[b]
                    saved.append(gain)
                    if best is None or gain > best[0]:
                        best = (gain, d1[b], d2[b], meters(ents[a], ents[b]))
        if best:
            print(f"  {name or '(이름 없음)'} ({osm}): 입구 {len(ids)}개, 짧아지는 입구 쌍 {len(saved)}개 · "
                  f"가장 크게 직선 {best[3]:.0f}m 입구끼리 {best[1]:.0f}m → {best[2]:.0f}m")
        else:
            print(f"  {name or '(이름 없음)'} ({osm}): 입구 {len(ids)}개, 짧아지는 쌍 없음")


if __name__ == "__main__":
    main()
