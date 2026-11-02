-- edge_stat에 밤 도움 지점과 어린이보호구역 거리 칸을 더한다. (2026-11-02, 한 번만 실행)
-- help_m은 낮에만 여는 치안센터·지킴이집(가게)까지 세므로, 밤 경로에는 밤에도 열린 곳만 센 help_night_m을 쓴다.
-- 밤 도움 지점 = 지구대·파출소·경찰서·스마트 안심부스(24시간) + 길가·공원·주차장 비상벨(24시간) + 편의점
--   (편의점이 24시간인지는 모름. 대부분 늦게까지 열어 넣었고, 요구사항상 24시간 판별은 하지 않음)
-- school_zone_m: 가장 가까운 어린이보호구역 대상 시설(학교·유치원 등)까지 m. 300m 안이면 보호구역 안 길로 본다 (어린이 모드)
-- 실행 뒤 값을 채운다:  python3 scripts/build_edge_stat.py
ALTER TABLE edge_stat
    ADD COLUMN help_night_m     DOUBLE   NULL COMMENT '가장 가까운 밤 도움 지점까지 m (24시간 경찰·길가 비상벨·편의점). 2km 넘으면 NULL' AFTER help_m,
    ADD COLUMN help_night_n_1km SMALLINT NOT NULL DEFAULT 0 COMMENT '반경 1km 안 밤 도움 지점 수' AFTER help_n_1km,
    ADD COLUMN school_zone_m    DOUBLE   NULL COMMENT '가장 가까운 어린이보호구역 대상 시설까지 m. 300m 안이면 보호구역 안. 2km 넘으면 NULL';
