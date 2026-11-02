-- 세잎지도 DB 표 (MySQL 8.0 이상)
-- 실행: mysql -u <사용자> -p <DB> < src/main/resources/db/schema.sql
--
-- 시설은 종류마다 표를 하나씩 둔다 (police, streetlight, security_light, cctv, ...).
-- 모든 시설 표는 아래 "공통 칸"을 똑같이 갖고, 종류마다 필요한 칸이 뒤에 붙는다.
--
-- 공통 칸
--   id          표 안에서의 번호
--   kind        세부 종류 (지구대·파출소, 방범등·한전주, 생활방범 등)
--   source      출처 (safetydata, safemap, localdata, gwangju, gwangju_donggu)
--   source_id   출처에서의 번호. (source, source_id)가 겹치면 한 줄만 들어간다
--   name, address, sido_code(시도 2자리), sgg_code(시군구 5자리)
--   lon, lat    WGS84 경도·위도
--   geom        POINT(lon, lat), SRID 0. 범위 검색용 공간 인덱스가 걸려 있다
--   attrs       따로 칸을 만들지 않은 원본 값 (JSON)
--
-- 인덱스
--   geom        공간 인덱스. 지도 범위·주변 검색 (MBRContains, ST_Distance_Sphere와 함께)
--   source      (source, source_id) 중복 방지 겸 출처별 검색
--   kind        세부 종류가 여러 개인 표만 (예: police에서 지구대만)
--   sgg_code    시군구 코드를 주는 출처의 표만 (예: sgg_code = '29110' 광주 동구)
--   address     앞 20글자. 주소 앞부분 검색 (예: address LIKE '서울특별시 종로구%')
--   name        앞 20글자. 이름이 있는 표만 (예: name LIKE '서석%')
--
-- 종류별 칸 중 "attrs에서 꺼낸 값" 표시가 있는 칸은 attrs JSON에서 바로 읽어 보여 주는 가상 칸이다.
-- 저장 공간을 더 쓰지 않고, 적재 코드는 attrs만 채우면 된다.
--
-- 좌표를 SRID 4326으로 넣으면 MySQL이 위도·경도 순서로 읽어서 헷갈리기 쉬워,
-- 평면 좌표(SRID 0)로 넣고 거리는 ST_Distance_Sphere로 잰다.

-- ---------------------------------------------------------------- 밝기

CREATE TABLE IF NOT EXISTS streetlight (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    kind       VARCHAR(50)  NULL,
    source     VARCHAR(20)  NOT NULL,
    source_id  VARCHAR(64)  NOT NULL,
    name       VARCHAR(200) NULL,
    address    VARCHAR(300) NULL,
    sido_code  CHAR(2)      NULL,
    sgg_code   CHAR(5)      NULL,
    lon        DOUBLE       NOT NULL,
    lat        DOUBLE       NOT NULL,
    geom       POINT        NOT NULL SRID 0,
    attrs      JSON         NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_streetlight_source (source, source_id),
    KEY idx_streetlight_sgg (sgg_code),
    KEY idx_streetlight_address (address(20)),
    SPATIAL INDEX sx_streetlight_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '가로등';

CREATE TABLE IF NOT EXISTS security_light (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    kind         VARCHAR(50)  NULL COMMENT '방범등, 보안등, 한전주, 전용주 등',
    source       VARCHAR(20)  NOT NULL,
    source_id    VARCHAR(64)  NOT NULL,
    name         VARCHAR(200) NULL,
    address      VARCHAR(300) NULL,
    sido_code    CHAR(2)      NULL,
    sgg_code     CHAR(5)      NULL,
    lon          DOUBLE       NOT NULL,
    lat          DOUBLE       NOT NULL,
    geom         POINT        NOT NULL SRID 0,
    attrs        JSON         NULL,
    lamp_count   SMALLINT     NOT NULL DEFAULT 1 COMMENT '등 개수 (없으면 1)',
    install_year  VARCHAR(10)  AS (COALESCE(attrs ->> '$.installationYear', attrs ->> '$."설치연도"')) VIRTUAL COMMENT '설치 연도 (attrs에서 꺼낸 값)',
    manager       VARCHAR(100) AS (COALESCE(attrs ->> '$.institutionNm', attrs ->> '$."관리기관명"')) VIRTUAL COMMENT '관리 기관 (attrs에서 꺼낸 값)',
    manager_phone VARCHAR(30)  AS (attrs ->> '$.phoneNumber') VIRTUAL COMMENT '관리 기관 전화 (attrs에서 꺼낸 값)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_security_light_source (source, source_id),
    KEY idx_security_light_kind (kind),
    KEY idx_security_light_sgg (sgg_code),
    KEY idx_security_light_address (address(20)),
    SPATIAL INDEX sx_security_light_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '보안등';

-- ---------------------------------------------------------------- 감시

CREATE TABLE IF NOT EXISTS cctv (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    kind           VARCHAR(50)  NULL COMMENT '설치 목적 (생활방범, 어린이보호, 교통단속 등)',
    source         VARCHAR(20)  NOT NULL,
    source_id      VARCHAR(64)  NOT NULL,
    name           VARCHAR(200) NULL COMMENT '관리 기관',
    address        VARCHAR(300) NULL,
    sido_code      CHAR(2)      NULL,
    sgg_code       CHAR(5)      NULL,
    lon            DOUBLE       NOT NULL,
    lat            DOUBLE       NOT NULL,
    geom           POINT        NOT NULL SRID 0,
    attrs          JSON         NULL,
    camera_count   SMALLINT     NOT NULL DEFAULT 1 COMMENT '카메라 대수 (없으면 1)',
    pixels         VARCHAR(20)  AS (COALESCE(attrs ->> '$."카메라화소수"', attrs ->> '$."카메라화소"')) VIRTUAL COMMENT '화소 (attrs에서 꺼낸 값)',
    direction      VARCHAR(100) AS (COALESCE(attrs ->> '$."촬영방면정보"', attrs ->> '$."촬영방면"')) VIRTUAL COMMENT '촬영 방면 (attrs에서 꺼낸 값)',
    retention_days VARCHAR(20)  AS (attrs ->> '$."보관일수"') VIRTUAL COMMENT '보관 일수 (attrs에서 꺼낸 값)',
    installed      VARCHAR(20)  AS (COALESCE(attrs ->> '$."설치연월"', attrs ->> '$."설치연도"')) VIRTUAL COMMENT '설치 시기 (attrs에서 꺼낸 값)',
    manager_phone  VARCHAR(30)  AS (attrs ->> '$."관리기관전화번호"') VIRTUAL COMMENT '관리 기관 전화 (attrs에서 꺼낸 값)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cctv_source (source, source_id),
    KEY idx_cctv_kind (kind),
    KEY idx_cctv_address (address(20)),
    SPATIAL INDEX sx_cctv_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'CCTV';

-- ---------------------------------------------------------------- 도움 받을 곳

CREATE TABLE IF NOT EXISTS police (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    kind       VARCHAR(50)  NULL COMMENT '경찰서, 지구대, 파출소, 치안센터, 스마트 안심부스 등',
    source     VARCHAR(20)  NOT NULL,
    source_id  VARCHAR(64)  NOT NULL,
    name       VARCHAR(200) NULL,
    address    VARCHAR(300) NULL,
    sido_code  CHAR(2)      NULL,
    sgg_code   CHAR(5)      NULL,
    lon        DOUBLE       NOT NULL,
    lat        DOUBLE       NOT NULL,
    geom       POINT        NOT NULL SRID 0,
    attrs      JSON         NULL,
    phone      VARCHAR(30)  AS (attrs ->> '$.TELNO') VIRTUAL COMMENT '전화번호 (attrs에서 꺼낸 값)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_police_source (source, source_id),
    KEY idx_police_kind (kind),
    KEY idx_police_sgg (sgg_code),
    KEY idx_police_address (address(20)),
    KEY idx_police_name (name(20)),
    SPATIAL INDEX sx_police_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '경찰 시설';

CREATE TABLE IF NOT EXISTS emergency_bell (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    kind           VARCHAR(50)  NULL COMMENT '설치 장소 (가로변, 공원, 화장실, 주차장 등)',
    source         VARCHAR(20)  NOT NULL,
    source_id      VARCHAR(64)  NOT NULL,
    name           VARCHAR(200) NULL,
    address        VARCHAR(300) NULL,
    sido_code      CHAR(2)      NULL,
    sgg_code       CHAR(5)      NULL,
    lon            DOUBLE       NOT NULL,
    lat            DOUBLE       NOT NULL,
    geom           POINT        NOT NULL SRID 0,
    attrs          JSON         NULL,
    purpose        VARCHAR(50)  AS (attrs ->> '$.ins_purpos') VIRTUAL COMMENT '설치 목적 (attrs에서 꺼낸 값)',
    link_type      VARCHAR(30)  AS (attrs ->> '$.lnk_type') VIRTUAL COMMENT '연결 방식 (attrs에서 꺼낸 값)',
    police_linked  VARCHAR(1)   AS (attrs ->> '$.flag_pol_l') VIRTUAL COMMENT '경찰 연계 Y/N (attrs에서 꺼낸 값)',
    manager        VARCHAR(100) AS (attrs ->> '$.mng_inst') VIRTUAL COMMENT '관리 기관 (attrs에서 꺼낸 값)',
    manager_phone  VARCHAR(30)  AS (attrs ->> '$.mng_tel') VIRTUAL COMMENT '관리 기관 전화 (attrs에서 꺼낸 값)',
    install_year   VARCHAR(10)  AS (attrs ->> '$.ins_year') VIRTUAL COMMENT '설치 연도 (attrs에서 꺼낸 값)',
    last_inspected VARCHAR(10)  AS (attrs ->> '$.last_inspd') VIRTUAL COMMENT '마지막 점검일 (attrs에서 꺼낸 값)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_emergency_bell_source (source, source_id),
    KEY idx_emergency_bell_kind (kind),
    KEY idx_emergency_bell_sgg (sgg_code),
    KEY idx_emergency_bell_address (address(20)),
    SPATIAL INDEX sx_emergency_bell_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '안전 비상벨';

CREATE TABLE IF NOT EXISTS convenience_store (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    kind       VARCHAR(50)  NULL,
    source     VARCHAR(20)  NOT NULL,
    source_id  VARCHAR(64)  NOT NULL,
    name       VARCHAR(200) NULL,
    address    VARCHAR(300) NULL,
    sido_code  CHAR(2)      NULL,
    sgg_code   CHAR(5)      NULL,
    lon        DOUBLE       NOT NULL,
    lat        DOUBLE       NOT NULL,
    geom       POINT        NOT NULL SRID 0,
    attrs      JSON         NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_convenience_store_source (source, source_id),
    KEY idx_convenience_store_sgg (sgg_code),
    KEY idx_convenience_store_address (address(20)),
    KEY idx_convenience_store_name (name(20)),
    SPATIAL INDEX sx_convenience_store_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '편의점';

CREATE TABLE IF NOT EXISTS child_safety_house (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    kind       VARCHAR(50)  NULL,
    source     VARCHAR(20)  NOT NULL,
    source_id  VARCHAR(64)  NOT NULL,
    name       VARCHAR(200) NULL,
    address    VARCHAR(300) NULL,
    sido_code  CHAR(2)      NULL,
    sgg_code   CHAR(5)      NULL,
    lon        DOUBLE       NOT NULL,
    lat        DOUBLE       NOT NULL,
    geom       POINT        NOT NULL SRID 0,
    attrs      JSON         NULL,
    phone      VARCHAR(30)  AS (attrs ->> '$.telno') VIRTUAL COMMENT '전화번호 (attrs에서 꺼낸 값)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_child_safety_house_source (source, source_id),
    KEY idx_child_safety_house_sgg (sgg_code),
    KEY idx_child_safety_house_address (address(20)),
    KEY idx_child_safety_house_name (name(20)),
    SPATIAL INDEX sx_child_safety_house_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '아동안전지킴이집';

-- ---------------------------------------------------------------- 쉬어 갈 곳

CREATE TABLE IF NOT EXISTS heat_shelter (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    kind         VARCHAR(50)  NULL COMMENT '특정계층이용시설, 공공시설, 야외시설 등',
    source       VARCHAR(20)  NOT NULL,
    source_id    VARCHAR(64)  NOT NULL,
    name         VARCHAR(200) NULL,
    address      VARCHAR(300) NULL,
    sido_code    CHAR(2)      NULL,
    sgg_code     CHAR(5)      NULL,
    lon          DOUBLE       NOT NULL,
    lat          DOUBLE       NOT NULL,
    geom         POINT        NOT NULL SRID 0,
    attrs        JSON         NULL,
    capacity     VARCHAR(10)  AS (attrs ->> '$.use_num') VIRTUAL COMMENT '이용 가능 인원 (attrs에서 꺼낸 값)',
    area_m2      VARCHAR(10)  AS (attrs ->> '$.tot_ar') VIRTUAL COMMENT '면적 (attrs에서 꺼낸 값)',
    night_open   VARCHAR(1)   AS (attrs ->> '$.night_at') VIRTUAL COMMENT '야간 개방 Y/N (attrs에서 꺼낸 값)',
    weekend_open VARCHAR(1)   AS (attrs ->> '$.weekend_at') VIRTUAL COMMENT '주말 개방 Y/N (attrs에서 꺼낸 값)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_heat_shelter_source (source, source_id),
    KEY idx_heat_shelter_kind (kind),
    KEY idx_heat_shelter_address (address(20)),
    KEY idx_heat_shelter_name (name(20)),
    SPATIAL INDEX sx_heat_shelter_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '무더위쉼터';

CREATE TABLE IF NOT EXISTS cold_shelter (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    kind       VARCHAR(50)  NULL COMMENT '특정계층이용시설, 공공시설 등',
    source     VARCHAR(20)  NOT NULL,
    source_id  VARCHAR(64)  NOT NULL,
    name       VARCHAR(200) NULL,
    address    VARCHAR(300) NULL,
    sido_code  CHAR(2)      NULL,
    sgg_code   CHAR(5)      NULL,
    lon        DOUBLE       NOT NULL,
    lat        DOUBLE       NOT NULL,
    geom       POINT        NOT NULL SRID 0,
    attrs      JSON         NULL,
    capacity       VARCHAR(10)  AS (COALESCE(attrs ->> '$.UTZTN_PSBLTY_TNOP', attrs ->> '$.utztn_psblty_tnop')) VIRTUAL COMMENT '이용 가능 인원 (attrs에서 꺼낸 값)',
    weekday_hours  VARCHAR(20)  AS (NULLIF(CONCAT_WS('~', attrs ->> '$.WKDY_OPER_BGNG_HR', attrs ->> '$.WKDY_OPER_END_HR'), '')) VIRTUAL COMMENT '평일 운영 시간, 예: 0900~1800 (attrs에서 꺼낸 값)',
    saturday_hours VARCHAR(20)  AS (NULLIF(CONCAT_WS('~', attrs ->> '$.STDY_OPER_BGNG_HR', attrs ->> '$.STDY_OPER_END_HR'), '')) VIRTUAL COMMENT '토요일 운영 시간 (attrs에서 꺼낸 값)',
    sunday_hours   VARCHAR(20)  AS (NULLIF(CONCAT_WS('~', attrs ->> '$.SNDY_OPER_BGNG_HR', attrs ->> '$.SNDY_OPER_END_HR'), '')) VIRTUAL COMMENT '일요일 운영 시간 (attrs에서 꺼낸 값)',
    holiday_hours  VARCHAR(20)  AS (NULLIF(CONCAT_WS('~', attrs ->> '$.LHLDY_OPER_BGNG_HR', attrs ->> '$.LHLDY_OPER_END_HR'), '')) VIRTUAL COMMENT '공휴일 운영 시간 (attrs에서 꺼낸 값)',
    note           VARCHAR(500) AS (attrs ->> '$.RMRK') VIRTUAL COMMENT '비고: 특보 시 연장 운영 등 (attrs에서 꺼낸 값)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cold_shelter_source (source, source_id),
    KEY idx_cold_shelter_sgg (sgg_code),
    KEY idx_cold_shelter_kind (kind),
    KEY idx_cold_shelter_address (address(20)),
    KEY idx_cold_shelter_name (name(20)),
    SPATIAL INDEX sx_cold_shelter_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '한파쉼터';

CREATE TABLE IF NOT EXISTS public_toilet (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    kind       VARCHAR(50)  NULL COMMENT '공중화장실, 개방화장실, 간이화장실, 이동화장실',
    source     VARCHAR(20)  NOT NULL,
    source_id  VARCHAR(64)  NOT NULL,
    name       VARCHAR(200) NULL,
    address    VARCHAR(300) NULL,
    sido_code  CHAR(2)      NULL,
    sgg_code   CHAR(5)      NULL,
    lon        DOUBLE       NOT NULL,
    lat        DOUBLE       NOT NULL,
    geom       POINT        NOT NULL SRID 0,
    attrs      JSON         NULL,
    phone      VARCHAR(30)  AS (attrs ->> '$.telno') VIRTUAL COMMENT '전화번호 (attrs에서 꺼낸 값)',
    open_hours VARCHAR(100) AS (attrs ->> '$.opn_hr') VIRTUAL COMMENT '개방 시간 (attrs에서 꺼낸 값)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_public_toilet_source (source, source_id),
    KEY idx_public_toilet_kind (kind),
    KEY idx_public_toilet_address (address(20)),
    KEY idx_public_toilet_name (name(20)),
    SPATIAL INDEX sx_public_toilet_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '공중화장실';

-- ---------------------------------------------------------------- 길 위 구조물

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

-- ---------------------------------------------------------------- 전체 보기

-- 모든 시설을 한 번에 볼 때 쓰는 뷰. 종류별 표의 공통 칸만 이어 붙인다.
-- 예) 조선대 500m 안 시설 수:
--   SELECT type, COUNT(*) FROM facility_all
--   WHERE ST_Distance_Sphere(geom, POINT(126.9306, 35.1408)) < 500 GROUP BY type;
-- 큰 표를 검색할 때는 공간 인덱스를 타도록 종류별 표에 MBRContains로 직접 묻는 편이 빠르다.
CREATE OR REPLACE VIEW facility_all AS
          SELECT 'streetlight'        AS type, id, kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom FROM streetlight
UNION ALL SELECT 'security_light'     AS type, id, kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom FROM security_light
UNION ALL SELECT 'cctv'               AS type, id, kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom FROM cctv
UNION ALL SELECT 'police'             AS type, id, kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom FROM police
UNION ALL SELECT 'emergency_bell'     AS type, id, kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom FROM emergency_bell
UNION ALL SELECT 'convenience_store'  AS type, id, kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom FROM convenience_store
UNION ALL SELECT 'child_safety_house' AS type, id, kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom FROM child_safety_house
UNION ALL SELECT 'heat_shelter'       AS type, id, kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom FROM heat_shelter
UNION ALL SELECT 'cold_shelter'       AS type, id, kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom FROM cold_shelter
UNION ALL SELECT 'public_toilet'      AS type, id, kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom FROM public_toilet;

-- ---------------------------------------------------------------- 적재 현황

-- 데이터 출처별 적재 현황. type은 위 시설 표 이름과 같다.
CREATE TABLE IF NOT EXISTS data_source (
    type          VARCHAR(30)  NOT NULL COMMENT '시설 표 이름',
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '데이터 출처별 적재 현황';
