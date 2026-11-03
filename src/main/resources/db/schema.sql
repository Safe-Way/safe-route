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
    purpose_group  VARCHAR(10)  NOT NULL DEFAULT '모름' COMMENT '설치 목적 분류: 방범·어린이·다목적·교통·쓰레기·시설·재난·기타·모름 (scripts/classify_cctv.py, 013)',
    purpose_from   VARCHAR(20)  NULL COMMENT '분류 근거: 원본 / 10m 안 행안부 파일 / 직접 입력',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cctv_source (source, source_id),
    KEY idx_cctv_kind (kind),
    KEY idx_cctv_purpose (purpose_group),
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

-- ---------------------------------------------------------------- 도로망 (OSM)

-- 점: 길이 만나거나 끝나는 곳, 그리고 횡단보도·엘리베이터처럼 표시가 붙은 점. id는 OSM 노드 번호
CREATE TABLE IF NOT EXISTS road_node (
    id        BIGINT      NOT NULL COMMENT 'OSM 노드 번호',
    lon       DOUBLE      NOT NULL,
    lat       DOUBLE      NOT NULL,
    geom      POINT       NOT NULL SRID 0,
    tags      JSON        NULL COMMENT 'OSM 태그 (표시가 있는 점만)',
    highway   VARCHAR(30) AS (tags ->> '$.highway') VIRTUAL COMMENT 'crossing, traffic_signals, elevator 등',
    crossing  VARCHAR(30) AS (tags ->> '$.crossing') VIRTUAL COMMENT 'traffic_signals, uncontrolled, marked, unmarked 등. NULL = 모름',
    kerb      VARCHAR(20) AS (tags ->> '$.kerb') VIRTUAL COMMENT '보도턱 (lowered, raised, flush 등)',
    PRIMARY KEY (id),
    KEY idx_road_node_highway (highway),
    SPATIAL INDEX sx_road_node_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '도로망 점 (OSM)';

-- 길: 두 점 사이의 한 구간. OSM 길(way)을 교차점과 표시가 있는 점에서 잘랐다. 걷는 방향은 양쪽 모두
CREATE TABLE IF NOT EXISTS road_edge (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    way_id    BIGINT       NOT NULL COMMENT 'OSM 길 번호',
    seq       INT          NOT NULL COMMENT '그 길 안에서 몇 번째 구간인지 (0부터)',
    from_node BIGINT       NOT NULL,
    to_node   BIGINT       NOT NULL,
    length_m  DOUBLE       NOT NULL,
    highway   VARCHAR(30)  NOT NULL COMMENT 'OSM 길 종류 (footway, residential, primary, steps 등)',
    name      VARCHAR(200) NULL,
    footway   VARCHAR(30)  NULL COMMENT 'sidewalk(차도 옆 인도), crossing(횡단 구간) 등',
    steps     TINYINT      NOT NULL DEFAULT 0 COMMENT '계단이면 1',
    covered   TINYINT      NOT NULL DEFAULT 0 COMMENT '덮인 길(covered·tunnel·indoor)이면 1',
    bridge    TINYINT      NOT NULL DEFAULT 0 COMMENT '다리·육교 위면 1',
    surface   VARCHAR(30)  NULL COMMENT '바닥 재질 (asphalt, paving_stones 등)',
    tags      JSON         NULL COMMENT '그 밖의 OSM 태그',
    geom      LINESTRING   NOT NULL SRID 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_road_edge_way_seq (way_id, seq),
    KEY idx_road_edge_from (from_node),
    KEY idx_road_edge_to (to_node),
    KEY idx_road_edge_highway (highway),
    SPATIAL INDEX sx_road_edge_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '도로망 길 구간 (OSM)';

-- ---------------------------------------------------------------- 행정구역 경계 (SGIS)

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

-- ---------------------------------------------------------------- 길별 감점 원값

CREATE TABLE IF NOT EXISTS edge_stat (
    edge_id        BIGINT      NOT NULL COMMENT 'road_edge.id',
    light_n        SMALLINT    NOT NULL COMMENT '길에서 30m 안 가로등·보안등 수 (같은 종류 10m 안 중복은 하나)',
    light_cover_m  DOUBLE      NOT NULL COMMENT '길이 중 등 30m 안에 드는 길이(m). 5m 간격 점으로 잼',
    cctv_n         SMALLINT    NOT NULL COMMENT '길에서 50m 안 CCTV 위치 수 (10m 안 중복은 하나, 교통·쓰레기 단속과 시설·재난 관리용은 뺌)',
    cctv_cover_m   DOUBLE      NOT NULL COMMENT '길이 중 CCTV 50m 안에 드는 길이(m)',
    help_m         DOUBLE      NULL COMMENT '길 가운데에서 가장 가까운 도움 지점(경찰·편의점·지킴이집·비상벨)까지 m. 2km 넘으면 NULL',
    help_night_m   DOUBLE      NULL COMMENT '가장 가까운 밤 도움 지점까지 m (24시간 경찰·길가 비상벨·편의점). 2km 넘으면 NULL',
    guard_m        DOUBLE      NULL COMMENT '가장 가까운 아동안전지킴이집까지 m. 2km 넘으면 NULL',
    rest_m         DOUBLE      NULL COMMENT '가장 가까운 쉼 곳(무더위·한파쉼터·공중화장실)까지 m. 2km 넘으면 NULL',
    heat_m         DOUBLE      NULL COMMENT '가장 가까운 무더위쉼터까지 m. 2km 넘으면 NULL',
    cold_m         DOUBLE      NULL COMMENT '가장 가까운 한파쉼터까지 m. 2km 넘으면 NULL',
    help_n_1km     SMALLINT    NOT NULL COMMENT '길 가운데 반경 1km 안 도움 지점 수 (지역 기준 A5)',
    help_night_n_1km SMALLINT  NOT NULL DEFAULT 0 COMMENT '반경 1km 안 밤 도움 지점 수',
    guard_n_1km    SMALLINT    NOT NULL COMMENT '반경 1km 안 지킴이집 수',
    rest_n_1km     SMALLINT    NOT NULL COMMENT '반경 1km 안 쉼 곳 수',
    heat_n_1km     SMALLINT    NOT NULL DEFAULT 0 COMMENT '반경 1km 안 무더위쉼터 수',
    cold_n_1km     SMALLINT    NOT NULL DEFAULT 0 COMMENT '반경 1km 안 한파쉼터 수',
    xing_signal    TINYINT     NOT NULL COMMENT '이 길에서 건너는 횡단보도 중 신호 있음',
    xing_nosignal  TINYINT     NOT NULL COMMENT '신호 없음',
    xing_unknown   TINYINT     NOT NULL COMMENT '신호 모름',
    curb           TINYINT     NULL COMMENT '건너는 횡단보도 중 가장 나쁜 보도턱: 1 안 낮춤, 0 낮춤, NULL 모름',
    footbridge_ok  TINYINT     NULL COMMENT '육교 위 길이면 휠체어 가능 1, 불가 0. 육교가 아니거나 모르면 NULL',
    road_class     VARCHAR(30) NULL COMMENT '큰 차도 판정용 길 종류. 차도는 자기 종류, 차도 옆 인도는 15m 안 가장 큰 차도 종류',
    sgg_cd         VARCHAR(10) NULL COMMENT '길 시작점이 있는 시군구 (SGIS 코드, admin_area)',
    dong_cd        VARCHAR(10) NULL COMMENT '길 시작점이 있는 행정동 (SGIS 코드)',
    data_gap       SMALLINT    NULL COMMENT '데이터 부족 비트 (1 밝기, 2 CCTV, 4 도움, 8 지킴이집, 16 쉼 곳, 32 횡단 신호, 64 보도턱). NULL = 아직 판정 안 함',
    computed_at    DATETIME    NOT NULL,
    school_zone_m  DOUBLE      NULL COMMENT '가장 가까운 어린이보호구역 대상 시설까지 m. 300m 안이면 보호구역 안. 2km 넘으면 NULL',
    PRIMARY KEY (edge_id),
    KEY idx_edge_stat_dong (dong_cd)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '길별 감점 원값';

-- ---------------------------------------------------------------- 시군구별 시설 밀도 (데이터 부족 판정)

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

-- ---------------------------------------------------------------- 정제 시설 (지도 표시용, scripts/build_poi.py)

-- 지도 표시·경로 응답용 정제 시설 표 (광주). 원본 시설 표 13개를 한 모양으로 모으고 중복을 합친다.
-- 원본 표는 그대로 두고, scripts/build_poi.py가 이 표를 지우고 다시 만든다.
-- kind: light 등, cctv, police 경찰, bell 비상벨, store 편의점, guard_house 아동안전지킴이집,
--       shelter 무더위·한파쉼터, toilet 공중화장실, footbridge 육교, crosswalk 횡단보도(표준데이터 + OSM), school_zone 어린이보호구역,
--       stairs 계단(OSM, 도로망 범위)
-- modes: 지도에 먼저 보일 모드 (FR-48, 결정 35). common = 모든 모드
-- 지금 이용할 수 있는지 (서버가 경로 위 화장실·쉼터에 붙임):
--   season_from이 있고 오늘(MM-DD)이 운영 기간 밖이면 '운영 기간 아님' (해를 넘기는 기간은 from > to)
--   지도에는 쉼터를 계절과 상관없이 모두 보여 주고, 길찾기 결과의 시설마다 아래 상태를 붙인다 (2026-11-02 결정)
--   계산은 아래 poi_status 뷰가 한다 (DB 시각 기준, 학교 DB는 한국 시각)
--   공휴일(holiday 표)은 주말처럼 본다 (015_holiday.sql).
--   경찰: 지구대·파출소·경찰서·스마트 안심부스는 24시간, 치안센터는 평일 09~18시. 비상벨: 길가는 24시간,
--   화장실 안 비상벨은 그 화장실이 열려 있을 때만. 아동안전지킴이집: 가게 종류별 일반 영업시간이고 시간 밖은 '확인 안 됨'.
--   무더위쉼터: 기간 안 일반 운영 시간 평일 09~18시, 원본에 야간 개방이면 21시까지, 주말 개방이면 매일.
--   행정복지센터·은행·도서관·복지관·보건소·지하철역 쉼터는 쉼터 기간이 아니어도 일반 업무 시간(biz_*)에는 들어가 쉴 수 있어
--   '이용 가능'으로 본다. 업무 시간은 기관마다 다를 수 있는 일반값이라 now_note에 그 근거를 적는다. 경로당은 회원 위주라 기간 밖이면 '운영 기간 아님'
--   open_from이 NULL이면 '확인 안 됨', open_days='평일'인데 주말이면 '확인 안 됨',
--   open_from <= open_to 이면 open_from <= 지금 < open_to, 아니면(밤을 넘김) 지금 >= open_from OR 지금 < open_to
-- 쉼터 운영 기간은 원본에 없어 정부 대책 기간(폭염 5.20~9.30, 한파 11.15~3.15)을 넣었다. 지자체마다 조금 다를 수 있음
-- 비상벨 indoor = 1은 화장실·건물 안 (참고용). 화장실 안 비상벨도 도움 지점이라 모든 모드에 표시한다
CREATE TABLE IF NOT EXISTS poi (
    id         INT          NOT NULL AUTO_INCREMENT,
    kind       VARCHAR(20)  NOT NULL COMMENT '앱 아이콘 종류 (위 목록)',
    sub        VARCHAR(50)  NULL     COMMENT '세부 종류 (파출소, 개방화장실, 무더위쉼터·한파쉼터, 승강기 등)',
    name       VARCHAR(200) NOT NULL COMMENT '화면에 보일 이름 (원본이 비면 종류 이름)',
    address    VARCHAR(300) NULL,
    lon        DOUBLE       NOT NULL,
    lat        DOUBLE       NOT NULL,
    geom       POINT        NOT NULL SRID 0,
    cnt        SMALLINT     NOT NULL DEFAULT 1 COMMENT '이 점에 합친 수 (CCTV 카메라 대수, 등 개수)',
    wheelchair TINYINT      NULL     COMMENT '휠체어: 육교 통과 1/0, 횡단보도 보도턱 낮춤 1/0, 화장실 장애인용 변기 1/0',
    open_hours VARCHAR(100) NULL     COMMENT '여는 시간 (화면 표시용 글자, 화장실·쉼터)',
    open_from  TIME         NULL     COMMENT '여는 시각. 24시간이면 00:00 (모르면 NULL)',
    open_to    TIME         NULL     COMMENT '닫는 시각. 24시간이면 24:00, 밤을 넘기면 open_from보다 작음',
    open_days  VARCHAR(10)  NULL     COMMENT '매일 / 평일 (평일이면 주말은 확인 안 됨)',
    season_from CHAR(5)     NULL     COMMENT '운영 기간 시작 MM-DD (무더위쉼터 05-20, 한파쉼터 11-15). NULL이면 늘',
    season_to   CHAR(5)     NULL     COMMENT '운영 기간 끝 MM-DD (무더위쉼터 09-30, 한파쉼터 03-15, 해를 넘길 수 있음)',
    indoor     TINYINT      NULL     COMMENT '1 = 화장실·건물 안이라 길에서 바로 못 씀 (비상벨), 0 = 길가·공원·주차장',
    place_type VARCHAR(20)  NULL     COMMENT '장소 종류: 쉼터(경로당·행정복지센터·은행·도서관 등), 지킴이집(편의점·문구점·약국 등), 경찰(치안센터 등)',
    biz_from   TIME         NULL     COMMENT '일반 업무·영업 시간 시작 (원본에 시간이 없어 종류별 일반값을 넣음)',
    biz_to     TIME         NULL     COMMENT '일반 업무 시간 끝',
    biz_days   VARCHAR(10)  NULL     COMMENT '매일 / 평일',
    biz_sure   TINYINT      NULL     COMMENT '1 = 기관이라 업무 시간 밖이면 닫힘, 0 = 가게라 시간 밖이면 닫혔을 수 있음(확인 안 됨)',
    phone      VARCHAR(30)  NULL,
    modes      SET('common', 'child', 'senior', 'wheelchair') NOT NULL DEFAULT '' COMMENT '지도에 먼저 보일 모드',
    detail     VARCHAR(300) NULL     COMMENT '앱에 바로 띄울 짧은 설명',
    sgg_cd     VARCHAR(10)  NULL     COMMENT '시군구 (admin_area SGIS 코드)',
    dong_cd    VARCHAR(10)  NULL     COMMENT '행정동 (admin_area SGIS 코드)',
    sources    JSON         NOT NULL COMMENT '합친 원본 ["표:id", ...] (최대 20개)',
    built_at   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_poi_kind (kind),
    KEY idx_poi_dong (dong_cd),
    SPATIAL INDEX sx_poi_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '정제 시설 (광주, 지도 표시용)';

-- ---------------------------------------------------------------- 공휴일

-- 공휴일 (관공서의 공휴일에 관한 규정 기준, 대체공휴일 포함). poi_status 뷰가 '평일' 규칙을 판단할 때 주말처럼 본다.
-- 직접 넣은 값이라 임시공휴일이 생기면 한 줄 더 넣는다. 5월 1일(근로자의 날·노동절)은 관공서 공휴일이 아니라 넣지 않음
-- (은행은 쉬는 곳이 많음). 2026-11-02 기준.
CREATE TABLE IF NOT EXISTS holiday (
    day  DATE        NOT NULL,
    name VARCHAR(30) NOT NULL,
    PRIMARY KEY (day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '공휴일';

INSERT IGNORE INTO holiday (day, name) VALUES
('2026-01-01', '신정'), ('2026-02-16', '설날 연휴'), ('2026-02-17', '설날'), ('2026-02-18', '설날 연휴'),
('2026-03-01', '삼일절'), ('2026-03-02', '삼일절 대체공휴일'), ('2026-05-05', '어린이날'),
('2026-05-24', '부처님오신날'), ('2026-05-25', '부처님오신날 대체공휴일'), ('2026-06-03', '전국동시지방선거'),
('2026-06-06', '현충일'), ('2026-08-15', '광복절'), ('2026-08-17', '광복절 대체공휴일'),
('2026-09-24', '추석 연휴'), ('2026-09-25', '추석'), ('2026-09-26', '추석 연휴'),
('2026-10-03', '개천절'), ('2026-10-05', '개천절 대체공휴일'), ('2026-10-09', '한글날'), ('2026-12-25', '성탄절'),
('2027-01-01', '신정'), ('2027-02-06', '설날 연휴'), ('2027-02-07', '설날'), ('2027-02-08', '설날 연휴'),
('2027-02-09', '설날 대체공휴일'), ('2027-03-01', '삼일절'), ('2027-05-05', '어린이날'), ('2027-05-13', '부처님오신날'),
('2027-06-06', '현충일'), ('2027-08-15', '광복절'), ('2027-08-16', '광복절 대체공휴일'),
('2027-09-14', '추석 연휴'), ('2027-09-15', '추석'), ('2027-09-16', '추석 연휴'),
('2027-10-03', '개천절'), ('2027-10-04', '개천절 대체공휴일'), ('2027-10-09', '한글날'), ('2027-10-11', '한글날 대체공휴일'),
('2027-12-25', '성탄절'), ('2027-12-27', '성탄절 대체공휴일');

-- 지금 이용할 수 있는지를 붙인 보기. 서버는 경로 주변 시설을 이 뷰에서 꺼내 now_status·now_note를 그대로 보여 준다.
-- now_status: 이용 가능 / 닫힘 / 운영 기간 아님 / 확인 안 됨. 시간과 상관없는 시설(CCTV·등·횡단보도·편의점 등)은 NULL
-- now_note: 무엇으로 판단했는지 (쉼터 운영 시간, 일반 업무 시간 기준, 24시간 등)
CREATE OR REPLACE VIEW poi_status AS
SELECT v.*,
       CASE
           WHEN v.open_from IS NULL AND v.biz_from IS NULL AND v.season_from IS NULL
                AND v.kind NOT IN ('toilet', 'shelter', 'bell') THEN NULL
           WHEN v.in_season AND v.open_from IS NOT NULL AND NOT (v.open_days = '평일' AND v.off_day)
               THEN IF(v.open_now, '이용 가능', '닫힘')
           WHEN v.biz_from IS NOT NULL AND NOT (v.biz_days = '평일' AND v.off_day) AND v.biz_now THEN '이용 가능'
           WHEN v.biz_from IS NOT NULL THEN IF(v.biz_sure = 1, '닫힘', '확인 안 됨')
           WHEN NOT v.in_season THEN '운영 기간 아님'
           ELSE '확인 안 됨'
       END AS now_status,
       CASE
           WHEN v.open_from IS NULL AND v.biz_from IS NULL AND v.season_from IS NULL
                AND v.kind NOT IN ('toilet', 'shelter', 'bell') THEN NULL
           WHEN v.in_season AND v.open_from IS NOT NULL AND NOT (v.open_days = '평일' AND v.off_day)
               THEN CONCAT(CASE v.kind WHEN 'shelter' THEN '쉼터 운영 시간 ' WHEN 'toilet' THEN '개방 시간 '
                                       WHEN 'bell' THEN '' ELSE '' END, v.open_hours)
           WHEN v.biz_from IS NOT NULL
               THEN CONCAT(IF(v.in_season, '', '쉼터 기간은 아니지만 '), v.place_type, ' 일반 ',
                           IF(v.biz_sure = 1, '업무', '영업'), ' 시간(', v.biz_days, ' ', TIME_FORMAT(v.biz_from, '%H:%i'),
                           '~', TIME_FORMAT(v.biz_to, '%H:%i'), ') 기준',
                           IF(v.biz_days = '평일' AND v.off_day, ', 오늘은 주말·공휴일', ''),
                           IF(v.biz_sure = 0 AND NOT v.biz_now, ', 그 밖의 시간엔 닫혔을 수 있음', ''))
           WHEN NOT v.in_season THEN CONCAT(v.sub, ' 운영 기간 아님 (', v.season_from, '~', v.season_to, ')')
           WHEN v.open_days = '평일' AND v.off_day THEN CONCAT('평일에만 여는 시간이 적혀 있음 (', v.open_hours, ')')
           ELSE '여는 시간 정보 없음'
       END AS now_note
FROM (
    SELECT p.*,
           (DAYOFWEEK(CURDATE()) IN (1, 7) OR EXISTS (SELECT 1 FROM holiday h WHERE h.day = CURDATE())) AS off_day,
           (p.season_from IS NULL
            OR (p.season_from <= p.season_to AND DATE_FORMAT(CURDATE(), '%m-%d') BETWEEN p.season_from AND p.season_to)
            OR (p.season_from > p.season_to AND (DATE_FORMAT(CURDATE(), '%m-%d') >= p.season_from
                                                OR DATE_FORMAT(CURDATE(), '%m-%d') <= p.season_to))) AS in_season,
           ((p.open_from <= p.open_to AND CURTIME() >= p.open_from AND CURTIME() < p.open_to)
            OR (p.open_from > p.open_to AND (CURTIME() >= p.open_from OR CURTIME() < p.open_to))) AS open_now,
           ((p.biz_from <= p.biz_to AND CURTIME() >= p.biz_from AND CURTIME() < p.biz_to)
            OR (p.biz_from > p.biz_to AND (CURTIME() >= p.biz_from OR CURTIME() < p.biz_to))) AS biz_now
    FROM poi p
) v;

-- ---------------------------------------------------------------- 길별 주변 시설 (scripts/build_edge_poi.py)

-- 길마다 주변 시설 (poi) 묶음. 경로 응답의 시설 목록, "80m 앞 편의점" 안내, "쉬는 곳이 300m마다" 설명을
-- 경로의 길 id만 모아 바로 만들 수 있게 한다. scripts/build_edge_poi.py가 만든다 (poi를 다시 만들면 id가 바뀌므로 이것도 다시 만듦).
-- 등(light)은 너무 많아 넣지 않고 edge_stat의 수로 본다.
CREATE TABLE IF NOT EXISTS edge_poi (
    edge_id BIGINT   NOT NULL COMMENT 'road_edge.id',
    poi_id  INT      NOT NULL COMMENT 'poi.id',
    kind    VARCHAR(20) NOT NULL COMMENT 'poi.kind (조회 편하게 같이 둠)',
    dist_m  DOUBLE   NOT NULL COMMENT '길에서 시설까지 가장 가까운 거리 m (50m 안)',
    pos     DOUBLE   NOT NULL COMMENT '길 위 위치: from_node 0 → to_node 1 (가장 가까운 점 기준)',
    PRIMARY KEY (edge_id, poi_id),
    KEY idx_edge_poi_poi (poi_id),
    KEY idx_edge_poi_kind (kind)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '길별 주변 시설';

-- ---------------------------------------------------------------- 위치를 못 찾아 뺀 시설

-- 위치를 믿을 수 없어 시설 표에서 뺀 줄을 지우지 않고 모아 두는 표.
-- 좌표가 한 점에 몰렸거나(지자체가 기관 좌표·기본값을 넣음) 좌표가 없었는데, 카카오 주소·장소 검색으로도 못 찾았거나
-- 번지 있는 주소가 없는 줄. 나중에 다른 자료·현장 확인으로 좌표를 찾으면 원래 표로 되돌린다.
-- 넣는 곳: fix_cctv_coords.py, fix_pile_coords.py, fill_missing_coords.py, load_toilet_std.py, restore_removed.py
CREATE TABLE IF NOT EXISTS facility_unlocated (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    tbl        VARCHAR(30)  NOT NULL COMMENT '원래 시설 표 이름 (security_light, cctv 등)',
    source     VARCHAR(20)  NOT NULL,
    source_id  VARCHAR(64)  NOT NULL COMMENT '원래 표의 source_id (원본 번호가 없으면 줄 해시)',
    kind       VARCHAR(50)  NULL,
    name       VARCHAR(200) NULL,
    address    VARCHAR(300) NULL,
    lon        DOUBLE       NULL     COMMENT '원본 좌표 (틀린 값 그대로, 없으면 NULL)',
    lat        DOUBLE       NULL,
    reason     VARCHAR(100) NOT NULL COMMENT '뺀 이유 (좌표 몰림·주소로 못 찾음, 좌표 없음·번지 없는 주소 등)',
    tried      VARCHAR(300) NULL     COMMENT '찾아본 검색어·힌트',
    attrs      JSON         NULL     COMMENT '원래 줄의 attrs 또는 원본 줄 전체',
    moved_at   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_unlocated (tbl, source, source_id),
    KEY idx_unlocated_reason (tbl, reason)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '위치를 못 찾아 시설 표에서 뺀 줄';


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
