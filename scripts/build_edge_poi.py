"""길마다 50m 안 시설(poi, 등 제외)을 edge_poi 표에 넣는다 (017_edge_poi.sql).

- dist_m: 길(선)에서 시설까지 가장 가까운 거리, pos: 그 가장 가까운 점이 길의 어디쯤인지 (from_node 0 → to_node 1)
- 서버는 경로의 길 id 목록으로 이 표를 읽어 시설 목록을 만들고, 길 순서와 pos로 "몇 m 앞"을 계산한다
- poi를 다시 만들면(build_poi.py) id가 바뀌므로 이것도 다시 돌린다 (표를 비우고 새로 넣음, 수십 초)

실행: python3 scripts/build_edge_poi.py [--sql-only]
"""
import math
import os
import re
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_edge_stat import ROOT, query, run_file  # noqa: E402

OUT = os.path.join(ROOT, "data", "raw", "osm", "edge_poi.sql")
R = 50.0
CELL = 100.0


def main():
    edges = query("SELECT id, ST_AsText(geom) FROM road_edge")
    lines = {}
    xs, ys = [], []
    for eid, wkt in edges:
        pts = [tuple(map(float, p.split())) for p in re.search(r"\((.*)\)", wkt).group(1).split(",")]
        lines[eid] = pts
        xs += [p[0] for p in pts]
        ys += [p[1] for p in pts]
    lat0 = (min(ys) + max(ys)) / 2
    kx, ky = 111320.0 * math.cos(math.radians(lat0)), 110540.0
    pois = query(f"SELECT id, kind, lon, lat FROM poi WHERE kind <> 'light' AND lon BETWEEN {min(xs) - 0.001} AND "
                 f"{max(xs) + 0.001} AND lat BETWEEN {min(ys) - 0.001} AND {max(ys) + 0.001}")
    grid = defaultdict(list)
    for pid, kind, lon, lat in pois:
        x, y = float(lon) * kx, float(lat) * ky
        grid[(int(x // CELL), int(y // CELL))].append((pid, kind, x, y))

    rows = []
    for eid, pts in lines.items():
        P = [(x * kx, y * ky) for x, y in pts]
        seg = [math.dist(P[i], P[i + 1]) for i in range(len(P) - 1)]
        total = sum(seg) or 1.0
        x0, x1 = min(p[0] for p in P) - R, max(p[0] for p in P) + R
        y0, y1 = min(p[1] for p in P) - R, max(p[1] for p in P) + R
        seen = set()
        for gx in range(int(x0 // CELL), int(x1 // CELL) + 1):
            for gy in range(int(y0 // CELL), int(y1 // CELL) + 1):
                for pid, kind, x, y in grid.get((gx, gy), ()):
                    if pid in seen:
                        continue
                    seen.add(pid)
                    best, along, acc = None, 0.0, 0.0
                    for i in range(len(P) - 1):
                        (ax, ay), (bx, by) = P[i], P[i + 1]
                        dx, dy = bx - ax, by - ay
                        L2 = dx * dx + dy * dy
                        t = 0.0 if L2 == 0 else max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / L2))
                        d = math.hypot(ax + t * dx - x, ay + t * dy - y)
                        if best is None or d < best:
                            best, along = d, acc + t * seg[i]
                        acc += seg[i]
                    if best is not None and best <= R:
                        rows.append(f"({eid},{pid},'{kind}',{best:.1f},{along / total:.3f})")
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("DELETE FROM edge_poi;\n")
        for k in range(0, len(rows), 2000):
            f.write("INSERT INTO edge_poi (edge_id, poi_id, kind, dist_m, pos) VALUES\n" + ",\n".join(rows[k:k + 2000]) + ";\n")
    print(f"길 {len(lines)}개, 시설 {len(pois)}곳 → 묶음 {len(rows)}줄 | SQL: {OUT}")
    if "--sql-only" not in sys.argv:
        run_file(OUT)
        print("DB 적재 끝")


if __name__ == "__main__":
    main()
