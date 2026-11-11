# -*- coding: utf-8 -*-
"""현장에서 막혀 있던 길을 도로망에서 뺀다 (A파트 2026-11-11, 현장 시험 j29).

OSM은 늦게 고쳐진다. 새 건물이 들어서 막힌 골목도 OSM에 길로 남아 있으면 경로가 그리로 난다
(문화전당 → 푸른길 2구간 시험: 계절따라 옆 서쪽 골목, docs/field-test/2026-11-11.md). 현장에서 확인한 곳을 data/survey/blocked_edges.csv에 적고
이 스크립트로 학교 DB의 road_edge에서 road_edge_excluded(reason = field_blocked)로 옮긴다.
도로망을 OSM에서 다시 받을 때(load_road_network.py)도 같은 목록을 읽어 다시 뺀다.

- 길은 OSM 길 번호 + 양 끝 점 번호로 찾는다(구간 번호 seq는 다시 받으면 바뀔 수 있음). 점 순서는 상관없다.
- 020과 같은 방식: edge_stat은 edge_stat_excluded에 보관하고, edge_poi·edge_stat·road_edge 줄은 지운다.
- 엔진은 road_edge_excluded에서 underground·plaza 줄만 읽으므로 field_blocked 줄은 경로에 안 쓰인다.
  엔진은 도로망을 켤 때 한 번 읽으므로, 서버를 다시 켜야(배포) 반영된다.
- 되돌리기: CSV에서 줄을 지우고, road_edge_excluded·edge_stat_excluded의 그 줄을 road_edge·edge_stat으로 다시 넣은 뒤
  scripts/build_edge_poi.py를 다시 돌린다 (020 머리말과 같음).

실행: python3 scripts/apply_field_blocked.py          (미리 보기만)
      python3 scripts/apply_field_blocked.py --write  (학교 DB에 반영)
"""
import csv
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import review_reports as R  # noqa: E402  (mysql 접속: .env)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV = os.path.join(ROOT, "data", "survey", "blocked_edges.csv")
REASON = "field_blocked"


def blocked():
    """CSV의 막힌 길 목록: [(way_id, from_node, to_node, 메모)]"""
    with open(CSV, encoding="utf-8") as f:
        return [(int(r["way_id"]), int(r["from_node"]), int(r["to_node"]), r["memo"]) for r in csv.DictReader(f)]


def match(way, a, b):
    return f"(way_id = {way} AND ((from_node = {a} AND to_node = {b}) OR (from_node = {b} AND to_node = {a})))"


def main():
    write = "--write" in sys.argv[1:]
    rows = blocked()
    cmd, cnf = R.mysql_cmd()
    try:
        where = " OR ".join(match(w, a, b) for w, a, b, _ in rows)
        found = R.query(cmd, f"SELECT id, way_id, IFNULL(name, ''), highway, ROUND(length_m) FROM road_edge WHERE {where}")
        gone = R.query(cmd, f"SELECT way_id FROM road_edge_excluded WHERE reason = '{REASON}' AND ({where})")
        done = {int(x[0]) for x in gone}
        print(f"목록 {len(rows)}곳 → 도로망에 남은 구간 {len(found)}개, 이미 뺀 구간 {len(gone)}개")
        for eid, way, name, hw, m in found:
            print(f"  road_edge {eid} (OSM 길 {way}, {hw} {name}, {m}m)")
        for w, a, b, memo in rows:
            if w not in done and not any(int(x[1]) == w for x in found):
                print(f"  못 찾음: OSM 길 {w} {a}-{b} — OSM에서 길이 바뀌었는지 확인 ({memo[:30]})")
        if not found:
            return
        if not write:
            print("미리 보기만 했습니다. 반영: --write (그 뒤 서버를 다시 켜야 엔진에 반영)")
            return
        ids = ",".join(x[0] for x in found)
        R.query(cmd, "INSERT IGNORE INTO road_edge_excluded (edge_id, way_id, seq, from_node, to_node, length_m, highway,"
                     " name, footway, steps, covered, bridge, surface, tags, geom, reason)"
                     " SELECT id, way_id, seq, from_node, to_node, length_m, highway, name, footway, steps, covered, bridge,"
                     f" surface, tags, geom, '{REASON}' FROM road_edge WHERE id IN ({ids});"
                     f" INSERT IGNORE INTO edge_stat_excluded SELECT * FROM edge_stat WHERE edge_id IN ({ids});"
                     f" DELETE FROM edge_poi WHERE edge_id IN ({ids});"
                     f" DELETE FROM edge_stat WHERE edge_id IN ({ids});"
                     f" DELETE FROM road_edge WHERE id IN ({ids});")
        print(f"뺐습니다: road_edge {ids} → road_edge_excluded (reason = {REASON}). 서버를 다시 켜야 엔진에 반영됩니다")
    finally:
        os.remove(cnf)


if __name__ == "__main__":
    main()
