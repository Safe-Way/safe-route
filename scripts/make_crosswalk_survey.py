"""OSM 횡단보도 위치로 현장 조사표(CSV)를 만든다.

전국횡단보도표준데이터에 광주 동구가 없어서, 시연 지역의 보도턱·점자블록·신호등을 직접 보고 적기 위한 표다.
- OSM에서 중심점 반경 안의 highway=crossing 점과, 그 점이 걸린 도로 이름·등급을 가져온다.
- 중심에서 본 방향으로 4구역(A 북서, B 북동, C 남서, D 남동)으로 나눠 네 명이 나눠 조사할 수 있게 한다.
- 이미 있는 파일은 덮어쓰지 않는다 (조사한 값이 지워지지 않게).

실행: python3 scripts/make_crosswalk_survey.py [--json 받아 둔 Overpass 응답] [출력 파일] [위도 경도 반경m]
기본: data/survey/crosswalk_chosun.csv, 조선대(35.1408, 126.9306), 1000m
Overpass 서버가 바쁠 때는 미리 받아 둔 응답(JSON)을 --json 으로 줄 수 있다.
"""
import csv
import json
import math
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OVERPASS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]

# 조사하는 사람이 채우는 칸 (적재할 때 attrs 키가 된다. schema.sql crosswalk 표의 가상 칸과 이름을 맞춤)
FILL = ["보도턱낮춤", "턱높이cm", "점자블록", "보행신호등", "음향신호기", "횡단길이m", "사진", "조사자", "조사일", "메모"]
HEADER = ["번호", "구역", "우선순위", "위도", "경도", "지도", "건너는 도로", "도로 등급", "중심에서m",
          "OSM 신호", "OSM 점자블록", "osm_id"] + FILL

ROAD_CLASS = {
    "primary": "큰길", "primary_link": "큰길", "secondary": "큰길", "secondary_link": "큰길",
    "tertiary": "중간 도로", "tertiary_link": "중간 도로",
    "residential": "골목·이면도로", "service": "골목·이면도로", "unclassified": "골목·이면도로",
    "living_street": "골목·이면도로",
}
SIGNAL = {"traffic_signals": "있음", "uncontrolled": "없음", "marked": "없음", "unmarked": "없음(표시 없음)"}


def fetch(lat, lon, radius):
    query = f"""[out:json][timeout:60];
node["highway"="crossing"](around:{radius},{lat},{lon})->.c;
.c out body;
way(bn.c)["highway"]->.w;
.w out body;
"""
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        f.write(query)
        qfile = f.name
    for url in OVERPASS:
        # 파이썬 기본 SSL로는 일부 서버와 연결이 안 돼서 curl을 쓴다
        out = subprocess.run(["curl", "-s", "-m", "90", "-A", "safe-route-student-project/0.1",
                              "-H", "Accept: application/json", "--data-urlencode", "data@" + qfile, url],
                             capture_output=True, text=True).stdout
        if out.startswith("{"):
            return json.loads(out)
        print("서버가 바빠서 다음 서버로:", url, file=sys.stderr)
    sys.exit("Overpass 서버가 모두 바쁩니다. 잠시 뒤 다시 실행하세요.")


def meters(lat1, lon1, lat2, lon2):
    dy = (lat2 - lat1) * 111_320
    dx = (lon2 - lon1) * 111_320 * math.cos(math.radians(lat1))
    return math.hypot(dx, dy)


def zone(lat, lon, clat, clon):
    north = lat >= clat
    east = lon >= clon
    return {(True, False): "A 북서", (True, True): "B 북동", (False, False): "C 남서", (False, True): "D 남동"}[(north, east)]


def main():
    args = sys.argv[1:]
    cached = None
    if args[:1] == ["--json"]:
        cached, args = args[1], args[2:]
    out = args[0] if args else os.path.join(ROOT, "data", "survey", "crosswalk_chosun.csv")
    clat, clon, radius = (float(args[1]), float(args[2]), int(args[3])) if len(args) >= 4 else (35.1408, 126.9306, 1000)
    if os.path.exists(out):
        sys.exit(f"{out} 가 이미 있어서 덮어쓰지 않습니다 (조사한 값을 지키려고). 새로 만들려면 다른 이름을 주세요.")

    if cached:
        with open(cached, encoding="utf-8") as f:
            data = json.load(f)
    else:
        data = fetch(clat, clon, radius)
    nodes = [e for e in data["elements"] if e["type"] == "node"]
    ways = [e for e in data["elements"] if e["type"] == "way"]
    roads = {}  # 점 id → (도로 이름, 등급) 중 가장 큰 도로
    rank = {"큰길": 0, "중간 도로": 1, "골목·이면도로": 2}
    for w in ways:
        cls = ROAD_CLASS.get(w.get("tags", {}).get("highway"))
        if cls is None:
            continue  # 보도(footway)·계단 등은 건너는 도로가 아님
        name = w["tags"].get("name") or "(이름 없음)"
        for nid in w.get("nodes", []):
            old = roads.get(nid)
            if old is None or rank[cls] < rank[old[1]]:
                roads[nid] = (name, cls)

    rows = []
    for n in nodes:
        tags = n.get("tags", {})
        d = meters(clat, clon, n["lat"], n["lon"])
        name, cls = roads.get(n["id"], ("(확인 필요)", "확인 필요"))
        sig = tags.get("crossing", "")
        sig = "있음" if "traffic_signals" in sig else SIGNAL.get(sig, "모름")
        tactile = {"yes": "있음", "no": "없음"}.get(tags.get("tactile_paving"), "모름")
        priority = 1 if d <= 500 else 2 if d <= 800 else 3
        rows.append([zone(n["lat"], n["lon"], clat, clon), priority, round(n["lat"], 7), round(n["lon"], 7),
                     name, cls, round(d), sig, tactile, n["id"]])
    rows.sort(key=lambda r: (r[0], r[1], r[6]))

    os.makedirs(os.path.dirname(out), exist_ok=True)
    # 엑셀에서 한글이 깨지지 않게 BOM을 붙인 UTF-8로 저장
    with open(out, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(HEADER)
        for i, r in enumerate(rows, 1):
            z, p, lat, lon, name, cls, d, sig, tactile, oid = r
            link = f"https://map.kakao.com/link/map/횡단보도{i},{lat},{lon}"
            w.writerow([i, z, p, lat, lon, link, name, cls, d, sig, tactile, oid] + [""] * len(FILL))
    print(f"{out}: 횡단보도 {len(rows)}곳")


if __name__ == "__main__":
    main()
