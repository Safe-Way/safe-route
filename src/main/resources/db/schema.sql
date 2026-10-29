-- 안심길 DB 표 (MySQL 8.4)
-- 실행: mysql -u safe_route -p safe_route < src/main/resources/db/schema.sql
--
-- 좌표는 WGS84 경도(lon)·위도(lat)를 그대로 POINT(lon, lat)에 넣는다 (SRID 0).
-- SRID 4326을 쓰면 MySQL이 위도·경도 순서로 읽어서 헷갈리기 쉬워, 평면 좌표로 넣고
-- 거리는 ST_Distance_Sphere로 잰다. 공간 인덱스는 범위(bbox) 검색에 쓴다.

-- 시설 한 개 = 한 줄. 가로등·보안등·CCTV·경찰·비상벨·편의점·지킴이집·쉼터·화장실을 한 표에 둔다.
CREATE TABLE IF NOT EXISTS facility (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    type        VARCHAR(30)  NOT NULL COMMENT '시설 종류 (streetlight, security_light, cctv, police, ...)',
    subtype     VARCHAR(50)  NULL     COMMENT '세부 종류 (지구대, 파출소, 방범등, 생활방범 등)',
    source      VARCHAR(20)  NOT NULL COMMENT '출처 (safetydata, safemap, localdata)',
    source_id   VARCHAR(64)  NOT NULL COMMENT '출처에서의 번호',
    name        VARCHAR(200) NULL,
    address     VARCHAR(300) NULL,
    sido_code   CHAR(2)      NULL     COMMENT '시도 코드 (출처가 줄 때만)',
    sgg_code    CHAR(5)      NULL     COMMENT '시군구 코드 (출처가 줄 때만)',
    lon         DOUBLE       NOT NULL,
    lat         DOUBLE       NOT NULL,
    unit_count  SMALLINT     NOT NULL DEFAULT 1 COMMENT 'CCTV 카메라 대수 등. 없으면 1',
    geom        POINT        NOT NULL SRID 0,
    attrs       JSON         NULL     COMMENT '공통 칸에 없는 원본 값',
    PRIMARY KEY (id),
    UNIQUE KEY uk_facility_source (source, type, source_id),
    KEY idx_facility_type (type),
    SPATIAL INDEX sx_facility_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 데이터 출처별 적재 현황. 다시 받을 때 무엇이 언제 들어갔는지 확인용.
CREATE TABLE IF NOT EXISTS data_source (
    type          VARCHAR(30)  NOT NULL,
    source        VARCHAR(20)  NOT NULL,
    title         VARCHAR(100) NOT NULL,
    api           VARCHAR(200) NOT NULL COMMENT 'API 주소 또는 파일 이름',
    license       VARCHAR(100) NULL,
    total_count   INT          NULL     COMMENT '출처가 알려 준 전체 건수',
    loaded_count  INT          NOT NULL DEFAULT 0 COMMENT 'DB에 들어간 건수',
    skipped_count INT          NOT NULL DEFAULT 0 COMMENT '좌표가 없거나 이상해서 뺀 건수',
    complete      BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '전체를 다 받았는지',
    loaded_at     DATETIME     NULL,
    PRIMARY KEY (type, source)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
