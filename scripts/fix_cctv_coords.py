"""행정안전부 CCTV 파일(localdata)의 잘못된 좌표를 주소로 다시 찾아 고친다.

왜: 일부 지자체가 좌표를 잘못 올렸다(2026-11-02 확인). 충남 공주시 1,579대가 모두 "경도 127, 위도 36" 한 점,
대전 대덕구 990대가 "127.1, 36.1" 한 점, 전남 영광군 422대가 한 점에 몰려 있는 식이다. 주소는 들어 있다.

잘못된 좌표로 보는 것
- 소수점 둘째 자리 아래가 없는 좌표 (약 1km 정밀도도 안 됨)
- 약 10m 칸 하나에 CCTV 20대 이상이 있고 서로 다른 주소가 10개 이상인 곳

고치는 법: 주소(괄호 안 설명은 뺌)를 카카오 주소 검색으로 찾고, 없으면 번지 뒤 설명('앞 전신주' 등)을 잘라 한 번 더 찾는다. 번지가 없는 주소나 못 찾은 것은
틀린 위치를 남기지 않으려고 지우고 data/raw/national/cctv_removed.csv에 남긴다.
찾은 좌표는 data/raw/national/cctv_geocode.csv에 저장해 다시 쓴다.
CCTV를 다시 적재(Java FacilityImporter)하면 잘못된 좌표가 다시 들어오므로 이 스크립트도 다시 돌린다.

실행: python3 scripts/fix_cctv_coords.py [--sql-only]
"""
import concurrent.futures
import csv
import json
import os
import re
import sys
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_edge_stat import ROOT, env, query, run_file  # noqa: E402

CACHE = os.path.join(ROOT, "data", "raw", "national", "cctv_geocode.csv")
REMOVED = os.path.join(ROOT, "data", "raw", "national", "cctv_removed.csv")
OUT = os.path.join(ROOT, "data", "raw", "national", "cctv_fix.sql")


def bad_rows():
    low = query("SELECT id, source_id, IFNULL(address,''), lon, lat FROM cctv WHERE source = 'localdata' "
                "AND lon = ROUND(lon, 2) AND lat = ROUND(lat, 2)")
    pile = query("SELECT c.id, c.source_id, IFNULL(c.address,''), c.lon, c.lat FROM cctv c JOIN ("
                 "SELECT ROUND(lon, 4) x, ROUND(lat, 4) y FROM cctv GROUP BY x, y "
                 "HAVING COUNT(*) >= 20 AND COUNT(DISTINCT address) >= 10) p "
                 "ON ROUND(c.lon, 4) = p.x AND ROUND(c.lat, 4) = p.y WHERE c.source = 'localdata'")
    rows = {r[0]: r for r in low + pile}
    print(f"잘못된 좌표: 소수점 잘림 {len(low)}대, 한 점에 몰림 {len(pile)}대, 합쳐서 {len(rows)}대")
    return list(rows.values())


def clean(addr):
    return re.sub(r"\s+", " ", re.sub(r"\([^)]*\)", " ", addr)).strip()


NUMBER = re.compile(r"^(.*?(?:로|길|동|리|가)\s*(?:산\s*)?\d+(?:-\d+)?)(?![\d번길로가])")


def trimmed(addr):
    """번지 뒤에 붙은 설명을 자른다: '인주대로11번길 8-7 앞 전신주' → '인주대로11번길 8-7'"""
    m = NUMBER.match(addr)
    return m.group(1) if m else None


def precise(addr):
    return any(ch.isdigit() for ch in addr)


def kakao(q, key):
    url = "https://dapi.kakao.com/v2/local/search/address.json?" + urllib.parse.urlencode({"query": q, "size": 1})
    req = urllib.request.Request(url, headers={"Authorization": "KakaoAK " + key})
    with urllib.request.urlopen(req, timeout=10) as r:
        docs = json.load(r)["documents"]
    return (float(docs[0]["x"]), float(docs[0]["y"])) if docs else None


def main():
    rows = bad_rows()
    cache = {}
    if os.path.exists(CACHE):
        cache = {r["addr"]: r for r in csv.DictReader(open(CACHE, encoding="utf-8"))}
    addrs = sorted({clean(r[2]) for r in rows if precise(clean(r[2]))} - set(cache))
    print(f"주소 {len(addrs)}개 찾기 (저장해 둔 것 {len(cache)}개)")
    key = env()["KAKAO_REST_KEY"]

    def one(a):
        try:
            xy = kakao(a, key)
            short = trimmed(a)
            if xy is None and short and short != a:
                xy = kakao(short, key)
            return a, xy, None
        except Exception as e:
            return a, None, type(e).__name__

    new_file = not os.path.exists(CACHE)
    with open(CACHE, "a", encoding="utf-8", newline="") as f, concurrent.futures.ThreadPoolExecutor(6) as pool:
        w = csv.writer(f)
        if new_file:
            w.writerow(["addr", "x", "y"])
        for i, (a, xy, err) in enumerate(pool.map(one, addrs), 1):
            if err:
                continue  # 네트워크 오류는 다음 실행 때 다시
            row = {"addr": a, "x": f"{xy[0]:.7f}" if xy else "", "y": f"{xy[1]:.7f}" if xy else ""}
            w.writerow(row.values())
            cache[a] = row
            if i % 1000 == 0:
                print(f"  {i}/{len(addrs)}")

    fixed, removed, later = [], [], 0
    for rid, sid, addr, lon, lat in rows:
        a = clean(addr)
        c = cache.get(a)
        if precise(a) and c is None:
            later += 1  # 호출 오류로 아직 못 찾아봄: 지우지 않고 다음 실행 때 다시
        elif precise(a) and c["x"]:
            fixed.append((rid, c["x"], c["y"]))
        else:
            removed.append((rid, sid, addr, lon, lat))
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("SET NAMES utf8mb4;\n")
        for rid, x, y in fixed:
            f.write(f"UPDATE cctv SET lon = {x}, lat = {y}, geom = ST_SRID(POINT({x}, {y}), 0), "
                    "attrs = JSON_SET(COALESCE(attrs, JSON_OBJECT()), '$.\"좌표\"', '카카오 주소 검색 (원본 좌표 오류)') "
                    f"WHERE id = {rid};\n")
        for k in range(0, len(removed), 1000):
            f.write("DELETE FROM cctv WHERE id IN (" + ",".join(r[0] for r in removed[k:k + 1000]) + ");\n")
    with open(REMOVED, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["id", "source_id", "address", "lon", "lat"])
        w.writerows(removed)
    print(f"고침 {len(fixed)}대, 지움 {len(removed)}대, 다음에 다시 {later}대 (지운 목록: {REMOVED}) | SQL: {OUT}")
    if "--sql-only" not in sys.argv:
        run_file(OUT)
        print("DB 적재 끝")


if __name__ == "__main__":
    main()
