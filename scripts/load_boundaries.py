"""통계청 SGIS 행정구역 경계(SHP)를 위도·경도로 바꿔 admin_area 표에 넣는다.

- SHP·DBF를 직접 읽는다 (설치할 도구 없음). 좌표는 UTM-K(EPSG:5179)를 WGS84 위도·경도로 바꾼다.
- 기본은 광주(SGIS 코드 24)의 시도 1개·시군구·행정동만 넣는다. 다른 시도는 코드를 준다 (예: 11 서울).
- 같은 구역을 다시 넣으면 지우고 새로 넣으므로 여러 번 실행해도 된다.
- 표는 먼저 만든다: src/main/resources/db/migration/008_admin_area.sql
- 원본 폴더 기본값: data/raw/boundary/ (SGIS에서 받은 "2. 경계" 폴더를 그대로 둔다)

실행: python3 scripts/load_boundaries.py [--sql-only] [시도코드 ...] [--dir 경계 폴더]
DB 접속은 .env의 DB_URL·DB_USERNAME·DB_PASSWORD를 쓰고, mysql 명령으로 넣는다.
"""
import glob
import math
import os
import re
import struct
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEVELS = [  # (표의 level, 파일 이름 앞부분, 코드 칸, 이름 칸)
    ("sido", "bnd_sido", "SIDO_CD", "SIDO_NM"),
    ("sigungu", "bnd_sigungu", "SIGUNGU_CD", "SIGUNGU_NM"),
    ("dong", "bnd_dong", "ADM_CD", "ADM_NM"),
]

# ---------- UTM-K(EPSG:5179) → WGS84 ----------
A = 6378137.0
F = 1 / 298.257222101  # GRS80
E2 = F * (2 - F)
EP2 = E2 / (1 - E2)
K0, LAT0, LON0, FE, FN = 0.9996, math.radians(38.0), math.radians(127.5), 1000000.0, 2000000.0


def _meridian(phi):
    e4, e6 = E2 * E2, E2 * E2 * E2
    return A * ((1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi
                - (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * math.sin(2 * phi)
                + (15 * e4 / 256 + 45 * e6 / 1024) * math.sin(4 * phi)
                - (35 * e6 / 3072) * math.sin(6 * phi))


M0 = _meridian(LAT0)


def utmk_to_lonlat(x, y):
    """횡메르카토르 역변환 (Snyder 식). 국내 범위에서 오차는 1cm 아래"""
    m = M0 + (y - FN) / K0
    mu = m / (A * (1 - E2 / 4 - 3 * E2 ** 2 / 64 - 5 * E2 ** 3 / 256))
    e1 = (1 - math.sqrt(1 - E2)) / (1 + math.sqrt(1 - E2))
    phi1 = (mu + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * math.sin(2 * mu)
            + (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * math.sin(4 * mu)
            + (151 * e1 ** 3 / 96) * math.sin(6 * mu) + (1097 * e1 ** 4 / 512) * math.sin(8 * mu))
    s, c, t = math.sin(phi1), math.cos(phi1), math.tan(phi1)
    c1, t1 = EP2 * c * c, t * t
    n1 = A / math.sqrt(1 - E2 * s * s)
    r1 = A * (1 - E2) / (1 - E2 * s * s) ** 1.5
    d = (x - FE) / (n1 * K0)
    lat = phi1 - (n1 * t / r1) * (d ** 2 / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 ** 2 - 9 * EP2) * d ** 4 / 24
                                  + (61 + 90 * t1 + 298 * c1 + 45 * t1 ** 2 - 252 * EP2 - 3 * c1 ** 2) * d ** 6 / 720)
    lon = LON0 + (d - (1 + 2 * t1 + c1) * d ** 3 / 6
                  + (5 - 2 * c1 + 28 * t1 - 3 * c1 ** 2 + 8 * EP2 + 24 * t1 ** 2) * d ** 5 / 120) / c
    return math.degrees(lon), math.degrees(lat)


# ---------- SHP·DBF 읽기 ----------

def read_dbf(path):
    with open(path, "rb") as f:
        h = f.read(32)
        n, hl, rl = struct.unpack("<I", h[4:8])[0], struct.unpack("<H", h[8:10])[0], struct.unpack("<H", h[10:12])[0]
        fields = []
        while True:
            d = f.read(32)
            if d[0] == 0x0D:
                break
            fields.append((d[:11].split(b"\0")[0].decode(), d[16]))
        f.seek(hl)
        rows = []
        for _ in range(n):
            r = f.read(rl)
            pos, row = 1, {}
            for name, ln in fields:
                row[name] = r[pos:pos + ln].decode("utf-8").strip()
                pos += ln
            rows.append(row)
    return rows


def read_polygon(shp, shx, index):
    """index번째 도형의 고리(ring) 목록. 고리 = [(x, y), ...]"""
    shx.seek(100 + index * 8)
    offset = struct.unpack(">i", shx.read(4))[0] * 2
    shp.seek(offset + 8)
    shape_type = struct.unpack("<i", shp.read(4))[0]
    if shape_type == 0:
        return []
    if shape_type not in (5, 15, 25):
        raise ValueError(f"면(polygon)이 아닌 도형: {shape_type}")
    shp.read(32)  # 범위
    num_parts, num_points = struct.unpack("<ii", shp.read(8))
    parts = list(struct.unpack(f"<{num_parts}i", shp.read(4 * num_parts))) + [num_points]
    pts = struct.unpack(f"<{2 * num_points}d", shp.read(16 * num_points))
    return [[(pts[2 * i], pts[2 * i + 1]) for i in range(parts[p], parts[p + 1])] for p in range(num_parts)]


def signed_area(ring):
    return sum(ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1] for i in range(len(ring) - 1)) / 2


def inside(pt, ring):
    x, y = pt
    hit = False
    for i in range(len(ring) - 1):
        (x1, y1), (x2, y2) = ring[i], ring[i + 1]
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            hit = not hit
    return hit


def to_multipolygon(rings):
    """SHP 고리를 바깥 테두리(시계 방향)와 구멍(반시계)으로 묶어 MULTIPOLYGON WKT로 (경도 위도)"""
    outers, holes = [], []
    for r in rings:
        if len(r) < 4:
            continue
        (outers if signed_area(r) < 0 else holes).append(r)
    polys = [[o] for o in outers]
    for h in holes:
        owner = next((p for p in polys if inside(h[0], p[0])), None)
        if owner is not None:
            owner.append(h)
    out = []
    for poly in polys:
        rings_txt = []
        for r in poly:
            ll, last = [], None
            for x, y in r:
                lon, lat = utmk_to_lonlat(x, y)
                p = (round(lon, 7), round(lat, 7))
                if p != last:  # 반올림으로 겹친 점은 하나로
                    ll.append(p)
                    last = p
            if ll[0] != ll[-1]:
                ll.append(ll[0])
            if len(ll) >= 4:
                rings_txt.append("(" + ",".join(f"{lon} {lat}" for lon, lat in ll) + ")")
        if rings_txt:
            out.append("(" + ",".join(rings_txt) + ")")
    return "MULTIPOLYGON(" + ",".join(out) + ")"


# ---------- DB ----------

def q(v):
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def run_sql(path):
    env = {}
    with open(os.path.join(ROOT, ".env"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    m = re.match(r"jdbc:mysql://([^:/]+)(?::(\d+))?/([^?]+)", env["DB_URL"])
    cmd = ["mysql", "--default-character-set=utf8mb4", "--max-allowed-packet=256M", "-h", m.group(1),
           "-P", m.group(2) or "3306", "-u", env["DB_USERNAME"], m.group(3)]
    with open(path, encoding="utf-8") as f:
        r = subprocess.run(cmd, stdin=f, env=dict(os.environ, MYSQL_PWD=env["DB_PASSWORD"]))
    if r.returncode != 0:
        sys.exit("DB에 넣다가 실패했습니다. 위 오류를 확인하세요.")


def main():
    args = sys.argv[1:]
    sql_only = "--sql-only" in args
    base = os.path.join(ROOT, "data", "raw", "boundary")
    if "--dir" in args:
        i = args.index("--dir")
        base = args[i + 1]
        del args[i:i + 2]
    sidos = [a for a in args if a.isdigit()] or ["24"]

    names = {}  # 코드 → 이름 (전체 이름 만들 때)
    out_path = os.path.join(ROOT, "data", "raw", "boundary", "admin_area_" + "_".join(sidos) + ".sql")
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    counts = {}
    with open(out_path, "w", encoding="utf-8") as out:
        out.write("SET NAMES utf8mb4;\n")
        for level, prefix, code_col, name_col in LEVELS:
            shp_path = glob.glob(os.path.join(base, "**", prefix + "_*.shp"), recursive=True)
            if not shp_path:
                sys.exit(f"{prefix} SHP 파일이 {base} 아래에 없습니다.")
            shp_path = shp_path[0]
            rows = read_dbf(shp_path[:-4] + ".dbf")
            with open(shp_path, "rb") as shp, open(shp_path[:-4] + ".shx", "rb") as shx:
                for idx, row in enumerate(rows):
                    code, name = row[code_col], row[name_col]
                    if not any(code.startswith(s) for s in sidos):
                        continue
                    names[code] = name
                    full = " ".join(names[c] for c in (code[:2], code[:5], code)[: {"sido": 1, "sigungu": 2, "dong": 3}[level]]
                                    if c in names)
                    wkt = to_multipolygon(read_polygon(shp, shx, idx))
                    out.write(f"DELETE FROM admin_area WHERE level = '{level}' AND code = {q(code)};\n")
                    out.write("INSERT INTO admin_area (level, code, name, full_name, base_date, geom) VALUES "
                              f"('{level}', {q(code)}, {q(name)}, {q(full)}, {q(row['BASE_DATE'])}, "
                              f"ST_GeomFromText('{wkt}', 0));\n")
                    counts[level] = counts.get(level, 0) + 1
        for level, n in counts.items():
            out.write(f"""INSERT INTO data_source (type, source, title, api, license, total_count, loaded_count,
                    skipped_count, complete, loaded_at)
VALUES ('admin_area_{level}', 'sgis', '통계청 SGIS 행정구역 경계 ({level}, 시도 {",".join(sidos)})',
        'SGIS 행정구역 통계 및 경계 (2025년 2분기)', '확인 필요', {n}, {n}, 0, 1, NOW())
ON DUPLICATE KEY UPDATE title = VALUES(title), total_count = VALUES(total_count),
  loaded_count = VALUES(loaded_count), complete = 1, loaded_at = NOW();
""")
    print("구역 수:", counts, "| SQL:", out_path, f"({os.path.getsize(out_path) // 1024}KB)")
    if not sql_only:
        run_sql(out_path)
        print("DB 적재 끝")


if __name__ == "__main__":
    main()
