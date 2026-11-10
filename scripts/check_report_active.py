# -*- coding: utf-8 -*-
"""road_report_active 뷰(032) 규칙 시험 (결정 57, A파트 2026-11-10).

학교 DB에 꾸민 신고를 넣지 않는다(넣으면 앱 지도에 바로 보임). 032 SQL의 base 부분(@@BASE@@~@@END@@)을
아래 꾸민 신고 줄로 바꾸고, CURDATE()(@@TODAY@@)를 정한 날짜로 바꿔 같은 SQL을 학교 DB에서 계산만 한다.

실행: python3 scripts/check_report_active.py   (틀린 것이 있으면 끝 상태 1)
"""
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SQL = os.path.join(ROOT, "src", "main", "resources", "db", "migration", "032_road_report_active.sql")

# 꾸민 신고: (번호, 종류, 위도, 경도, 날짜, 상태). 시험마다 위치를 멀리 둬서 서로 안 묶이게 함
R = [
    # A 공사 하루: 11-10 → 11-16까지
    (101, "construction", 35.1000, 126.9000, "2026-11-10", "open"),
    # B 공사 마지막 날 연장: 11-10, 11-16(마지막 날 → 11-17), 11-17(늘어난 마지막 날 → 11-18)
    (201, "construction", 35.1100, 126.9000, "2026-11-10", "open"),
    (202, "construction", 35.11005, 126.9000, "2026-11-16", "open"),
    (203, "construction", 35.1100, 126.90005, "2026-11-17", "open"),
    # C 공사 중간 신고: 11-10, 11-13 → 기간 그대로 11-16, 2건이라 confirmed
    (301, "construction", 35.1200, 126.9000, "2026-11-10", "open"),
    (302, "construction", 35.1200, 126.9001, "2026-11-13", "open"),
    # D 공사 끝난 뒤: 11-10(→11-16), 11-18 신고 → 새로 11-18~11-24, 1건
    (401, "construction", 35.1300, 126.9000, "2026-11-10", "open"),
    (402, "construction", 35.1300, 126.9000, "2026-11-18", "open"),
    # E 사고 1일: 11-10만
    (501, "accident", 35.1400, 126.9000, "2026-11-10", "open"),
    # F 턱 30일: 11-10 → 12-09
    (601, "curb", 35.1500, 126.9000, "2026-11-10", "open"),
    # G 같은 곳 다른 종류는 따로: 공사 + 턱
    (701, "construction", 35.1600, 126.9000, "2026-11-10", "open"),
    (702, "curb", 35.1600, 126.9000, "2026-11-10", "open"),
    # H 50m 떨어진 같은 종류는 따로
    (801, "construction", 35.1700, 126.9000, "2026-11-10", "open"),
    (802, "construction", 35.17045, 126.9000, "2026-11-10", "open"),
    # I 숨긴 신고는 안 셈: 2건 중 1건 hidden → 1건, confirmed 0
    (901, "obstacle", 35.1800, 126.9000, "2026-11-10", "open"),
    (902, "obstacle", 35.1800, 126.9000, "2026-11-10", "hidden"),
    # J 마지막 날 같은 날 2건 → 하루만 연장(11-17)
    (1001, "construction", 35.1900, 126.9000, "2026-11-10", "open"),
    (1002, "construction", 35.1900, 126.9000, "2026-11-16", "open"),
    (1003, "construction", 35.1900, 126.9000, "2026-11-16", "open"),
    # K 팀 확인(confirmed) 1건 → confirmed 1
    (1101, "broken", 35.2000, 126.9000, "2026-11-10", "confirmed"),
]

# (오늘, 묶음 번호, 기대: None = 안 보임, 또는 (시작, 끝, 건수, confirmed))
EXPECT = [
    ("2026-11-16", 101, ("2026-11-10", "2026-11-16", 1, 0)),
    ("2026-11-17", 101, None),
    ("2026-11-17", 201, ("2026-11-10", "2026-11-18", 3, 1)),
    ("2026-11-18", 201, ("2026-11-10", "2026-11-18", 3, 1)),
    ("2026-11-19", 201, None),
    ("2026-11-14", 301, ("2026-11-10", "2026-11-16", 2, 1)),
    ("2026-11-17", 301, None),
    ("2026-11-17", 401, None),
    ("2026-11-20", 401, ("2026-11-18", "2026-11-24", 1, 0)),
    ("2026-11-10", 501, ("2026-11-10", "2026-11-10", 1, 0)),
    ("2026-11-11", 501, None),
    ("2026-12-09", 601, ("2026-11-10", "2026-12-09", 1, 0)),
    ("2026-12-10", 601, None),
    ("2026-11-12", 701, ("2026-11-10", "2026-11-16", 1, 0)),
    ("2026-11-12", 702, ("2026-11-10", "2026-12-09", 1, 0)),
    ("2026-11-12", 801, ("2026-11-10", "2026-11-16", 1, 0)),
    ("2026-11-12", 802, ("2026-11-10", "2026-11-16", 1, 0)),
    ("2026-11-10", 901, ("2026-11-10", "2026-11-10", 1, 0)),
    ("2026-11-17", 1001, ("2026-11-10", "2026-11-17", 3, 1)),
    ("2026-11-18", 1001, None),
    ("2026-11-12", 1101, ("2026-11-10", "2026-12-09", 1, 1)),
]


def read_env():
    env = {}
    with open(os.path.join(ROOT, ".env"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def fake_base(today):
    """그날까지 들어온 꾸민 신고만 (미래 신고는 아직 없음)"""
    rows = []
    for i, t, lat, lon, d, st in R:
        if d > today:
            continue
        rows.append(f"SELECT {i} AS id, '{t}' AS report_type, {lat} AS lat, {lon} AS lon, "
                    f"ST_SRID(POINT({lon}, {lat}), 0) AS geom, NULL AS edge_id, NULL AS accuracy_m, NULL AS memo, "
                    f"'{st}' AS status, TIMESTAMP('{d} 09:00:00') AS created_at, DATE('{d}') AS d, 0 AS photos")
    # 실제 base 처럼 숨긴 신고는 뺀다
    return "SELECT * FROM (\n" + "\nUNION ALL ".join(rows) + "\n) f WHERE f.status <> 'hidden'"


def query_for(today):
    sql = open(SQL, encoding="utf-8").read()
    q = sql[sql.index("WITH RECURSIVE"):].rstrip().rstrip(";")
    q = re.sub(r"-- @@BASE@@.*?-- @@END@@", lambda _: fake_base(today), q, flags=re.S)
    q = q.replace("CURDATE()   -- @@TODAY@@", f"DATE('{today}')")
    return q + ";"


def main():
    env = read_env()
    m = re.match(r"jdbc:mysql://([^:/?]+)(?::(\d+))?/([^?]+)", env["DB_URL"])
    fd, cnf = tempfile.mkstemp(suffix=".cnf")
    try:
        with os.fdopen(fd, "w") as f:
            f.write("[client]\nuser=%s\npassword=%s\nhost=%s\nport=%s\n"
                    % (env["DB_USERNAME"], env["DB_PASSWORD"], m.group(1), m.group(2) or "3306"))
        os.chmod(cnf, 0o600)
        cmd = ["mysql", "--defaults-extra-file=" + cnf, "--default-character-set=utf8mb4", "-B", "-N", m.group(3)]
        cache, bad = {}, 0
        for today, spot, want in EXPECT:
            if today not in cache:
                r = subprocess.run(cmd + ["-e", query_for(today)], capture_output=True, text=True)
                if r.returncode != 0:
                    sys.exit("SQL 실패: " + r.stderr.strip())
                cache[today] = {int(c[0]): c for c in (line.split("\t") for line in r.stdout.splitlines() if line)}
            row = cache[today].get(spot)
            got = None if row is None else (row[5], row[6], int(row[7]), int(row[14]))
            ok = got == want
            bad += not ok
            print(f"  {'통과' if ok else '실패'} 오늘 {today} 묶음 {spot}: 기대 {want} → {got}")
        print(f"시험 {len(EXPECT) - bad}/{len(EXPECT)}")
        sys.exit(1 if bad else 0)
    finally:
        os.remove(cnf)


if __name__ == "__main__":
    main()
