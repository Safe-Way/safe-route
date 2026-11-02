-- 시군구마다 시설 종류별 밀도를 담는 region_density 표를 만든다. (2026-11-02, 한 번만 실행)
-- 요구사항 6.3 "데이터 없음"과 6.5 A4: 시군구의 시설 밀도가 전국 중간값의 10% 아래인 종류는
-- 그 지역에서 데이터 부족으로 보고, 길마다 edge_stat.data_gap 비트를 켠다.
-- 실행 뒤 값을 채운다:  python3 scripts/build_data_gap.py   (먼저 전국 시군구 경계를 admin_area에 넣는다)
CREATE TABLE IF NOT EXISTS region_density (
    sgg_cd      VARCHAR(10) NOT NULL COMMENT 'SGIS 시군구 코드 (admin_area.code)',
    kind        VARCHAR(10) NOT NULL COMMENT 'light, cctv, help, guard, rest (data_gap 비트 1, 2, 4, 8, 16)',
    n           INT         NOT NULL COMMENT '시군구 경계 안 시설 수',
    area_km2    DOUBLE      NOT NULL COMMENT '시군구 넓이(km², 위도 보정 근사)',
    density     DOUBLE      NOT NULL COMMENT 'n ÷ area_km2',
    ratio       DOUBLE      NULL COMMENT 'density ÷ 전국 시군구 중간값',
    gap         TINYINT     NOT NULL COMMENT 'ratio가 0.1 아래면 1 (데이터 부족)',
    computed_at DATETIME    NOT NULL,
    PRIMARY KEY (sgg_cd, kind)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '시군구별 시설 밀도와 데이터 부족 판정';
