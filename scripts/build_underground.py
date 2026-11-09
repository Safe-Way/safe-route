# -*- coding: utf-8 -*-
"""비 오는 날 쓸 지하 길(역 대합실·지하상가)을 손으로 만든 길로 넣는다 (2026-11-09, 진행표 w04·j23).

OSM에는 시연 지역의 덮인 길이 0m라서(금남로 지하상가가 없음) 비 오는 날 '덮인 길 먼저'가 차이를 못 낸다.
그래서 우리가 가진 자료로 지하 길을 직접 만든다. 출처는 data_source에 '직접 그림'으로 남기고 발표에서도 밝힌다.

  1. 역마다: 지상 출구 점 → 대합실을 계단으로 잇는다. 엘리베이터가 있는 출구(subway_entrance.elevator = 1,
     결정 51 현장 확인)는 엘리베이터 길을 하나 더 둔다. 휠체어는 계단을 빼므로 엘리베이터 출구로만 드나든다.
     대합실은 역 좌표 한 점으로 두고, 한 역의 출구는 모두 대합실로 이어진다고 본다(유료 구역 밖에서).
  2. 지하상가: MALLS의 두 역 대합실을 지하 통로(corridor)로 잇는다. 가게는 닫아도 통로는 늘 열려 있다(팀 확인).

지금 엔진이 읽는 road_edge가 아니라 road_edge_excluded(reason = 'underground')에 넣는다.
맑은 날 길에 지하가 섞이면(지하에선 GPS가 끊김) 시연 길 안내가 깨질 수 있어서, 엔진(원일 w04)이 비 올 때만 읽는다.
새로 만든 점(출구·대합실·통로 점)은 road_node_excluded(reason = 'underground')에 넣는다.
출구 점은 지상 길과 아직 이어져 있지 않다: 도로망 점은 길이 갈라지는 곳에만 있어 출구 옆을 지나는 길도 점은 멀 수 있다.
그래서 출구 점 tags에 가장 가까운 걷는 길(attach_edge = road_edge.id, attach_m = 거리)을 적어 두고,
엔진이 불러올 때 출발·도착처럼 그 길 위 수직의 발에 붙인다(w09 RoadGraph와 같은 방식).

쓰는 법:
  python3 scripts/build_underground.py            # SQL을 만들고 DB에 넣음
  python3 scripts/build_underground.py --dry-run  # SQL만 만들고 요약을 보여 줌
다시 돌려도 된다(underground 줄을 지우고 새로 넣음).
"""
import json
import math
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "scripts", "underground_edges.sql")
REASON = "underground"
SOURCE = "직접 그림 (2026-11-09, 역 출구·엘리베이터는 결정 51 자료, 지하상가 범위는 팀 확인)"

# 지하상가: 두 역의 대합실을 잇는다. 점(lat, lon)은 그 사이에 지나는 곳 (없으면 곧은 선)
MALLS = [
    {"name": "금남지하상가", "from": "문화전당", "to": "금남로4가", "via": []},
]

# 출구 좌표는 지도에 그린 길에서 27~60m 떨어진 곳이 있다 (문화전당역은 출구가 아시아문화광장으로 나오는데 광장 안 보행로가 OSM에 없음).
# 그래서 65m까지 붙이고, 붙인 거리는 출구 점 tags.attach_m에 남긴다
NEAR_WALK_M = 65   # 출구에서 이 거리 안의 걷는 길(인도·보행로 등)에 붙인다
NEAR_ANY_M = 65    # 걷는 길이 없으면 이 거리 안의 아무 길에
PIECE_M = 100      # 지하상가 통로를 이 길이쯤으로 나눈다
WALK_HIGHWAYS = ("footway", "pedestrian", "path", "steps", "living_street", "corridor", "service", "residential")

# 새 점·길 번호는 음수로 둔다 (OSM 번호와 겹치지 않게)
def concourse_id(station_id):
    return -(1_000_000 + station_id)


def exit_node_id(station_id, exit_no):
    return -(3_000_000 + station_id * 100 + int(exit_no))


def mall_node_id(mall_index, k):
    return -(2_000_000 + mall_index * 1000 + k)


def exit_way_id(station_id, exit_no, elevator):
    return -(10_000 + station_id * 100 + int(exit_no) * 2 + (1 if elevator else 0))


def mall_way_id(mall_index):
    return -(90_000 + mall_index)


def read_env():
    env = {}
    with open(os.path.join(ROOT, ".env"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def mysql_cmd(env, cnf):
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


def meters(a, b):
    """(lat, lon) 두 점 사이 거리(m)"""
    lat = math.radians((a[0] + b[0]) / 2)
    dx = (b[1] - a[1]) * 111_320 * math.cos(lat)
    dy = (b[0] - a[0]) * 110_540
    return math.hypot(dx, dy)


def q(v):
    if v is None:
        return "NULL"
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def line_wkt(pts):
    return "LINESTRING(" + ",".join(f"{lon:.7f} {lat:.7f}" for lat, lon in pts) + ")"


def length(pts):
    return sum(meters(pts[i], pts[i + 1]) for i in range(len(pts) - 1))


def seg_dist(p, a, b):
    """(lat, lon) 점 p에서 선분 ab까지 거리(m)"""
    lat = math.radians(p[0])
    kx, ky = 111_320 * math.cos(lat), 110_540
    px, py = 0.0, 0.0
    ax, ay = (a[1] - p[1]) * kx, (a[0] - p[0]) * ky
    bx, by = (b[1] - p[1]) * kx, (b[0] - p[0]) * ky
    dx, dy = bx - ax, by - ay
    t = 0.0 if dx == dy == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(ax + t * dx - px, ay + t * dy - py)


def nearest_edge(cmd, lat, lon):
    """출구에서 가장 가까운 지상 길: 걷는 길 먼저, 없으면 아무 길 (NEAR_*_M 안). (edge_id, 거리 m) 또는 None"""
    d = 0.0008  # 약 80m
    box = (f"ST_GeomFromText('POLYGON(({lon - d} {lat - d},{lon + d} {lat - d},{lon + d} {lat + d},"
           f"{lon - d} {lat + d},{lon - d} {lat - d}))', 0)")
    rows = query(cmd, f"""
        SELECT id, (highway IN ({",".join(q(h) for h in WALK_HIGHWAYS)}) OR footway IS NOT NULL) AS walk,
               ST_AsText(geom)
        FROM road_edge WHERE MBRIntersects({box}, geom)""")
    best = None
    for eid, walk, wkt in rows:
        pts = [tuple(map(float, xy.split()))[::-1] for xy in re.findall(r"[-\d.]+ [-\d.]+", wkt)]
        dist = min(seg_dist((lat, lon), pts[i], pts[i + 1]) for i in range(len(pts) - 1))
        if dist > (NEAR_WALK_M if walk == "1" else NEAR_ANY_M):
            continue
        key = (0 if walk == "1" else 1, dist)
        if best is None or key < best[0]:
            best = (key, int(eid), dist)
    return None if best is None else best[1:]


def main():
    dry = "--dry-run" in sys.argv
    env = read_env()
    fd, cnf = tempfile.mkstemp(suffix=".cnf")
    os.close(fd)
    try:
        cmd = mysql_cmd(env, cnf)
        stations = {int(r[0]): {"id": int(r[0]), "name": r[1], "lat": float(r[2]), "lon": float(r[3])}
                    for r in query(cmd, "SELECT station_id, name, lat, lon FROM subway_station")}
        exits = query(cmd, """SELECT station_id, exit_no, COALESCE(elevator, 0), lat, lon
                              FROM subway_entrance WHERE station_id IS NOT NULL AND exit_no REGEXP '^[0-9]+$'
                              ORDER BY station_id, exit_no + 0""")

        nodes, edges, skipped = [], [], []
        for s in stations.values():
            nodes.append({"id": concourse_id(s["id"]), "lat": s["lat"], "lon": s["lon"],
                          "tags": {"name": s["name"] + "역 대합실", "layer": "-1", "indoor": "yes"}})
        for sid, exit_no, elevator, lat, lon in exits:
            s = stations[int(sid)]
            ex = (float(lat), float(lon))
            near = nearest_edge(cmd, *ex)
            label = f"{s['name']}역 {exit_no}번 출구"
            if near is None:
                skipped.append(label)
                continue
            nid = exit_node_id(s["id"], exit_no)
            nodes.append({"id": nid, "lat": ex[0], "lon": ex[1],
                          "tags": {"name": label, "station": s["name"], "exit": exit_no,
                                   "elevator": "yes" if elevator == "1" else "no",
                                   "attach_edge": near[0], "attach_m": round(near[1], 1)}})
            pts = [ex, (s["lat"], s["lon"])]
            base = {"layer": "-1", "station": s["name"], "exit": exit_no, "source": "manual"}
            edges.append({"way_id": exit_way_id(s["id"], exit_no, False), "from": nid, "to": concourse_id(s["id"]),
                          "pts": pts, "highway": "steps", "name": label + " 계단", "steps": 1,
                          "tags": base})
            if elevator == "1":
                edges.append({"way_id": exit_way_id(s["id"], exit_no, True), "from": nid,
                              "to": concourse_id(s["id"]), "pts": pts, "highway": "elevator",
                              "name": label + " 엘리베이터", "steps": 0, "tags": dict(base, elevator="yes")})

        by_name = {s["name"]: s for s in stations.values()}
        for i, m in enumerate(MALLS, start=1):
            a, b = by_name[m["from"]], by_name[m["to"]]
            corner = [(a["lat"], a["lon"])] + list(m["via"]) + [(b["lat"], b["lon"])]
            # 통로를 PIECE_M쯤으로 나눈다
            pts = [corner[0]]
            for p, r in zip(corner, corner[1:]):
                n = max(1, round(meters(p, r) / PIECE_M))
                pts += [(p[0] + (r[0] - p[0]) * k / n, p[1] + (r[1] - p[1]) * k / n) for k in range(1, n + 1)]
            ids = [concourse_id(a["id"])]
            for k in range(1, len(pts) - 1):
                ids.append(mall_node_id(i, k))
                nodes.append({"id": ids[-1], "lat": pts[k][0], "lon": pts[k][1],
                              "tags": {"name": m["name"], "layer": "-1", "indoor": "yes"}})
            ids.append(concourse_id(b["id"]))
            for k in range(len(pts) - 1):
                edges.append({"way_id": mall_way_id(i), "seq": k, "from": ids[k], "to": ids[k + 1],
                              "pts": [pts[k], pts[k + 1]], "highway": "corridor", "name": m["name"], "steps": 0,
                              "tags": {"layer": "-1", "indoor": "yes", "source": "manual",
                                       "note": "가게는 닫아도 통로는 늘 열림 (팀 확인 2026-11-09)"}})

        with open(OUT, "w", encoding="utf-8") as f:
            f.write("-- scripts/build_underground.py 가 만든 파일. 손으로 고치지 말고 스크립트를 다시 돌린다.\n")
            f.write("-- 비 오는 날 쓸 지하 길: 역 출구 계단·엘리베이터 → 대합실, 지하상가 통로. 엔진은 비 올 때만 읽는다 (w04)\n")
            f.write("SET NAMES utf8mb4;\n")
            f.write(f"DELETE FROM road_edge_excluded WHERE reason = {q(REASON)};\n")
            f.write(f"DELETE FROM road_node_excluded WHERE reason = {q(REASON)};\n")
            f.write("INSERT INTO road_node_excluded (id, lon, lat, geom, tags, reason) VALUES\n")
            f.write(",\n".join(
                f"({n['id']},{n['lon']:.7f},{n['lat']:.7f},ST_SRID(POINT({n['lon']:.7f},{n['lat']:.7f}),0),"
                f"{q(json.dumps(n['tags'], ensure_ascii=False))},{q(REASON)})" for n in nodes) + ";\n")
            f.write("INSERT INTO road_edge_excluded (way_id, seq, from_node, to_node, length_m, highway, name, footway,"
                    " steps, covered, bridge, surface, tags, geom, reason) VALUES\n")
            f.write(",\n".join(
                f"({e['way_id']},{e.get('seq', 0)},{e['from']},{e['to']},{length(e['pts']):.2f},{q(e['highway'])},"
                f"{q(e['name'])},NULL,{e['steps']},1,0,'paved',{q(json.dumps(e['tags'], ensure_ascii=False))},"
                f"ST_GeomFromText('{line_wkt(e['pts'])}', 0),{q(REASON)})" for e in edges) + ";\n")
            f.write(f"""INSERT INTO data_source (type, source, title, api, license, total_count, loaded_count,
                    skipped_count, complete, loaded_at)
VALUES ('road_edge_underground', 'manual', {q(SOURCE)}, '없음 (직접 그림)', '팀이 직접 그림', {len(edges) + len(skipped)}, {len(edges)},
        {len(skipped)}, 1, NOW())
ON DUPLICATE KEY UPDATE title = VALUES(title), total_count = VALUES(total_count), loaded_count = VALUES(loaded_count),
  skipped_count = VALUES(skipped_count), complete = 1, loaded_at = NOW();
""")

        steps = [e for e in edges if e["highway"] == "steps"]
        elev = [e for e in edges if e["highway"] == "elevator"]
        mall = [e for e in edges if e["highway"] == "corridor"]
        print(f"역 대합실 {len(stations)}곳, 출구 계단 {len(steps)}개, 엘리베이터 {len(elev)}개, "
              f"지하상가 통로 {len(mall)}구간 {sum(length(e['pts']) for e in mall):.0f}m")
        if skipped:
            print("가까운 길이 없어 뺀 출구:", ", ".join(skipped))
        print("SQL:", os.path.relpath(OUT, ROOT))
        if dry:
            return
        with open(OUT, encoding="utf-8") as f:
            r = subprocess.run(cmd, stdin=f, capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit("DB에 넣다가 실패: " + r.stderr.strip())
        print("DB에 넣음 (road_edge_excluded·road_node_excluded, reason = underground)")
    finally:
        os.remove(cnf)


if __name__ == "__main__":
    main()
