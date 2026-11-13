#!/usr/bin/env python3
"""
광주 버스 노선마다 지나는 정류장을 받아 src/main/resources/bus/line_stops.json 으로 저장한다 (11-13, 비서 "○○로 가는 버스").
서버는 이 파일만 읽고 API를 부르지 않는다. 노선이 바뀌었을 때만 다시 돌린다.

- 자료: 광주버스정보 gj_bis/lineInfo(노선 목록) + lineStationInfo(노선별 정류장), 키는 .env 의 DATA_GO_KR_KEY
- 하루 부를 수 있는 횟수가 적어서(11-13 한도 초과 확인) 하나씩 천천히(0.5초 간격) 부르고,
  한도 초과(429)가 나오면 바로 멈추고 파일은 그대로 둔다. 노선 127개면 128번 부른다.
- 쓰는 법: python3 scripts/fetch_line_stops.py
"""
import datetime
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "bus", "line_stops.json")
API = "https://apis.data.go.kr/6290000/gj_bis/"


def key():
    for line in open(os.path.join(ROOT, ".env"), encoding="utf-8"):
        if line.startswith("DATA_GO_KR_KEY="):
            return line.split("=", 1)[1].strip().strip('"')
    sys.exit(".env 에 DATA_GO_KR_KEY 가 없습니다")


class Limit(Exception):
    pass


def get(path, k, **q):
    q.update(serviceKey=k, resultType="json")
    url = API + path + "?" + urllib.parse.urlencode(q, safe="%")
    try:
        with urllib.request.urlopen(url, timeout=15) as r:
            return json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        if e.code == 429:
            raise Limit()
        raise


def items(node, key_name):
    if isinstance(node, dict):
        if key_name in node:
            yield node
        for v in node.values():
            yield from items(v, key_name)
    elif isinstance(node, list):
        for v in node:
            yield from items(v, key_name)


def main():
    k = key()
    try:
        lines = sorted({int(l["LINE_ID"]) for l in items(get("lineInfo", k), "LINE_ID")})
        print(f"노선 {len(lines)}개")
        out = {}
        for n, lid in enumerate(lines, 1):
            time.sleep(0.5)
            stops = []
            for s in items(get("lineStationInfo", k, LINE_ID=lid), "BUSSTOP_ID"):
                lat, lon = s.get("LATITUDE"), s.get("LONGITUDE")
                if isinstance(lat, (int, float)) and isinstance(lon, (int, float)):
                    stops.append([int(s.get("BUSSTOP_NUM") or len(stops) + 1), int(s["BUSSTOP_ID"]),
                                  str(s.get("BUSSTOP_NAME", "")), round(lat, 6), round(lon, 6)])
            out[str(lid)] = stops
            if n % 20 == 0:
                print(f"  {n}/{len(lines)}")
    except Limit:
        sys.exit("오늘 부를 수 있는 횟수를 다 썼어요(429). 파일은 그대로 두었어요. 내일 다시 돌려 주세요.")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"fetchedAt": datetime.date.today().isoformat(), "lines": out}, f, ensure_ascii=False,
                  separators=(",", ":"))
        f.write("\n")
    total = sum(len(v) for v in out.values())
    print(f"저장: {OUT} (노선 {len(out)}개, 정류장 {total}개, {os.path.getsize(OUT) // 1024}KB)")


if __name__ == "__main__":
    main()
