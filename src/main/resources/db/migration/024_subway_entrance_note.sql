-- 지하철 출구마다 화면에 덧붙일 안내 한 줄 (2026-11-05).
-- 엘리베이터 판단(elevator)은 바꾸지 않고 설명에만 붙인다. 예: 양동시장 3번 출구 휠체어리프트.
--   광주교통공사 역정보(출구별 안내)와 공공데이터 15048368(승강설비 현황, 2021)에 휠체어리프트 1대가 있고,
--   실시간 편의시설 API는 0대라서 엘리베이터로 치지 않고 "있다"는 안내만 한다(사용자 결정).
-- 값은 scripts/subway_entrance_elevator.sql이 채우고, scripts/build_poi.py가 출구·역 설명(poi.detail)에 붙인다.
-- 칸 추가만 하므로 돌고 있는 서버에 영향이 없다.
ALTER TABLE subway_entrance
    ADD COLUMN note VARCHAR(200) NULL COMMENT '설명에 덧붙일 출구 안내 (예: 휠체어리프트 있음). 엘리베이터 판단에는 쓰지 않음' AFTER elevator_source;
