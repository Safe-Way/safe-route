"""처음 적재 때 좌표가 없거나 국외 좌표라 빠진 시설을 카카오 지도 검색으로 좌표를 찾아 넣는다.

빠진 것 (2026-11-02, data_source.skipped_count)
- 보안등 표준데이터(security_light_std): 좌표 없음 269,359 + 국외 좌표 4,765. 주소는 모두 있음 (서로 다른 주소 약 22만)
- 아동안전지킴이집 126, 공통POI 보안등 50, 안전비상벨 9: 좌표가 국외이거나 비어 있음
  (공중화장실은 scripts/load_toilet_std.py, CCTV는 원본 CSV라 여기서 하지 않음)

찾는 법: 도로명주소 → 지번주소 순서로 주소 검색 (번지 숫자가 없는 '송파구 성내천로' 같은 주소는 길 한가운데로 찍혀
몰림이 생기므로 쓰지 않음). 이름이 장소 이름인 표(지킴이집)는 주소로 못 찾으면 '시·군 + 이름'으로 장소 검색.
찾은 줄은 attrs의 "좌표" 항목에 '카카오 주소 검색 (원본 좌표 없음)'처럼 적는다.
못 찾았거나 번지 있는 주소가 없는 줄은 facility_unlocated 표에 원본 줄 그대로 넣어 둔다.

카카오 키는 서버 장소 검색(/api/places)과 같이 쓰고 하루 10만 번까지라, 한 번에 --limit 개(기본 25,000)만 새로 찾는다.
찾은 결과는 data/raw/national/missing_geocode.csv에 남겨 다시 쓰므로, 날마다 다시 돌리면 이어서 채운다.
원본 번호가 없는 표준데이터는 source_id를 'geo:' + 줄 해시로 만든다 (Java 적재가 지우고 다시 넣으면 이 스크립트도 다시 돌림).

광주 주소를 먼저 찾는다. gwangju를 붙이면 광주만 찾는다 (시연 지역).
실행: python3 scripts/fill_missing_coords.py [gwangju] [표 폴더 ...] [--limit 25000] [--sql-only]
"""
import concurrent.futures
import csv
import glob
import hashlib
import json
import math
import os
import re
import sys
from decimal import Decimal, InvalidOperation

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_edge_stat import ROOT, env, query, run_file  # noqa: E402
from fix_pile_coords import kakao, region_of  # noqa: E402

RAW = os.path.join(ROOT, "data", "raw", "national")
CACHE = os.path.join(RAW, "missing_geocode.csv")
OUT = os.path.join(RAW, "missing_fill.sql")

# Java FacilityDataset과 같은 칸 (folder: table, source, id, name, addr, kind, sido, sgg, x, y, 경위도 여부, 개수 칸, 장소 검색)
DATASETS = {
    "security_light_std": dict(table="security_light", source="data_go_kr", id=[], name=["lmpLcNm"],
                               addr=["rdnmadr", "lnmadr"], kind=["installationType"], sido=None, sgg=None,
                               x="longitude", y="latitude", lonlat=True, count="installationCo", keyword=False),
    "child_safety_house": dict(table="child_safety_house", source="safemap", id=["objt_id"], name=["fclty_nm"],
                               addr=["rn_adres", "adres"], kind=["fclty_ty"], sido="ctprvn_cd", sgg="sgg_cd",
                               x="x", y="y", lonlat=False, count=None, keyword=True),
    "emergency_bell": dict(table="emergency_bell", source="safemap", id=["objt_id"], name=[],
                           addr=["rn_adres", "ins_detail", "adres"], kind=["ins_type"], sido="ctprvn_cd",
                           sgg="sgg_cd", x="x", y="y", lonlat=False, count=None, keyword=False),
    "security_light": dict(table="security_light", source="safetydata", id=["SN"],
                           name=["POLSTN_NM", "FCLT_GVMNFC_NM", "NTNLPLCAGNC_NM"], addr=["ROAD_NM_ADDR", "ADDR"],
                           kind=["FCLT_TYPE"], sido="STDG_CTPV_CD", sgg="STDG_SGG_CD", x="XMAP_CRTS", y="YMAP_CRTS",
                           lonlat=False, count=None, keyword=False),
}
COUNT_COL = {"security_light": "lamp_count"}
GWANGJU = re.compile(r"광주광역시|전남광주통합특별시\s*(동구|서구|남구|북구|광산구)")


def is_gwangju(k):
    return bool(GWANGJU.search(" ".join(json.loads(k)[0])))


def clean(v):
    if v is None:
        return None
    v = str(v).strip()
    return None if v == "" or v == "-" or v.lower() == "null" else v


def first(item, keys):
    return next((clean(item.get(k)) for k in keys if clean(item.get(k))), None)


def num(v):
    try:
        return float(clean(v))
    except (TypeError, ValueError):
        return None


def code(item, key, n):
    v = clean(item.get(key)) if key else None
    if v is None:
        return None
    try:
        v = format(Decimal(v).normalize(), "f")
    except InvalidOperation:
        pass
    return v[:n] if len(v) >= n else None


def to_lonlat(x, y, lonlat):
    if lonlat:
        return x, y
    return x / 6378137.0 * 180 / math.pi, (2 * math.atan(math.exp(y / 6378137.0)) - math.pi / 2) * 180 / math.pi


def in_korea(lon, lat):
    return 124.0 <= lon <= 132.0 and 33.0 <= lat <= 39.0


def missing(folder, ds):
    """좌표가 없거나 국외라 Java 적재에서 빠진 줄"""
    out = []
    for p in sorted(glob.glob(os.path.join(RAW, folder, "p*.json"))):
        with open(p, encoding="utf-8") as f:
            for item in json.load(f):
                x, y = num(item.get(ds["x"])), num(item.get(ds["y"]))
                if x is not None and y is not None and in_korea(*to_lonlat(x, y, ds["lonlat"])):
                    continue
                out.append(item)
    return out


def addresses(item, ds):
    """번지 숫자가 있는 주소만, 중복 없이"""
    return list(dict.fromkeys(a for a in (clean(item.get(k)) for k in ds["addr"]) if a and any(c.isdigit() for c in a)))


def locate(addrs, name, key):
    for a in addrs:
        region, core = region_of(a)
        docs = kakao("address", a, key)
        if docs and (core is None or core in docs[0]["address_name"]):
            return float(docs[0]["x"]), float(docs[0]["y"]), "카카오 주소 검색"
    if name and addrs:
        region, core = region_of(*addrs)
        if region:
            for d in kakao("keyword", f"{region} {name}", key):
                if core in d["address_name"]:
                    return float(d["x"]), float(d["y"]), "카카오 장소 검색"
    return None, None, "none"


def q(v):
    return "NULL" if v is None else "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def row_sql(item, ds, x, y, how):
    sid = first(item, ds["id"]) or "geo:" + hashlib.sha1(
        json.dumps(item, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()[:24]
    used = set([ds["x"], ds["y"], "GEOM"] + ds["id"] + ds["name"] + ds["addr"] + ds["kind"]
               + [k for k in (ds["count"], ds["sido"], ds["sgg"]) if k])
    attrs = {k: clean(v) for k, v in item.items() if k not in used and clean(v) is not None}
    attrs["좌표"] = f"{how} (원본 좌표 없음)"
    vals = [q(first(item, ds["kind"])), q(ds["source"]), q(sid[:64]), q((first(item, ds["name"]) or "")[:200] or None),
            q((first(item, ds["addr"]) or "")[:300] or None), q(code(item, ds["sido"], 2)), q(code(item, ds["sgg"], 5)),
            f"{x:.7f}", f"{y:.7f}", f"ST_SRID(POINT({x:.7f}, {y:.7f}), 0)", q(json.dumps(attrs, ensure_ascii=False))]
    if ds["table"] in COUNT_COL:
        c = num(item.get(ds["count"])) if ds["count"] else None
        vals.append(str(1 if c is None or c < 1 else int(min(c, 32767))))
    return "(" + ",".join(vals) + ")"


def unlocated_sql(item, ds, reason, tried):
    """못 찾은 줄 → facility_unlocated (014). 원본 줄 전체를 attrs에 둔다"""
    sid = first(item, ds["id"]) or "geo:" + hashlib.sha1(
        json.dumps(item, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()[:24]
    x, y = num(item.get(ds["x"])), num(item.get(ds["y"]))
    lon, lat = to_lonlat(x, y, ds["lonlat"]) if x is not None and y is not None else (None, None)
    ok = lon is not None and abs(lon) <= 180 and abs(lat) <= 90
    return (f"({q(ds['table'])},{q(ds['source'])},{q(sid[:64])},{q((first(item, ds['kind']) or '')[:50] or None)},"
            f"{q((first(item, ds['name']) or '')[:200] or None)},{q((first(item, ds['addr']) or '')[:300] or None)},"
            f"{f'{lon:.7f}' if ok else 'NULL'},{f'{lat:.7f}' if ok else 'NULL'},{q(reason)},{q(tried[:300])},"
            f"{q(json.dumps(item, ensure_ascii=False))},NOW())")


def main():
    args = sys.argv[1:]
    limit = int(args[args.index("--limit") + 1]) if "--limit" in args else 25000
    folders = [a for a in args if a in DATASETS] or list(DATASETS)
    key = env()["KAKAO_REST_KEY"]
    cache = {}
    if os.path.exists(CACHE):
        with open(CACHE, encoding="utf-8") as f:
            cache = {r["q"]: r for r in csv.DictReader(f)}
    work = {}
    for folder in folders:
        ds = DATASETS[folder]
        items = missing(folder, ds)
        work[folder] = [(it, json.dumps([addresses(it, ds), first(it, ds["name"]) if ds["keyword"] else None],
                                        ensure_ascii=False)) for it in items]
        print(f"{folder}: 빠진 줄 {len(items):,} (번지 있는 주소 없음 {sum(1 for it in items if not addresses(it, ds)):,})")
    todo = list(dict.fromkeys(k for rows in work.values() for _, k in rows
                              if k not in cache and json.loads(k)[0]))
    gj = [k for k in todo if is_gwangju(k)]
    todo = gj + ([] if "gwangju" in args else [k for k in todo if not is_gwangju(k)])
    print(f"광주 주소 {len(gj):,}개를 먼저 찾음" + (" (광주만)" if "gwangju" in args else ""))
    print(f"새로 찾을 것 {len(todo):,}개 (저장해 둔 것 {len(cache):,}개), 이번에 {min(limit, len(todo)):,}개")
    todo = todo[:limit]

    def one(k):
        a, name = json.loads(k)
        try:
            return k, locate(a, name, key), None
        except Exception as e:
            return k, None, f"{type(e).__name__}: {getattr(e, 'code', '')}"

    new_file = not os.path.exists(CACHE)
    errors, quota_hit = 0, False
    with open(CACHE, "a", encoding="utf-8", newline="") as f, concurrent.futures.ThreadPoolExecutor(6) as pool:
        w = csv.writer(f)
        if new_file:
            w.writerow(["q", "x", "y", "how"])
        for i, (k, res, err) in enumerate(pool.map(one, todo), 1):
            if err:
                errors += 1
                quota_hit = quota_hit or "429" in err
                continue
            x, y, how = res
            row = {"q": k, "x": f"{x:.7f}" if x else "", "y": f"{y:.7f}" if y else "", "how": how}
            w.writerow(row.values())
            cache[k] = row
            if i % 2000 == 0:
                print(f"  {i:,}/{len(todo):,}", flush=True)

    sql = ["SET NAMES utf8mb4;"]
    for folder, rows in work.items():
        ds = DATASETS[folder]
        vals, gone, found, none, left = [], [], 0, 0, 0
        for it, k in rows:
            c = cache.get(k)
            if c and c["x"]:
                found += 1
                vals.append(row_sql(it, ds, float(c["x"]), float(c["y"]), c["how"]))
            elif c or not json.loads(k)[0]:
                none += 1
                a = json.loads(k)[0]
                gone.append(unlocated_sql(it, ds, "좌표 없음·주소로 못 찾음 (카카오에 없음)" if a
                                          else "좌표 없음·번지 있는 주소 없음", " / ".join(a) or (first(it, ds["addr"]) or "")))
            else:
                left += 1
        cols = "kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs" + (
            ", " + COUNT_COL[ds["table"]] if ds["table"] in COUNT_COL else "")
        for i in range(0, len(vals), 1000):
            sql.append(f"INSERT IGNORE INTO {ds['table']} ({cols}) VALUES\n" + ",\n".join(vals[i:i + 1000]) + ";")
        for i in range(0, len(gone), 1000):
            sql.append("INSERT INTO facility_unlocated (tbl, source, source_id, kind, name, address, lon, lat, reason, "
                       "tried, attrs, moved_at) VALUES\n" + ",\n".join(gone[i:i + 1000])
                       + "\nON DUPLICATE KEY UPDATE reason = VALUES(reason), moved_at = NOW();")
        sql.append(f"UPDATE data_source d SET loaded_count = (SELECT COUNT(*) FROM {ds['table']} WHERE source = "
                   f"'{ds['source']}'), skipped_count = GREATEST(0, total_count - loaded_count) "
                   f"WHERE type = '{ds['table']}' AND source = '{ds['source']}';")
        print(f"{folder}: 좌표 찾음 {found:,}, 못 찾음 {none:,}, 아직 안 찾음 {left:,}")
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(sql) + "\n")
    print("SQL:", OUT, f"| 호출 오류 {errors}개" if errors else "",
          "| 카카오 하루 한도에 걸림: 내일 다시 돌리면 이어서 찾음" if quota_hit else "")
    if "--sql-only" not in args:
        run_file(OUT)
        print("DB 적재 끝")


if __name__ == "__main__":
    main()
