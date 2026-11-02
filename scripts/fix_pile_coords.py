"""시설 표에서 한 점에 몰린 좌표를 카카오 지도 검색으로 다시 찍는다 (보안등·비상벨·횡단보도 등).

왜 몰리나 (2026-11-02 분석)
- 위치를 안 넣어 기본값이 들어감: 경도 127.0000·위도 36.0000 같은 정수 좌표 (비상벨 괴산·영덕)
- 관리 기관 좌표로 찍음: 시청·구청 한 곳 좌표를 전부에 넣음 (안동시 보안등 3,378개가 한 점)
- 읍·면·리 대표점: 지번을 좌표로 바꿀 때 마을 단위까지만 찾음 (홍천 화촌면 보안등 540개)
- 실제로 한 부지: 시청 부지 안 CCTV 여러 대 등. 오류가 아니므로 건드리지 않는다

고르는 법: 약 10m 칸(소수 넷째 자리) 하나에 10개 이상이 있고, 괄호를 뗀 주소가 5가지 이상인 칸
(주소가 거의 같으면 실제로 한 부지로 본다).

찾는 순서 (카카오 로컬 API, .env의 KAKAO_REST_KEY)
1. 주소 검색: 괄호를 뗀 주소 → 안 되면 번지 뒤 설명을 자른 주소 ('…길 3, 이담보건진료소' → '…길 3')
2. 장소 검색: 주소 대신 '푸른솔식당 앞'처럼 설명만 있으면 관리 기관의 시·군·구 이름을 붙여 찾는다
   ('하남시 덕풍공원'). 다른 지역의 같은 이름이 걸리지 않게 결과 주소에 그 시·군·구가 있어야 쓴다
3. 그래도 못 찾으면 틀린 위치를 남기지 않으려고 시설 표에서 빼서 facility_unlocated 표로 옮기고
   data/raw/national/pile_removed.csv에도 남긴다
   (호출 오류는 지우지 않고 다음 실행 때 다시)
찾은 좌표는 attrs의 "좌표" 항목에 어떻게 찾았는지 적는다. 결과는 data/raw/national/pile_geocode.csv에 저장해 다시 쓴다.

원본을 다시 적재하면 몰림이 다시 들어오므로 이 스크립트도 다시 돌린다.
실행: python3 scripts/fix_pile_coords.py [표 이름 ...] [--sql-only]   (기본: security_light emergency_bell crosswalk)
"""
import concurrent.futures
import csv
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_edge_stat import ROOT, env, query, run_file  # noqa: E402
from fix_cctv_coords import clean, move_sql, trimmed  # noqa: E402

CACHE = os.path.join(ROOT, "data", "raw", "national", "pile_geocode.csv")
REMOVED = os.path.join(ROOT, "data", "raw", "national", "pile_removed.csv")
OUT = os.path.join(ROOT, "data", "raw", "national", "pile_fix.sql")
DEFAULT = ["security_light", "emergency_bell", "crosswalk"]
HINT_COL = {"emergency_bell": "attrs ->> '$.mng_inst'", "security_light": "attrs ->> '$.institutionNm'",
            "crosswalk": "attrs ->> '$.institutionNm'"}
TAIL = re.compile(r"\s*(앞|옆|뒤|뒷편|뒤편|주변|부근|인근|입구|정면|맞은편|건너편|사거리|삼거리|도로\s*주변|쪽)\s*$")


BIG = ("특별시", "광역시", "특별자치시", "통합특별시")


def region_of(*texts):
    """'하남시청', '괴산군청 환경과', '경상남도 창원시 진해구 수산산림과' → ('하남시', '하남'), ('창원시 진해구', '창원') …
    시·도 이름(…특별시·광역시)은 건너뛴다. 주소에 시·군이 없으면 다음 글(관리 기관)에서 찾는다."""
    for t in texts:
        words = re.sub(r"청(?=\s|$)", "", t or "").split()
        for i, w in enumerate(words):
            if re.fullmatch(r"[가-힣]{1,6}[시군]", w) and not w.endswith(BIG):
                gu = words[i + 1] if i + 1 < len(words) and re.fullmatch(r"[가-힣]{1,4}구", words[i + 1]) else None
                return w + (" " + gu if gu else ""), w[:-1]
    return None, None


def kakao(path, q, key):
    url = f"https://dapi.kakao.com/v2/local/search/{path}.json?" + urllib.parse.urlencode({"query": q, "size": 5})
    req = urllib.request.Request(url, headers={"Authorization": "KakaoAK " + key})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)["documents"]


def locate(addr, hint, key):
    """hint = '관리 기관|몰린 점의 시·군·구'. → (x, y, 방법) 또는 (None, None, 'none')"""
    a = clean(addr)
    a = re.sub(r"\\n|\n", " ", a).strip()
    region, core = region_of(a, *hint.split("|"))
    if any(ch.isdigit() for ch in a):
        qs = [a, trimmed(a) or a]
        if region and region_of(a)[0] is None:  # '경기도 백암면 가창리 271-53'처럼 시·군이 빠진 주소
            bare = re.sub(r"^[가-힣]+(?:도|특별시|광역시|특별자치시|특별자치도)\s+", "", a)
            qs += [f"{region} {bare}", f"{region} {trimmed(bare) or bare}"]
        for q in dict.fromkeys(qs):
            docs = kakao("address", q, key)
            if docs and (core is None or core in docs[0]["address_name"]):
                return float(docs[0]["x"]), float(docs[0]["y"]), "카카오 주소 검색"
    if region:
        place = a.split(",")[-1].strip() if "," in a else a
        tries = [place]
        while TAIL.search(tries[-1]):
            tries.append(TAIL.sub("", tries[-1]).strip())
        if " " in tries[-1]:  # '음양1리 푸른솔식당' → '푸른솔식당'
            tries.append(tries[-1].split()[-1])
        for p in dict.fromkeys(t for t in tries if len(t) >= 2):
            q = p if core and core in p else f"{region} {p}"
            for d in kakao("keyword", q, key):
                if core in d["address_name"]:
                    return float(d["x"]), float(d["y"]), "카카오 장소 검색"
    return None, None, "none"


def cell_region(x, y, key):
    """몰린 점이 있는 시·군·구 (예: '용인시 처인구'). 관리 기관 좌표로 찍힌 경우가 많아 주소에 시·군이 없을 때 쓴다"""
    url = "https://dapi.kakao.com/v2/local/geo/coord2regioncode.json?" + urllib.parse.urlencode({"x": x, "y": y})
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"Authorization": "KakaoAK " + key}),
                                    timeout=10) as r:
            docs = json.load(r)["documents"]
        return docs[0]["region_2depth_name"] if docs else ""
    except Exception:
        return ""


def piles(table, key):
    rows = query(f"SELECT c.id, c.source_id, IFNULL(c.address, ''), IFNULL({HINT_COL.get(table, 'NULL')}, ''), "
                 f"c.lon, c.lat, ROUND(c.lon, 4), ROUND(c.lat, 4) FROM {table} c JOIN ("
                 f"SELECT ROUND(lon, 4) x, ROUND(lat, 4) y FROM {table} GROUP BY x, y "
                 f"HAVING COUNT(*) >= 10 AND COUNT(DISTINCT address) >= 5) p "
                 f"ON ROUND(c.lon, 4) = p.x AND ROUND(c.lat, 4) = p.y")
    cells = defaultdict(list)
    for r in rows:
        cells[(r[6], r[7])].append(r)
    keep = []
    for (x, y), cell in cells.items():
        if len({clean(r[2]) for r in cell}) >= 5:
            reg = cell_region(x, y, key)
            keep += [r[:3] + [f"{r[3]}|{reg}"] + r[4:] for r in cell]
    print(f"{table}: 몰린 칸 {len(cells)}곳 {len(rows)}개 중 고칠 것 {len(keep)}개 "
          f"(나머지는 주소가 거의 같아 실제로 한 부지로 봄)")
    return keep


def main():
    tables = [a for a in sys.argv[1:] if not a.startswith("--")] or DEFAULT
    key = env()["KAKAO_REST_KEY"]
    cache = {}
    if os.path.exists(CACHE):
        with open(CACHE, encoding="utf-8") as f:
            cache = {(r["addr"], r["hint"]): r for r in csv.DictReader(f)}
    todo = {t: piles(t, key) for t in tables}
    ask = sorted({(r[2], r[3]) for rows in todo.values() for r in rows} - set(cache))
    print(f"검색할 주소 {len(ask)}개 (저장해 둔 것 {len(cache)}개)")

    def one(k):
        try:
            return k, locate(k[0], k[1], key), None
        except Exception as e:  # 호출 오류는 다음 실행 때 다시
            return k, None, type(e).__name__

    new_file = not os.path.exists(CACHE)
    errors = 0
    with open(CACHE, "a", encoding="utf-8", newline="") as f, concurrent.futures.ThreadPoolExecutor(6) as pool:
        w = csv.writer(f)
        if new_file:
            w.writerow(["addr", "hint", "x", "y", "how"])
        for i, (k, res, err) in enumerate(pool.map(one, ask), 1):
            if err:
                errors += 1
                continue
            x, y, how = res
            row = {"addr": k[0], "hint": k[1], "x": f"{x:.7f}" if x else "", "y": f"{y:.7f}" if y else "", "how": how}
            w.writerow(row.values())
            cache[k] = row
            if i % 1000 == 0:
                print(f"  {i}/{len(ask)}")

    sql, removed = ["SET NAMES utf8mb4;"], []
    for t, rows in todo.items():
        fixed, gone, later, how_n = 0, [], 0, defaultdict(int)
        for rid, sid, addr, hint, lon, lat, _, _ in rows:
            c = cache.get((addr, hint))
            if c is None:
                later += 1
            elif c["x"]:
                fixed += 1
                how_n[c["how"]] += 1
                sql.append(f"UPDATE {t} SET lon = {c['x']}, lat = {c['y']}, geom = ST_SRID(POINT({c['x']}, {c['y']}), 0), "
                           f"attrs = JSON_SET(COALESCE(attrs, JSON_OBJECT()), '$.\"좌표\"', '{c['how']} (원본 좌표 몰림)') "
                           f"WHERE id = {rid};")
            else:
                gone.append(rid)
                removed.append((t, rid, sid, addr, hint, lon, lat))
        sql += move_sql(t, gone, "좌표 몰림·주소로 못 찾음 (카카오 주소·장소 검색 실패)")
        print(f"{t}: 고침 {fixed}개 ({', '.join(f'{h} {n}' for h, n in how_n.items())}), 지움 {len(gone)}개, "
              f"다음에 다시 {later}개")
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(sql) + "\n")
    with open(REMOVED, "a", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        if f.tell() == 0:
            w.writerow(["table", "id", "source_id", "address", "hint", "lon", "lat"])
        w.writerows(removed)
    print(f"SQL: {OUT} | 지운 목록: {REMOVED}" + (f" | 호출 오류 {errors}개" if errors else ""))
    if "--sql-only" not in sys.argv:
        run_file(OUT)
        print("DB 적재 끝")


if __name__ == "__main__":
    main()
