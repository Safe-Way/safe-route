-- 전국어린이보호구역표준데이터(공공데이터포털 15012891)를 담을 child_protection_zone 표를 만든다. (2026-11-02, 한 번만 실행)
-- 어린이 모드 지도에 보호구역을 그리는 데 쓴다(FR-48). 대상 시설(학교·유치원 등) 위치만 있어서 반경 300m로 그린다.
-- API는 활용 신청이 아직 반영되지 않아, 포털에서 받은 CSV를 data/raw/national/child_protection_zone.csv 로 두고 넣는다:
--   ./mvnw spring-boot:run -Dspring-boot.run.profiles=import -Dspring-boot.run.arguments=--types=child_protection_zone

-- 어린이보호구역: 안전 시설이 아니라 구역 표시용이라 facility_all 뷰에는 넣지 않는다.
-- kind = 시설 종류 (초등학교, 유치원, 어린이집, 특수학교, 학원, 대안학교)
-- 지정여부·CCTV 칸은 대부분 비어 있어서(광주 429곳 중 2곳만 값이 있음) 목록에 있는 곳을 모두 보호구역으로 본다.
CREATE TABLE IF NOT EXISTS child_protection_zone (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    kind        VARCHAR(50)  NULL COMMENT '시설 종류 (초등학교, 유치원, 어린이집 등)',
    source      VARCHAR(20)  NOT NULL,
    source_id   VARCHAR(64)  NOT NULL,
    name        VARCHAR(200) NULL COMMENT '대상 시설 이름',
    address     VARCHAR(300) NULL,
    sido_code   CHAR(2)      NULL,
    sgg_code    CHAR(5)      NULL,
    lon         DOUBLE       NOT NULL,
    lat         DOUBLE       NOT NULL,
    geom        POINT        NOT NULL SRID 0,
    attrs       JSON         NULL,
    road_width  VARCHAR(20)  AS (attrs ->> '$."보호구역도로폭"') VIRTUAL COMMENT '보호구역 도로 폭 m (원문, 예: 8, 5~12)',
    cctv_count  VARCHAR(10)  AS (attrs ->> '$."CCTV설치대수"') VIRTUAL COMMENT 'CCTV 대수 (대부분 비어 있음)',
    designated  CHAR(1)      AS (attrs ->> '$."어린이보호구역지정여부"') VIRTUAL COMMENT '지정 여부 Y/N (대부분 비어 있음)',
    police      VARCHAR(50)  AS (attrs ->> '$."관할경찰서명"') VIRTUAL COMMENT '관할 경찰서',
    PRIMARY KEY (id),
    UNIQUE KEY uk_child_protection_zone_source (source, source_id),
    KEY idx_child_protection_zone_kind (kind),
    KEY idx_child_protection_zone_name (name(20)),
    SPATIAL INDEX sx_child_protection_zone_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '어린이보호구역 (대상 시설 위치)';
