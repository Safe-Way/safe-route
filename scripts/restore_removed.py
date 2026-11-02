"""좌표를 못 찾아 시설 표에서 지웠던 줄을 원본 파일에서 다시 꺼내 facility_unlocated 표에 넣는다 (한 번만 쓰면 되는 복구용).

2026-11-02에 fix_cctv_coords.py(CCTV 1,870)와 fix_pile_coords.py(보안등 2,432·비상벨 17·횡단보도 39)가 지운 줄.
지운 목록(data/raw/national/cctv_removed.csv, pile_removed.csv)에는 번호·주소·좌표만 있어서, 원본에서 이름·종류·속성을 찾는다.
- CCTV: 관리번호(source_id)로 cctv_national.csv 줄
- 공통POI 보안등(safetydata): SN, 비상벨(safemap): objt_id
- 표준데이터(보안등·횡단보도, 원본 번호 없음): 주소와 좌표(소수 여섯째 자리)가 같은 원본 줄
이제는 이 스크립트들이 지우기 전에 facility_unlocated로 옮기므로 다시 돌릴 일은 없다 (여러 번 돌려도 같은 결과).

실행: python3 scripts/restore_removed.py [--sql-only]
"""
import csv
import glob
import io
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_edge_stat import ROOT, run_file  # noqa: E402

RAW = os.path.join(ROOT, "data", "raw", "national")
OUT = os.path.join(RAW, "restore_removed.sql")
# 원본 폴더, 번호 칸, 이름 칸, 주소 칸, 종류 칸, 좌표 칸
SRC = {
    ("security_light", "safetydata"): ("security_light", "SN", ["POLSTN_NM", "FCLT_GVMNFC_NM"], ["ROAD_NM_ADDR", "ADDR"],
                                       "FCLT_TYPE", None),
    ("security_light", "data_go_kr"): ("security_light_std", None, ["lmpLcNm"], ["rdnmadr", "lnmadr"],
                                       "installationType", ("longitude", "latitude")),
    ("emergency_bell", "safemap"): ("emergency_bell", "objt_id", [], ["rn_adres", "ins_detail", "adres"], "ins_type", None),
    ("crosswalk", "data_go_kr"): ("crosswalk", None, ["roadNm"], ["rdnmadr", "lnmadr"], "crslkKnd",
                                  ("longitude", "latitude")),
}


def q(v):
    return "NULL" if v in (None, "") else "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def pick(item, keys):
    return next((str(item[k]).strip() for k in keys if item.get(k) not in (None, "") and str(item[k]).strip()), None)


def norm(v):
    """'35580.0', 35580 → '35580'"""
    v = str(v).strip()
    return v[:-2] if v.endswith(".0") else v


def key6(lon, lat):
    return f"{float(lon):.6f},{float(lat):.6f}"


def row(tbl, source, sid, kind, name, addr, lon, lat, reason, tried, attrs):
    return (f"({q(tbl)},{q(source)},{q(sid)},{q((kind or '')[:50])},{q((name or '')[:200])},{q((addr or '')[:300])},"
            f"{lon or 'NULL'},{lat or 'NULL'},{q(reason)},{q((tried or '')[:300])},"
            f"{q(json.dumps(attrs, ensure_ascii=False))},NOW())")


def main():
    vals, found, lost = [], defaultdict(int), defaultdict(int)

    # CCTV
    path = os.path.join(RAW, "cctv_removed.csv")
    if os.path.exists(path):
        removed = {r["source_id"]: r for r in csv.DictReader(open(path, encoding="utf-8"))}
        text = open(os.path.join(RAW, "cctv_national.csv"), "rb").read().decode("cp949", errors="replace")
        for r in csv.DictReader(io.StringIO(text)):
            rm = removed.pop(r.get("관리번호"), None)
            if rm:
                found["cctv"] += 1
                vals.append(row("cctv", "localdata", rm["source_id"], r.get("설치목적구분"), r.get("관리기관명"),
                                rm["address"], rm["lon"], rm["lat"], "좌표 오류·주소로 못 찾음 (번지 없음·카카오에 없음)",
                                rm["address"], {k: v for k, v in r.items() if v}))
        for rm in removed.values():
            lost["cctv"] += 1
            vals.append(row("cctv", "localdata", rm["source_id"], None, None, rm["address"], rm["lon"], rm["lat"],
                            "좌표 오류·주소로 못 찾음 (원본 줄 못 찾음)", rm["address"], {}))

    # 몰림 정리로 지운 것
    path = os.path.join(RAW, "pile_removed.csv")
    want = defaultdict(dict)  # (표, 출처) → {찾을 키: 지운 줄}
    if os.path.exists(path):
        for r in csv.DictReader(open(path, encoding="utf-8")):
            hashed = len(r["source_id"]) == 24 and all(c in "0123456789abcdef" for c in r["source_id"])
            for (tbl, source), (_, idk, _, _, _, xy) in SRC.items():
                # 보안등은 출처가 둘: 원본 번호가 없는 표준데이터는 source_id가 24자리 해시
                if tbl != r["table"] or (tbl == "security_light" and (source == "data_go_kr") != hashed):
                    continue
                k = norm(r["source_id"]) if idk else (r["address"], key6(r["lon"], r["lat"]))
                want[(tbl, source)].setdefault(k, []).append(r)  # 주소·좌표가 같은 줄이 여럿일 수 있음
    for (tbl, source), rows in want.items():
        folder, idk, names, addrs, kindk, xy = SRC[(tbl, source)]
        for p in sorted(glob.glob(os.path.join(RAW, folder, "p*.json"))):
            if not any(rows.values()):
                break
            for item in json.load(open(p, encoding="utf-8")):
                if idk:
                    k = norm(item.get(idk, ""))
                else:
                    try:
                        k = (pick(item, addrs) or "", key6(item[xy[0]], item[xy[1]]))
                    except (KeyError, TypeError, ValueError):
                        continue
                rm = rows[k].pop() if rows.get(k) else None
                if rm:
                    found[tbl] += 1
                    vals.append(row(tbl, source, rm["source_id"], pick(item, [kindk]), pick(item, names), rm["address"],
                                    rm["lon"], rm["lat"], "좌표 몰림·주소로 못 찾음 (카카오 주소·장소 검색 실패)",
                                    f"{rm['address']} | {rm['hint']}", item))
    # 원본에서 못 찾은 것도 아는 만큼 남긴다 (출처는 표에 하나뿐이면 그 출처)
    for (tbl, source), rows in want.items():
        for rm in (r for lst in rows.values() for r in lst):
            lost[tbl] += 1
            vals.append(row(tbl, source, rm["source_id"], None, None, rm["address"], rm["lon"], rm["lat"],
                            "좌표 몰림·주소로 못 찾음 (원본 줄 못 찾음)", f"{rm['address']} | {rm['hint']}", {}))

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("SET NAMES utf8mb4;\n")
        for i in range(0, len(vals), 500):
            f.write("INSERT INTO facility_unlocated (tbl, source, source_id, kind, name, address, lon, lat, "
                    "reason, tried, attrs, moved_at) VALUES\n" + ",\n".join(vals[i:i + 500])
                    + "\nON DUPLICATE KEY UPDATE kind = VALUES(kind), name = VALUES(name), reason = VALUES(reason), "
                    "attrs = VALUES(attrs);\n")
    print("원본에서 찾아 되살림:", dict(found), "| 원본 줄은 못 찾고 기록만 남김:", dict(lost), "| SQL:", OUT)
    if "--sql-only" not in sys.argv:
        run_file(OUT)
        print("DB 적재 끝")


if __name__ == "__main__":
    main()
