"""CCTV 설치 목적을 묶어 cctv.purpose_group에 넣는다 (013_cctv_purpose.sql).

- 원문 목적(kind)이 있으면 그대로 묶는다 (purpose_from = '원본').
- 광주 파일처럼 목적이 비어 있으면, 10m 안에 행안부 파일(localdata) 카메라가 있을 때 그 목적을 가져온다
  (purpose_from = '10m 안 행안부 파일'). 같은 카메라가 두 파일에 같이 있는 경우가 많다. 여럿이면 가장 많은 목적.
- 광주 파일(source = 'gwangju')에서 그래도 빈 것은 '통합관제(방범)'으로 둔다 (purpose_from = '자료 설명(통합관제센터)').
  공공데이터포털 15084631 자료 설명: 광주시 CCTV통합관제센터(사회재난과)가 관제·운영하는 CCTV, 키워드 범죄예방·방범용.
  줄마다 단서(관리 기관·촬영 방면)가 모두 같아 하나씩 가를 수는 없고, 파일 전체의 설명으로 정한다 (2026-11-05).
  길 계산은 단속용(교통·쓰레기·시설·재난)만 빼므로 '모름'과 똑같이 세고, 지도 세부 종류(poi.sub) 이름만 바뀐다.
- 그래도 없으면 '모름'. 직접 고친 줄(purpose_from = '직접 입력')은 건드리지 않는다.

CCTV를 다시 적재(Java FacilityImporter)하거나 fix_cctv_coords.py를 돌린 뒤에 다시 돌린다.
실행: python3 scripts/classify_cctv.py [--sql-only]
"""
import math
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_edge_stat import ROOT, query, run_file  # noqa: E402

OUT = os.path.join(ROOT, "data", "raw", "national", "cctv_purpose.sql")
GROUP = {"생활방범": "방범", "차량방범": "방범", "어린이보호": "어린이", "다목적": "다목적", "교통단속": "교통",
         "교통정보수집": "교통", "쓰레기단속": "쓰레기", "시설물관리": "시설·재난", "재난재해": "시설·재난", "기타": "기타"}
NEAR = 10.0


def main():
    case = " ".join(f"WHEN '{k}' THEN '{v}'" for k, v in GROUP.items())
    keep = "(purpose_from IS NULL OR purpose_from <> '직접 입력')"
    sql = ["SET NAMES utf8mb4;",
           f"UPDATE cctv SET purpose_group = CASE kind {case} ELSE '모름' END, "
           f"purpose_from = IF(kind IS NULL, NULL, '원본') WHERE {keep};"]

    # 목적이 빈 카메라 → 10m 안 행안부 파일 카메라의 목적
    empty = query(f"SELECT id, lon, lat FROM cctv WHERE kind IS NULL AND {keep}")
    if empty:
        xs, ys = [float(r[1]) for r in empty], [float(r[2]) for r in empty]
        box = f"lon BETWEEN {min(xs) - 0.001} AND {max(xs) + 0.001} AND lat BETWEEN {min(ys) - 0.001} AND {max(ys) + 0.001}"
        ref = query(f"SELECT lon, lat, kind FROM cctv WHERE source = 'localdata' AND kind IS NOT NULL AND {box}")
        kx, ky = 111320.0 * math.cos(math.radians(sum(ys) / len(ys))), 110540.0
        grid = defaultdict(list)
        for lon, lat, kind in ref:
            x, y = float(lon) * kx, float(lat) * ky
            grid[(int(x // NEAR), int(y // NEAR))].append((x, y, GROUP.get(kind, "기타")))
        hit = defaultdict(list)
        for rid, lon, lat in empty:
            x, y = float(lon) * kx, float(lat) * ky
            near = [g for dx in (-1, 0, 1) for dy in (-1, 0, 1)
                    for (gx, gy, g) in grid[(int(x // NEAR) + dx, int(y // NEAR) + dy)]
                    if (gx - x) ** 2 + (gy - y) ** 2 <= NEAR * NEAR]
            if near:
                hit[Counter(near).most_common(1)[0][0]].append(rid)
        for g, ids in hit.items():
            for k in range(0, len(ids), 1000):
                sql.append(f"UPDATE cctv SET purpose_group = '{g}', purpose_from = '10m 안 행안부 파일' "
                           f"WHERE id IN ({','.join(ids[k:k + 1000])});")
        print(f"목적이 빈 카메라 {len(empty)}곳 중 10m 안 행안부 파일로 채움 {sum(map(len, hit.values()))}곳: "
              + ", ".join(f"{g} {len(v)}" for g, v in sorted(hit.items(), key=lambda t: -len(t[1]))))
    # 광주 통합관제센터 파일: 10m 안 행안부 파일로도 못 찾은 것은 자료 설명대로 (위 설명)
    sql.append("UPDATE cctv SET purpose_group = '통합관제(방범)', purpose_from = '자료 설명(통합관제센터)' "
               f"WHERE source = 'gwangju' AND kind IS NULL AND purpose_group = '모름' AND {keep};")
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(sql) + "\n")
    print("SQL:", OUT)
    if "--sql-only" not in sys.argv:
        run_file(OUT)
        for g, n in query("SELECT purpose_group, COUNT(*) FROM cctv GROUP BY 1 ORDER BY 2 DESC"):
            print(f"  {g}: {n}")


if __name__ == "__main__":
    main()
