# -*- coding: utf-8 -*-
"""신고 사진 확인·숨기기와 오래된 신고 정리 미리 보기 (결정 59, A파트 2026-11-10).

1. 새 신고 보기 (기본): 최근 N일 신고를 표로 찍고, 사진은 data/raw/report_review/ 에 받아 둔다(저장소에 안 올라감).
   얼굴·차 번호판이 보이거나 잘못 올린 신고면 2번으로 숨긴다. 재휘가 매일 아침 비서 점검 때 같이 본다.
2. 숨기기: --hide 12,13 → status = 'hidden'(지도·길찾기·창구에서 빠짐) + 그 신고 사진을 바로 지운다.
3. 정리 미리 보기: --expired → 결정 59 보관 기간이 지나 지울 사진·신고 줄 수만 보여 준다(지우지 않음).
   실제로 지우는 것은 서버가 하루 한 번 한다(채림, 같은 SQL: PHOTO_DUE·ROW_DUE).

결정 59 보관 기간:
- 사진: 신고 기간(종류별 1·7·30일)이 끝나고 7일 뒤 지움. 숨긴 신고의 사진은 바로 지움.
- 신고 줄: 신고 기간이 끝나고 30일 뒤 지움(사진도 같이 지워짐).
- 같은 종류 30m 안에 아직 기간이 남은 신고가 있으면 지우지 않는다. 길게 이어지는 공사는 첫 신고부터 기간을 이어
  계산하므로(결정 57, 뷰 road_report_active), 앞 신고를 먼저 지우면 계산이 바뀌기 때문.

실행: python3 scripts/review_reports.py [--days 2]
      python3 scripts/review_reports.py --hide 12,13
      python3 scripts/review_reports.py --expired
"""
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "data", "raw", "report_review")

# 종류별 신고 기간(일) — 결정 57, 뷰 road_report_active와 같음
DAYS = ("CASE {t}.report_type WHEN 'obstacle' THEN 1 WHEN 'accident' THEN 1 WHEN 'construction' THEN 7"
        " WHEN 'curb' THEN 30 WHEN 'steep' THEN 30 WHEN 'broken' THEN 30 ELSE 7 END")


def still_kept(keep):
    """같은 종류 30m 안(자기 포함)에 '기간 끝 + keep일'이 아직 안 지난 신고가 있으면 참. 숨긴 신고는 자기 자신만 지킴"""
    return (f"EXISTS (SELECT 1 FROM road_report n WHERE (n.status <> 'hidden' OR n.id = r.id)"
            f" AND n.report_type = r.report_type"
            f" AND ST_Distance_Sphere(n.geom, r.geom) <= 30"
            f" AND DATE_ADD(DATE(n.created_at), INTERVAL ({DAYS.format(t='n')}) - 1 + {keep} DAY) >= CURDATE())")


# 사진을 지울 신고: 숨긴 신고 또는 둘레 신고까지 모두 '기간 끝 + 7일'이 지난 신고
PHOTO_DUE = ("SELECT r.id FROM road_report r WHERE EXISTS (SELECT 1 FROM road_report_photo p WHERE p.report_id = r.id)"
             f" AND (r.status = 'hidden' OR NOT {still_kept(7)})")
# 줄을 지울 신고: 둘레 신고까지 모두 '기간 끝 + 30일'이 지난 신고 (숨긴 신고도 같은 날 지움)
ROW_DUE = f"SELECT r.id FROM road_report r WHERE NOT {still_kept(30)}"


def mysql_cmd():
    env = {}
    with open(os.path.join(ROOT, ".env"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    m = re.match(r"jdbc:mysql://([^:/?]+)(?::(\d+))?/([^?]+)", env["DB_URL"])
    fd, cnf = tempfile.mkstemp(suffix=".cnf")
    with os.fdopen(fd, "w") as f:
        f.write("[client]\nuser=%s\npassword=%s\nhost=%s\nport=%s\n"
                % (env["DB_USERNAME"], env["DB_PASSWORD"], m.group(1), m.group(2) or "3306"))
    os.chmod(cnf, 0o600)
    return ["mysql", "--defaults-extra-file=" + cnf, "--default-character-set=utf8mb4", m.group(3)], cnf


def query(cmd, sql):
    r = subprocess.run(cmd + ["-B", "-N", "-e", sql], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("DB 오류: " + r.stderr.strip())
    return [line.split("\t") for line in r.stdout.splitlines() if line]


def show_new(cmd, days):
    rows = query(cmd, "SELECT r.id, r.report_type, r.via, r.status, DATE_FORMAT(r.created_at, '%m-%d %H:%i'),"
                      " ROUND(r.lat, 4), ROUND(r.lon, 4), IFNULL(r.memo, ''),"
                      " (SELECT COUNT(*) FROM road_report_photo p WHERE p.report_id = r.id)"
                      f" FROM road_report r WHERE r.created_at >= CURDATE() - INTERVAL {days} DAY ORDER BY r.id")
    print(f"최근 {days}일 신고 {len(rows)}건")
    if not rows:
        return
    os.makedirs(OUT, exist_ok=True)
    for rid, typ, via, status, at, lat, lon, memo, photos in rows:
        print(f"  #{rid} {at} {typ} ({via}, {status}) {lat},{lon} 사진 {photos} {memo[:40]}")
        for seq in range(1, int(photos) + 1):
            path = os.path.join(OUT, f"{rid}_{seq}.jpg")
            with open(path, "wb") as f:
                subprocess.run(cmd + ["-B", "-N", "--raw", "-e",
                                      f"SELECT data FROM road_report_photo WHERE report_id = {int(rid)} AND seq = {seq}"],
                               stdout=f)
            print(f"      사진: {os.path.relpath(path, ROOT)}")
    print("문제가 있으면: python3 scripts/review_reports.py --hide 번호,번호")


def hide(cmd, ids):
    ids = ",".join(str(int(x)) for x in ids)
    before = query(cmd, f"SELECT COUNT(*) FROM road_report WHERE id IN ({ids}) AND status <> 'hidden'")[0][0]
    photos = query(cmd, f"SELECT COUNT(*) FROM road_report_photo WHERE report_id IN ({ids})")[0][0]
    query(cmd, f"UPDATE road_report SET status = 'hidden' WHERE id IN ({ids}) AND status <> 'hidden';"
               f" DELETE FROM road_report_photo WHERE report_id IN ({ids});")
    print(f"숨김 {before}건, 지운 사진 {photos}장 (신고 {ids})")


def expired(cmd):
    photo = query(cmd, f"SELECT COUNT(*), IFNULL(SUM((SELECT COUNT(*) FROM road_report_photo p WHERE p.report_id = d.id)), 0)"
                       f" FROM ({PHOTO_DUE}) d")[0]
    row = query(cmd, f"SELECT COUNT(*) FROM ({ROW_DUE}) d")[0][0]
    total = query(cmd, "SELECT COUNT(*) FROM road_report")[0][0]
    print(f"신고 {total}건 중 사진을 지울 신고 {photo[0]}건(사진 {photo[1]}장), 줄을 지울 신고 {row}건 — 미리 보기만, 지우지 않음")
    print("\n서버가 하루 한 번 할 SQL (결정 59):")
    print(f"  DELETE FROM road_report_photo WHERE report_id IN (SELECT id FROM ({PHOTO_DUE}) d);")
    print(f"  DELETE FROM road_report WHERE id IN (SELECT id FROM ({ROW_DUE}) d);")


def main():
    args = sys.argv[1:]
    cmd, cnf = mysql_cmd()
    try:
        if "--hide" in args:
            hide(cmd, [x for x in args[args.index("--hide") + 1].split(",") if x])
        elif "--expired" in args:
            expired(cmd)
        else:
            show_new(cmd, int(args[args.index("--days") + 1]) if "--days" in args else 2)
    finally:
        os.remove(cnf)


if __name__ == "__main__":
    main()
