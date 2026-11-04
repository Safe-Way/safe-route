"""원본 시설 표 14개를 정제해 poi 표(광주)를 다시 만든다. 서버·앱이 지도 아이콘과 경로 주변 시설을 한 표에서 꺼내게 한다.

정제하는 것
- 범위: 광주 시도 경계(admin_area sido 24) 안만. 범위 상자로 고르면 나주·담양 일부가 섞인다.
- 중복: 같은 종류가 10m 안에 있으면 한 점으로 합친다. 출처가 여럿인 CCTV·보안등은 출처마다 센 수 중 큰 값을 cnt로 둔다
  (같은 카메라가 전국 파일과 광주 파일에 같이 있음). 무더위쉼터와 한파쉼터는 여는 계절이 달라 합치지 않는다.
- 쉼터 운영 기간(season_from·season_to): 원본에 없어 정부 대책 기간(폭염 5.20~9.30, 한파 11.15~3.15)을 넣는다.
- 비상벨 indoor: 설치 장소가 화장실·건물이면 1, 공원·가로변·주차장이면 0 (참고용. 화장실 안 비상벨도 모든 모드에 표시).
- CCTV 세부 종류(sub)는 cctv.purpose_group (scripts/classify_cctv.py를 먼저 돌림).
- 쉼터 place_type·biz_*: 이름으로 경로당·행정복지센터·은행·도서관 등을 나누고, 경로당이 아닌 곳은 일반 업무 시간을 넣는다
  (쉼터 기간이 아니어도 그 시간엔 들어가 쉴 수 있음. 판단은 poi_status 뷰)
- 이름: 비면 종류 이름(비상벨, 가로등 등). 가로등 이름 칸의 기둥 번호는 쓰지 않는다.
- attrs JSON에 흩어진 값 → wheelchair, open_hours, phone, detail(앱에 바로 띄울 문장)
- 여는 시간: 화면 글자(open_hours)와 별도로 open_from·open_to·open_days를 뽑아, 서버가 지금 시각과 비교해
  '이용 가능 / 닫힘 / 확인 안 됨'을 붙일 수 있게 한다 (화장실 '08시~22시', '24시간', '0900~1800' 등). 못 읽으면 NULL
- OSM 횡단보도(road_node crossing)·계단(road_edge steps)도 넣는다 (도로망 범위만). 횡단보도 표준데이터 10m 안이면 뺀다.
- modes: 지도에 먼저 보일 모드 (FR-48, 결정 35). CCTV·경찰·비상벨·편의점은 모든 모드(common),
  지킴이집·어린이보호구역은 child, 쉼터·화장실은 senior, 육교·횡단보도·장애인 화장실은 wheelchair. 등은 아이콘 대신 밝기로만 쓰므로 비움.
- 지하철역(subway_station, 광주 1호선 20역): 노인·장애인 모드. 엘리베이터가 있으면 wheelchair = 1(녹동역은 없음).
  여는 시간은 열차 시간표(subway_timetable 평일)의 그 역 첫차~막차. 엘리베이터가 몇 번 출구에 있는지는 공개 자료가 없다.
  저상버스는 정류장이 아니라 버스 한 대마다 붙은 정보라 지도 점으로 넣지 않는다 (요청 때 광주 버스 도착 API의 LOW_BUS로 판단).
- sgg_cd / dong_cd: 점이 드는 시군구·행정동

원본 표를 다시 적재하면 이 스크립트를 다시 돌린다 (표를 비우고 새로 넣음, 1분 안팎).
먼저 할 일: src/main/resources/db/migration/012_poi.sql

실행: python3 scripts/build_poi.py [--sql-only]
"""
import json
import math
import os
import re
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_edge_stat import CCTV_SKIP, ROOT, query, run_file  # noqa: E402

OUT = os.path.join(ROOT, "data", "raw", "osm", "poi.sql")
SIDO = "24"  # 광주 (SGIS)
MERGE_R = 10.0
# 쉼터 장소 종류와 일반 업무 시간 (쉼터 기간이 아니어도 들어가 쉴 수 있는 곳). 기관마다 다를 수 있는 일반값
PLACE = [  # (종류, 이름 모양, 업무 시간)
    ("경로당", r"경로당|노인정|노인회|부녀|시니어", None),
    ("지하철역", r"역$|지하철|도시철도", ("05:30", "24:00", "매일")),
    ("은행", r"은행|금고|농협|수협|신협|새마을|금융", ("09:00", "16:00", "평일")),
    ("행정복지센터", r"행정복지센터|주민센터|동사무소|복지센터$", ("09:00", "18:00", "평일")),
    ("도서관", r"도서관", ("09:00", "18:00", "평일")),
    ("복지관", r"복지관|복지회관", ("09:00", "18:00", "평일")),
    ("보건소", r"보건소|보건지소|보건진료|건강생활지원센터", ("09:00", "18:00", "평일")),
    ("공공기관", r"구청|시청|청사|우체국|커뮤니|문화센터|주민자치", ("09:00", "18:00", "평일")),
]


# 아동안전지킴이집 가게 종류와 일반 영업시간 (시간 밖은 확인 안 됨). 편의점도 24시간인지는 모름 (요구사항: 판별 안 함)
SHOP = [
    ("편의점", r"GS25|CU|세븐|이마트24|미니스톱|편의점|씨유|지에스|스토리웨이", ("06:00", "24:00", "매일")),
    ("약국", r"약국", ("09:00", "20:00", "평일")),
    ("문구점", r"문구|팬시|서점|알파|종이와", ("08:00", "20:00", "평일")),
    ("마트", r"마트|슈퍼|수퍼|상회|식자재", ("09:00", "21:00", "매일")),
]
SHOP_DEFAULT = ("가게", ("09:00", "19:00", "평일"))


def place_of(name):
    for kind, pat, biz in PLACE:
        if re.search(pat, name or ""):
            return kind, biz
    return "기타", None


INDOOR = {"화장실": 1, "건물": 1, "공원": 0, "가로변": 0, "주차장": 0}  # 비상벨 설치 장소 → 실내 여부 (기타는 모름)
LAT0 = 35.15
KX, KY = 111320.0 * math.cos(math.radians(LAT0)), 110540.0


def rows(table, cols, where=""):
    """광주 경계 안 원본 줄. cols는 SELECT 식 목록 (t. 접두어 사용), where는 'AND ...'"""
    sql = (f"SELECT t.id, t.lon, t.lat, {', '.join(cols)} FROM {table} t JOIN admin_area a ON a.level = 'sido' "
           f"AND a.code = '{SIDO}' AND MBRContains(a.geom, t.geom) AND ST_Contains(a.geom, t.geom) {where}")
    out = []
    for r in query(sql):
        r = [None if v == "NULL" else v for v in r]
        d = {"src": f"{table}:{r[0]}", "table": table, "lon": float(r[1]), "lat": float(r[2])}
        for c, v in zip(cols, r[3:]):
            d[c.split(" AS ")[-1].replace("t.", "")] = v
        out.append(d)
    return out


def cluster(points, r):
    """r m 안의 점을 묶는다 (격자 + 첫 점 기준). 반환: [[점, ...], ...]"""
    grid, groups = defaultdict(list), []
    for p in points:
        x, y = p["lon"] * KX, p["lat"] * KY
        cx, cy = int(x // r), int(y // r)
        hit = None
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for g in grid[(cx + dx, cy + dy)]:
                    gx, gy = g[0]["lon"] * KX, g[0]["lat"] * KY
                    if (gx - x) ** 2 + (gy - y) ** 2 <= r * r:
                        hit = g
                        break
                if hit:
                    break
            if hit:
                break
        if hit is None:
            hit = [p]
            groups.append(hit)
            grid[(cx, cy)].append(hit)
        else:
            hit.append(p)
    return groups


def per_source_max(g, weight=lambda p: 1):
    """출처마다 센 수 중 큰 값 (같은 시설이 출처 여러 곳에 겹쳐 있을 때)"""
    by = defaultdict(int)
    for p in g:
        by[(p["table"], p.get("source"))] += weight(p)
    return max(by.values())


def first(g, key):
    return next((p[key] for p in g if p.get(key)), None)


def poi(kind, g, name, **kw):
    lon = sum(p["lon"] for p in g) / len(g)
    lat = sum(p["lat"] for p in g) / len(g)
    d = {"kind": kind, "name": name, "lon": lon, "lat": lat, "address": first(g, "address"),
         "sources": [p["src"] for p in g][:20], "cnt": 1, "sub": None, "wheelchair": None, "open_hours": None,
         "open_from": None, "open_to": None, "open_days": None, "season_from": None, "season_to": None,
         "indoor": None, "place_type": None, "biz_from": None, "biz_to": None, "biz_days": None, "biz_sure": None,
         "phone": None, "modes": "", "detail": None}
    d.update(kw)
    return d


def yes(v):
    return v in ("Y", "1", 1, "y")


HOURS = re.compile(r"^(\d{1,2})(?::?(\d{2}))?\s*[~-]\s*(\d{1,2})(?::?(\d{2}))?$")


def hours_fmt(s):
    """'08~22', '06:00 ~ 16:00', '0900~1800' → '08:00~22:00'. 모양이 다르면 원문 그대로"""
    s = (s or "").strip()
    m = HOURS.match(s)
    if not m:
        return s or None
    h1, m1, h2, m2 = m.groups()
    return f"{int(h1):02d}:{m1 or '00'}~{int(h2):02d}:{m2 or '00'}"


RANGE = re.compile(r"(\d{1,2})(?::?(\d{2}))?\s*시?\s*[~-]\s*(\d{1,2})(?::?(\d{2}))?\s*시?")


def parse_hours(s):
    """여는 시간 글자 → (여는 시각, 닫는 시각, 요일). 못 읽으면 (None, None, None).
    여러 시간대가 적혀 있으면 첫 시간대를 쓴다 (화면에는 원문을 같이 보여 줌)."""
    s = (s or "").strip()
    if s in ("상시", "24시", "24시간") or s.startswith("24시간"):
        return "00:00", "24:00", "매일"
    m = RANGE.search(s)
    if not m:
        return None, None, None
    h1, m1, h2, m2 = m.groups()
    if int(h1) > 24 or int(h2) > 24:
        return None, None, None
    days = "평일" if ("평일" in s or "월~금" in s) and "주말" not in s and "토" not in s else "매일"
    return f"{int(h1):02d}:{m1 or '00'}", f"{int(h2):02d}:{m2 or '00'}", days


def toilet_hours(p):
    """(화면 글자, 여는 시각, 닫는 시각, 요일)"""
    h = (p.get("open_hours") or "").strip()
    det = (p.get("opn_detail") or "").strip()
    if h == "상시":
        return ("24시간",) + parse_hours("상시")
    if h == "미개방":
        return "개방 안 함", None, None, None
    if det:
        frm, to, days = parse_hours(det)
        text = hours_fmt(det) if HOURS.match(det) else det
        if frm == "00:00" and to == "24:00":
            text = "24시간"
        return (text[:100], frm, to, days)
    return {"정시": "정해진 시간", "불규칙": "불규칙"}.get(h), None, None, None


def build():
    out = []

    # 등: 가로등 + 보안등 (출처 셋이 겹침). 앱 아이콘 대신 밝기 표시·경로 계산에 쓰므로 modes는 비움
    # 가로등과 보안등은 다른 등이라 서로 합치지 않고, 같은 종류끼리만 합친다 (edge_stat과 같음)
    n_raw = 0
    for table, sub in (("streetlight", "가로등"), ("security_light", "보안등")):
        lights = rows(table, ["t.source"])
        n_raw += len(lights)
        for g in cluster(lights, MERGE_R):
            n = per_source_max(g)
            out.append(poi("light", g, sub, sub=sub, cnt=n, detail=f"{sub} {n}개" if n > 1 else sub))
    print(f"  등: 원본 {n_raw} → {sum(1 for p in out if p['kind'] == 'light')}")

    # CCTV: 전국 파일 + 광주 파일
    # 단속·관리용(교통·쓰레기·시설·재난)은 길 계산과 같게 빼고, 목적 모름(광주시 파일)은 넣는다
    cams = rows("cctv", ["t.source", "t.purpose_group", "t.camera_count", "t.address"],
                f"WHERE t.purpose_group NOT IN {CCTV_SKIP}")
    for g in cluster(cams, MERGE_R):
        n = per_source_max(g, lambda p: int(p.get("camera_count") or 1))
        purposes = sorted({p["purpose_group"] for p in g if p.get("purpose_group") not in (None, "모름")})
        out.append(poi("cctv", g, "CCTV", sub="·".join(purposes) or None, cnt=n, modes="common",
                       detail=f"CCTV {n}대" + (f" · {'·'.join(purposes)}" if purposes else "")))
    print(f"  CCTV: 원본 {len(cams)} → {sum(1 for p in out if p['kind'] == 'cctv')}")

    # 지구대·파출소·경찰서·지방청은 24시간 근무, 스마트 안심부스는 24시간 무인, 치안센터는 낮에만(사람이 없는 곳도 많음)
    for g in cluster(rows("police", ["t.kind", "t.name", "t.address", "t.phone"]), MERGE_R):
        p = g[0]
        kind = p.get("kind") or "경찰"
        if kind == "치안센터":
            hours = dict(place_type="치안센터", biz_from="09:00", biz_to="18:00", biz_days="평일", biz_sure=1)
        else:
            hours = dict(open_from="00:00", open_to="24:00", open_days="매일",
                         open_hours="24시간 무인" if "부스" in kind else "24시간 근무")
        out.append(poi("police", g, p.get("name") or kind, sub=kind, phone=p.get("phone"), modes="common",
                       detail=kind, **hours))

    bells = rows("emergency_bell", ["t.kind", "t.address", "t.police_linked", "t.purpose"])
    for g in cluster(bells, MERGE_R):
        place = first(g, "kind")
        linked = any(yes(p.get("police_linked")) for p in g)
        indoor = INDOOR.get(place)
        # 길가·공원·주차장 비상벨은 24시간. 화장실 안 비상벨은 아래에서 그 화장실 개방 시간을 붙인다
        always = dict(open_from="00:00", open_to="24:00", open_days="매일", open_hours="24시간") if indoor == 0 else {}
        out.append(poi("bell", g, "비상벨", sub=place, cnt=len(g), indoor=indoor, modes="common", **always,
                       detail="비상벨" + (f" · {place}" if place else "") + (" · 경찰 연결" if linked else "")))

    for g in cluster(rows("convenience_store", ["t.name", "t.address"]), MERGE_R):
        out.append(poi("store", g, first(g, "name") or "편의점", sub="편의점", modes="common", detail="편의점"))

    # 아동안전지킴이집은 대부분 가게라 가게 종류별 일반 영업시간을 넣고, 시간 밖은 '닫혔을 수 있음(확인 안 됨)'
    for g in cluster(rows("child_safety_house", ["t.name", "t.address", "t.phone"]), MERGE_R):
        name = first(g, "name") or "아동안전지킴이집"
        shop, (frm, to, days) = next(((k, h) for k, pat, h in SHOP if re.search(pat, name, re.I)), SHOP_DEFAULT)
        out.append(poi("guard_house", g, name, sub="아동안전지킴이집", phone=first(g, "phone"), modes="child",
                       place_type=shop, biz_from=frm, biz_to=to, biz_days=days, biz_sure=0,
                       detail=f"아동안전지킴이집 · {shop}"))

    # 쉼터: 무더위쉼터와 한파쉼터는 여는 계절이 달라 따로 둔다 (같은 경로당이면 같은 자리에 두 줄).
    # 운영 기간은 원본에 없어 정부 대책 기간을 넣는다. 지도에는 모두 보이고 상태는 poi_status 뷰가 판단
    n_raw = 0
    for table, sub, season in (("heat_shelter", "무더위쉼터", ("05-20", "09-30")),
                               ("cold_shelter", "한파쉼터", ("11-15", "03-15"))):
        cols = ["t.name", "t.address"] + (["t.night_open", "t.weekend_open"] if table == "heat_shelter"
                                          else ["t.weekday_hours"])
        src = rows(table, cols)
        n_raw += len(src)
        for g in cluster(src, MERGE_R):
            hours, frm, to, days = first(g, "weekday_hours"), None, None, None
            if hours:
                frm, to, _ = parse_hours(hours)
                days = "평일" if frm else None
                hours = "평일 " + hours_fmt(hours)
            extra = [s for s, ok in (("야간 개방", any(yes(p.get("night_open")) for p in g)),
                                     ("주말 개방", any(yes(p.get("weekend_open")) for p in g))) if ok]
            period = f"{int(season[0][:2])}.{int(season[0][3:])}~{int(season[1][:2])}.{int(season[1][3:])} 운영"
            ptype, biz = place_of(first(g, "name"))
            if table == "heat_shelter" and not biz:
                # 무더위쉼터는 원본에 시간이 없어 일반 운영 시간(평일 09~18시)을 넣고, 야간·주말 개방 표시를 반영
                night = any(yes(p.get("night_open")) for p in g)
                weekend = any(yes(p.get("weekend_open")) for p in g)
                frm, to, days = "09:00", "21:00" if night else "18:00", "매일" if weekend else "평일"
                hours = f"{days} {frm}~{to} (일반 운영 시간)"
            out.append(poi("shelter", g, first(g, "name") or sub, sub=sub, open_hours=hours, open_from=frm,
                           open_to=to, open_days=days, season_from=season[0], season_to=season[1], modes="senior",
                           place_type=ptype, biz_from=biz and biz[0], biz_to=biz and biz[1], biz_days=biz and biz[2],
                           biz_sure=1 if biz else None,
                           detail=" · ".join([sub, period] + ([hours] if hours else []) + extra)))
    print(f"  쉼터: 원본 {n_raw} → {sum(1 for p in out if p['kind'] == 'shelter')}")

    toilets = rows("public_toilet", ["t.kind", "t.name", "t.address", "t.phone", "t.open_hours",
                                     "t.attrs ->> '$.opn_hr_detail' AS opn_detail",
                                     "t.attrs ->> '$.ml_pwdbs_wacl_cnt' AS dis_m",
                                     "t.attrs ->> '$.fml_pwdbs_wacl_cnt' AS dis_f",
                                     "t.attrs ->> '$.emcy_bl_instl_yn' AS bell",
                                     "t.attrs ->> '$.dpr_exch_yn' AS diaper"])
    for g in cluster(toilets, MERGE_R):
        p = g[0]
        dis = any(int(x.get("dis_m") or 0) + int(x.get("dis_f") or 0) > 0 for x in g)
        hours, frm, to, days = toilet_hours(p)
        parts = [p.get("kind") or "화장실"] + ([f"개방 {hours}"] if hours else [])
        parts += [s for s, ok in (("장애인 화장실", dis), ("비상벨", any(yes(x.get("bell")) for x in g)),
                                  ("기저귀 교환대", any(yes(x.get("diaper")) for x in g))) if ok]
        out.append(poi("toilet", g, p.get("name") or "화장실", sub=p.get("kind"), wheelchair=1 if dis else 0,
                       open_hours=hours, open_from=frm, open_to=to, open_days=days, phone=first(g, "phone"), modes="senior,wheelchair" if dis else "senior",
                       detail=" · ".join(parts)))

    for g in cluster(rows("footbridge", ["t.kind", "t.name", "t.address", "t.wheelchair_ok"]), MERGE_R):
        p = max(g, key=lambda x: int(x.get("wheelchair_ok") or 0))
        ok = int(p.get("wheelchair_ok") or 0)
        way = p.get("kind") if p.get("kind") not in (None, "없음") else "계단만 있음"
        out.append(poi("footbridge", g, p.get("name") or "육교", sub=p.get("kind"), wheelchair=ok,
                       modes="wheelchair", detail=f"육교 · {way} · " + ("휠체어 통과 가능" if ok else "휠체어 통과 못 함")))

    for g in cluster(rows("crosswalk", ["t.address", "t.curb_lowered", "t.sound_signal", "t.traffic_light"]), MERGE_R):
        p = g[0]
        curb = p.get("curb_lowered")
        parts = ["횡단보도", "신호등" if yes(p.get("traffic_light")) else "신호 없음"]
        parts += ["보도턱 낮춤" if yes(curb) else ("보도턱 있음" if curb == "0" else "보도턱 모름")]
        if yes(p.get("sound_signal")):
            parts.append("음향신호기")
        out.append(poi("crosswalk", g, "횡단보도", sub=parts[1], wheelchair=None if curb is None else int(curb),
                       modes="wheelchair", detail=" · ".join(parts)))

    # OSM 횡단보도 (도로망 범위). 동구는 횡단보도 표준데이터가 거의 없어 OSM으로 채운다. 표준데이터 10m 안이면 뺀다
    std = [p for p in out if p["kind"] == "crosswalk"]
    n_osm = 0
    for nid, lon, lat, crossing, kerb in query(
            "SELECT id, lon, lat, IFNULL(crossing, ''), IFNULL(kerb, '') FROM road_node WHERE highway = 'crossing'"):
        lon, lat = float(lon), float(lat)
        if any(((t["lon"] - lon) * KX) ** 2 + ((t["lat"] - lat) * KY) ** 2 <= MERGE_R ** 2 for t in std):
            continue
        sig = "신호등" if "traffic_signals" in crossing else ("신호 없음" if crossing else "신호 모름")
        wc = 1 if kerb in ("lowered", "flush") else (0 if kerb == "raised" else None)
        curb = {1: "보도턱 낮춤", 0: "보도턱 있음"}.get(wc, "보도턱 모름")
        out.append(poi("crosswalk", [{"lon": lon, "lat": lat, "src": f"road_node:{nid}", "address": None}], "횡단보도",
                       sub=sig, wheelchair=wc, modes="wheelchair", detail=f"횡단보도 · {sig} · {curb} (OSM)"))
        n_osm += 1
    # OSM 계단: 노인·장애인 모드 지도와 "계단 2곳을 피함" 설명용. 길 가운데에 점을 찍는다
    n_steps = 0
    # 휠체어 경사로(018 wheelchair_ramp)가 붙은 계단은 wheelchair=1
    for eid, lon, lat, length, name, ramp in query(
            "SELECT id, ST_X(ST_PointN(geom, CEIL(ST_NumPoints(geom) / 2))), ST_Y(ST_PointN(geom, CEIL(ST_NumPoints(geom) / 2))), "
            "length_m, IFNULL(name, ''), IFNULL(wheelchair_ramp, 'NULL') FROM road_edge WHERE steps = 1"):
        ramp = {"1": 1, "0": 0}.get(str(ramp))
        ramp_text = {1: "휠체어 경사로 있음", 0: "경사로 없음"}.get(ramp, "경사로 모름")
        out.append(poi("stairs", [{"lon": float(lon), "lat": float(lat), "src": f"road_edge:{eid}", "address": None}],
                       name or "계단", sub="계단", wheelchair=1 if ramp == 1 else 0, modes="senior,wheelchair",
                       detail=f"계단 · 길이 {float(length):.0f}m · {ramp_text} (OSM)"))
        n_steps += 1
    print(f"  OSM 횡단보도 {n_osm}곳 (표준데이터와 겹친 것 뺌), 계단 {n_steps}곳")

    # 같은 자리(초등학교와 병설유치원 등)는 한 보호구역으로 합치고 이름을 이어 붙인다
    for g in cluster(rows("child_protection_zone", ["t.kind", "t.name", "t.address", "t.cctv_count"]), MERGE_R):
        p = g[0]
        c = max((int(x["cctv_count"]) for x in g if x.get("cctv_count")), default=None)
        names = list(dict.fromkeys(x["name"] for x in g if x.get("name")))
        out.append(poi("school_zone", g, "·".join(names)[:200] or "어린이보호구역", sub=p.get("kind"), modes="child",
                       detail=f"{p.get('kind') or ''} 앞 어린이보호구역".strip() + (f" · CCTV {c}대" if c else "")))
    # 지하철역: 교통약자 편의시설 수(광주교통공사 API, 019_transit)와 열차 시간표의 첫차~막차
    trains = {name: (frm, to) for name, frm, to in query(
        "SELECT station_name, TIME_FORMAT(MIN(arrive_time), '%H:%i'), TIME_FORMAT(MAX(arrive_time), '%H:%i') "
        "FROM subway_timetable WHERE day_type = '평일' GROUP BY station_name")}
    for sid, name, addr, lon, lat, elev, elev_ok, toilet, charger in query(
            "SELECT station_id, name, IFNULL(address, ''), lon, lat, elevator_total, elevator_available, "
            "accessible_toilet_total, wheelchair_charger_total FROM subway_station"):
        elev, elev_ok, toilet, charger = int(elev), int(elev_ok), int(toilet), int(charger)
        frm, to = trains.get(name, (None, None))
        parts = ["지하철역", f"엘리베이터 {elev}대" + (f"(지금 {elev_ok}대 운행)" if elev_ok < elev else "")
                 if elev else "엘리베이터 없음"]
        parts += [s for s, ok in ((f"장애인 화장실 {toilet}곳", toilet), (f"전동휠체어 충전기 {charger}대", charger)) if ok]
        if frm:
            parts.append(f"열차 {frm}~{to}")
        out.append(poi("subway", [{"lon": float(lon), "lat": float(lat), "src": f"subway_station:{sid}",
                                   "address": addr or None}],
                       f"{name}역", sub="지하철역", wheelchair=1 if elev_ok > 0 else 0, modes="senior,wheelchair",
                       open_hours=f"열차 {frm}~{to}" if frm else None, open_from=frm, open_to=to,
                       open_days="매일" if frm else None, detail=" · ".join(parts)))

    # 화장실 안 비상벨: 50m 안 가장 가까운 화장실이 열려 있을 때만 쓸 수 있다
    toilets_xy = [t for t in out if t["kind"] == "toilet"]
    for b in out:
        if b["kind"] == "bell" and b["sub"] == "화장실":
            near = min(toilets_xy, key=lambda t: ((t["lon"] - b["lon"]) * KX) ** 2 + ((t["lat"] - b["lat"]) * KY) ** 2,
                       default=None)
            if near and (((near["lon"] - b["lon"]) * KX) ** 2 + ((near["lat"] - b["lat"]) * KY) ** 2) <= 50 ** 2:
                b.update(open_from=near["open_from"], open_to=near["open_to"], open_days=near["open_days"],
                         open_hours=f"{near['name']} 개방 시간 {near['open_hours'] or '모름'}",
                         place_type="화장실 안")
    return out


def q(v):
    if v is None:
        return "NULL"
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def main():
    print("광주 원본 시설 읽는 중...")
    out = build()
    by = defaultdict(int)
    for p in out:
        by[p["kind"]] += 1
    print("정제 결과:", dict(by), "합계", len(out))
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("SET NAMES utf8mb4;\nDELETE FROM poi;\n")
        for k in range(0, len(out), 1000):
            vals = []
            for p in out[k:k + 1000]:
                x, y = f"{p['lon']:.7f}", f"{p['lat']:.7f}"
                vals.append(f"({q(p['kind'])},{q(p['sub'])},{q(p['name'][:200])},{q(p['address'])},{x},{y},"
                            f"ST_SRID(POINT({x},{y}),0),{min(p['cnt'], 32767)},"
                            f"{'NULL' if p['wheelchair'] is None else int(p['wheelchair'])},{q(p['open_hours'])},{q(p['open_from'])},{q(p['open_to'])},{q(p['open_days'])},{q(p['season_from'])},{q(p['season_to'])},"
                            f"{'NULL' if p['indoor'] is None else int(p['indoor'])},{q(p['place_type'])},"
                            f"{q(p['biz_from'])},{q(p['biz_to'])},{q(p['biz_days'])},"
                            f"{'NULL' if p['biz_sure'] is None else int(p['biz_sure'])},"
                            f"{q(p['phone'])},{q(p['modes'])},{q(p['detail'][:300] if p['detail'] else None)},"
                            f"{q(json.dumps(p['sources']))},NOW())")
            f.write("INSERT INTO poi (kind, sub, name, address, lon, lat, geom, cnt, wheelchair, open_hours, open_from, open_to, open_days, season_from, season_to, indoor, place_type, biz_from, biz_to, biz_days, biz_sure, phone, "
                    "modes, detail, sources, built_at) VALUES\n" + ",\n".join(vals) + ";\n")
        for level, col in (("sigungu", "sgg_cd"), ("dong", "dong_cd")):
            f.write(f"UPDATE poi p JOIN admin_area a ON a.level = '{level}' AND MBRContains(a.geom, p.geom) "
                    f"AND ST_Contains(a.geom, p.geom) SET p.{col} = a.code;\n")
        f.write("INSERT INTO data_source (type, source, title, api, license, total_count, loaded_count, skipped_count,"
                " complete, loaded_at)\nVALUES ('poi', 'computed', '정제 시설 광주 (scripts/build_poi.py)', "
                f"'시설 표 14개', '-', {len(out)}, {len(out)}, 0, 1, NOW())\n"
                "ON DUPLICATE KEY UPDATE total_count = VALUES(total_count), loaded_count = VALUES(loaded_count),"
                " loaded_at = NOW();\n")
    print("SQL:", OUT)
    if "--sql-only" not in sys.argv:
        run_file(OUT)
        print("DB 적재 끝")


if __name__ == "__main__":
    main()
