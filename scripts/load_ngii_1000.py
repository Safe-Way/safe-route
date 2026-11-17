# -*- coding: utf-8 -*-
"""1:1,000 수치지도(국토지리정보원)로 시연 지역의 계단·횡단보도·인도 폭을 채운다 (2026-11-17, A파트, 진행표 j37).

원본 (data/raw/ngii_1000/ 아래에 받은 그대로 둔다. git에는 안 올림)
  - 국토정보플랫폼 수치지도 1:1,000, 광주 동구·남구·북구 일부 79도엽 (재휘가 윈도우에서 받음)
  - 2021·2022년 도엽: 'B010 수치지도_도엽번호_연도_….zip' 안의 SHP (N1A_·N1L_·N1P_ 층)
  - 2020년 도엽: 같은 이름의 .ngi(도형)·.nda(속성) 글자 파일 (수치지도 2.0 NGI)
  - 좌표는 EPSG:5186 (GRS80 중부원점, 가로 200,000 · 세로 600,000), 글자는 CP949
  - 쓰는 층: C0390000 계단(면, 구조·폭), A0043325 횡단보도(면), C0493376 신호등(점, 용도 보행용·차량용),
    A0033320 인도(SHP는 면, NGI는 선, 폭·재질)

하는 일 (모두 다시 돌려도 결과가 같다)
  1. 계단: 계단 면 안을 지나는데 steps 표시가 없는 우리 길(road_edge)에 steps = 1 을 붙인다.
     엔진은 road_edge.steps 만 읽어서(휠체어는 빼고 노인은 피함) OSM 원본 태그·길 모양은 그대로 두고 이 칸만 바꾼다.
     바꾼 길에는 tags.ngii_steps(수치지도 계단 UFID)를 남겨, 다시 돌릴 때 먼저 되돌리고 새로 붙인다.
     긴 길이 계단 끝에 몇 m만 걸치면(광주천 자전거길 1,882m 중 3m 등) 길 전체가 막히므로,
     인도·오솔길은 계단 안 STAIR_WALK_M 이상이면서 길이의 STAIR_WALK_RATIO 이상, 그 밖의 길은 길이의 STAIR_RATIO 이상만 붙인다.
     현장에서 계단이 아니라고 확인한 길은 STAIR_NOT 에 적어 빼 둔다.
     ※ load_road_network.py 로 도로망을 다시 넣으면 steps 가 OSM 값으로 돌아가므로 이 스크립트를 다시 돌린다.
  2. 횡단보도: 시설 표 crosswalk 에 source = 'ngii_1000' 줄로 넣는다 (면의 가운데 점).
     15m 안에 보행 신호등(용도에 '보행')이 있으면 신호 있음(tfclghtYn = Y), 없으면 N. 1:1,000 지도는 신호등을 모두 그리므로
     없으면 없음으로 본다. 그 뒤 build_poi.py --only crosswalk,stairs 로 지도용 시설을 다시 만들고
     (같은 자리 10m 안 공공데이터·OSM 횡단보도와는 하나로 묶임), poi id 가 바뀌므로 build_edge_poi.py 로 길별 시설도 다시 만든다.
     엔진은 이 점을 큰 길 건너는 자리(crossAt)에만 쓴다 (경로 비용에는 안 씀).
  3. 인도 폭: 우리 길마다 붙은 1:1,000 인도 폭의 중간값으로 edge_sidewalk.width_m 을 바꾼다 (source = 'ngii_1000').
     길에 인도를 붙이는 방법은 load_sidewalk.py 와 같다(match). 인도 쪽(side)은 그대로 두고 폭만 바꾼다.
     엔진이 보도 자료를 쓰지 않는 20m 미만 길은 건너뛴다.
     ※ load_sidewalk.py --write 는 edge_sidewalk 를 지우고 다시 넣으므로 그 뒤에 이 스크립트를 다시 돌린다.

실행
  python3 scripts/load_ngii_1000.py            → 미리 보기 (DB는 읽기만, scripts/ngii_1000.sql 만 만든다)
  python3 scripts/load_ngii_1000.py --write    → 학교 DB에 넣고 build_poi(횡단보도·계단)·build_edge_poi 를 돌린다
엔진은 도로망을 서버를 켤 때 한 번 읽으므로, 넣은 뒤 서버를 다시 켜야(배포) 길찾기에 반영된다.
"""
import argparse
import collections
import csv
import glob
import io
import json
import math
import os
import re
import struct
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_edge_stat import query, run_file  # noqa: E402  (.env의 학교 DB 접속)
import load_sidewalk  # noqa: E402  (우리 길에 인도 붙이기)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "data", "raw", "ngii_1000")
OUT = os.path.join(ROOT, "scripts", "ngii_1000.sql")
SOURCE = "ngii_1000"
LICENSE = "확인 필요 (국토지리정보원 수치지도, 출처 표시)"

STAIR_STEP_M = 1.0       # 길을 이 간격으로 나눠 계단 면 안인지 본다
STAIR_WALK_M = 3.0       # 인도·오솔길: 계단 안이 이 길이 이상이고
STAIR_WALK_RATIO = 0.1   # 길이의 이 비율 이상이면 계단 표시
STAIR_RATIO = 0.3        # 그 밖의 길(서비스 길·차도 등): 길이의 이 비율 이상만
WALK = {"footway", "path", "pedestrian", "corridor"}
# 현장·로드뷰로 확인해 계단이 아닌 길 (길 번호: 확인 메모). 계단 면 끝에 걸치기만 한 길이 기준을 넘는 경우
STAIR_NOT = {
    128447: "2026-11-17 재휘 확인: 계단 없음 (조선대 쪽 인도, 계단 면에 3m만 걸침)",
    128414: "2026-11-17 재휘 확인: 계단 없음. 계단은 광주e스포츠경기장 바로 서쪽 앞에 따로 있음 (폭 10m 계단 면에 9m 걸침, 도로망에 그 계단 길은 없음)",
    118526: "2026-11-17 재휘 확인: 계단 없음 (조선대 안 인도). 더 북쪽 인도(128307 쪽)에 계단이 있고, 건물 안 엘리베이터로 돌아갈 수 있음",
}
# 확인해서 계단 표시를 그대로 두는 길 (판단 기록용, 동작에는 안 씀)
# 96360: 2026-11-17 재휘 확인 — 산책로(산길) 오르막이라 휠체어는 못 감 (OSM 재질·등산 표시가 없어 지금은 휠체어가 씀)
# 128307: 2026-11-17 재휘 — 조선대 118526 북쪽 인도, 계단 있음
SIGNAL_M = 15.0          # 횡단보도 가운데에서 이 안에 보행 신호등이 있으면 신호 있음
SIDEWALK_MIN_EDGE_M = 20.0  # route.yml sidewalk-data-min-edge-m 과 같음 (짧은 길은 엔진이 보도 자료를 안 씀)

# ---------- EPSG:5186 → 경위도 (가로 메르카토르 역변환, Snyder. load_sidewalk.py 와 같은 식, 원점만 다름) ----------
_A, _F = 6378137.0, 1 / 298.257222101
_K0, _LON0, _LAT0, _FE, _FN = 1.0, math.radians(127.0), math.radians(38.0), 200000.0, 600000.0
_E2 = _F * (2 - _F)
_EP2 = _E2 / (1 - _E2)
_E1 = (1 - math.sqrt(1 - _E2)) / (1 + math.sqrt(1 - _E2))


def _m(lat):
    e2 = _E2
    return _A * ((1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256) * lat
                 - (3 * e2 / 8 + 3 * e2 ** 2 / 32 + 45 * e2 ** 3 / 1024) * math.sin(2 * lat)
                 + (15 * e2 ** 2 / 256 + 45 * e2 ** 3 / 1024) * math.sin(4 * lat)
                 - (35 * e2 ** 3 / 3072) * math.sin(6 * lat))


_M0 = _m(_LAT0)


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


# ---------- SHP (zip 안, 풀지 않고 읽음) ----------

def dbf_rows(data):
    n, hlen, rlen = struct.unpack("<IHH", data[4:12])
    fields, pos = [], 32
    while data[pos] != 0x0D:
        d = data[pos:pos + 32]
        fields.append((d[:11].split(b"\x00")[0].decode("cp949", "ignore"), d[16]))
        pos += 32
    rows = []
    for i in range(n):
        r, out, p = data[hlen + i * rlen + 1:hlen + (i + 1) * rlen], {}, 0
        for name, ln in fields:
            out[name] = r[p:p + ln].decode("cp949", "ignore").strip()
            p += ln
        rows.append(out)
    return rows


def shp_shapes(data):
    """[[[(x, y), ...] 부분들] 또는 None] — 점은 부분 하나에 점 하나"""
    out, pos = [], 100
    while pos + 8 <= len(data):
        ln = struct.unpack(">i", data[pos + 4:pos + 8])[0] * 2
        rec = data[pos + 8:pos + 8 + ln]
        pos += 8 + ln
        t = struct.unpack("<i", rec[:4])[0]
        if t in (1, 11, 21):
            out.append([[struct.unpack("<2d", rec[4:20])]])
        elif t in (3, 5, 13, 15, 23, 25):
            nparts, npts = struct.unpack("<ii", rec[36:44])
            parts = list(struct.unpack("<%di" % nparts, rec[44:44 + 4 * nparts])) + [npts]
            p0 = 44 + 4 * nparts
            xy = [struct.unpack("<2d", rec[p0 + 16 * k:p0 + 16 * k + 16]) for k in range(npts)]
            out.append([xy[parts[k]:parts[k + 1]] for k in range(nparts)])
        else:
            out.append(None)
    return out


def read_zip(path, layers):
    """zip 하나에서 층마다 [(칸, 평면 부분들)]. 이름은 'N1A_C0390000.shp' 꼴 (앞 3글자는 면·선·점)"""
    out = collections.defaultdict(list)
    with zipfile.ZipFile(path) as z:
        names = {os.path.basename(n): n for n in z.namelist()}
        for base in {n[:-4] for n in names if n.endswith(".shp")}:
            layer = base[4:]
            if layer not in layers or base + ".dbf" not in names:
                continue
            for row, geo in zip(dbf_rows(z.read(names[base + ".dbf"])), shp_shapes(z.read(names[base + ".shp"]))):
                if geo:
                    out[layer].append((row, geo))
    return out


# ---------- NGI·NDA (글자 파일) ----------

def ngi_blocks(path):
    """층마다 (이름, 머리 줄들, 자료 줄들)"""
    lines = open(path, encoding="cp949", errors="ignore").read().splitlines()
    name, head, data, mode = None, [], [], None
    for i, raw in enumerate(lines):
        s = raw.strip()
        if s == "<LAYER_START>":
            name, head, data, mode = None, [], [], None
        elif s == "<LAYER_END>":
            yield name, head, data
        elif s == "$LAYER_NAME":
            name = lines[i + 1].strip().strip('"')
        elif s == "<HEADER>":
            mode = "h"
        elif s == "<DATA>":
            mode = "d"
        elif s == "<END>":
            mode = None
        elif mode == "h":
            head.append(s)
        elif mode == "d":
            data.append(s)


def read_ngi(ngi_path, nda_path, layers):
    attrs = {}
    for name, head, data in ngi_blocks(nda_path):
        if name not in layers:
            continue
        fields = [re.match(r'ATTRIB\("([^"]+)"', h).group(1) for h in head if h.startswith("ATTRIB(")]
        rows = {}
        for k, s in enumerate(data):
            if s.startswith("$RECORD"):
                vals = next(csv.reader(io.StringIO(data[k + 1]), skipinitialspace=True))
                rows[int(s.split()[1])] = dict(zip(fields, [v.strip() for v in vals]))
        attrs[name] = rows
    out = collections.defaultdict(list)
    for name, _, data in ngi_blocks(ngi_path):
        if name not in layers:
            continue
        k = 0
        while k < len(data):
            if not data[k].startswith("$RECORD"):
                k += 1
                continue
            rid, g = int(data[k].split()[1]), data[k + 1]
            k += 2
            if g == "POINT" or g.startswith("TEXT"):
                parts = [[tuple(map(float, data[k].split()[:2]))]]
                k += 1
            elif g == "LINESTRING":
                n = int(data[k])
                parts = [[tuple(map(float, data[k + 1 + t].split()[:2])) for t in range(n)]]
                k += 1 + n
            elif g == "POLYGON":
                nparts = int(data[k].split()[1])
                k += 1
                parts = []
                for _ in range(nparts):
                    n = int(data[k])
                    parts.append([tuple(map(float, data[k + 1 + t].split()[:2])) for t in range(n)])
                    k += 1 + n
            else:
                continue
            out[name].append((attrs.get(name, {}).get(rid, {}), parts))
    return out


def features(layers):
    """모든 도엽에서 층마다 [(칸, 경위도 부분들)]. 같은 UFID 는 한 번만 (도엽 가장자리에서 겹칠 수 있음)"""
    out, seen = collections.defaultdict(list), set()
    sources = [("zip", p) for p in sorted(glob.glob(os.path.join(RAW, "*.zip")))]
    sources += [("ngi", p) for p in sorted(glob.glob(os.path.join(RAW, "*.ngi")))]
    if not sources:
        sys.exit(f"{os.path.relpath(RAW, ROOT)} 에 수치지도 파일(.zip 또는 .ngi·.nda)이 없습니다.")
    for kind, p in sources:
        got = read_zip(p, layers) if kind == "zip" else read_ngi(p, p[:-4] + ".nda", layers)
        for layer, recs in got.items():
            for row, parts in recs:
                u = row.get("UFID")
                if u and u in seen:
                    continue
                seen.add(u)
                out[layer].append((row, [[to_lonlat(x, y) for x, y in part] for part in parts]))
    print(f"수치지도 {len(sources)}개 파일:", {k: len(v) for k, v in out.items()})
    return out


# ---------- 평면 계산 (시연 지역 안이라 경위도를 m로 바꿔도 충분) ----------

KX, KY = 111320.0 * math.cos(math.radians(35.14)), 110540.0


def xy(p):
    return (p[0] * KX, p[1] * KY)


def inside(p, ring):
    x, y = p
    c = False
    for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]):
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            c = not c
    return c


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def sql_str(v):
    return load_sidewalk.sql_str(v)


# ---------- 1. 계단 ----------

def stair_edges(edges, feats):
    """[(길 번호, 계단 UFID, 계단 안 m, 길 종류, 길이)] — 붙일 길만"""
    stairs, grid = [], collections.defaultdict(list)
    for row, parts in feats.get("C0390000", []):
        if row.get("구조") != "계단" or len(parts[0]) < 3:
            continue
        ring = [xy(p) for p in parts[0]]
        i = len(stairs)
        stairs.append((ring, row.get("UFID")))
        xs, ys = [p[0] for p in ring], [p[1] for p in ring]
        for gx in range(int(min(xs) // 50), int(max(xs) // 50) + 1):
            for gy in range(int(min(ys) // 50), int(max(ys) // 50) + 1):
                grid[(gx, gy)].append(i)
    out = []
    for eid, hw, steps, length, pts in edges:
        if steps or eid in STAIR_NOT:
            continue
        q = [xy(p) for p in pts]
        hit = collections.Counter()
        for a, b in zip(q, q[1:]):
            seg = math.hypot(b[0] - a[0], b[1] - a[1])
            n = max(1, int(seg // STAIR_STEP_M))
            for t in range(n):
                p = (a[0] + (b[0] - a[0]) * (t + 0.5) / n, a[1] + (b[1] - a[1]) * (t + 0.5) / n)
                for i in grid.get((int(p[0] // 50), int(p[1] // 50)), ()):
                    if inside(p, stairs[i][0]):
                        hit[i] += seg / n
                        break
        m = sum(hit.values())
        if m <= 0 or length <= 0:
            continue
        ok = (m >= STAIR_WALK_M and m / length >= STAIR_WALK_RATIO) if hw in WALK else m / length >= STAIR_RATIO
        if ok:
            out.append((eid, stairs[hit.most_common(1)[0][0]][1], round(m, 1), hw, length))
    return out, len(stairs)


# ---------- 2. 횡단보도 ----------

def crosswalks(feats):
    """[(UFID, 경도, 위도, 신호 Y/N)]"""
    sig = [xy(parts[0][0]) for row, parts in feats.get("C0493376", []) if "보행" in (row.get("용도") or "")]
    sgrid = collections.defaultdict(list)
    for s in sig:
        sgrid[(int(s[0] // 25), int(s[1] // 25))].append(s)
    out = []
    for row, parts in feats.get("A0043325", []):
        ring = parts[0]
        if len(ring) < 3 or not row.get("UFID"):
            continue
        lon, lat = sum(p[0] for p in ring) / len(ring), sum(p[1] for p in ring) / len(ring)
        c = xy((lon, lat))
        near = any(math.hypot(c[0] - s[0], c[1] - s[1]) <= SIGNAL_M
                   for dx in (-1, 0, 1) for dy in (-1, 0, 1)
                   for s in sgrid.get((int(c[0] // 25) + dx, int(c[1] // 25) + dy), ()))
        out.append((row["UFID"], round(lon, 7), round(lat, 7), "Y" if near else "N"))
    return out, len(sig)


# ---------- 3. 인도 폭 ----------

def sidewalk_widths(edges, feats):
    """{길 번호: 폭} — load_sidewalk.match 에 1:1,000 인도(선, 면은 테두리)를 폭과 함께 넘긴다"""
    rows = []
    for row, parts in feats.get("A0033320", []):
        w = num(row.get("폭"))
        if not w or w <= 0:
            continue
        for part in parts:
            if len(part) >= 2:
                rows.append(("ngii_line", None, None, w, None, None, None, None, part))
    m = load_sidewalk.match([(eid, hw, pts) for eid, hw, _, length, pts in edges
                             if hw in load_sidewalk.ROADS and length >= SIDEWALK_MIN_EDGE_M], rows)
    return {eid: v[3] for eid, v in m.items() if v[3]}


# ---------- 실행 ----------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true", help="학교 DB에 넣기 (없으면 미리 보기)")
    a = ap.parse_args()
    feats = features({"C0390000", "A0043325", "C0493376", "A0033320"})
    lons = [p[0] for recs in feats.values() for _, parts in recs for part in parts for p in part]
    lats = [p[1] for recs in feats.values() for _, parts in recs for part in parts for p in part]
    w, s, e, n = min(lons), min(lats), max(lons), max(lats)
    print(f"범위: 경도 {w:.4f}~{e:.4f}, 위도 {s:.4f}~{n:.4f}")
    box = f"ST_GeomFromText('POLYGON(({w} {s},{e} {s},{e} {n},{w} {n},{w} {s}))', 0)"
    edges = []
    for eid, hw, steps, length, wkt in query(
            f"SELECT id, highway, steps, length_m, ST_AsText(geom) FROM road_edge WHERE MBRIntersects({box}, geom)"):
        pts = [tuple(map(float, t.split())) for t in re.findall(r"[-\d.]+ [-\d.]+", wkt)]
        edges.append((int(eid), hw, int(steps), float(length), pts))
    print("범위 안 우리 길:", len(edges))

    # 앞서 이 스크립트가 붙인 계단은 되돌린 상태로 다시 판단한다
    ours = {int(r[0]) for r in query("SELECT id FROM road_edge WHERE JSON_EXTRACT(tags, '$.ngii_steps') IS NOT NULL")}
    edges = [(eid, hw, 0 if eid in ours else st, ln, pts) for eid, hw, st, ln, pts in edges]

    st, n_stairs = stair_edges(edges, feats)
    print(f"1. 계단 면 {n_stairs}개 → steps 표시할 길 {len(st)}개 (길이 합 {sum(x[4] for x in st):.0f}m)"
          f", 종류 {dict(collections.Counter(x[3] for x in st))}")
    for eid, ufid, m, hw, ln in sorted(st, key=lambda x: -x[2])[:10]:
        print(f"   {eid} {hw} {ln:.0f}m 중 계단 안 {m}m ({ufid})")

    cw, n_sig = crosswalks(feats)
    print(f"2. 횡단보도 {len(cw)}개 (보행 신호등 {n_sig}개 기준 신호 있음 {sum(1 for x in cw if x[3] == 'Y')})")

    widths = sidewalk_widths(edges, feats)
    cur = {int(r[0]): (None if r[1] == "NULL" else float(r[1])) for r in query(
        "SELECT edge_id, IFNULL(width_m, 'NULL') FROM edge_sidewalk WHERE edge_id IN ("
        + ",".join(str(x) for x in widths) + ")")} if widths else {}
    widths = {k: v for k, v in widths.items() if k in cur}
    narrow = 1.5
    new_n = [k for k, v in widths.items() if v < narrow and (cur[k] is None or cur[k] >= narrow)]
    gone_n = [k for k, v in widths.items() if v >= narrow and cur[k] is not None and cur[k] < narrow]
    print(f"3. 인도 폭 바꿀 길 {len(widths)}개 (지금 모름 {sum(1 for k in widths if cur[k] is None)},"
          f" 0.5m 넘게 달라짐 {sum(1 for k, v in widths.items() if cur[k] is not None and abs(cur[k] - v) >= 0.5)})"
          f" · 좁은 인도(1.5m 미만) 새로 {len(new_n)}개, 풀림 {len(gone_n)}개")

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("-- scripts/load_ngii_1000.py 가 만든 파일. 손으로 고치지 말고 스크립트를 다시 돌린다.\n")
        f.write("SET NAMES utf8mb4;\n")
        f.write("UPDATE road_edge SET steps = 0, tags = JSON_REMOVE(tags, '$.ngii_steps')"
                " WHERE JSON_EXTRACT(tags, '$.ngii_steps') IS NOT NULL;\n")
        for eid, ufid, *_ in st:
            f.write(f"UPDATE road_edge SET steps = 1, tags = JSON_SET(COALESCE(tags, JSON_OBJECT()), '$.ngii_steps', "
                    f"{sql_str(ufid)}) WHERE id = {eid} AND steps = 0;\n")
        f.write(f"DELETE FROM crosswalk WHERE source = '{SOURCE}';\n")
        for k in range(0, len(cw), 500):
            f.write("INSERT INTO crosswalk (kind, source, source_id, name, address, lon, lat, geom, attrs) VALUES\n"
                    + ",\n".join(f"(NULL,'{SOURCE}',{sql_str(u)},NULL,NULL,{lo},{la},ST_SRID(POINT({lo},{la}),0),"
                                 f"{sql_str(json.dumps({'tfclghtYn': sg, 'layer': 'N1A_A0043325'}))})"
                                 for u, lo, la, sg in cw[k:k + 500]) + ";\n")
        for eid, wd in widths.items():
            f.write(f"UPDATE edge_sidewalk SET width_m = {wd:.2f}, source = '{SOURCE}' WHERE edge_id = {eid};\n")
        for typ, title, cnt in (("road_edge_steps", "계단 (1:1,000 수치지도 계단 면 → road_edge.steps)", len(st)),
                                ("crosswalk", "횡단보도 (1:1,000 수치지도 횡단보도 면·보행 신호등)", len(cw)),
                                ("edge_sidewalk", "인도 폭 (1:1,000 수치지도 인도 → edge_sidewalk.width_m)", len(widths))):
            f.write("INSERT INTO data_source (type, source, title, api, license, total_count, loaded_count, skipped_count,"
                    f" complete, loaded_at) VALUES ({sql_str(typ)}, '{SOURCE}', {sql_str(title)},"
                    f" '국토정보플랫폼 수치지도 1:1,000 (data/raw/ngii_1000)', {sql_str(LICENSE)}, {cnt}, {cnt}, 0, 1, NOW())"
                    " ON DUPLICATE KEY UPDATE title = VALUES(title), total_count = VALUES(total_count),"
                    " loaded_count = VALUES(loaded_count), license = VALUES(license), loaded_at = NOW();\n")
    print("SQL:", os.path.relpath(OUT, ROOT))
    if not a.write:
        print("미리 보기만 했습니다. --write를 붙이면 학교 DB에 넣습니다.")
        return
    run_file(OUT)
    print("넣음:", query("SELECT COUNT(*) FROM road_edge WHERE JSON_EXTRACT(tags, '$.ngii_steps') IS NOT NULL"),
          query(f"SELECT COUNT(*) FROM crosswalk WHERE source = '{SOURCE}'"),
          query(f"SELECT COUNT(*) FROM edge_sidewalk WHERE source = '{SOURCE}'"))
    py = sys.executable
    for cmd in ([py, "scripts/build_poi.py", "--only", "crosswalk,stairs"], [py, "scripts/build_edge_poi.py"]):
        print("실행:", " ".join(cmd[1:]))
        if subprocess.run(cmd, cwd=ROOT).returncode != 0:
            sys.exit(f"{cmd[1]} 실패")
    print("끝. 서버를 다시 켜면(배포) 길찾기에 반영됩니다.")


if __name__ == "__main__":
    main()
