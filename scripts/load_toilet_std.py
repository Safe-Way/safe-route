"""공공데이터포털 공중화장실 CSV(주소만 있고 좌표 없음)를 카카오 주소 검색으로 좌표로 바꿔 public_toilet 표에 넣는다.

왜: 지금 쓰던 생활안전지도 공중화장실 API(IF_0132)는 약 2.5만 곳이 엉뚱한 좌표 세 점에 몰려 있고
(강릉 쪽 한 점에 14,798곳 등) 주소 칸도 모두 비어 있다(2026-11-02 확인). 새 CSV는 주소가 모두 있다.

- 원본: data/raw/national/public_toilet_std.csv (공공데이터포털 "공중화장실정보", cp949, 53,578건)
- 도로명주소로 먼저 찾고, 없으면 지번주소로 찾는다. 결과는 data/raw/national/public_toilet_geocode.csv에
  남겨 두고 다음에 다시 쓴다 (같은 주소를 두 번 부르지 않음)
- 넣을 지역의 생활안전지도(safemap) 화장실은 지우고 새 자료(data_go_kr)로 바꾼다
- 카카오 REST 키는 .env의 KAKAO_REST_KEY (하루 10만 번 무료)

실행: python3 scripts/load_toilet_std.py [gwangju | all] [--sql-only]   (기본 gwangju)
"""
import concurrent.futures
import csv
import io
import json
import os
import sys
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_edge_stat import ROOT, env, run_file  # noqa: E402

SRC = os.path.join(ROOT, "data", "raw", "national", "public_toilet_std.csv")
CACHE = os.path.join(ROOT, "data", "raw", "national", "public_toilet_geocode.csv")
OUT = os.path.join(ROOT, "data", "raw", "national", "public_toilet_std.sql")
GWANGJU = ("광주광역시",) + tuple(f"전남광주통합특별시 {g} " for g in ("동구", "서구", "남구", "북구", "광산구"))
GWANGJU_SIDO = "24"  # SGIS 시도 코드 (admin_area)
KEEP = {  # CSV 칸 → attrs 이름 (telno·opn_hr는 표의 phone·open_hours 가상 칸이 읽음)
    "전화번호": "telno", "개방시간": "opn_hr", "개방시간상세": "opn_hr_detail", "관리기관명": "mng_inst_nm",
    "소재지지번주소": "lotno_addr", "남성용-장애인용대변기수": "ml_pwdbs_wacl_cnt", "여성용-장애인용대변기수": "fml_pwdbs_wacl_cnt",
    "남성용-어린이용대변기수": "ml_kid_wacl_cnt", "여성용-어린이용대변기수": "fml_kid_wacl_cnt", "비상벨설치여부": "emcy_bl_instl_yn",
    "화장실입구CCTV설치유무": "fclty_cctv_instl_yn", "기저귀교환대유무": "dpr_exch_yn", "설치연월": "instl_ym",
    "화장실소유구분명": "owner", "데이터기준일자": "dtin_stda",
}


def precise(addr):
    """번지(숫자)가 있는 주소만 믿는다. '대전광역시 서구'처럼 구까지만 적힌 주소는 구청 근처 한 점으로 찍힌다"""
    return any(ch.isdigit() for ch in addr)


def read_rows(region):
    text = open(SRC, "rb").read().decode("cp949")
    rows = list(csv.DictReader(io.StringIO(text)))
    if region == "gwangju":
        rows = [r for r in rows if (r["소재지도로명주소"] or r["소재지지번주소"]).startswith(GWANGJU)]
    return rows


def load_cache():
    if not os.path.exists(CACHE):
        return {}
    with open(CACHE, encoding="utf-8") as f:
        return {r["id"]: r for r in csv.DictReader(f)}


def kakao(query, key):
    url = "https://dapi.kakao.com/v2/local/search/address.json?" + urllib.parse.urlencode({"query": query, "size": 1})
    req = urllib.request.Request(url, headers={"Authorization": "KakaoAK " + key})
    with urllib.request.urlopen(req, timeout=10) as r:
        docs = json.load(r)["documents"]
    return (float(docs[0]["x"]), float(docs[0]["y"])) if docs else None


def geocode(rows, cache):
    key = env()["KAKAO_REST_KEY"]
    todo = [r for r in rows if r["관리번호"] not in cache]
    print(f"좌표 찾기: {len(todo)}건 (저장해 둔 것 {len(rows) - len(todo)}건)")

    def one(r):
        for how, col in (("road", "소재지도로명주소"), ("lotno", "소재지지번주소")):
            q = r[col].strip()
            if precise(q):
                try:
                    xy = kakao(q, key)
                except Exception as e:  # 네트워크 오류는 이번엔 건너뛰고 다음 실행 때 다시
                    return r["관리번호"], None, "error:" + type(e).__name__
                if xy:
                    return r["관리번호"], xy, how
        return r["관리번호"], None, "none"

    new = 0
    write_header = not os.path.exists(CACHE)
    with open(CACHE, "a", encoding="utf-8", newline="") as f, \
            concurrent.futures.ThreadPoolExecutor(6) as pool:
        w = csv.writer(f)
        if write_header:
            w.writerow(["id", "x", "y", "how"])
        for i, (rid, xy, how) in enumerate(pool.map(one, todo), 1):
            if how.startswith("error"):
                continue
            row = {"id": rid, "x": f"{xy[0]:.7f}" if xy else "", "y": f"{xy[1]:.7f}" if xy else "", "how": how}
            w.writerow(row.values())
            cache[rid] = row
            new += 1
            if i % 500 == 0:
                print(f"  {i}/{len(todo)}")
    return new


def q(v):
    return "NULL" if v is None or v == "" else "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def main():
    args = sys.argv[1:]
    region = "all" if "all" in args else "gwangju"
    rows = read_rows(region)
    cache = load_cache()
    geocode(rows, cache)
    def usable(r):
        c = cache.get(r["관리번호"], {})
        col = {"road": "소재지도로명주소", "lotno": "소재지지번주소"}.get(c.get("how"))
        return bool(c.get("x")) and col is not None and precise(r[col])

    ok = [(r, cache[r["관리번호"]]) for r in rows if usable(r)]
    vague = sum(1 for r in rows if cache.get(r["관리번호"], {}).get("x") and not usable(r))
    how = {}
    for r in rows:
        h = cache.get(r["관리번호"], {}).get("how", "error")
        how[h] = how.get(h, 0) + 1
    print(f"{region}: {len(rows)}건 중 좌표 {len(ok)}건 | 찾은 방법 {how} | 번지 없는 주소라 뺀 것 {vague}건")

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("SET NAMES utf8mb4;\n")
        if region == "gwangju":
            # 광주 경계 안의 옛 생활안전지도 화장실을 지운다
            f.write("DELETE t FROM public_toilet t JOIN admin_area a ON a.level = 'sido' AND a.code = "
                    f"'{GWANGJU_SIDO}' AND MBRContains(a.geom, t.geom) AND ST_Contains(a.geom, t.geom) "
                    "WHERE t.source = 'safemap';\n")
        else:
            f.write("DELETE FROM public_toilet WHERE source = 'safemap';\n")
        for k in range(0, len(ok), 500):
            vals = []
            for r, c in ok[k:k + 500]:
                attrs = {v: r[col].strip() for col, v in KEEP.items() if r.get(col, "").strip()}
                vals.append(f"({q(r['구분명'] or None)},'data_go_kr',{q(r['관리번호'])},{q(r['화장실명'])},"
                            f"{q(r['소재지도로명주소'] or r['소재지지번주소'])},{c['x']},{c['y']},"
                            f"ST_SRID(POINT({c['x']},{c['y']}),0),{q(json.dumps(attrs, ensure_ascii=False))})")
            f.write("INSERT INTO public_toilet (kind, source, source_id, name, address, lon, lat, geom, attrs) VALUES\n"
                    + ",\n".join(vals) + "\nON DUPLICATE KEY UPDATE kind = VALUES(kind), name = VALUES(name), "
                    "address = VALUES(address), lon = VALUES(lon), lat = VALUES(lat), geom = VALUES(geom), "
                    "attrs = VALUES(attrs);\n")
        f.write("INSERT INTO data_source (type, source, title, api, license, total_count, loaded_count, skipped_count,"
                " complete, loaded_at)\nVALUES ('public_toilet', 'data_go_kr', "
                f"'공중화장실정보 CSV ({'광주' if region == 'gwangju' else '전국'}, 카카오 주소 검색으로 좌표)', "
                "'공공데이터포털 CSV + 카카오 로컬 주소 검색', '확인 필요', "
                f"{len(rows)}, {len(ok)}, {len(rows) - len(ok)}, {1 if region == 'all' else 0}, NOW())\n"
                "ON DUPLICATE KEY UPDATE title = VALUES(title), total_count = VALUES(total_count), "
                "loaded_count = VALUES(loaded_count), skipped_count = VALUES(skipped_count), "
                "complete = VALUES(complete), loaded_at = NOW();\n")
    print("SQL:", OUT)
    if "--sql-only" not in args:
        run_file(OUT)
        print("DB 적재 끝")


if __name__ == "__main__":
    main()
