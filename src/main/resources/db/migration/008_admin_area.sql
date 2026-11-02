-- 행정구역(시도·시군구·행정동) 경계를 담는 admin_area 표를 만든다. (2026-11-02, 한 번만 실행)
-- 데이터 부족을 구 단위로 판정하고(요구사항 6.3·6.5 A4), 시설·길이 어느 구·동에 있는지 찾는 데 쓴다.
-- 원본은 통계청(국가데이터처) SGIS 행정구역 경계 SHP(2025년 2분기, UTM-K). 실행 뒤 데이터를 넣는다:
--   python3 scripts/load_boundaries.py            (기본: 광주 시도·구·행정동)
-- code는 SGIS 코드라 행정표준코드와 다르다 (광주: SGIS 24, 행정표준 29. 동구: SGIS 24010, 행정표준 29110).
CREATE TABLE IF NOT EXISTS admin_area (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    level      VARCHAR(10)  NOT NULL COMMENT 'sido, sigungu, dong',
    code       VARCHAR(10)  NOT NULL COMMENT 'SGIS 행정구역 코드 (시도 2자리, 시군구 5자리, 행정동 8자리)',
    name       VARCHAR(50)  NOT NULL,
    full_name  VARCHAR(100) NULL COMMENT '예: 광주광역시 동구 충장동',
    base_date  CHAR(8)      NOT NULL COMMENT '경계 기준일 (예: 20250630)',
    geom       MULTIPOLYGON NOT NULL SRID 0 COMMENT '경도·위도 (WGS84로 바꾼 값)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_area (level, code),
    KEY idx_admin_area_name (name),
    SPATIAL INDEX sx_admin_area_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '행정구역 경계 (SGIS)';
