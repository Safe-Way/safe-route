-- 전국육교정보표준데이터(공공데이터포털)를 담을 footbridge 표를 만든다. (2026-10-30, 한 번만 실행)
-- 휠체어 모드에서 엘리베이터·경사로 없는 육교를 피하려고 쓴다. 실행 뒤 데이터를 넣는다:
--   python3 scripts/download_national.py footbridge
--   ./mvnw spring-boot:run -Dspring-boot.run.profiles=import -Dspring-boot.run.arguments=--types=footbridge

-- 육교: 안전 시설이 아니라 휠체어 모드에서 피할 곳을 찾는 데 쓴다. 그래서 facility_all 뷰에는 넣지 않는다.
-- kind = 장애인 편의시설 종류 원문 (없음, 경사로, 엘리베이터, 승강기, 경사로+엘리베이터 등)
-- wheelchair_ok = 엘리베이터·승강기·경사로가 있으면 1. 점자블록만 있거나 없음이면 0
CREATE TABLE IF NOT EXISTS footbridge (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    kind          VARCHAR(50)  NULL COMMENT '장애인 편의시설 종류 (없음, 경사로, 엘리베이터 등)',
    source        VARCHAR(20)  NOT NULL,
    source_id     VARCHAR(64)  NOT NULL,
    name          VARCHAR(200) NULL,
    address       VARCHAR(300) NULL,
    sido_code     CHAR(2)      NULL,
    sgg_code      CHAR(5)      NULL,
    lon           DOUBLE       NOT NULL,
    lat           DOUBLE       NOT NULL,
    geom          POINT        NOT NULL SRID 0,
    attrs         JSON         NULL,
    wheelchair_ok TINYINT      AS (CASE WHEN CONCAT_WS(' ', kind, attrs ->> '$.etcFcltyType') REGEXP '엘리베이터|승강기|경사로|E/V' THEN 1 ELSE 0 END) VIRTUAL COMMENT '휠체어 통과 가능 1, 불가 0 (kind와 기타 시설에서 판단)',
    length_m      VARCHAR(10)  AS (attrs ->> '$.ovrpsEt') VIRTUAL COMMENT '육교 길이 m (attrs에서 꺼낸 값)',
    width_m       VARCHAR(10)  AS (attrs ->> '$.ovrpsBt') VIRTUAL COMMENT '육교 폭 m (attrs에서 꺼낸 값)',
    safe_grade    VARCHAR(10)  AS (attrs ->> '$.safeGrad') VIRTUAL COMMENT '안전 등급 (attrs에서 꺼낸 값)',
    use_limit     VARCHAR(20)  AS (attrs ->> '$.useLmttType') VIRTUAL COMMENT '이용 제한 (attrs에서 꺼낸 값)',
    manager       VARCHAR(100) AS (attrs ->> '$.institutionNm') VIRTUAL COMMENT '관리 기관 (attrs에서 꺼낸 값)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_footbridge_source (source, source_id),
    KEY idx_footbridge_kind (kind),
    KEY idx_footbridge_address (address(20)),
    KEY idx_footbridge_name (name(20)),
    SPATIAL INDEX sx_footbridge_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '육교';
