-- 지하철 출입구마다 지상 엘리베이터가 있는지 (결정 51).
-- OSM wheelchair 표시는 92곳 중 4곳만 있어서, 광주교통공사 역 안내 등에서 손으로 확인한 값을 따로 둔다.
-- scripts/build_poi.py가 이 값으로 poi(kind = 'subway_entrance').wheelchair를 채운다.
-- 칸 추가만 하므로 돌고 있는 서버(옛 버전)에 영향이 없다.
ALTER TABLE subway_entrance
    ADD COLUMN elevator        TINYINT      NULL COMMENT '이 출구 쪽 지상 엘리베이터: 1 있음, 0 없음, NULL 모름' AFTER wheelchair,
    ADD COLUMN elevator_source VARCHAR(300) NULL COMMENT 'elevator 값의 근거 (주소·문서 이름)' AFTER elevator;
