-- 전국횡단보도표준데이터(공공데이터포털)를 담을 crosswalk 표를 만든다. (2026-10-30, 한 번만 실행)
-- 장애인(휠체어) 모드에서 보도턱을 낮추지 않은 횡단보도를 피하려고 쓴다. 실행 뒤 데이터를 넣는다:
--   python3 scripts/download_national.py crosswalk
--   ./mvnw spring-boot:run -Dspring-boot.run.profiles=import -Dspring-boot.run.arguments=--types=crosswalk

-- 횡단보도: 장애인 모드에서 보도턱을 낮추지 않은 곳을 피하는 데 쓴다. facility_all 뷰에는 넣지 않는다.
-- kind = 횡단보도 종류 코드 (원본 그대로, 두 자리로 맞춤). 값이 비어 있으면 가상 칸이 NULL = "확인 안 됨"
CREATE TABLE IF NOT EXISTS crosswalk (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    kind           VARCHAR(50)  NULL COMMENT '횡단보도 종류 코드 (01, 02, 03, 04, 99)',
    source         VARCHAR(20)  NOT NULL,
    source_id      VARCHAR(64)  NOT NULL,
    name           VARCHAR(200) NULL COMMENT '도로명',
    address        VARCHAR(300) NULL,
    sido_code      CHAR(2)      NULL,
    sgg_code       CHAR(5)      NULL,
    lon            DOUBLE       NOT NULL,
    lat            DOUBLE       NOT NULL,
    geom           POINT        NOT NULL SRID 0,
    attrs          JSON         NULL,
    curb_lowered   TINYINT      AS (CASE attrs ->> '$.ftpthLowerYn' WHEN 'Y' THEN 1 WHEN 'N' THEN 0 END) VIRTUAL COMMENT '보도턱 낮춤 1, 아님 0, 모름 NULL',
    braille_block  TINYINT      AS (CASE attrs ->> '$.brllBlckYn' WHEN 'Y' THEN 1 WHEN 'N' THEN 0 END) VIRTUAL COMMENT '점자블록 있음 1, 없음 0, 모름 NULL',
    sound_signal   TINYINT      AS (CASE attrs ->> '$.sondSgngnrYn' WHEN 'Y' THEN 1 WHEN 'N' THEN 0 END) VIRTUAL COMMENT '음향신호기 있음 1, 없음 0, 모름 NULL',
    traffic_light  TINYINT      AS (CASE attrs ->> '$.tfclghtYn' WHEN 'Y' THEN 1 WHEN 'N' THEN 0 END) VIRTUAL COMMENT '보행자 신호등 있음 1, 없음 0, 모름 NULL',
    raised         TINYINT      AS (CASE attrs ->> '$.highlandYn' WHEN 'Y' THEN 1 WHEN 'N' THEN 0 END) VIRTUAL COMMENT '고원식(턱 없이 높인 횡단보도) 1, 아님 0',
    spot_light     TINYINT      AS (CASE attrs ->> '$.cnctrLghtFcltyYn' WHEN 'Y' THEN 1 WHEN 'N' THEN 0 END) VIRTUAL COMMENT '집중조명 있음 1, 없음 0, 모름 NULL',
    green_seconds  VARCHAR(10)  AS (attrs ->> '$.greenSgngnrTime') VIRTUAL COMMENT '보행 신호 시간(초)',
    lanes          VARCHAR(10)  AS (attrs ->> '$.cartrkCo') VIRTUAL COMMENT '차로 수',
    length_m       VARCHAR(10)  AS (attrs ->> '$.et') VIRTUAL COMMENT '횡단 길이 m',
    manager        VARCHAR(100) AS (attrs ->> '$.institutionNm') VIRTUAL COMMENT '관리 기관',
    PRIMARY KEY (id),
    UNIQUE KEY uk_crosswalk_source (source, source_id),
    KEY idx_crosswalk_kind (kind),
    KEY idx_crosswalk_address (address(20)),
    SPATIAL INDEX sx_crosswalk_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '횡단보도';
