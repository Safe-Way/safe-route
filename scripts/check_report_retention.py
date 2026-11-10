# -*- coding: utf-8 -*-
"""결정 59 보관 기간 시험 (A파트 2026-11-10). review_reports.py의 PHOTO_DUE·ROW_DUE를 꾸민 신고로 계산만 한다.

학교 DB에 신고를 넣지 않는다. SQL의 road_report를 아래 꾸민 줄로 바꾸고 CURDATE()를 오늘(TODAY)로 바꾼다.
사진이 있는지는 보지 않는다(모든 꾸민 신고에 사진이 있다고 봄).

실행: python3 scripts/check_report_retention.py   (틀린 것이 있으면 끝 상태 1)
"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import review_reports as R  # noqa: E402

TODAY = "2026-11-30"
# (번호, 종류, 위도, 경도, 날짜, 상태, 사진 지울까, 줄 지울까)
CASES = [
    (101, "obstacle", 35.10, 126.90, "2026-11-20", "open", True, False),       # 기간 11-20 → 사진 11-27 뒤 지움, 줄은 12-20 뒤
    (201, "curb", 35.11, 126.90, "2026-11-10", "open", False, False),          # 턱 30일: 12-09까지라 그대로
    (301, "construction", 35.12, 126.90, "2026-11-01", "open", False, False),  # 공사 앞 신고: 30m 안 뒤 신고가 남아 있어 그대로
    (302, "construction", 35.12005, 126.90, "2026-11-22", "open", False, False),
    (401, "construction", 35.13, 126.90, "2026-11-01", "open", True, False),   # 공사 혼자: 11-07 끝 → 사진 지움, 줄은 12-07 뒤
    (501, "accident", 35.14, 126.90, "2026-11-29", "hidden", True, False),     # 숨긴 신고: 사진 바로 지움
    (601, "other", 35.15, 126.90, "2026-10-01", "open", True, True),           # 10-07 끝 + 30일 지남 → 줄도 지움
    (701, "accident", 35.16, 126.90, "2026-11-23", "open", False, False),      # 11-23 끝 + 7일 = 11-30, 오늘까지는 둠
    (801, "obstacle", 35.17, 126.90, "2026-11-01", "open", True, False),       # 옆 다른 종류(턱) 신고는 지키지 않음
    (802, "curb", 35.17, 126.90, "2026-11-29", "open", False, False),
    (901, "construction", 35.18, 126.90, "2026-11-01", "open", True, False),   # 50m 떨어진 같은 종류는 지키지 않음
    (902, "construction", 35.1805, 126.90, "2026-11-25", "open", False, False),
]


def fake_rows():
    return " UNION ALL ".join(
        f"SELECT {i} AS id, '{t}' AS report_type, '{s}' AS status, TIMESTAMP('{d} 09:00:00') AS created_at,"
        f" ST_SRID(POINT({lon}, {lat}), 0) AS geom" for i, t, lat, lon, d, s, _, _ in CASES)


def due(sql):
    # 사진이 있는지 보는 조건은 빼고(꾸민 신고엔 사진 표가 없음), 표와 오늘을 바꾼다
    sql = sql.replace("EXISTS (SELECT 1 FROM road_report_photo p WHERE p.report_id = r.id) AND ", "")
    sql = sql.replace("FROM road_report ", f"FROM ({fake_rows()}) ").replace("CURDATE()", f"DATE('{TODAY}')")
    return sql


def main():
    cmd, cnf = R.mysql_cmd()
    try:
        photo = {int(x[0]) for x in R.query(cmd, due(R.PHOTO_DUE))}
        row = {int(x[0]) for x in R.query(cmd, due(R.ROW_DUE))}
    finally:
        os.remove(cnf)
    bad = 0
    for i, t, _, _, d, s, want_photo, want_row in CASES:
        ok = (i in photo) == want_photo and (i in row) == want_row
        bad += not ok
        print(f"{'통과' if ok else '실패'} #{i} {t} {d} {s}: 사진 {'지움' if i in photo else '둠'}, 줄 {'지움' if i in row else '둠'}"
              + ("" if ok else f" (기대 사진 {'지움' if want_photo else '둠'}, 줄 {'지움' if want_row else '둠'})"))
    print(f"\n{len(CASES) - bad}/{len(CASES)} 통과 (오늘 = {TODAY})")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
