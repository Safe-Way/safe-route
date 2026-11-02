"""road_edge의 길마다 감점 원값을 계산해 edge_stat 표에 넣는다 (요구사항 6.5 A파트 답 C1~C6, 김재휘 안).

계산하는 값
- light_n / light_cover_m : 길에서 30m 안 가로등·보안등 수, 길이 중 등 30m 안에 드는 길이
- cctv_n / cctv_cover_m   : 길에서 50m 안 CCTV 위치 수, 길이 중 CCTV 50m 안에 드는 길이
- help_m / guard_m / rest_m : 길 가운데에서 가장 가까운 도움 지점·지킴이집·쉼 곳까지 거리 (2km 넘으면 NULL)
- heat_m / cold_m        : 가장 가까운 무더위쉼터·한파쉼터까지 거리 (폭염·한파 가중치용, 2km 넘으면 NULL)
- *_n_1km                 : 길 가운데 반경 1km 안 개수 (지역 밀도 기준 A5)
- xing_*                  : 이 길에서 건너는 횡단보도의 신호별 수 (C4: 횡단 구간 footway=crossing, 없으면
                            차도가 아닌 보행 길이 끝에 횡단 점을 가진 경우)
- curb                    : 건너는 횡단보도의 가장 나쁜 보도턱 (OSM kerb, 없으면 횡단보도 표준데이터 15m 안)
- footbridge_ok           : 육교 위 보행 길이면 육교 표준데이터 40m 안의 휠체어 통과 여부
- road_class              : 차도는 자기 종류, 차도 옆 인도(footway=sidewalk)는 15m 안 가장 큰 차도 종류 (C5)
- sgg_cd / dong_cd        : 길 시작점이 있는 시군구·행정동 (admin_area)
- data_gap                : 아직 판정하지 않음 (전국 시군구 경계를 넣은 뒤 구 단위로 판정, A4)

같은 종류 시설이 10m 안에 겹치면 하나로 센다(보안등은 출처 세 곳이 겹침, CCTV는 두 곳).
거리는 범위 가운데 위도 기준 평면 근사(수 km 안 오차 0.1% 아래)로 잰다.
여러 번 실행해도 된다 (계산한 길은 지우고 새로 넣음).

실행: python3 scripts/build_edge_stat.py [--sql-only]
"""
import math
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "data", "raw", "osm", "edge_stat.sql")
LIGHT_R, CCTV_R, SAMPLE = 30.0, 50.0, 5.0
NEAR_CAP, DENSITY_R, DEDUP = 2000.0, 1000.0, 10.0
XING_STD_R, BRIDGE_R, SIDEWALK_R = 15.0, 40.0, 15.0
ROADS = {"trunk": 6, "trunk_link": 6, "primary": 5, "primary_link": 5, "secondary": 4, "secondary_link": 4,
         "tertiary": 3, "tertiary_link": 3, "unclassified": 2, "residential": 2, "living_street": 1, "service": 1}
SIGNAL = {"traffic_signals": "signal", "uncontrolled": "nosignal", "marked": "nosignal", "unmarked": "nosignal",
          "zebra": "nosignal", "no": "nosignal", "informal": "nosignal"}


# ---------- DB ----------

def env():
    e = {}
    with open(os.path.join(ROOT, ".env"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                e[k.strip()] = v.strip()
    return e


def mysql_cmd(extra):
    e = env()
    m = re.match(r"jdbc:mysql://([^:/]+)(?::(\d+))?/([^?]+)", e["DB_URL"])
    return (["mysql", "--default-character-set=utf8mb4", "--max-allowed-packet=256M", "-h", m.group(1),
             "-P", m.group(2) or "3306", "-u", e["DB_USERNAME"], m.group(3)] + extra,
            dict(os.environ, MYSQL_PWD=e["DB_PASSWORD"]))


def query(sql):
    cmd, en = mysql_cmd(["-B", "-N", "-e", sql])
    r = subprocess.run(cmd, capture_output=True, text=True, env=en)
    if r.returncode != 0:
        sys.exit("DB 조회 실패: " + r.stderr)
    return [line.split("\t") for line in r.stdout.splitlines() if line]


def run_file(path):
    cmd, en = mysql_cmd([])
    with open(path, encoding="utf-8") as f:
        r = subprocess.run(cmd, stdin=f, env=en)
    if r.returncode != 0:
        sys.exit("DB에 넣다가 실패했습니다.")


def num(v):
    return None if v in (None, "NULL", "") else v


# ---------- 평면 좌표와 격자 ----------

class Plane:
    def __init__(self, lat0, lon0):
        self.lat0, self.lon0 = lat0, lon0
        self.kx = 111320.0 * math.cos(math.radians(lat0))
        self.ky = 110574.0

    def xy(self, lon, lat):
        return ((lon - self.lon0) * self.kx, (lat - self.lat0) * self.ky)


class Grid:
    def __init__(self, pts, cell):
        self.cell, self.cells = cell, {}
        for p in pts:
            self.cells.setdefault((int(p[0] // cell), int(p[1] // cell)), []).append(p)

    def near(self, x, y, r):
        c, out = self.cell, []
        for gx in range(int((x - r) // c), int((x + r) // c) + 1):
            for gy in range(int((y - r) // c), int((y + r) // c) + 1):
                out.extend(self.cells.get((gx, gy), ()))
        return out


def dedup(pts, d=DEDUP):
    """d m 안에 겹치는 점은 하나로"""
    kept, grid = [], {}
    for p in pts:
        k = (int(p[0] // d), int(p[1] // d))
        hit = False
        for gx in (k[0] - 1, k[0], k[0] + 1):
            for gy in (k[1] - 1, k[1], k[1] + 1):
                for q in grid.get((gx, gy), ()):
                    if (q[0] - p[0]) ** 2 + (q[1] - p[1]) ** 2 <= d * d:
                        hit = True
                        break
                if hit:
                    break
            if hit:
                break
        if not hit:
            grid.setdefault(k, []).append(p)
            kept.append(p)
    return kept


def seg_dist(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    L = dx * dx + dy * dy
    t = 0.0 if L == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def line_dist(p, line):
    return min(seg_dist(p[0], p[1], *line[i], *line[i + 1]) for i in range(len(line) - 1))


def samples(line, step=SAMPLE):
    """길 위 step m 간격 점 (양 끝 포함)과 길이"""
    pts, total = [line[0]], 0.0
    for i in range(len(line) - 1):
        (ax, ay), (bx, by) = line[i], line[i + 1]
        seg = math.hypot(bx - ax, by - ay)
        n = max(1, int(seg // step))
        for k in range(1, n + 1):
            t = k / n
            pts.append((ax + t * (bx - ax), ay + t * (by - ay)))
        total += seg
    return pts, total


def midpoint(line):
    _, total = samples(line, 1e9)
    half, acc = total / 2, 0.0
    for i in range(len(line) - 1):
        (ax, ay), (bx, by) = line[i], line[i + 1]
        seg = math.hypot(bx - ax, by - ay)
        if acc + seg >= half and seg > 0:
            t = (half - acc) / seg
            return (ax + t * (bx - ax), ay + t * (by - ay))
        acc += seg
    return line[0]


def nearest(grid, p, cap):
    """cap m 안에서 가장 가까운 점까지 거리. 반경을 넓혀 가며 찾고, 그 반경 안에서 찾은 값만 믿는다"""
    for r in (100.0, 200.0, 400.0, 800.0, 1600.0, cap):
        cand = grid.near(p[0], p[1], r)
        if cand:
            d = min(math.hypot(q[0] - p[0], q[1] - p[1]) for q in cand)
            if d <= r:
                return d if d <= cap else None
    return None


def count_within(grid, p, r):
    return sum(1 for q in grid.near(p[0], p[1], r) if (q[0] - p[0]) ** 2 + (q[1] - p[1]) ** 2 <= r * r)


def parse_line(wkt):
    inner = wkt[wkt.index("(") + 1: wkt.rindex(")")]
    return [tuple(map(float, xy.split())) for xy in inner.split(",")]


# ---------- 계산 ----------

def main():
    sql_only = "--sql-only" in sys.argv
    print("도로망 읽는 중...")
    edges = query("SELECT id, highway, IFNULL(footway,''), bridge, from_node, to_node, "
                  "IFNULL(tags->>'$.crossing',''), ST_AsText(geom) FROM road_edge")
    if not edges:
        sys.exit("road_edge가 비어 있습니다. 먼저 scripts/load_road_network.py 를 실행하세요.")
    nodes = {int(r[0]): r for r in query(
        "SELECT id, lon, lat, IFNULL(highway,''), IFNULL(crossing,''), IFNULL(kerb,'') FROM road_node")}
    lines_ll = {int(e[0]): parse_line(e[7]) for e in edges}
    lons = [p[0] for ln in lines_ll.values() for p in ln]
    lats = [p[1] for ln in lines_ll.values() for p in ln]
    w, s, e_, n = min(lons), min(lats), max(lons), max(lats)
    plane = Plane((s + n) / 2, (w + e_) / 2)
    pad_lat, pad_lon = (NEAR_CAP + 100) / 110574.0, (NEAR_CAP + 100) / plane.kx
    box = f"lon BETWEEN {w - pad_lon} AND {e_ + pad_lon} AND lat BETWEEN {s - pad_lat} AND {n + pad_lat}"

    def pts(table, where=""):
        rows = query(f"SELECT lon, lat FROM {table} WHERE {box} {where}")
        return [plane.xy(float(r[0]), float(r[1])) for r in rows]

    print("시설 읽는 중...")
    lights = dedup(pts("streetlight")) + dedup(pts("security_light"))
    cctv = dedup(pts("cctv"))
    help_pts = pts("police") + pts("convenience_store") + pts("child_safety_house") + pts("emergency_bell")
    guard = pts("child_safety_house")
    heat, cold = pts("heat_shelter"), pts("cold_shelter")
    rest = heat + cold + pts("public_toilet")
    std_x = [(plane.xy(float(r[0]), float(r[1])), num(r[2]), num(r[3])) for r in
             query(f"SELECT lon, lat, IFNULL(traffic_light,'NULL'), IFNULL(curb_lowered,'NULL') FROM crosswalk WHERE {box}")]
    bridges = [(plane.xy(float(r[0]), float(r[1])), int(r[2])) for r in
               query(f"SELECT lon, lat, wheelchair_ok FROM footbridge WHERE {box}")]
    print(f"  등 {len(lights)}, CCTV {len(cctv)}, 도움 지점 {len(help_pts)}, 지킴이집 {len(guard)}, 쉼 곳 {len(rest)}, "
          f"무더위쉼터 {len(heat)}, 한파쉼터 {len(cold)}, 횡단 표준 {len(std_x)}, 육교 {len(bridges)}")

    g_light, g_cctv = Grid(lights, 50), Grid(cctv, 50)
    g_help, g_guard, g_rest = Grid(help_pts, 200), Grid(guard, 200), Grid(rest, 200)
    g_heat, g_cold = Grid(heat, 200), Grid(cold, 200)
    g_std = Grid([(p[0][0], p[0][1], i) for i, p in enumerate(std_x)], 50)

    lines = {eid: [plane.xy(lon, lat) for lon, lat in ln] for eid, ln in lines_ll.items()}
    # 큰 차도 선분 (인도에 차도 등급을 물려줄 때)
    road_lines = [(ROADS[e[1]], e[1], lines[int(e[0])]) for e in edges if e[1] in ROADS and ROADS[e[1]] >= 3]
    road_grid = Grid([(p[0], p[1], i) for i, (_, _, ln) in enumerate(road_lines) for p in ln], 100)

    def crossing_state(node_id):
        """횡단 점의 (신호 상태, 보도턱) — OSM 먼저, 모르면 표준데이터 15m 안"""
        nd = nodes.get(node_id)
        sig, curb = SIGNAL.get(nd[4]) if nd else None, None
        if nd and nd[5]:
            curb = 1 if nd[5] == "raised" else 0 if nd[5] in ("lowered", "flush", "no") else None
        if nd and (sig is None or curb is None):
            x, y = plane.xy(float(nd[1]), float(nd[2]))
            best = None
            for q in g_std.near(x, y, XING_STD_R):
                d = math.hypot(q[0] - x, q[1] - y)
                if d <= XING_STD_R and (best is None or d < best[0]):
                    best = (d, std_x[q[2]])
            if best:
                tl, cl = best[1][1], best[1][2]
                if sig is None and tl is not None:
                    sig = "signal" if tl == "1" else "nosignal"
                if curb is None and cl is not None:
                    curb = 0 if cl == "1" else 1
        return sig or "unknown", curb

    out_rows = []
    for i, e in enumerate(edges):
        eid, hw, fw, bridge = int(e[0]), e[1], e[2], int(e[3])
        ln = lines[eid]
        sp, length = samples(ln)
        # 등·CCTV: 30m·50m 안 수와 덮는 길이
        cand_l = {q for p in sp for q in g_light.near(p[0], p[1], LIGHT_R)}
        light_n = sum(1 for q in cand_l if line_dist(q, ln) <= LIGHT_R)
        cand_c = {q for p in sp for q in g_cctv.near(p[0], p[1], CCTV_R)}
        cctv_n = sum(1 for q in cand_c if line_dist(q, ln) <= CCTV_R)

        def cover(grid, r):
            hit = sum(1 for p in sp if any((q[0] - p[0]) ** 2 + (q[1] - p[1]) ** 2 <= r * r for q in grid.near(p[0], p[1], r)))
            return length * hit / len(sp)

        light_cover, cctv_cover = cover(g_light, LIGHT_R), cover(g_cctv, CCTV_R)
        mid = midpoint(ln)
        help_m, guard_m, rest_m = nearest(g_help, mid, NEAR_CAP), nearest(g_guard, mid, NEAR_CAP), nearest(g_rest, mid, NEAR_CAP)
        help_n, guard_n, rest_n = (count_within(g_help, mid, DENSITY_R), count_within(g_guard, mid, DENSITY_R),
                                   count_within(g_rest, mid, DENSITY_R))
        heat_m, cold_m = nearest(g_heat, mid, NEAR_CAP), nearest(g_cold, mid, NEAR_CAP)
        heat_n, cold_n = count_within(g_heat, mid, DENSITY_R), count_within(g_cold, mid, DENSITY_R)
        # 횡단: 횡단 구간이면 그 길의 신호 태그와 횡단 점, 아니면 보행 길에 붙은 횡단 점.
        # 횡단 점에서 길이 둘로 잘리므로 그 점이 '도착 점'인 쪽 하나에만 센다 (한 번 건너는데 두 번 세지 않게)
        xs = {"signal": 0, "nosignal": 0, "unknown": 0}
        curbs = []
        is_road = hw in ROADS
        from_n, to_n = int(e[4]), int(e[5])

        def is_x(nid):
            return bool(nodes.get(nid)) and nodes[nid][3] == "crossing"

        if fw == "crossing":
            sig_way = SIGNAL.get(e[6])
            if is_x(to_n):
                st, cb = crossing_state(to_n)
                xs[sig_way or st] += 1
                curbs.append(cb)
            elif not is_x(from_n):  # 횡단 점이 없는 횡단 구간 (잘리지 않은 한 덩어리)
                xs[sig_way or "unknown"] += 1
        elif not is_road and is_x(to_n):
            st, cb = crossing_state(to_n)
            xs[st] += 1
            curbs.append(cb)
        known = [c for c in curbs if c is not None]
        curb = max(known) if known else None
        # 육교 위 보행 길
        fb = None
        if bridge and not is_road:
            for (bx, by), ok in bridges:
                if line_dist((bx, by), ln) <= BRIDGE_R:
                    fb = ok if fb is None else min(fb, ok)
        # 큰 차도 등급: 차도는 자기 종류, 인도는 15m 안 가장 큰 차도
        rc = hw if is_road else None
        if fw == "sidewalk":
            best = None
            for q in road_grid.near(mid[0], mid[1], SIDEWALK_R + 100):
                rank, name, rln = road_lines[q[2]]
                if (best is None or rank > best[0]) and line_dist(mid, rln) <= SIDEWALK_R:
                    best = (rank, name)
            rc = best[1] if best else None
        out_rows.append((eid, light_n, light_cover, cctv_n, cctv_cover, help_m, guard_m, rest_m, heat_m, cold_m,
                         help_n, guard_n, rest_n, heat_n, cold_n, xs["signal"], xs["nosignal"], xs["unknown"],
                         curb, fb, rc))
        if (i + 1) % 2000 == 0:
            print(f"  {i + 1}/{len(edges)}")

    def v(x, fmt="{:.1f}"):
        if x is None:
            return "NULL"
        if isinstance(x, str):
            return "'" + x.replace("'", "''") + "'"
        return fmt.format(x) if isinstance(x, float) else str(x)

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("SET NAMES utf8mb4;\nDELETE FROM edge_stat WHERE edge_id IN (SELECT id FROM road_edge);\n")
        for k in range(0, len(out_rows), 1000):
            f.write("INSERT INTO edge_stat (edge_id, light_n, light_cover_m, cctv_n, cctv_cover_m, help_m, guard_m, "
                    "rest_m, heat_m, cold_m, help_n_1km, guard_n_1km, rest_n_1km, heat_n_1km, cold_n_1km, xing_signal, xing_nosignal, xing_unknown, curb, "
                    "footbridge_ok, road_class, computed_at) VALUES\n"
                    + ",\n".join("(" + ",".join(v(x) for x in r) + ",NOW())" for r in out_rows[k:k + 1000]) + ";\n")
        # 시작점이 있는 행정동·시군구
        f.write("""UPDATE edge_stat s JOIN road_edge e ON e.id = s.edge_id
JOIN admin_area a ON a.level = 'dong' AND MBRContains(a.geom, ST_StartPoint(e.geom)) AND ST_Contains(a.geom, ST_StartPoint(e.geom))
SET s.dong_cd = a.code, s.sgg_cd = LEFT(a.code, 5);
INSERT INTO data_source (type, source, title, api, license, total_count, loaded_count, skipped_count, complete, loaded_at)
VALUES ('edge_stat', 'computed', '길별 감점 원값 (scripts/build_edge_stat.py)', 'road_edge + 시설 표', '-', """
                + f"{len(out_rows)}, {len(out_rows)}, 0, 1, NOW())\n"
                + "ON DUPLICATE KEY UPDATE total_count = VALUES(total_count), loaded_count = VALUES(loaded_count), loaded_at = NOW();\n")
    print(f"길 {len(out_rows)}개 계산 | SQL: {OUT}")
    if not sql_only:
        run_file(OUT)
        print("DB 적재 끝")


if __name__ == "__main__":
    main()
