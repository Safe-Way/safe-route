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
새 점은 만들지 않는다(입구는 모두 이미 road_node 에 있음).

쓰는 법
  python3 scripts/build_plazas.py            # 받고, SQL 만들고, DB에 넣고, 시연 지역 거리 비교
  python3 scripts/build_plazas.py --dry-run  # DB에 넣지 않음 (비교는 함)
  --refresh: 받아 둔 OSM 응답(data/raw/osm/plazas_gwangju.json) 대신 새로 받음
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


def car_lines_in(cmd, s, w, n, e, lat0):
    """범위 안 찻길 선들 (평면 좌표 점 목록)"""
    box = f"ST_GeomFromText('POLYGON(({w} {s},{e} {s},{e} {n},{w} {n},{w} {s}))', 0)"
    rows = query(cmd, f"""SELECT ST_AsText(geom) FROM road_edge WHERE MBRIntersects({box}, geom)
        AND highway IN ({",".join(q(h) for h in CAR_ROADS)})""")
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

        with open(OUT, "w", encoding="utf-8") as f:
            f.write("-- scripts/build_plazas.py 가 만든 파일. 손으로 고치지 말고 스크립트를 다시 돌린다.\n")
            f.write("-- 보행자 광장(OSM 면)을 가로지르는 길. 엔진은 원일님이 확인한 뒤 읽는다 (reason plaza)\n")
            f.write("SET NAMES utf8mb4;\n")
            f.write(f"DELETE FROM road_edge_excluded WHERE reason = {q(REASON)};\n")
            for i in range(0, len(edges), 500):
                chunk = edges[i:i + 500]
                f.write("INSERT INTO road_edge_excluded (way_id, seq, from_node, to_node, length_m, highway, name, footway,"
                        " steps, covered, bridge, surface, tags, geom, reason) VALUES\n")
                f.write(",\n".join(
                    f"({e['way_id']},{e['seq']},{e['from']},{e['to']},{meters(*e['pts']):.2f},'pedestrian',"
                    f"{q(e['name']) if e['name'] else 'NULL'},NULL,0,0,0,NULL,"
                    f"{q(json.dumps({'area': 'plaza', 'osm': e['osm'], 'wheelchair': 'unknown', 'source': 'manual'}, ensure_ascii=False))},"
                    f"ST_GeomFromText('LINESTRING({e['pts'][0][1]:.7f} {e['pts'][0][0]:.7f},"
                    f"{e['pts'][1][1]:.7f} {e['pts'][1][0]:.7f})', 0),{q(REASON)})" for e in chunk) + ";\n")
            f.write(f"""INSERT INTO data_source (type, source, title, api, license, total_count, loaded_count,
                    skipped_count, complete, loaded_at)
VALUES ('road_edge_plaza', 'osm', {q('보행자 광장 가로지르기 (OSM 면 → 입구끼리 곧은 길, build_plazas.py)')},
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
        l = meters(*ed["pts"])
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
