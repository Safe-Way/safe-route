"""시군구별 시설 밀도를 세어 region_density 표에 넣고, 길마다 데이터 부족 비트(edge_stat.data_gap)를 채운다.

판정 (요구사항 6.3 "데이터 없음", 6.5 A4·C3, 김재휘 안)
- 비트 1 밝기, 2 CCTV, 4 도움 지점, 8 지킴이집, 16 쉼 곳:
  시군구 경계 안 시설 수 ÷ 넓이(km²)가 전국 시군구 중간값의 10% 아래면 그 시군구의 모든 길에 켠다.
- 캠퍼스 구역: 대학 안 조명·CCTV는 학교가 관리해 공공데이터에 없다. OSM 대학 구역 안의 길 중
  30m 안에 등이 하나도 없으면 비트 1, 50m 안에 CCTV가 없으면 비트 2를 켠다.
- 비트 32 횡단 신호: 이 길에서 건너는 횡단 중 신호를 모르는 것이 있다.
- 비트 64 보도턱: 건너는 횡단이 있는데 보도턱을 모른다.
- 0이면 부족한 것이 없다.

먼저 할 일
1. 표 만들기: src/main/resources/db/migration/011_region_density.sql
2. 전국 시군구 경계:  python3 scripts/load_boundaries.py all --levels sido,sigungu
3. 길별 원값:  python3 scripts/build_edge_stat.py

실행: python3 scripts/build_data_gap.py   (대학 구역은 Overpass에서 받아 data/raw/osm/campus.json에 남기고 다시 씀)
"""
import json
import os
import statistics
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_edge_stat import ROOT, query, run_file  # noqa: E402

KINDS = {  # 종류: (비트, 표 목록)
    "light": (1, ["streetlight", "security_light"]),
    "cctv": (2, ["cctv"]),
    "help": (4, ["police", "convenience_store", "child_safety_house", "emergency_bell"]),
    "guard": (8, ["child_safety_house"]),
    "rest": (16, ["heat_shelter", "cold_shelter", "public_toilet"]),
}
GAP_RATIO = 0.1
CAMPUS_JSON = os.path.join(ROOT, "data", "raw", "osm", "campus.json")
OVERPASS = ["https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter"]


CELL = 200  # 격자 칸 = 1/200도 (약 450m × 550m)


def parse_polys(wkt):
    """POLYGON·MULTIPOLYGON WKT → [[고리, ...], ...]  고리 = [(경도, 위도), ...]"""
    body = wkt[wkt.index("("):]
    polys = []
    for p in body.strip("()").split(")),(("):
        polys.append([[tuple(map(float, xy.split())) for xy in ring.strip("()").split(",")]
                      for ring in p.split("),(")])
    return polys


def inside(x, y, rings):
    """짝홀 규칙 (구멍 고리도 같이 처리)"""
    hit = False
    for ring in rings:
        for i in range(len(ring) - 1):
            (x1, y1), (x2, y2) = ring[i], ring[i + 1]
            if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
                hit = not hit
    return hit


def region_density():
    """시군구마다 종류별 밀도와 판정.
    점 수백만 개를 다각형마다 DB에서 세면 너무 느려서(무더위쉼터 5.8만 건에 5분), 시설을 약 500m 격자 칸으로
    묶어 받고 칸 가운데가 드는 시군구에 그 칸의 수를 더한다. 경계는 약 100m로 단순화한다. 밀도 판정용이라 충분하다."""
    sgg, polys = {}, []
    for code, area, wkt in query("SELECT code, ST_Area(geom) * 111.32 * 111.32 * COS(RADIANS(ST_Y(ST_Centroid(geom))))"
                                 ", ST_AsText(ST_Simplify(geom, 0.001)) FROM admin_area WHERE level = 'sigungu'"):
        sgg[code] = float(area)
        for rings in parse_polys(wkt):
            xs = [p[0] for r in rings for p in r]
            ys = [p[1] for r in rings for p in r]
            polys.append((min(xs), min(ys), max(xs), max(ys), rings, code))
    if len(sgg) < 200:
        sys.exit(f"시군구 경계가 {len(sgg)}개뿐입니다. 먼저 전국 경계를 넣으세요: "
                 "python3 scripts/load_boundaries.py all --levels sido,sigungu")
    cell_sgg = {}

    def which(cx, cy):
        key = (cx, cy)
        if key not in cell_sgg:
            x, y = (cx + 0.5) / CELL, (cy + 0.5) / CELL
            cell_sgg[key] = next((c for x0, y0, x1, y1, rings, c in polys
                                  if x0 <= x <= x1 and y0 <= y <= y1 and inside(x, y, rings)), None)
        return cell_sgg[key]

    counts, lost = {}, {}
    tables = sorted({t for _, ts in KINDS.values() for t in ts})
    for t in tables:
        rows = query(f"SELECT FLOOR(lon * {CELL}), FLOOR(lat * {CELL}), COUNT(*) FROM {t} "
                     "WHERE lon IS NOT NULL GROUP BY 1, 2")
        for cx, cy, n in rows:
            c = which(int(cx), int(cy))
            if c is None:
                lost[t] = lost.get(t, 0) + int(n)
            else:
                counts[(c, t)] = counts.get((c, t), 0) + int(n)
        print(f"  {t}: 칸 {len(rows)}개, 경계 밖(바다 등) {lost.get(t, 0)}건")
    rows, gaps = [], {}
    for kind, (_, ts) in KINDS.items():
        dens = {c: sum(counts.get((c, t), 0) for t in ts) / area for c, area in sgg.items()}
        med = statistics.median(dens.values())
        for c, d in dens.items():
            ratio = d / med if med > 0 else None
            gap = 1 if ratio is not None and ratio < GAP_RATIO else 0
            gaps[(c, kind)] = gap
            n = sum(counts.get((c, t), 0) for t in ts)
            rows.append(f"('{c}','{kind}',{n},{sgg[c]:.3f},{d:.4f},{'NULL' if ratio is None else f'{ratio:.4f}'},{gap},NOW())")
        print(f"  {kind}: 전국 중간값 {med:.2f}개/km², 부족 시군구 {sum(gaps[(c, kind)] for c in sgg)}곳")
    return rows, gaps


def overpass(q):
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        f.write(q)
        qfile = f.name
    for url in OVERPASS:
        out = subprocess.run(["curl", "-s", "-m", "120", "-A", "safe-route-student-project/0.1",
                              "--data-urlencode", "data@" + qfile, url], capture_output=True, text=True).stdout
        if out.startswith("{"):
            return json.loads(out)
    sys.exit("Overpass 서버가 모두 바쁩니다. 잠시 뒤 다시 실행하세요.")


def campus_wkt():
    """도로망 범위 안 대학 구역을 MULTIPOLYGON WKT로 (경도 위도)"""
    if os.path.exists(CAMPUS_JSON):
        data = json.load(open(CAMPUS_JSON, encoding="utf-8"))
    else:
        w, s, e, n = map(float, query("SELECT MIN(ST_X(geom)), MIN(ST_Y(geom)), MAX(ST_X(geom)), MAX(ST_Y(geom)) "
                                      "FROM road_node")[0])
        data = overpass(f'[out:json][timeout:90];(way["amenity"~"^(university|college)$"]({s},{w},{n},{e});'
                        f'relation["amenity"~"^(university|college)$"]({s},{w},{n},{e}););out geom;')
        os.makedirs(os.path.dirname(CAMPUS_JSON), exist_ok=True)
        json.dump(data, open(CAMPUS_JSON, "w", encoding="utf-8"), ensure_ascii=False)
    polys, names = [], []
    for el in data["elements"]:
        rings = []
        if el["type"] == "way" and "geometry" in el:
            rings = [el["geometry"]]
        elif el["type"] == "relation":
            rings = [m["geometry"] for m in el.get("members", []) if m.get("role") == "outer" and "geometry" in m]
        for g in rings:
            pts = [(p["lon"], p["lat"]) for p in g]
            if len(pts) >= 4 and pts[0] == pts[-1]:
                polys.append("((" + ",".join(f"{x:.7f} {y:.7f}" for x, y in pts) + "))")
        if rings:
            names.append(el.get("tags", {}).get("name", "?"))
    return ("MULTIPOLYGON(" + ",".join(polys) + ")") if polys else None, names


def main():
    print("시군구별 시설 밀도 계산...")
    rows, gaps = region_density()
    wkt, names = campus_wkt()
    print(f"대학 구역 {len(names)}곳: {', '.join(names)}")
    out = os.path.join(ROOT, "data", "raw", "osm", "data_gap.sql")
    with open(out, "w", encoding="utf-8") as f:
        f.write("SET NAMES utf8mb4;\nDELETE FROM region_density;\n")
        for k in range(0, len(rows), 500):
            f.write("INSERT INTO region_density (sgg_cd, kind, n, area_km2, density, ratio, gap, computed_at) VALUES\n"
                    + ",\n".join(rows[k:k + 500]) + ";\n")
        bits = " + ".join(f"IF(EXISTS(SELECT 1 FROM region_density r WHERE r.sgg_cd = s.sgg_cd AND r.kind = '{kind}' "
                          f"AND r.gap = 1), {bit}, 0)" for kind, (bit, _) in KINDS.items())
        f.write(f"UPDATE edge_stat s SET s.data_gap = {bits}\n"
                " + IF(s.xing_unknown > 0, 32, 0)\n"
                " + IF(s.xing_signal + s.xing_nosignal + s.xing_unknown > 0 AND s.curb IS NULL, 64, 0);\n")
        if wkt:
            f.write("SET @campus = ST_GeomFromText('" + wkt + "', 0);\n"
                    "UPDATE edge_stat s JOIN road_edge e ON e.id = s.edge_id\n"
                    "SET s.data_gap = s.data_gap | IF(s.light_n = 0, 1, 0) | IF(s.cctv_n = 0, 2, 0)\n"
                    "WHERE ST_Contains(@campus, ST_Centroid(e.geom));\n")
        f.write("INSERT INTO data_source (type, source, title, api, license, total_count, loaded_count, skipped_count,"
                " complete, loaded_at)\nVALUES ('region_density', 'computed', '시군구별 시설 밀도 (scripts/build_data_gap.py)',"
                f" 'admin_area + 시설 표', '-', {len(rows)}, {len(rows)}, 0, 1, NOW())\n"
                "ON DUPLICATE KEY UPDATE total_count = VALUES(total_count), loaded_count = VALUES(loaded_count),"
                " loaded_at = NOW();\n")
    run_file(out)
    print("DB 적재 끝 | SQL:", out)


if __name__ == "__main__":
    main()
