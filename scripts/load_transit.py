"""광주 대중교통 CSV(data/raw/regional/)를 DB 표에 넣는다. 표마다 모두 지우고 새로 넣으므로 여러 번 실행해도 된다.

- 표는 먼저 만든다: src/main/resources/db/migration/019_transit.sql
- CSV 만드는 곳: scripts/fetch_subway_elevator.py (역·출입구), 나머지는 공공데이터포털 파일과 광주버스정보 API (결정 42)
- 열차 시간표 원본은 역 코드가 한 칸 밀린 줄이 있어, 역 이름으로 코드를 다시 붙인다 (녹동 100 ~ 평동 119).
- 좌표 없는 버스 정류장은 넣지 않고 data_source.skipped_count에 센다.

실행: python3 scripts/load_transit.py [--sql-only]
DB 접속은 .env의 DB_URL·DB_USERNAME·DB_PASSWORD를 쓰고, mysql 명령으로 넣는다.
"""
import csv
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from load_road_network import q, run_sql  # noqa: E402  같은 SQL 문자열 처리와 mysql 실행을 쓴다

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "data", "raw", "regional")
OUT = os.path.join(ROOT, "data", "raw", "regional", "transit_load.sql")
BATCH = 1000
# 녹동 100 ~ 평동 119 (역사별 환승 데이터의 코드와 같음)
STATIONS = ["녹동", "소태", "학동증심사입구", "남광주", "문화전당", "금남로4가", "금남로5가", "양동시장", "돌고개", "농성",
            "화정", "쌍촌", "운천", "상무", "김대중컨벤션센터", "공항", "송정공원", "광주송정", "도산", "평동"]
CODE = {name: 100 + i for i, name in enumerate(STATIONS)}
FAC = ["elevator", "escalator", "wheelchair_lift", "accessible_toilet", "tactile_map", "wheelchair_rental",
       "voice_guide", "wide_gate", "wheelchair_charger"]
GATES = ["elevator", "escalator", "wheelchair_lift"]


def read(name):
    with open(os.path.join(SRC, name), encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def code_of(name):
    return CODE[name.removesuffix("역").replace(" ", "").replace("·", "")]


def num(v):
    v = (v or "").strip()
    return v if v.lstrip("-").replace(".", "", 1).isdigit() else "NULL"


def secs(mmss):
    """'02:16' → 136"""
    if not mmss or ":" not in mmss:
        return "NULL"
    m, s = mmss.split(":")
    return str(int(m) * 60 + int(s))


def hhmm(v):
    return q(v + ":00") if v and len(v) == 5 else "NULL"


def pt(lon, lat):
    return f"ST_SRID(POINT({float(lon):.7f},{float(lat):.7f}),0)"


def dot_date(v):
    """'2022.12.30' → '2022-12-30'"""
    return q(v.replace(".", "-"))


def date_tail(source):
    """'광주교통공사_역사출구별안내정보_20210309' → '2021-03-09'"""
    d = source.rsplit("_", 1)[-1]
    return q(f"{d[:4]}-{d[4:6]}-{d[6:8]}")


def inserts(f, table, cols, rows):
    for i in range(0, len(rows), BATCH):
        f.write(f"INSERT INTO {table} ({', '.join(cols)}) VALUES\n"
                + ",\n".join("(" + ",".join(r) + ")" for r in rows[i:i + BATCH]) + ";\n")


def source_row(f, typ, source, title, api, license_, total, loaded):
    f.write(f"""INSERT INTO data_source (type, source, title, api, license, total_count, loaded_count, skipped_count,
  complete, loaded_at)
VALUES ({q(typ)}, {q(source)}, {q(title)}, {q(api)}, {q(license_)}, {total}, {loaded}, {total - loaded}, 1, NOW())
ON DUPLICATE KEY UPDATE title = VALUES(title), api = VALUES(api), license = VALUES(license),
  total_count = VALUES(total_count), loaded_count = VALUES(loaded_count), skipped_count = VALUES(skipped_count),
  complete = 1, loaded_at = NOW();
""")


def build():
    out, report = {}, []

    st = read("gwangju_subway_station.csv")
    cols = ["station_id", "name", "address", "lon", "lat", "geom"]
    for k in FAC:
        cols += [k + "_total", k + "_available"] + ([k + "_disabled_gates"] if k in GATES else [])
    cols += ["osm_id", "coord_from", "fetched_at"]
    rows = []
    for r in st:
        v = [r["station_id"], q(r["station_name"]), q(r["address"] or None), r["lon"], r["lat"], pt(r["lon"], r["lat"])]
        for k in FAC:
            v += [num(r.get(k + "_total")).replace("NULL", "0"), num(r.get(k + "_available")).replace("NULL", "0")]
            if k in GATES:
                v.append(q(r.get(k + "_disabled_gates") or None))
        v += [num(r["osm_id"]), q(r["coord_from"] or None), q(r["fetched_at"])]
        rows.append(v)
    out["subway_station"] = (cols, rows, len(st), "grtc", "광주 지하철역 장애인 편의시설",
                             "광주교통공사 오픈API disabledFacilityInformation + OSM", "확인 필요")

    en = read("gwangju_subway_entrance.csv")
    rows = [[r["osm_id"], num(r["station_id"]), q(r["exit_no"] or None), q(r["name"] or None), q(r["wheelchair"] or None),
             r["lon"], r["lat"], pt(r["lon"], r["lat"]), num(r["dist_to_station_m"]), q(r["fetched_at"])] for r in en]
    out["subway_entrance"] = (["osm_id", "station_id", "exit_no", "name", "wheelchair", "lon", "lat", "geom",
                               "dist_to_station_m", "fetched_at"], rows, len(en), "osm", "광주 지하철 출입구",
                              "Overpass railway=subway_entrance", "ODbL (출처 표시)")

    tt = read("gwangju_subway_timetable.csv")
    fixed = sum(1 for r in tt if int(r["역사코드"]) != code_of(r["역사명"]))
    rows = [[str(code_of(r["역사명"])), q(r["역사명"].removesuffix("역")), q(r["요일"]), r["방향(상_하행)"],
             str(code_of(r["종착역명"])), q(r["종착역명"].removesuffix("역")), hhmm(r["도착시간"]), r["호선"],
             q(r["기준일자"])] for r in tt]
    out["subway_timetable"] = (["station_code", "station_name", "day_type", "direction", "end_station_code",
                                "end_station_name", "arrive_time", "line", "base_date"], rows, len(tt), "data.go.kr",
                               "광주교통공사 열차 시간표", "공공데이터포털 15111497", "확인 필요")
    report.append(f"시간표 역 코드를 이름으로 고친 줄 {fixed}")

    tr = read("gwangju_subway_transfer.csv")
    bad = sum(1 for r in tr if int(r["하차역 코드"]) != code_of(r["하차역명"]))
    rows = [[str(code_of(r["하차역명"])), q(r["하차역명"].removesuffix("역")),
             str(code_of(r["이전역명"])) if r["이전역명"] else "NULL", q(r["이전역명"].removesuffix("역") or None),
             q(r["하차위치"] or None), q(r["출구번호"] or None), secs(r["하차위치-출구 소요시간"]),
             secs(r["출구-정류장 소요시간"]), secs(r["환승시간"]), q(r["정류장 번호"] or None),
             q(r["버스노선"].rsplit("_", 1)[-1] or None), r["호선"], dot_date(r["기준일자"])] for r in tr]
    out["subway_transfer"] = (["station_code", "station_name", "prev_station_code", "prev_station_name", "alight_pos",
                               "exit_no", "alight_to_exit_s", "exit_to_stop_s", "transfer_s", "busstop_no", "bus_route",
                               "line", "base_date"], rows, len(tr), "data.go.kr", "광주교통공사 역사별 환승",
                              "공공데이터포털 15111491", "확인 필요")
    report.append(f"환승 자료 역 코드가 이름과 다른 줄 {bad}")

    eb = read("gwangju_subway_exit_bus.csv")
    rows = [[q(r["역명"].removesuffix("역")), q(r["출구번호"] or None), q(r["연계버스"]), q(r["버스별 행선지"] or None),
             date_tail(r["source"])] for r in eb]
    out["subway_exit_bus"] = (["station_name", "exit_no", "bus_route", "destination", "base_date"], rows, len(eb),
                              "data.go.kr", "광주교통공사 출구 연계 버스", "공공데이터포털 15046048", "확인 필요")

    ep = read("gwangju_subway_exit_place.csv")
    rows = [[q(r["역명"].removesuffix("역")), q(r["지명"]), q(r["출구번호"] or None), num(r["거리(m)"]),
             num(r["소요시간(도보/분)"]), date_tail(r["source"])] for r in ep]
    out["subway_exit_place"] = (["station_name", "place_name", "exit_no", "distance_m", "walk_min", "base_date"], rows,
                                len(ep), "data.go.kr", "광주교통공사 출구 주변 장소", "공공데이터포털 15046045", "확인 필요")

    bs = read("gwangju_bus_stop.csv")
    ok = [r for r in bs if r["LONGITUDE"] and r["LATITUDE"]]
    rows = [[r["BUSSTOP_ID"], q(r["BUSSTOP_NAME"]), q(r["NAME_E"] or None), q(r["ARS_ID"] or None),
             q(r["NEXT_BUSSTOP"] or None), r["LONGITUDE"], r["LATITUDE"], pt(r["LONGITUDE"], r["LATITUDE"]),
             q(r["fetched_at"])] for r in ok]
    out["bus_stop"] = (["busstop_id", "name", "name_en", "ars_id", "next_busstop", "lon", "lat", "geom", "fetched_at"],
                       rows, len(bs), "data.go.kr", "광주버스정보 정류장", "apis.data.go.kr/6290000/gj_bis/stationInfo",
                       "확인 필요")
    report.append(f"좌표 없어 뺀 정류장 {[r['BUSSTOP_NAME'] for r in bs if r not in ok]}")

    bl = read("gwangju_bus_line.csv")
    rows = [[r["LINE_ID"], q(r["LINE_NAME"]), q(r["DIR_UP_NAME"] or None), q(r["DIR_DOWN_NAME"] or None),
             hhmm(r["FIRST_RUN_TIME"]), hhmm(r["LAST_RUN_TIME"]), num(r["RUN_INTERVAL"]), num(r["LINE_KIND"]),
             q(r["fetched_at"])] for r in bl]
    out["bus_line"] = (["line_id", "line_name", "dir_up_name", "dir_down_name", "first_run", "last_run", "run_interval",
                        "line_kind", "fetched_at"], rows, len(bl), "data.go.kr", "광주버스정보 노선",
                       "apis.data.go.kr/6290000/gj_bis/lineInfo", "확인 필요")

    lb = read("gwangju_lowfloor_bus.csv")
    rows = [[q(r["vehicle_no"]), r["low_floor"], q(r["company"] or None), q(r["route_name"] or None), q(r["base_date"])]
            for r in lb]
    out["lowfloor_bus"] = (["vehicle_no", "low_floor", "company", "route_name", "base_date"], rows, len(lb),
                           "data.go.kr", "전남광주통합특별시 저상버스 운행 현황", "공공데이터포털 파일 (2025-12-31)",
                           "확인 필요")
    return out, report


def main():
    out, report = build()
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("SET NAMES utf8mb4;\n")
        for table, (cols, rows, total, source, title, api, lic) in out.items():
            f.write(f"DELETE FROM {table};\n")
            inserts(f, table, cols, rows)
            source_row(f, table, source, title, api, lic, total, len(rows))
    for table, (_, rows, total, *_rest) in out.items():
        print(f"{table}: {len(rows)}" + (f" (원본 {total})" if total != len(rows) else ""))
    for line in report:
        print(line)
    print("SQL:", OUT)
    if "--sql-only" not in sys.argv[1:]:
        run_sql(OUT)
        print("DB 적재 끝")


if __name__ == "__main__":
    main()
