-- 한파쉼터 출처를 생활안전지도(10,000건 제한)에서 재난안전데이터(DSSP-IF-10804, 전국 54,378건)로 바꾼다.
-- (2026-10-29, 한 번만 실행) 실행 뒤 새 데이터를 넣는다:
--   ./mvnw spring-boot:run -Dspring-boot.run.profiles=import -Dspring-boot.run.arguments=--types=cold_shelter

-- 1. 새 출처의 칸 이름(대문자)도 읽도록 가상 칸을 바꾸고, 운영 시간·비고 칸과 시군구 인덱스를 추가
ALTER TABLE cold_shelter
    MODIFY capacity VARCHAR(10) AS (COALESCE(attrs ->> '$.UTZTN_PSBLTY_TNOP', attrs ->> '$.utztn_psblty_tnop')) VIRTUAL COMMENT '이용 가능 인원 (attrs에서 꺼낸 값)',
    ADD COLUMN weekday_hours  VARCHAR(20)  AS (NULLIF(CONCAT_WS('~', attrs ->> '$.WKDY_OPER_BGNG_HR', attrs ->> '$.WKDY_OPER_END_HR'), '')) VIRTUAL COMMENT '평일 운영 시간, 예: 0900~1800 (attrs에서 꺼낸 값)',
    ADD COLUMN saturday_hours VARCHAR(20)  AS (NULLIF(CONCAT_WS('~', attrs ->> '$.STDY_OPER_BGNG_HR', attrs ->> '$.STDY_OPER_END_HR'), '')) VIRTUAL COMMENT '토요일 운영 시간 (attrs에서 꺼낸 값)',
    ADD COLUMN sunday_hours   VARCHAR(20)  AS (NULLIF(CONCAT_WS('~', attrs ->> '$.SNDY_OPER_BGNG_HR', attrs ->> '$.SNDY_OPER_END_HR'), '')) VIRTUAL COMMENT '일요일 운영 시간 (attrs에서 꺼낸 값)',
    ADD COLUMN holiday_hours  VARCHAR(20)  AS (NULLIF(CONCAT_WS('~', attrs ->> '$.LHLDY_OPER_BGNG_HR', attrs ->> '$.LHLDY_OPER_END_HR'), '')) VIRTUAL COMMENT '공휴일 운영 시간 (attrs에서 꺼낸 값)',
    ADD COLUMN note           VARCHAR(500) AS (attrs ->> '$.RMRK') VIRTUAL COMMENT '비고: 특보 시 연장 운영 등 (attrs에서 꺼낸 값)',
    ADD KEY idx_cold_shelter_sgg (sgg_code);

-- 2. 생활안전지도에서 받은 옛 한파쉼터 지우기
DELETE FROM cold_shelter WHERE source = 'safemap';
DELETE FROM data_source WHERE type = 'cold_shelter' AND source = 'safemap';
