"""길 위의 행사·돌발상황을 받아 road_event 표(026)에 넣는다. 광주 시도 경계 안만.

- 행사(festival): 공공데이터포털 전국문화축제표준데이터 API (DATA_GO_KR_KEY, 활용 신청 필요).
  전국을 모두 받아 끝난 지 7일이 지난 행사는 뺀다. 좌표가 없으면 카카오 주소 검색 → 장소 검색으로 찾고,
  찾은 좌표는 data/raw/regional/festival_geocode.csv에 남겨 다시 쓴다(같은 장소를 두 번 부르지 않음).
  원본에 행사 번호가 없어 이름·기간·장소로 source_id를 만든다.
- 돌발(its): 국가교통정보센터 돌발상황 API (ITS_API_KEY). 광주 범위 상자로 지금 있는 공사·사고·통제를 받는다.
  대부분 고속도로·국도라 걷는 길에는 적게 걸린다. 키가 없으면 건너뛴다.
- 출처마다 지우고 새로 넣으므로 여러 번 돌려도 된다(돌발은 그때그때 바뀜).
- 영향 반경: 축제 300m("일원"), 돌발 100m.

먼저 할 일: src/main/resources/db/migration/026_road_event.sql
실행: python3 scripts/load_events.py [--sql-only]
"""
import csv
import hashlib
import json
import math
import os
import sys
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_edge_stat import query, run_file  # noqa: E402
from fix_pile_coords import kakao  # noqa: E402
from load_road_network import AREA_BBOX, ROOT, read_env  # noqa: E402

OUT = os.path.join(ROOT, "data", "raw", "regional", "road_event.sql")
GEOCODE = os.path.join(ROOT, "data", "raw", "regional", "festival_geocode.csv")
FESTIVAL_API = "http://api.data.go.kr/openapi/tn_pubr_public_cltur_fstvl_api"
ITS_API = "https://openapi.its.go.kr:9443/eventInfo"
SIDO = "24"  # 광주 (SGIS), build_poi.py와 같음
SOUTH, WEST, NORTH, EAST = AREA_BBOX["KR-29"]
KEEP_ENDED_DAYS = 7
ITS_KIND = {"공사": "공사", "교통사고": "사고", "재난": "재난", "기상": "재난", "기타돌발": "기타", "기타": "기타"}


def q(v, n=300):
    if v is None or v == "":
        return "NULL"
    s = str(v)[:n].replace("\\", "\\\\").replace("'", "''")
    return f"'{s}'"


def in_box(lat, lon):
    return SOUTH <= lat <= NORTH and WEST <= lon <= EAST


def get_json(url, params, timeout=60):
    u = url + "?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(u, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


# ---------- 행사 ----------

def festival_pages(key):
    page, items = 1, []
    while True:
        d = get_json(FESTIVAL_API, {"serviceKey": key, "pageNo": page, "numOfRows": 1000, "type": "json"})
        if "body" not in d.get("response", d):
            raise SystemExit("축제 API가 정상 응답을 주지 않았어요 (활용 신청·키 확인): "
                             + json.dumps(d, ensure_ascii=False)[:200])
        body = d.get("response", d)["body"]
        got = body["items"]
        got = got.get("item", got) if isinstance(got, dict) else got
        items += got if isinstance(got, list) else [got]
        if len(items) >= int(body["totalCount"]) or not got:
            return items
        page += 1


def load_geocode():
    if not os.path.exists(GEOCODE):
        return {}
    with open(GEOCODE, encoding="utf-8") as f:
        return {r["query"]: r for r in csv.DictReader(f)}


def save_geocode(cache):
    os.makedirs(os.path.dirname(GEOCODE), exist_ok=True)
    with open(GEOCODE, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["query", "lon", "lat", "how"])
        w.writeheader()
        for r in cache.values():
            w.writerow(r)


def geocode(item, kakao_key, cache):
    """좌표 없는 행사 → (lon, lat, 방법). 광주 주소로 나온 결과만 쓴다"""
    addr = item.get("rdnmadr") or item.get("lnmadr") or ""
    place = (item.get("opar") or "").split("+")[0].split(" 및 ")[0].strip()
    tries = [("address", addr, "카카오 주소 검색")] if addr else []
    if place:
        tries.append(("keyword", f"광주 {place}", "카카오 장소 검색"))
    for path, text, how in tries:
        if text in cache:
            c = cache[text]
            if c["lon"]:
                return float(c["lon"]), float(c["lat"]), c["how"]
            continue
        found = None
        try:
            for doc in kakao(path, text, kakao_key):
                full = (doc.get("address_name") or "") + (doc.get("road_address_name") or "")
                if "광주" in full and "경기" not in full:
                    found = (float(doc["x"]), float(doc["y"]))
                    break
        except Exception as e:  # 호출 오류는 저장하지 않고 다음 실행 때 다시
            print(f"  카카오 검색 실패 ({text}): {e}")
            continue
        cache[text] = {"query": text, "lon": found[0] if found else "", "lat": found[1] if found else "", "how": how}
        if found:
            return found[0], found[1], how
    return None, None, None


def festival_rows(env, today):
    items = festival_pages(env["DATA_GO_KR_KEY"])
    cache = load_geocode()
    rows, skipped = [], {"끝남": 0, "광주 아님": 0, "좌표 못 찾음": 0}
    for it in items:
        try:
            end = datetime.strptime(it.get("fstvlEndDate") or "", "%Y-%m-%d").date()
        except ValueError:
            end = None
        if end and end < today - timedelta(days=KEEP_ENDED_DAYS):
            skipped["끝남"] += 1
            continue
        try:
            lat, lon = float(it.get("latitude") or 0), float(it.get("longitude") or 0)
        except ValueError:
            lat = lon = 0.0
        how = "원본"
        if not (lat and lon):
            text = (it.get("rdnmadr") or "") + (it.get("lnmadr") or "") + (it.get("insttNm") or "")
            if "광주" not in text:
                skipped["광주 아님"] += 1
                continue
            lon, lat, how = geocode(it, env["KAKAO_REST_KEY"], cache)
            if lon is None:
                skipped["좌표 못 찾음"] += 1
                continue
        if not in_box(lat, lon):  # 경계 밖은 DB에 넣은 뒤 시도 경계로 한 번 더 거른다
            skipped["광주 아님"] += 1
            continue
        name = it.get("fstvlNm") or "행사"
        start = it.get("fstvlStartDate") or None
        sid = hashlib.sha1("|".join([name, start or "", it.get("fstvlEndDate") or "", it.get("opar") or ""])
                           .encode("utf-8")).hexdigest()[:16]
        rows.append({
            "source": "festival", "source_id": sid, "kind": "행사", "detail": "축제", "title": name,
            "place": it.get("opar"), "address": it.get("rdnmadr") or it.get("lnmadr"), "lon": lon, "lat": lat,
            "radius_m": 300, "starts_at": f"{start} 00:00:00" if start else None,
            "ends_at": f"{it['fstvlEndDate']} 23:59:59" if end else None, "lanes_blocked": None,
            "message": (it.get("fstvlCo") or "")[:300] or None, "url": it.get("homepageUrl"), "coord_from": how})
    save_geocode(cache)
    print(f"행사: 전국 {len(items)}건 → 광주 범위 {len(rows)}건 (뺀 것 {skipped})")
    return rows


# ---------- 돌발상황 ----------

def its_time(v):
    try:
        return datetime.strptime(v, "%Y%m%d%H%M%S").strftime("%Y-%m-%d %H:%M:%S")
    except (TypeError, ValueError):
        return None


def its_rows(env):
    key = env.get("ITS_API_KEY", "")
    if not key:
        print("돌발: .env에 ITS_API_KEY가 없어 건너뜀")
        return None
    d = get_json(ITS_API, {"apiKey": key, "type": "all", "eventType": "all", "minX": WEST, "maxX": EAST,
                           "minY": SOUTH, "maxY": NORTH, "getType": "json"})
    if str(d.get("header", {}).get("resultCode")) != "0":
        raise SystemExit("돌발상황 API 오류: " + json.dumps(d.get("header"), ensure_ascii=False))
    rows = []
    for it in d["body"].get("items") or []:
        try:
            lon, lat = float(it["coordX"]), float(it["coordY"])
        except (KeyError, ValueError):
            continue
        kind = ITS_KIND.get(it.get("eventType"), "기타")
        if (it.get("lanesBlockType") or "").startswith("전면"):
            kind = "통제" if kind in ("공사", "기타") else kind
        start = its_time(it.get("startDate"))
        sid = hashlib.sha1("|".join([it.get("linkId") or "", it.get("startDate") or "", it.get("eventType") or "",
                                     f"{lon:.5f},{lat:.5f}"]).encode("utf-8")).hexdigest()[:16]
        rows.append({
            "source": "its", "source_id": sid, "kind": kind, "detail": it.get("eventDetailType") or None,
            "title": f"{it.get('roadName') or '도로'} {it.get('eventType') or '돌발'}", "place": it.get("roadName"),
            "address": None, "lon": lon, "lat": lat, "radius_m": 100, "starts_at": start,
            "ends_at": its_time(it.get("endDate")), "lanes_blocked": it.get("lanesBlocked") or None,
            "message": it.get("message") or None, "url": None, "coord_from": "원본"})
    print(f"돌발: 광주 범위 {len(rows)}건 (도로 종류 {sorted({i.get('type') for i in d['body'].get('items') or []})})")
    return rows


# ---------- 넣기 ----------

def main():
    env = read_env()
    today = date.today()
    fest = festival_rows(env, today)
    its = its_rows(env)
    cols = ["source", "source_id", "kind", "detail", "title", "place", "address", "lon", "lat", "radius_m",
            "starts_at", "ends_at", "lanes_blocked", "message", "url", "coord_from"]
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("SET NAMES utf8mb4;\n")
        for source, rows in (("festival", fest), ("its", its)):
            if rows is None:  # 키가 없어 못 받은 출처는 옛 줄을 그대로 둔다
                continue
            f.write(f"DELETE FROM road_event WHERE source = '{source}';\n")
            for r in rows:
                vals = []
                for c in cols:
                    v = r[c]
                    vals.append(str(v) if c in ("lon", "lat", "radius_m") else q(v, 200 if c == "title" else 300))
                f.write(f"INSERT IGNORE INTO road_event ({', '.join(cols)}, geom, fetched_at) VALUES ({', '.join(vals)}, "
                        f"ST_SRID(POINT({r['lon']}, {r['lat']}), 0), NOW());\n")
        # 범위 상자에는 나주·담양 일부가 섞이므로 광주 시도 경계 밖은 뺀다 (build_poi.py와 같은 기준)
        f.write(f"DELETE e FROM road_event e WHERE NOT EXISTS (SELECT 1 FROM admin_area a WHERE a.level = 'sido' "
                f"AND a.code = '{SIDO}' AND MBRContains(a.geom, e.geom) AND ST_Contains(a.geom, e.geom));\n")
    print("SQL:", OUT)
    if "--sql-only" in sys.argv:
        return
    run_file(OUT)
    for source, kind, n, now in query("SELECT source, kind, COUNT(*), SUM(starts_at <= NOW() AND (ends_at IS NULL OR "
                                      "ends_at >= NOW())) FROM road_event GROUP BY 1, 2 ORDER BY 1, 2"):
        print(f"  {source} {kind}: {n}건 (지금 진행 중 {now})")


if __name__ == "__main__":
    main()
