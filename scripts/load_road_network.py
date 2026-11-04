"""시연 지역의 걸을 수 있는 길(OSM)을 받아 길 그래프로 나눈 뒤 road_node·road_edge 표에 넣는다.

- Overpass에서 중심점 둘레 네모 범위의 길(way)과 그 점(node)을 받는다. 받은 원본은 data/raw/osm/ 에 남긴다.
- 길을 교차점(두 길 이상이 만나는 점)·끝점·표시가 있는 점(횡단보도, 신호, 엘리베이터, 보도턱)에서 잘라 구간으로 만든다.
- 걷는 방향은 양쪽 모두로 본다(일방통행은 차에만 해당).
- 같은 OSM 길·점을 다시 넣으면 지우고 새로 넣으므로 여러 번 실행해도 된다.
- 표는 먼저 만든다: src/main/resources/db/migration/007_road_network.sql
- 계단의 휠체어 경사로(wheelchair_ramp, 018)는 tags의 ramp:wheelchair·ramp로 DB가 계산하므로 여기서 따로 넣지 않는다.

실행: python3 scripts/load_road_network.py [--json 받아 둔 응답] [--sql-only] [위도 경도 반경m 이름 | --area 코드 이름]
기본: 국립아시아문화전당(35.1466, 126.9204) 둘레 2km, 이름 acc
광주 전체: python3 scripts/load_road_network.py --area KR-29 gwangju (OSM 행정구역 경계 안, 2026-11-03부터 이 범위)
DB 접속은 .env의 DB_URL·DB_USERNAME·DB_PASSWORD를 쓰고, mysql 명령으로 넣는다.
"""
import json
import math
import os
import re
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "data", "raw", "osm")
OVERPASS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
# 사람이 걸을 수 있는 길 종류. 고속도로(motorway)는 뺀다
WALKABLE = ("footway|path|pedestrian|steps|living_street|residential|service|unclassified|track|corridor|cycleway|"
            "tertiary|tertiary_link|secondary|secondary_link|primary|primary_link|trunk|trunk_link")
SPLIT_TAGS = ("highway", "crossing", "kerb", "barrier")  # 이런 표시가 붙은 점에서도 길을 자른다
EDGE_COLUMNS = {"highway", "name", "footway", "surface"}  # 칸으로 따로 두는 태그 (나머지는 tags JSON)
BATCH = 1000


def read_env():
    env = {}
    with open(os.path.join(ROOT, ".env"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    return env


# 행정구역 전체를 받을 때 쓰는 감싼 사각형 (남, 서, 북, 동). 한 번에 받으면 응답이 느려 끊기므로 칸으로 나눠 받는다
AREA_BBOX = {"KR-29": (35.0508, 126.6447, 35.2588, 127.0223)}  # 광주 (SGIS 경계)
AREA_TILES = (5, 6)  # 세로 5 × 가로 6 칸 (도심 칸이 무거워 잘게 나눔)
TILE_ROUNDS = 4      # 서버가 모두 바쁘면 쉬었다가 다시 시도하는 횟수


def overpass_retry(query, max_time):
    """서버 세 곳이 모두 실패하면 점점 길게 쉬었다가 다시 시도한다"""
    for k in range(TILE_ROUNDS):
        try:
            return overpass(query, max_time)
        except SystemExit:
            if k == TILE_ROUNDS - 1:
                raise
            wait = 60 * (k + 1)
            print(f"  {wait}초 쉬고 다시 시도 ({k + 2}/{TILE_ROUNDS})", file=sys.stderr)
            time.sleep(wait)


def overpass(query, max_time):
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        f.write(query)
        qfile = f.name
    for url in OVERPASS:
        # 파이썬 기본 SSL로는 일부 서버와 연결이 안 돼서 curl을 쓴다
        out = subprocess.run(["curl", "-s", "-m", str(max_time), "-A", "safe-route-student-project/0.1",
                              "-H", "Accept: application/json", "--data-urlencode", "data@" + qfile, url],
                             capture_output=True, text=True).stdout
        if out.startswith("{"):
            try:
                return json.loads(out)
            except json.JSONDecodeError:
                print("응답이 중간에 끊겨서 다음 서버로:", url, file=sys.stderr)
                continue
        print("서버가 바빠서 다음 서버로:", url, file=sys.stderr)
    sys.exit("Overpass 서버가 모두 바쁩니다. 잠시 뒤 다시 실행하세요.")


def fetch(s, w, n, e, area=None):
    """네모 범위(s, w, n, e) 또는 행정구역(area = ISO3166-2 코드, 예 KR-29 광주) 안의 길을 받는다"""
    if not area:
        return overpass(f"""[out:json][timeout:180];
way["highway"~"^({WALKABLE})$"]({s},{w},{n},{e});
(._;>;);
out body;
""", 240)
    # 행정구역: 감싼 사각형을 칸으로 나눠 칸마다 "경계 안 + 칸 안"의 길을 받고, 같은 길·점은 한 번만 남긴다
    s0, w0, n0, e0 = AREA_BBOX[area]
    rows, cols = AREA_TILES
    # 받은 칸은 파일로 남겨, 중간에 멈춰도 다시 실행하면 남은 칸만 받는다
    tile_dir = os.path.join(OUT, f"tiles_{area}_{rows}x{cols}")
    os.makedirs(tile_dir, exist_ok=True)
    seen, elements = set(), []
    for r in range(rows):
        for c in range(cols):
            ts, tn = s0 + (n0 - s0) * r / rows, s0 + (n0 - s0) * (r + 1) / rows
            tw, te = w0 + (e0 - w0) * c / cols, w0 + (e0 - w0) * (c + 1) / cols
            tile = os.path.join(tile_dir, f"r{r}c{c}.json")
            if os.path.exists(tile):
                with open(tile, encoding="utf-8") as f:
                    data = json.load(f)
            else:
                data = overpass_retry(f"""[out:json][timeout:180];
area["ISO3166-2"="{area}"]->.a;
way["highway"~"^({WALKABLE})$"](area.a)({ts:.5f},{tw:.5f},{tn:.5f},{te:.5f});
(._;>;);
out body;
""", 240)
                with open(tile, "w", encoding="utf-8") as f:
                    json.dump(data, f, ensure_ascii=False)
                time.sleep(5)  # 서버에 몰아서 보내지 않게
            new = 0
            for el in data["elements"]:
                k = (el["type"], el["id"])
                if k not in seen:
                    seen.add(k)
                    elements.append(el)
                    new += 1
            print(f"  칸 {r * cols + c + 1}/{rows * cols}: 새 요소 {new}개", file=sys.stderr)
    return {"elements": elements}


def excluded_reason(tags):
    """사람이 걸을 수 없는 자동차전용도로면 이유를 돌려준다 (도로망에서 빼고 road_edge_excluded에 둔다, 020)"""
    if tags.get("motorroad") == "yes":
        return "motorroad"
    if tags.get("expressway") == "yes":
        return "expressway"
    return None


def walkable(tags):
    """걸어서 지날 수 없는 길을 뺀다 (보행 금지, 사유지, 면으로 그린 광장 테두리)"""
    if tags.get("area") == "yes":
        return False
    foot = tags.get("foot")
    if foot == "no":
        return False
    if tags.get("access") in ("no", "private") and foot not in ("yes", "designated", "permissive"):
        return False
    return True


def meters(lat1, lon1, lat2, lon2):
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def build(data):
    """(점 목록, 구간 목록)을 만든다. 구간 = 교차점·끝점·표시가 있는 점 사이"""
    nodes = {e["id"]: e for e in data["elements"] if e["type"] == "node"}
    ways = [e for e in data["elements"] if e["type"] == "way" and walkable(e.get("tags", {}))]
    used = {}
    for w in ways:
        for nid in set(w["nodes"]):
            used[nid] = used.get(nid, 0) + 1

    def is_split(nid):
        tags = nodes.get(nid, {}).get("tags", {})
        return used.get(nid, 0) >= 2 or any(k in tags for k in SPLIT_TAGS)

    edges, vertex_ids = [], set()
    for w in ways:
        ids = [nid for nid in w["nodes"] if nid in nodes]
        if len(ids) < 2:
            continue
        tags = w.get("tags", {})
        start, seq = 0, 0
        for i in range(1, len(ids)):
            if i == len(ids) - 1 or is_split(ids[i]):
                part = ids[start:i + 1]
                pts = [(nodes[x]["lon"], nodes[x]["lat"]) for x in part]
                length = sum(meters(pts[j][1], pts[j][0], pts[j + 1][1], pts[j + 1][0]) for j in range(len(pts) - 1))
                if length > 0:
                    edges.append({"way_id": w["id"], "seq": seq, "from": part[0], "to": part[-1],
                                  "length": length, "tags": tags, "pts": pts})
                    vertex_ids.update((part[0], part[-1]))
                    seq += 1
                start = i
    vertices = [nodes[v] for v in vertex_ids]
    return vertices, edges, len(ways)


def split_excluded(vertices, edges):
    """자동차전용도로 구간을 따로 떼어 낸다. 남은 길에 쓰이는 점만 남긴다"""
    kept = [e for e in edges if not excluded_reason(e["tags"])]
    dropped = [e for e in edges if excluded_reason(e["tags"])]
    used = {x for e in kept for x in (e["from"], e["to"])}
    return [v for v in vertices if v["id"] in used], kept, dropped


def q(v):
    """SQL 문자열 값"""
    if v is None:
        return "NULL"
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def edge_row(e):
    t = e["tags"]
    covered = 1 if t.get("covered") == "yes" or t.get("indoor") == "yes" or t.get("tunnel") not in (None, "no") else 0
    bridge = 1 if t.get("bridge") not in (None, "no") else 0
    rest = {k: v for k, v in t.items() if k not in EDGE_COLUMNS}
    line = "LINESTRING(" + ",".join(f"{lon:.7f} {lat:.7f}" for lon, lat in e["pts"]) + ")"
    return (f"({e['way_id']},{e['seq']},{e['from']},{e['to']},{e['length']:.2f},{q(t.get('highway'))},"
            f"{q(t.get('name'))},{q(t.get('footway'))},{1 if t.get('highway') == 'steps' else 0},{covered},{bridge},"
            f"{q(t.get('surface'))},{q(json.dumps(rest, ensure_ascii=False)) if rest else 'NULL'},"
            f"ST_GeomFromText('{line}', 0))")


def node_row(n):
    tags = n.get("tags")
    return (f"({n['id']},{n['lon']:.7f},{n['lat']:.7f},ST_SRID(POINT({n['lon']:.7f},{n['lat']:.7f}),0),"
            f"{q(json.dumps(tags, ensure_ascii=False)) if tags else 'NULL'})")


def write_sql(path, vertices, edges, title, total_ways, dropped=()):
    way_ids = sorted({e["way_id"] for e in edges} | {e["way_id"] for e in dropped})
    with open(path, "w", encoding="utf-8") as f:
        f.write("SET NAMES utf8mb4;\n")
        # 같은 길을 다시 넣을 때를 위해 먼저 지운다
        for i in range(0, len(way_ids), BATCH):
            ids = ",".join(map(str, way_ids[i:i + BATCH]))
            f.write(f"DELETE FROM road_edge WHERE way_id IN ({ids});\n")
            f.write(f"DELETE FROM road_edge_excluded WHERE way_id IN ({ids});\n")
        for i in range(0, len(vertices), BATCH):
            f.write("INSERT INTO road_node (id, lon, lat, geom, tags) VALUES\n"
                    + ",\n".join(node_row(n) for n in vertices[i:i + BATCH])
                    + "\nON DUPLICATE KEY UPDATE lon = VALUES(lon), lat = VALUES(lat), geom = VALUES(geom), tags = VALUES(tags);\n")
        for i in range(0, len(edges), BATCH):
            f.write("INSERT INTO road_edge (way_id, seq, from_node, to_node, length_m, highway, name, footway, steps,"
                    " covered, bridge, surface, tags, geom) VALUES\n"
                    + ",\n".join(edge_row(e) for e in edges[i:i + BATCH]) + ";\n")
        for i in range(0, len(dropped), BATCH):
            f.write("INSERT INTO road_edge_excluded (way_id, seq, from_node, to_node, length_m, highway, name, footway,"
                    " steps, covered, bridge, surface, tags, geom, reason) VALUES\n"
                    + ",\n".join(edge_row(e)[:-1] + f",{q(excluded_reason(e['tags']))})" for e in dropped[i:i + BATCH])
                    + ";\n")
        for typ, n in (("road_node", len(vertices)), ("road_edge", len(edges))):
            f.write(f"""INSERT INTO data_source (type, source, title, api, license, total_count, loaded_count,
                    skipped_count, complete, loaded_at)
VALUES ('{typ}', 'osm', {q(title)}, 'Overpass API (overpass-api.de)', 'ODbL (출처 표시)', {n}, {n}, 0, 1, NOW())
ON DUPLICATE KEY UPDATE title = VALUES(title), api = VALUES(api), license = VALUES(license),
  total_count = VALUES(total_count), loaded_count = VALUES(loaded_count), skipped_count = 0, complete = 1, loaded_at = NOW();
""")


def run_sql(path):
    env = read_env()
    m = re.match(r"jdbc:mysql://([^:/]+)(?::(\d+))?/([^?]+)", env["DB_URL"])
    cmd = ["mysql", "--default-character-set=utf8mb4", "-h", m.group(1), "-P", m.group(2) or "3306",
           "-u", env["DB_USERNAME"], m.group(3)]
    with open(path, encoding="utf-8") as f:
        r = subprocess.run(cmd, stdin=f, env=dict(os.environ, MYSQL_PWD=env["DB_PASSWORD"]))
    if r.returncode != 0:
        sys.exit("DB에 넣다가 실패했습니다. 위 오류를 확인하세요.")


def main():
    args = sys.argv[1:]
    cached = None
    if args[:1] == ["--json"]:
        cached, args = args[1], args[2:]
    sql_only = "--sql-only" in args
    args = [a for a in args if a != "--sql-only"]
    area = None
    if args[:1] == ["--area"]:
        # 행정구역 전체: --area KR-29 gwangju
        area, name = args[1], args[2]
        lat = lon = radius = s = w = n = e = None
    else:
        lat, lon, radius, name = ((float(args[0]), float(args[1]), int(args[2]), args[3]) if len(args) >= 4
                                  else (35.1466, 126.9204, 2000, "acc"))
        dlat = radius / 111320
        dlon = radius / (111320 * math.cos(math.radians(lat)))
        s, w, n, e = lat - dlat, lon - dlon, lat + dlat, lon + dlon
    os.makedirs(OUT, exist_ok=True)

    if cached:
        with open(cached, encoding="utf-8") as f:
            data = json.load(f)
    else:
        data = (fetch(None, None, None, None, area=area) if area
                else fetch(round(s, 6), round(w, 6), round(n, 6), round(e, 6)))
        raw = os.path.join(OUT, f"overpass_{name}.json")
        with open(raw, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
        print("받은 원본:", raw)

    vertices, edges, total_ways = build(data)
    vertices, edges, dropped = split_excluded(vertices, edges)
    if dropped:
        km = sum(e["length"] for e in dropped) / 1000
        print(f"자동차전용도로 구간 {len(dropped)}개({km:.1f}km)는 road_edge_excluded로 뺌 (motorroad·expressway=yes)")
    steps = sum(1 for x in edges if x["tags"].get("highway") == "steps")
    crossings = sum(1 for v in vertices if v.get("tags", {}).get("highway") == "crossing")
    print(f"길 {total_ways}개 → 구간 {len(edges)}개, 점 {len(vertices)}개 (계단 구간 {steps}, 횡단보도 점 {crossings})")

    title = (f"OpenStreetMap 도로망 ({name}: 행정구역 {area} 전체)" if area
             else f"OpenStreetMap 도로망 ({name}: {lat}, {lon} 둘레 {radius}m)")
    sql = os.path.join(OUT, f"road_network_{name}.sql")
    write_sql(sql, vertices, edges, title, total_ways, dropped)
    print("SQL:", sql)
    if not sql_only:
        run_sql(sql)
        print("DB 적재 끝")


if __name__ == "__main__":
    main()
