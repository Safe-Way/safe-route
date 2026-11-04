"""광주 도시철도 1호선 20개 역의 엘리베이터·장애인 편의시설 수와 역·출입구 좌표를 받아 CSV로 저장한다.

- 편의시설 수: 광주교통공사 오픈API (키 없음, 실시간)
    https://www.grtc.co.kr/subway/openapi/json/disabledFacilityInformation?station_id=1~20
    엘리베이터·에스컬레이터·휠체어리프트·장애인화장실 등 9종의 전체·이용가능·이용불가 수와 이용불가 출입구 번호
- 역 주소: 같은 곳의 stationInformation API
- 역·출입구 좌표: OpenStreetMap (Overpass). 역은 railway=station, 출입구는 railway=subway_entrance (ref = 출구 번호)

엘리베이터가 몇 번 출구에 있는지는 공개된 자료가 없다(2026-11-03 광주교통공사 홈페이지·공공데이터포털 확인).
OSM 출입구의 wheelchair 표시도 거의 비어 있다.

저장: data/raw/regional/gwangju_subway_station.csv, data/raw/regional/gwangju_subway_entrance.csv
실행: python3 scripts/fetch_subway_elevator.py
"""
import csv
import json
import math
import os
import subprocess
import time
from datetime import date

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "data", "raw", "regional")
API = "https://www.grtc.co.kr/subway/openapi/json/"
OVERPASS = ["https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter"]
BBOX = (35.04, 126.63, 35.27, 127.03)  # 광주광역시를 덮는 네모
FACILITY = {"DT_01": "elevator", "DT_02": "escalator", "DT_03": "wheelchair_lift", "DT_04": "accessible_toilet",
            "DT_05": "tactile_map", "DT_06": "wheelchair_rental", "DT_07": "voice_guide", "DT_08": "wide_gate",
            "DT_09": "wheelchair_charger"}
ENTRANCE_R = 400.0  # 출입구를 이 거리 안 가장 가까운 역에 붙인다


def get_json(url, data=None):
    cmd = ["curl", "-s", "-L", "-m", "120", "-A", "safe-route-student-project/0.1"]
    if data:
        cmd += ["--data-urlencode", "data=" + data]
    out = subprocess.run(cmd + [url], capture_output=True, text=True).stdout
    return json.loads(out)


def norm(name):
    """역 이름 비교용: '학동 · 증심사입구역', '금남로/5가', '광주송정' 등을 같은 꼴로"""
    for ch in " ·/역":
        name = name.replace(ch, "")
    return name


def dist_m(lon1, lat1, lon2, lat2):
    x = (lon1 - lon2) * 111320 * math.cos(math.radians((lat1 + lat2) / 2))
    y = (lat1 - lat2) * 110540
    return math.hypot(x, y)


def overpass():
    """받은 응답은 data/raw/osm/gwangju_subway.json에 두고, 있으면 다시 받지 않는다 (새로 받으려면 지운다)"""
    cache = os.path.join(ROOT, "data", "raw", "osm", "gwangju_subway.json")
    if os.path.exists(cache):
        return json.load(open(cache, encoding="utf-8"))["elements"]
    s, w, n, e = BBOX
    q = (f"[out:json][timeout:90];(node[\"railway\"=\"station\"][\"station\"=\"subway\"]({s},{w},{n},{e});"
         f"node[\"railway\"=\"subway_entrance\"]({s},{w},{n},{e}););out body;")
    for url in OVERPASS * 3:  # 서버가 바쁘면 번갈아 세 번씩
        try:
            d = get_json(url, q)
            json.dump(d, open(cache, "w", encoding="utf-8"), ensure_ascii=False)
            return d["elements"]
        except (json.JSONDecodeError, KeyError):
            time.sleep(20)
    raise SystemExit("Overpass 응답 없음")


def kakao_station(name):
    """OSM에서 이름이 안 맞는 역은 카카오 장소 검색(지하철역 분류 SW8)으로 좌표를 찾는다"""
    key = None
    for line in open(os.path.join(ROOT, ".env"), encoding="utf-8"):
        if line.startswith("KAKAO_REST_KEY="):
            key = line.split("=", 1)[1].strip()
    if not key:
        return None
    out = subprocess.run(["curl", "-s", "-m", "20", "-G", "https://dapi.kakao.com/v2/local/search/keyword.json",
                          "-H", "Authorization: KakaoAK " + key, "--data-urlencode", f"query=광주 {name}역",
                          "--data-urlencode", "category_group_code=SW8"], capture_output=True, text=True).stdout
    docs = [d for d in json.loads(out).get("documents", []) if "광주" in d.get("address_name", "")]
    return (round(float(docs[0]["x"]), 7), round(float(docs[0]["y"]), 7), docs[0]["place_name"]) if docs else None


def main():
    today = date.today().isoformat()
    stations = []
    for sid in range(1, 21):
        info = get_json(f"{API}stationInformation?station_id={sid}")
        info = info[0] if isinstance(info, list) else info
        fac = get_json(f"{API}disabledFacilityInformation?station_id={sid}")
        row = {"station_id": sid, "station_name": fac[0]["station_name"], "address": info.get("station_place")}
        for r in fac:
            key = FACILITY.get(r["facility_id"])
            if not key:
                continue
            row[key + "_total"] = r["total_count"]
            row[key + "_available"] = r["available_count"]
            if key in ("elevator", "escalator", "wheelchair_lift"):
                row[key + "_disabled_gates"] = ",".join(map(str, r.get("disabled_gates") or []))
        stations.append(row)

    els = overpass()
    osm_st = [x for x in els if x["tags"].get("railway") == "station"]
    by_name = {norm(x["tags"].get("name", "")): x for x in osm_st}
    for row in stations:
        x = by_name.get(norm(row["station_name"]))
        row["lon"], row["lat"] = (round(x["lon"], 7), round(x["lat"], 7)) if x else (None, None)
        row["osm_id"] = x["id"] if x else None
        row["coord_from"] = "OSM" if x else None
        if not x:
            k = kakao_station(row["station_name"])
            if k:
                row["lon"], row["lat"], row["coord_from"] = k[0], k[1], "카카오 " + k[2]
        row["fetched_at"], row["source"] = today, "광주교통공사 오픈API + OSM"

    entrances = []
    located = [r for r in stations if r["lon"] is not None]
    for x in els:
        if x["tags"].get("railway") != "subway_entrance":
            continue
        near = min(located, key=lambda r: dist_m(x["lon"], x["lat"], r["lon"], r["lat"]))
        d = dist_m(x["lon"], x["lat"], near["lon"], near["lat"])
        entrances.append({"osm_id": x["id"], "station_id": near["station_id"] if d <= ENTRANCE_R else None,
                          "station_name": near["station_name"] if d <= ENTRANCE_R else None,
                          "exit_no": x["tags"].get("ref"), "name": x["tags"].get("name"),
                          "wheelchair": x["tags"].get("wheelchair"), "lon": round(x["lon"], 7),
                          "lat": round(x["lat"], 7), "dist_to_station_m": round(d), "fetched_at": today})
    entrances.sort(key=lambda r: (r["station_id"] or 99, str(r["exit_no"])))

    os.makedirs(OUT, exist_ok=True)
    for name, rows in (("gwangju_subway_station.csv", stations), ("gwangju_subway_entrance.csv", entrances)):
        cols = list(dict.fromkeys(k for r in rows for k in r))
        with open(os.path.join(OUT, name), "w", newline="", encoding="utf-8-sig") as f:
            w = csv.DictWriter(f, fieldnames=cols)
            w.writeheader()
            w.writerows(rows)
        print(f"{name}: {len(rows)}줄")
    print("엘리베이터 합계", sum(r.get("elevator_total", 0) for r in stations),
          "/ 좌표 못 찾은 역", [r["station_name"] for r in stations if r["lon"] is None],
          "/ 역에 못 붙인 출입구", sum(1 for r in entrances if r["station_id"] is None))


if __name__ == "__main__":
    main()
