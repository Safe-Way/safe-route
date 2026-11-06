"""시연용 게스트 3명의 내 정보·3주치 이동 기록·걸음 속도·지도 설정을 넣는다 (2026-11-06).

비서가 "지난번 비 올 때도 버스를 타셨어요", "월요일 8시 반엔 보통 문화전당에 가시죠?"처럼 기록으로 말하는 것을
시연하려면 기록이 있어야 하는데, 발표 전 일주일로는 진짜 기록이 쌓이지 않는다. 그래서 시연용 게스트에게 미리 넣는다.
- 모두 꾸민 기록이다. 날씨(비·추위)도 시연을 위해 정한 값이고 실제 그날 날씨가 아니다. 발표에서 "시연용"이라고 밝힌다.
- 게스트 번호는 00000000-0000-4000-8000-00000000de0N (시연 앱은 이 번호로 실행). 진짜 사용자 번호와 겹치지 않는다.
- 다시 돌리면 이 세 번호의 줄을 지우고 새로 넣는다(내 정보를 지우면 이동 기록·지도 설정도 같이 지워짐).

  de01 시연 A: 휠체어 사용자(수동 휠체어). 평일 8시 반 동명동 → 국립아시아문화전당, 18시 → 집. 비 오는 날은 버스
  de02 시연 B: 어르신(지팡이). 월·수·금 10시 충장동 → 남광주시장. 추운 날은 버스, 무릎 아픈 날은 쉴 곳 많은 길
  de03 시연 C: 평소 일반인데 다리를 다쳐 11-30까지 휠체어 (비서가 "언제까지 휠체어를 타세요?"라고 물은 뒤의 상태)

실행: python3 scripts/seed_demo.py [--sql-only]
"""
import os
import sys
from datetime import date, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_edge_stat import ROOT, run_file  # noqa: E402

OUT = os.path.join(ROOT, "data", "raw", "regional", "seed_demo.sql")
A, B, C = (f"00000000-0000-4000-8000-00000000de0{i}" for i in (1, 2, 3))
START, END = date(2026, 10, 17), date(2026, 11, 6)
RAIN = {date(2026, 10, 28), date(2026, 11, 3), date(2026, 11, 5)}   # 시연용으로 정한 비 오는 날
COLD = {date(2026, 11, 4)}                                         # 시연용으로 정한 추운 날
ACC = ("국립아시아문화전당", 35.147, 126.920)
HOME_A = ("집", 35.148, 126.926)        # 동명동 둘레 (약 100m 반올림, 정확한 집은 휴대폰에만)
YANGLIM = ("양림동 펭귄마을", 35.137, 126.914)
MARKET = ("남광주시장", 35.139, 126.928)


def q(v):
    return "NULL" if v is None else ("'" + str(v).replace("'", "''") + "'")


def trip(g, d, slot, dong, dest, mode, travel, kind, planned, actual, *, weather=None, body=None, fb=None,
         reason=None, assistant=0):
    name, lat, lon = dest
    return (f"({q(g)}, {q(d.isoformat())}, {d.isoweekday()}, {q(slot)}, {q(dong)}, {q(name)}, {lat}, {lon}, {q(mode)}, "
            f"{q(travel)}, {q(kind)}, {assistant}, {q(weather)}, 0, {q(body)}, {planned}, {actual}, 'arrived', "
            f"{q(fb)}, {q(reason)})")


def main():
    days = [START + timedelta(n) for n in range((END - START).days + 1)]
    rows = []
    for d in days:
        wd = d.isoweekday()
        rain, cold = d in RAIN, d in COLD
        w = "rain" if rain else ("cold" if cold else None)
        if wd <= 5:  # 시연 A: 출근·퇴근
            if rain:
                rows.append(trip(A, d, "08:30", "24010540", ACC, "wheelchair", "transit", "bus", 18, 20, weather=w,
                                 assistant=1 if d == date(2026, 11, 5) else 0, fb="good"))
            else:
                rows.append(trip(A, d, "08:30", "24010540", ACC, "wheelchair", "walk", "safe", 16, 17, weather=w,
                                 fb="bad" if d == date(2026, 11, 2) else None,
                                 reason="턱" if d == date(2026, 11, 2) else None))
            rows.append(trip(A, d, "18:00", "24010510", HOME_A, "wheelchair", "transit" if rain else "walk",
                             "bus" if rain else "safe", 16, 18, weather=w))
        if wd == 6:  # 시연 A: 토요일 오후 나들이
            rows.append(trip(A, d, "14:00", "24010540", YANGLIM, "wheelchair", "walk", "safe", 22, 25, weather=w,
                             fb="good"))
        if wd in (1, 3, 5):  # 시연 B: 월·수·금 시장
            knee = d == date(2026, 11, 6)
            rows.append(trip(B, d, "10:00", "24010510", MARKET, "senior", "transit" if (cold or rain) else "walk",
                             "bus" if (cold or rain) else "safe", 20, 24, weather=w,
                             body="무릎 아픔" if knee else None, fb="good" if knee else None))
    for d in (date(2026, 10, 27), date(2026, 10, 29)):  # 시연 C: 다치기 전에는 일반으로 걸어 다님
        rows.append(trip(C, d, "09:00", "24010540", ACC, "adult", "walk", "shortest", 10, 10))

    speeds = {A: ("wheelchair", 50, [44, 46, 45, 47, 43, 46, 45, 44]), B: ("senior", 60, [52, 55, 50, 53, 54, 51])}
    ids = ", ".join(q(g) for g in (A, B, C))
    sql = ["SET NAMES utf8mb4;",
           f"DELETE FROM walk_profile WHERE guest_id IN ({ids});",
           f"DELETE FROM guardian_share WHERE guest_id IN ({ids});",
           f"DELETE FROM user_profile WHERE guest_id IN ({ids});",
           "INSERT INTO user_profile (guest_id, mode, mobility_aid, stairs_ok, max_detour, rest_every_m, prefer_transit,"
           " home_dong, temp_mode, temp_aid, temp_reason, temp_until, next_ask_on, setup_done, consent_trips, consent_at)"
           " VALUES",
           f"({q(A)}, 'wheelchair', 'manual_wheelchair', 0, 1.20, NULL, 1, '24010540', NULL, NULL, NULL, NULL, NULL, 1, 1, '2026-10-17 08:00:00'),",
           f"({q(B)}, 'senior', 'cane', 1, 1.20, 300, 1, '24010510', NULL, NULL, NULL, NULL, NULL, 1, 1, '2026-10-17 09:00:00'),",
           f"({q(C)}, 'adult', NULL, 2, NULL, NULL, 1, '24010540', 'wheelchair', 'manual_wheelchair', '다리 다침', '2026-11-30', '2026-11-30', 1, 1, '2026-10-27 08:00:00');",
           "INSERT INTO trip_log (guest_id, started_on, weekday, start_slot, from_dong, to_name, to_lat, to_lon, mode,"
           " travel, route_kind, by_assistant, weather, night, body_note, planned_min, actual_min, result, feedback,"
           " feedback_reason) VALUES",
           ",\n".join(rows) + ";",
           "INSERT INTO user_map_layer (guest_id, layer, shown, until, reason) VALUES",
           f"({q(A)}, 'toilet:wheelchair', 1, NULL, NULL), ({q(A)}, 'crosswalk:curb_low', 1, NULL, NULL),"
           f" ({q(A)}, 'subway_entrance:elevator', 1, NULL, NULL),",
           f"({q(C)}, 'toilet:wheelchair', 1, '2026-11-30', '다리 다침'), ({q(C)}, 'crosswalk:curb_low', 1, '2026-11-30', '다리 다침');"]
    for g, (mode, base, xs) in speeds.items():
        n, s = len(xs), sum(xs)
        sql.append(f"INSERT INTO walk_profile (guest_id, mode, speed_m_min, samples, walked_m, moving_s) VALUES "
                   f"({q(g)}, {q(mode)}, {round((base * 5 + s) / (5 + n), 1)}, {n}, {n * 600}, "
                   f"{sum(round(600 / x * 60) for x in xs)});")
        sql.append("INSERT INTO walk_speed_sample (guest_id, mode, distance_m, moving_s, speed_m_min, measured_on) VALUES "
                   + ", ".join(f"({q(g)}, {q(mode)}, 600, {round(600 / x * 60)}, {x}, "
                               f"{q((START + timedelta(i)).isoformat())})" for i, x in enumerate(xs)) + ";")
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(sql) + "\n")
    print(f"시연 기록 {len(rows)}건, SQL: {OUT}")
    if "--sql-only" not in sys.argv:
        run_file(OUT)
        print("넣음")


if __name__ == "__main__":
    main()
