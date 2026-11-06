"""광주 데이터를 명령 하나로 갱신한다 (FR-08): 내려받기 → DB 적재 → 길 점수 다시 계산.

각 단계 스크립트는 같은 것을 다시 넣으면 지우고 새로 넣으므로, 이 명령을 몇 번 돌려도 결과가 같다.
끝나면 주요 표의 줄 수를 data/raw/refresh_last.json에 적고, 지난번과 달라진 표를 알려 준다.

순서가 중요한 곳
- 시설 원본 적재(Java) 뒤에 좌표 고치기(CCTV·몰린 좌표·빠진 좌표·화장실)를 다시 돌린다. 원본을 다시 넣으면
  잘못된 좌표도 다시 들어오기 때문이다. 찾은 좌표는 CSV에 남아 있어 카카오를 다시 부르지 않는다.
- CCTV 목적 분류는 CCTV 좌표를 고친 뒤에 돌린다.
- 대중교통을 다시 넣으면 출구 엘리베이터 값(손으로 확인한 값)이 비므로, 바로 뒤에 subway_entrance_elevator.sql을 넣는다.
- 정제 시설(poi)을 다시 만들면 id가 바뀌므로 길별 주변 시설(edge_poi)은 맨 끝에 만든다.
- 도로망을 다시 넣으면 길 번호가 바뀌므로 길별 원값(edge_stat)·데이터 부족·주변 시설을 모두 다시 계산한다.

서버는 길찾기 요청마다 DB를 읽으므로 다시 띄우지 않아도 반영된다. 다만 도는 동안(특히 '계산' 단계)에는
경로가 "확인 안 됨"으로 나올 수 있어, 시연·테스트가 없는 때에 돌린다.

실행
  python3 scripts/refresh_data.py                 전부 (받기 → 넣기 → 계산)
  python3 scripts/refresh_data.py --dry-run       할 일 목록만 보여 줌
  python3 scripts/refresh_data.py --from load     넣기부터 (받기는 건너뜀)
  python3 scripts/refresh_data.py --only compute  계산만 (download, load, compute 중 하나)
  python3 scripts/refresh_data.py --fresh police,crosswalk
      이 데이터는 받아 둔 폴더를 _old_<이름>_<시각>으로 옮기고 처음부터 다시 받는다.
      없으면 이어 받기만 한다(이미 받은 페이지는 건너뜀). 가로등처럼 큰 데이터는 하루 한도 때문에 며칠 걸린다.
  python3 scripts/refresh_data.py --roads         OSM 도로망도 다시 받아 넣음 (광주 전체, 수십 분)

직접 받아 넣는 CSV(data/raw/national/cctv_national.csv, data/raw/regional/의 광주 CSV, 공중화장실 CSV)는
새 파일로 바꿔 둔 뒤 이 명령을 돌린다. 기록: data/raw/refresh_logs/<시각>/<단계>.log
"""
import json
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from load_road_network import read_env, run_sql  # noqa: E402  같은 .env·mysql 접속을 쓴다

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "data", "raw")
LOCK = os.path.join(RAW, "refresh.lock")
LAST = os.path.join(RAW, "refresh_last.json")
PY = sys.executable
STAGES = ["download", "load", "compute"]

# (단계, 이름, 명령, 설명). 명령이 None이면 이 파일 안에서 처리한다
STEPS = [
    ("download", "national", [PY, "scripts/download_national.py"], "공공데이터 API (이어 받기)"),
    ("download", "subway", [PY, "scripts/fetch_subway_elevator.py"], "지하철역·출입구 CSV (광주교통공사·OSM)"),
    ("load", "facility", ["./mvnw", "-q", "spring-boot:run", "-Dspring-boot.run.profiles=import"],
     "시설 원본 → 종류별 시설 표 (Java)"),
    ("load", "cctv_coords", [PY, "scripts/fix_cctv_coords.py"], "CCTV 잘못된 좌표 고치기"),
    ("load", "pile_coords", [PY, "scripts/fix_pile_coords.py"], "한 점에 몰린 좌표 고치기"),
    ("load", "missing_coords", [PY, "scripts/fill_missing_coords.py", "gwangju"], "좌표 없는 시설 채우기 (광주)"),
    ("load", "toilet", [PY, "scripts/load_toilet_std.py", "gwangju"], "공중화장실 (광주)"),
    ("load", "cctv_purpose", [PY, "scripts/classify_cctv.py"], "CCTV 목적 분류"),
    ("load", "transit", [PY, "scripts/load_transit.py"], "대중교통 표"),
    ("load", "subway_elevator", None, "출구 엘리베이터 (손으로 확인한 값, scripts/subway_entrance_elevator.sql)"),
    ("load", "events", [PY, "scripts/load_events.py"], "행사·돌발상황 (문화축제 표준데이터, 국가교통정보센터)"),
    ("load", "roads", [PY, "scripts/load_road_network.py", "--area", "KR-29", "gwangju"],
     "OSM 도로망 (--roads 줄 때만)"),
    ("compute", "poi", [PY, "scripts/build_poi.py"], "정제 시설 (지도 표시용)"),
    ("compute", "edge_stat", [PY, "scripts/build_edge_stat.py"], "길별 감점 원값"),
    ("compute", "data_gap", [PY, "scripts/build_data_gap.py"], "데이터 부족 표시"),
    ("compute", "edge_poi", [PY, "scripts/build_edge_poi.py"], "길별 주변 시설"),
]

# 끝나고 줄 수를 세는 표. 다시 돌렸을 때 같아야 한다
COUNT_TABLES = ["streetlight", "security_light", "cctv", "police", "emergency_bell", "convenience_store",
                "child_safety_house", "heat_shelter", "cold_shelter", "public_toilet", "crosswalk", "footbridge",
                "facility_unlocated", "subway_station", "subway_entrance", "bus_stop", "road_node", "road_edge",
                "road_edge_excluded", "poi", "edge_stat", "edge_poi"]

CHECKS = [
    ("길마다 원값이 있음 (road_edge = edge_stat)",
     "SELECT (SELECT COUNT(*) FROM road_edge) - (SELECT COUNT(*) FROM edge_stat)"),
    ("없는 길·시설을 가리키는 edge_poi 줄 없음",
     "SELECT COUNT(*) FROM edge_poi ep LEFT JOIN road_edge e ON e.id = ep.edge_id"
     " LEFT JOIN poi p ON p.id = ep.poi_id WHERE e.id IS NULL OR p.id IS NULL"),
    ("확인 안 된 지하철 출구 없음", "SELECT COUNT(*) FROM subway_entrance WHERE elevator IS NULL"),
    ("같은 원본이 두 번 들어간 시설 없음 (cctv)",
     "SELECT COUNT(*) FROM (SELECT 1 FROM cctv GROUP BY source, source_id HAVING COUNT(*) > 1) d"),
]


def log(msg):
    print(f"[{datetime.now():%H:%M:%S}] {msg}", flush=True)


def query(sql):
    """한 값만 돌려주는 SQL을 학교 DB(.env)에 묻는다"""
    import re
    env = read_env()
    m = re.match(r"jdbc:mysql://([^:/]+)(?::(\d+))?/([^?]+)", env["DB_URL"])
    cmd = ["mysql", "--default-character-set=utf8mb4", "-h", m.group(1), "-P", m.group(2) or "3306",
           "-u", env["DB_USERNAME"], "-N", "-B", m.group(3), "-e", sql]
    r = subprocess.run(cmd, capture_output=True, text=True, env=dict(os.environ, MYSQL_PWD=env["DB_PASSWORD"]))
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip())
    return r.stdout.strip()


def parse_args(argv):
    opts = {"dry": False, "from": None, "only": None, "fresh": [], "roads": False}
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--dry-run":
            opts["dry"] = True
        elif a == "--roads":
            opts["roads"] = True
        elif a in ("--from", "--only", "--fresh") and i + 1 < len(argv):
            v = argv[i + 1]
            i += 1
            if a == "--fresh":
                opts["fresh"] = [x.strip() for x in v.split(",") if x.strip()]
            elif v not in STAGES:
                sys.exit(f"{a} 값은 {', '.join(STAGES)} 중 하나여야 합니다: {v}")
            else:
                opts[a[2:]] = v
        else:
            sys.exit(__doc__)
        i += 1
    return opts


def chosen_steps(opts):
    if opts["only"]:
        stages = [opts["only"]]
    elif opts["from"]:
        stages = STAGES[STAGES.index(opts["from"]):]
    else:
        stages = STAGES
    return [s for s in STEPS if s[0] in stages and (s[1] != "roads" or opts["roads"])]


def move_old(names, stamp, dry):
    """--fresh: 받아 둔 폴더를 옆으로 옮겨 처음부터 다시 받게 한다 (지우지 않음)"""
    for name in names:
        src = os.path.join(RAW, "national", name)
        if not os.path.isdir(src):
            log(f"  {name}: 받아 둔 폴더가 없어 그냥 받음")
            continue
        dst = os.path.join(RAW, "national", f"_old_{name}_{stamp}")
        log(f"  {name}: {os.path.relpath(src, ROOT)} → {os.path.relpath(dst, ROOT)}")
        if not dry:
            shutil.move(src, dst)


def counts():
    return {t: int(query(f"SELECT COUNT(*) FROM {t}")) for t in COUNT_TABLES}


def main():
    opts = parse_args(sys.argv[1:])
    steps = chosen_steps(opts)
    stamp = datetime.now().strftime("%Y%m%d%H%M")
    log(f"할 일 {len(steps)}개" + (" (미리 보기)" if opts["dry"] else ""))
    for stage, name, cmd, desc in steps:
        shown = " ".join("python3" if c == PY else c for c in cmd) if cmd else \
            "mysql < scripts/subway_entrance_elevator.sql"
        print(f"  {stage:8} {name:16} {desc}\n  {'':8} {'':16} $ {shown}")
    if opts["fresh"]:
        log("처음부터 다시 받을 데이터")
        move_old(opts["fresh"], stamp, dry=True)
    if opts["dry"]:
        return

    if os.path.exists(LOCK):
        sys.exit(f"다른 갱신이 돌고 있습니다 ({os.path.relpath(LOCK, ROOT)}). 멈춘 거라면 이 파일을 지우고 다시 실행하세요.")
    logdir = os.path.join(RAW, "refresh_logs", stamp)
    os.makedirs(logdir, exist_ok=True)
    with open(LOCK, "w") as f:
        f.write(f"{os.getpid()} {stamp}\n")
    started = time.time()
    try:
        if opts["fresh"] and any(s[0] == "download" for s in steps):
            move_old(opts["fresh"], stamp, dry=False)
        for stage, name, cmd, desc in steps:
            t0 = time.time()
            log(f"{stage} / {name}: {desc}")
            out = os.path.join(logdir, f"{name}.log")
            if cmd is None:
                run_sql(os.path.join(ROOT, "scripts", "subway_entrance_elevator.sql"))
                code = 0
            else:
                with open(out, "w", encoding="utf-8") as f:
                    code = subprocess.run(cmd, cwd=ROOT, stdout=f, stderr=subprocess.STDOUT).returncode
            if code != 0:
                tail = open(out, encoding="utf-8", errors="replace").read()[-1500:] if cmd else ""
                print(tail)
                sys.exit(f"{name} 단계가 실패했습니다 (기록: {os.path.relpath(out, ROOT)}). "
                         f"고친 뒤 --from {stage} 로 이어서 돌릴 수 있습니다.")
            log(f"  끝 ({time.time() - t0:.0f}초)")

        log("점검")
        bad = 0
        for title, sql in CHECKS:
            n = int(query(sql))
            bad += n != 0
            print(f"  {'OK ' if n == 0 else '확인'} {title}" + ("" if n == 0 else f": {n}"))
        now = counts()
        before = json.load(open(LAST, encoding="utf-8"))["counts"] if os.path.exists(LAST) else {}
        changed = {t: (before.get(t), n) for t, n in now.items() if before and before.get(t) != n}
        if not before:
            log("줄 수를 처음 적었습니다. 다음 실행 때 이 값과 비교합니다.")
        elif not changed:
            log("주요 표 줄 수가 지난번과 모두 같습니다.")
        else:
            log("지난번과 줄 수가 달라진 표 (새 원본을 받았다면 자연스러움)")
            for t, (a, b) in changed.items():
                print(f"  {t}: {a} → {b}")
        with open(LAST, "w", encoding="utf-8") as f:
            json.dump({"at": datetime.now().isoformat(timespec="seconds"), "steps": [s[1] for s in steps],
                       "counts": now}, f, ensure_ascii=False, indent=1)
        log(f"갱신 끝 ({(time.time() - started) / 60:.1f}분)" + (f", 점검 {bad}개 확인 필요" if bad else ""))
    finally:
        os.remove(LOCK)


if __name__ == "__main__":
    main()
