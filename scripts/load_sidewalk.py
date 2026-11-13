# -*- coding: utf-8 -*-
"""인도(보도) 데이터를 학교 DB에 넣고 우리 길(road_edge)마다 옆 인도를 붙인다 (034, 진행표 j36).

원본 (국토지리정보원, data/raw/ngii/ 아래에 둔다. git에는 안 올림)
  - 연속수치지형도 인도(보도) 선 N3L_A0033320 (전국, 1:5,000, 2023 제작): 폭 WIDT·재질 QUAL·종류 KIND·자전거도로 BYYN
    국토정보플랫폼 연속수치지형도 → 교통 → 인도(보도). 칸 뜻은 '연속수치지형도 데이터 설명서' 16~17쪽
  - 국가기본도 보도경계면 TN_FTPTH_BNDRY (전남광주통합특별시, 2020 측량, 2026-07 배포): 칸은 관리 정보뿐
둘 다 EPSG:5179(Korea 2000 / Unified CS)라 경위도로 바꾼다. 글자는 CP949(EUC-KR).

실행
  python3 scripts/load_sidewalk.py --line data/raw/ngii/N3L_A0033320 --area data/raw/ngii/ngii_tn_ftpth_bndry_12_202607
    → 미리 보기: 광주 안 인도 수, 우리 길 종류별로 옆에 인도가 있는 비율만 찍는다 (DB는 읽기만)
  ... --write  → sidewalk·edge_sidewalk를 지우고 다시 넣는다 (034 표가 있어야 함)
"""
import argparse
import collections
import math
import os
import struct
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_edge_stat import query, run_file  # noqa: E402  (.env의 학교 DB 접속)

SIDO = "24"  # 광주 (SGIS), build_poi.py와 같음
BBOX = (126.64, 35.05, 127.03, 35.26)  # 광주 경계를 넉넉히 감싼 범위. 정확한 경계는 DB에서 admin_area로 한 번 더 거른다
STEP_M = 5.0          # 길을 이 간격으로 나눠 본다
NEAR_BIG_M = 25.0     # 큰 길(차로가 많음)은 인도가 차로 중심선에서 멀다
NEAR_M = 12.0         # 그 밖의 길
PARALLEL_DEG = 30.0   # 길과 인도 방향 차이가 이 안이어야 그 길의 인도
SIDE_RATIO = 0.5      # 점의 이 비율 넘게 인도가 있으면 그쪽에 인도가 있다고 본다
BIG = {"trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link"}
ROADS = BIG | {"unclassified", "residential", "living_street", "service"}
MATERIAL = {"SWQ001": "asphalt", "SWQ002": "concrete", "SWQ003": "block", "SWQ004": "unpaved",
            "SWQ005": "asphalt_block", "SWQ999": "other"}
KIND = {"SWK001": "sidewalk", "SWK002": "cycleway"}

# ---------- EPSG:5179 → 경위도 (가로 메르카토르 역변환, Snyder) ----------
_A, _F = 6378137.0, 1 / 298.257222101
_K0, _LON0, _LAT0, _FE, _FN = 0.9996, math.radians(127.5), math.radians(38.0), 1000000.0, 2000000.0
_E2 = _F * (2 - _F)
_EP2 = _E2 / (1 - _E2)


def _m(lat):
    e2 = _E2
    return _A * ((1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256) * lat
                 - (3 * e2 / 8 + 3 * e2 ** 2 / 32 + 45 * e2 ** 3 / 1024) * math.sin(2 * lat)
                 + (15 * e2 ** 2 / 256 + 45 * e2 ** 3 / 1024) * math.sin(4 * lat)
                 - (35 * e2 ** 3 / 3072) * math.sin(6 * lat))


_M0 = _m(_LAT0)
_E1 = (1 - math.sqrt(1 - _E2)) / (1 + math.sqrt(1 - _E2))


def to_lonlat(x, y):
    mu = (_M0 + (y - _FN) / _K0) / (_A * (1 - _E2 / 4 - 3 * _E2 ** 2 / 64 - 5 * _E2 ** 3 / 256))
    e1 = _E1
    p1 = (mu + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * math.sin(2 * mu) + (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * math.sin(4 * mu)
          + (151 * e1 ** 3 / 96) * math.sin(6 * mu) + (1097 * e1 ** 4 / 512) * math.sin(8 * mu))
    c1, t1 = _EP2 * math.cos(p1) ** 2, math.tan(p1) ** 2
    n1 = _A / math.sqrt(1 - _E2 * math.sin(p1) ** 2)
    r1 = _A * (1 - _E2) / (1 - _E2 * math.sin(p1) ** 2) ** 1.5
    d = (x - _FE) / (n1 * _K0)
    lat = p1 - (n1 * math.tan(p1) / r1) * (d ** 2 / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 ** 2 - 9 * _EP2) * d ** 4 / 24
                                           + (61 + 90 * t1 + 298 * c1 + 45 * t1 ** 2 - 252 * _EP2 - 3 * c1 ** 2) * d ** 6 / 720)
    lon = _LON0 + (d - (1 + 2 * t1 + c1) * d ** 3 / 6
                   + (5 - 2 * c1 + 28 * t1 - 3 * c1 ** 2 + 8 * _EP2 + 24 * t1 ** 2) * d ** 5 / 120) / math.cos(p1)
    return math.degrees(lon), math.degrees(lat)


# ---------- 셰이프 읽기 (도구 없이) ----------

def dbf_reader(path):
    f = open(path, "rb")
    n, hlen, rlen = struct.unpack("<IHH", f.read(32)[4:12])
    fields = []
    while True:
        d = f.read(32)
        if d[0] == 0x0D:
            break
        fields.append((d[:11].split(b"\x00")[0].decode("ascii"), d[16]))

    def row(i):
        f.seek(hlen + i * rlen + 1)
        r, out, pos = f.read(rlen - 1), {}, 0
        for name, ln in fields:
            out[name.upper()] = r[pos:pos + ln].decode("cp949", "ignore").strip()
            pos += ln
        return out
    return row


def shapes(base):
    """(번호, 칸, [[(경도, 위도), ...] 부분들]) — 범위(BBOX) 밖은 건너뜀"""
    row = dbf_reader(base + ".dbf")
    with open(base + ".shp", "rb") as f:
        f.seek(100)
        i = -1
        while True:
            h = f.read(8)
            if len(h) < 8:
                return
            i += 1
            rec = f.read(struct.unpack(">ii", h)[1] * 2)
            if struct.unpack("<i", rec[:4])[0] not in (3, 5, 13, 15, 23, 25):
                continue
            xmin, ymin, xmax, ymax = struct.unpack("<4d", rec[4:36])
            lo1, la1 = to_lonlat(xmin, ymin)
            lo2, la2 = to_lonlat(xmax, ymax)
            if lo2 < BBOX[0] or lo1 > BBOX[2] or la2 < BBOX[1] or la1 > BBOX[3]:
                continue
            nparts, npts = struct.unpack("<ii", rec[36:44])
            parts = list(struct.unpack("<%di" % nparts, rec[44:44 + 4 * nparts])) + [npts]
            p0 = 44 + 4 * nparts
            xy = [struct.unpack("<2d", rec[p0 + 16 * k:p0 + 16 * k + 16]) for k in range(npts)]
            yield i, row(i), [[to_lonlat(x, y) for x, y in xy[parts[k]:parts[k + 1]]] for k in range(nparts)], \
                [xy[parts[k]:parts[k + 1]] for k in range(nparts)]


def area_width(ring_xy):
    """면의 대략 폭 = 넓이 ÷ (둘레/2). 5179가 미터라 그대로 잰다"""
    a = abs(sum(ring_xy[k][0] * ring_xy[k + 1][1] - ring_xy[k + 1][0] * ring_xy[k][1] for k in range(len(ring_xy) - 1))) / 2
    per = sum(math.hypot(ring_xy[k + 1][0] - ring_xy[k][0], ring_xy[k + 1][1] - ring_xy[k][1]) for k in range(len(ring_xy) - 1))
    return round(a / (per / 2), 2) if per else None


def wkt_line(pts):
    return "LINESTRING(" + ",".join("%.7f %.7f" % p for p in pts) + ")"


def wkt_poly(rings):
    return "POLYGON(" + ",".join("(" + ",".join("%.7f %.7f" % p for p in r) + ")" for r in rings) + ")"


# ---------- 읽기 ----------

def load(line_base, area_base):
    rows = []  # (source, source_id, kind, width, material, bicycle, made_on, wkt, segs[(a, b, width, material, src)])
    if line_base:
        for i, a, parts, _ in shapes(line_base):
            w = float(a.get("WIDT") or 0) or None
            mat = MATERIAL.get(a.get("QUAL"))
            byc = {"BYC001": 1, "BYC002": 0}.get(a.get("BYYN"))
            for k, pts in enumerate(parts):
                if len(pts) < 2:
                    continue
                sid = a.get("UFID") or str(i)
                rows.append(("ngii_line", sid if len(parts) == 1 else "%s#%d" % (sid, k), KIND.get(a.get("KIND")),
                             w, mat, byc, (a.get("FMTA") or "")[:10] or None, wkt_line(pts), pts))
    if area_base:
        for i, a, parts, xyparts in shapes(area_base):
            if not parts or len(parts[0]) < 4:
                continue
            rows.append(("ngii_area", a.get("NF_ID") or str(i), None, area_width(xyparts[0]), None, None,
                         a.get("OBCHG_DT") or None, wkt_poly(parts), parts))
    return rows


# ---------- 우리 길에 붙이기 ----------

class Grid:
    def __init__(self, lat0):
        self.kx, self.ky = 111320.0 * math.cos(math.radians(lat0)), 110574.0
        self.cells = collections.defaultdict(list)

    def xy(self, p):
        return (p[0] * self.kx, p[1] * self.ky)

    def add(self, a, b, info):
        a, b = self.xy(a), self.xy(b)
        for cx in range(int(min(a[0], b[0]) // 25), int(max(a[0], b[0]) // 25) + 1):
            for cy in range(int(min(a[1], b[1]) // 25), int(max(a[1], b[1]) // 25) + 1):
                self.cells[(cx, cy)].append((a, b, info))

    def near(self, p):
        cx, cy = int(p[0] // 25), int(p[1] // 25)
        seen = set()
        for i in (-1, 0, 1):
            for j in (-1, 0, 1):
                for s in self.cells.get((cx + i, cy + j), ()):
                    if id(s) not in seen:
                        seen.add(id(s))
                        yield s


def match(edges, rows):
    grid = Grid(35.15)
    for src, _, kind, w, mat, _, _, _, geo in rows:
        if src == "ngii_line":
            for a, b in zip(geo, geo[1:]):
                grid.add(a, b, (src, w, mat))
        else:
            for ring in geo:
                for a, b in zip(ring, ring[1:]):
                    grid.add(a, b, (src, None, None))
    out = {}
    for eid, hw, pts in edges:
        reach = NEAR_BIG_M if hw in BIG else NEAR_M
        q = [grid.xy(p) for p in pts]
        hits = {"L": 0, "R": 0}
        n = 0
        widths, mats, srcs = [], collections.Counter(), set()
        for a, b in zip(q, q[1:]):
            seg = math.hypot(b[0] - a[0], b[1] - a[1])
            if seg < 0.5:
                continue
            ux, uy = (b[0] - a[0]) / seg, (b[1] - a[1]) / seg
            k = max(1, int(seg // STEP_M))
            for s in range(k):
                t = (s + 0.5) / k
                p = (a[0] + ux * seg * t, a[1] + uy * seg * t)
                n += 1
                side_hit = {}
                for sa, sb, info in grid.near(p):
                    dx, dy = sb[0] - sa[0], sb[1] - sa[1]
                    ln = math.hypot(dx, dy)
                    if ln < 0.5:
                        continue
                    cos = abs((dx * ux + dy * uy) / ln)
                    if cos < math.cos(math.radians(PARALLEL_DEG)):
                        continue
                    tt = max(0.0, min(1.0, ((p[0] - sa[0]) * dx + (p[1] - sa[1]) * dy) / (ln * ln)))
                    fx, fy = sa[0] + tt * dx, sa[1] + tt * dy
                    d = math.hypot(fx - p[0], fy - p[1])
                    if d > reach or d < 0.3:
                        continue
                    side = "L" if (ux * (fy - p[1]) - uy * (fx - p[0])) > 0 else "R"
                    if side not in side_hit or d < side_hit[side][0]:
                        side_hit[side] = (d, info)
                for side, (_, (src, w, mat)) in side_hit.items():
                    hits[side] += 1
                    srcs.add(src)
                    if w:
                        widths.append(w)
                    if mat:
                        mats[mat] += 1
        if n == 0:
            continue
        lr, rr = hits["L"] / n, hits["R"] / n
        side = {(True, True): "both", (True, False): "left", (False, True): "right"}.get((lr > SIDE_RATIO, rr > SIDE_RATIO), "no")
        width = sorted(widths)[len(widths) // 2] if widths else None
        src = "both" if len(srcs) == 2 else (next(iter(srcs)) if srcs else None)
        out[eid] = (round(min(lr, 1), 2), round(min(rr, 1), 2), side, width, mats.most_common(1)[0][0] if mats else None, src)
    return out


def road_edges():
    rows = query("SELECT e.id, e.highway, ST_AsText(e.geom) FROM road_edge e JOIN admin_area a ON a.level = 'sido' "
                 f"AND a.code = '{SIDO}' AND MBRContains(a.geom, e.geom) "
                 f"WHERE e.highway IN ({','.join(repr(h) for h in sorted(ROADS))})")
    out = []
    for eid, hw, wkt in rows:
        pts = [tuple(map(float, s.split())) for s in wkt[wkt.index("(") + 1:wkt.rindex(")")].split(",")]
        out.append((int(eid), hw, pts))
    return out


def sql_str(v):
    if v is None:
        return "NULL"
    if isinstance(v, (int, float)):
        return repr(v)
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--line", help="연속수치지형도 인도 선 셰이프 (확장자 없이)")
    ap.add_argument("--area", help="국가기본도 보도경계면 셰이프 (확장자 없이)")
    ap.add_argument("--write", action="store_true", help="학교 DB에 넣기 (없으면 미리 보기)")
    a = ap.parse_args()
    rows = load(a.line, a.area)
    print("범위 안 인도:", collections.Counter(r[0] for r in rows))
    edges = road_edges()
    print("광주 차가 다니는 길:", len(edges))
    m = match(edges, rows)
    hw = {e[0]: e[1] for e in edges}
    length = {}
    for eid, h, pts in edges:
        length[eid] = sum(math.hypot((b[0] - a[0]) * 91000, (b[1] - a[1]) * 110574) for a, b in zip(pts, pts[1:]))
    tot = collections.defaultdict(lambda: [0.0, 0.0, 0])
    for eid, v in m.items():
        g = "큰 길" if hw[eid] in BIG else "골목급"
        tot[g][0] += length[eid]
        if v[2] != "no":
            tot[g][1] += length[eid]
            tot[g][2] += v[3] is not None
    for g, (L, H, wn) in tot.items():
        print(f"{g}: {L/1000:.0f}km 중 옆에 인도 있음 {H/1000:.0f}km ({100*H/L:.0f}%)")
    print("한쪽·양쪽:", collections.Counter(v[2] for v in m.values()))
    if not a.write:
        print("미리 보기만 했습니다. --write를 붙이면 학교 DB에 넣습니다.")
        return
    fd, path = tempfile.mkstemp(suffix=".sql")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write("SET NAMES utf8mb4;\nDELETE FROM edge_sidewalk;\nDELETE FROM sidewalk;\n")
        for k in range(0, len(rows), 500):
            vals = ",".join("(%s,%s,%s,%s,%s,%s,%s,ST_GeomFromText(%s))" % tuple(map(sql_str, r[:8]))
                            for r in rows[k:k + 500])
            f.write("INSERT INTO sidewalk (source,source_id,kind,width_m,material,bicycle,made_on,geom) VALUES " + vals + ";\n")
        f.write(f"DELETE s FROM sidewalk s JOIN admin_area a ON a.level = 'sido' AND a.code = '{SIDO}' "
                "WHERE NOT ST_Intersects(a.geom, s.geom);\n")
        items = list(m.items())
        for k in range(0, len(items), 1000):
            vals = ",".join("(%d,%s,%s,%s,%s,%s,%s)" % ((eid,) + tuple(map(sql_str, v))) for eid, v in items[k:k + 1000])
            f.write("INSERT INTO edge_sidewalk (edge_id,left_ratio,right_ratio,side,width_m,material,source) VALUES " + vals + ";\n")
    run_file(path)
    os.remove(path)
    print("넣음:", query("SELECT source, COUNT(*) FROM sidewalk GROUP BY source"),
          query("SELECT side, COUNT(*) FROM edge_sidewalk GROUP BY side"))


if __name__ == "__main__":
    main()
