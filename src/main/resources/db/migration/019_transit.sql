-- 광주 대중교통 자료 (FR-37 걷기+대중교통, 휠체어 모드). 결정 42: CSV로 먼저 받아 두고 DB에 접속되면 넣는다.
-- 넣기: python3 scripts/load_transit.py (data/raw/regional/의 CSV를 읽어 표마다 지우고 새로 넣음)
-- 버스 도착(광주버스정보 arriveInfo)은 저장하지 않고 요청 때 서버가 부른다.
-- 좌표는 다른 표처럼 POINT(경도, 위도), SRID 0.

-- 지하철역과 역별 장애인 편의시설 수 (광주교통공사 오픈API disabledFacilityInformation, 좌표는 OSM·카카오)
CREATE TABLE IF NOT EXISTS subway_station (
    station_id    INT          NOT NULL COMMENT '광주교통공사 역 번호 1 평동 ~ 20 녹동',
    name          VARCHAR(30)  NOT NULL,
    address       VARCHAR(100) NULL,
    lon           DOUBLE       NOT NULL,
    lat           DOUBLE       NOT NULL,
    geom          POINT        NOT NULL SRID 0,
    elevator_total                INT NOT NULL DEFAULT 0 COMMENT '엘리베이터 전체',
    elevator_available            INT NOT NULL DEFAULT 0 COMMENT '엘리베이터 이용 가능 (받은 때 기준)',
    elevator_disabled_gates       VARCHAR(50) NULL COMMENT '엘리베이터가 고장 난 출입구 번호 (쉼표)',
    escalator_total               INT NOT NULL DEFAULT 0,
    escalator_available           INT NOT NULL DEFAULT 0,
    escalator_disabled_gates      VARCHAR(50) NULL,
    wheelchair_lift_total         INT NOT NULL DEFAULT 0,
    wheelchair_lift_available     INT NOT NULL DEFAULT 0,
    wheelchair_lift_disabled_gates VARCHAR(50) NULL,
    accessible_toilet_total       INT NOT NULL DEFAULT 0 COMMENT '장애인 화장실',
    accessible_toilet_available   INT NOT NULL DEFAULT 0,
    tactile_map_total             INT NOT NULL DEFAULT 0 COMMENT '점자 안내도',
    tactile_map_available         INT NOT NULL DEFAULT 0,
    wheelchair_rental_total       INT NOT NULL DEFAULT 0 COMMENT '휠체어 대여',
    wheelchair_rental_available   INT NOT NULL DEFAULT 0,
    voice_guide_total             INT NOT NULL DEFAULT 0 COMMENT '음성 유도기',
    voice_guide_available         INT NOT NULL DEFAULT 0,
    wide_gate_total               INT NOT NULL DEFAULT 0 COMMENT '넓은 개찰구',
    wide_gate_available           INT NOT NULL DEFAULT 0,
    wheelchair_charger_total      INT NOT NULL DEFAULT 0 COMMENT '전동휠체어 충전기',
    wheelchair_charger_available  INT NOT NULL DEFAULT 0,
    osm_id        BIGINT       NULL,
    coord_from    VARCHAR(50)  NULL COMMENT '좌표 출처 (OSM 또는 카카오 장소 이름)',
    fetched_at    DATE         NOT NULL,
    PRIMARY KEY (station_id),
    SPATIAL INDEX idx_subway_station_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '광주 지하철역과 장애인 편의시설 수';

-- 역 출입구 (OSM railway=subway_entrance). 400m 안 가장 가까운 역에 붙임
CREATE TABLE IF NOT EXISTS subway_entrance (
    osm_id            BIGINT      NOT NULL,
    station_id        INT         NULL COMMENT '400m 안에 역이 없으면 NULL',
    exit_no           VARCHAR(10) NULL COMMENT 'OSM ref (출구 번호)',
    name              VARCHAR(100) NULL,
    wheelchair        VARCHAR(10) NULL COMMENT 'OSM wheelchair 표시 (대부분 비어 있음)',
    lon               DOUBLE      NOT NULL,
    lat               DOUBLE      NOT NULL,
    geom              POINT       NOT NULL SRID 0,
    dist_to_station_m INT         NULL,
    fetched_at        DATE        NOT NULL,
    PRIMARY KEY (osm_id),
    KEY idx_subway_entrance_station (station_id),
    SPATIAL INDEX idx_subway_entrance_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '광주 지하철 출입구';

-- 열차 도착 시각 (공공데이터포털 15111497). 실시간 도착 API가 없어 다음 열차를 이 표로 계산
CREATE TABLE IF NOT EXISTS subway_timetable (
    id               INT         NOT NULL AUTO_INCREMENT,
    station_code     INT         NOT NULL COMMENT '역사 코드 (녹동 100 ~ 평동 119). 원본에 한 칸 밀린 줄이 있어 역 이름으로 다시 붙임',
    station_name     VARCHAR(30) NOT NULL,
    day_type         VARCHAR(5)  NOT NULL COMMENT '평일·토요일·휴일·명절',
    direction        TINYINT     NOT NULL COMMENT '1 평동행, 2 소태·녹동행',
    end_station_code INT         NOT NULL,
    end_station_name VARCHAR(30) NOT NULL,
    arrive_time      TIME        NOT NULL,
    line             TINYINT     NOT NULL DEFAULT 1,
    base_date        DATE        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_subway_timetable_next (station_code, day_type, direction, arrive_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '광주 지하철 열차 시간표';

-- 역사별 환승 (공공데이터포털 15111491, 2022-12-30): 내리는 칸 → 출구 → 버스정류장
CREATE TABLE IF NOT EXISTS subway_transfer (
    id                 INT         NOT NULL AUTO_INCREMENT,
    station_code       INT         NOT NULL COMMENT '내리는 역 코드',
    station_name       VARCHAR(30) NOT NULL,
    prev_station_code  INT         NULL COMMENT '이전 역 코드 (오는 방향)',
    prev_station_name  VARCHAR(30) NULL,
    alight_pos         VARCHAR(10) NULL COMMENT '내리는 위치 (칸-문, 예 1-2)',
    exit_no            VARCHAR(10) NULL,
    alight_to_exit_s   INT         NULL COMMENT '내리는 곳 → 출구 초',
    exit_to_stop_s     INT         NULL COMMENT '출구 → 정류장 초',
    transfer_s         INT         NULL COMMENT '환승 전체 초',
    busstop_no         VARCHAR(10) NULL COMMENT '정류장 번호',
    bus_route          VARCHAR(50) NULL COMMENT '노선 (원본 "역_정류장_노선"의 마지막 부분)',
    line               TINYINT     NOT NULL DEFAULT 1,
    base_date          DATE        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_subway_transfer_station (station_code, prev_station_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '광주 지하철 역사별 환승';

-- 역사별 출구 연계 버스 (공공데이터포털 15046048, 2021-03-09)
CREATE TABLE IF NOT EXISTS subway_exit_bus (
    id           INT         NOT NULL AUTO_INCREMENT,
    station_name VARCHAR(30) NOT NULL,
    exit_no      VARCHAR(10) NULL,
    bus_route    VARCHAR(50) NOT NULL,
    destination  VARCHAR(100) NULL COMMENT '버스별 행선지',
    base_date    DATE        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_subway_exit_bus_station (station_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '광주 지하철 출구별 연계 버스';

-- 역사 출구별 주변 장소 (공공데이터포털 15046045, 2021-03-09)
CREATE TABLE IF NOT EXISTS subway_exit_place (
    id           INT          NOT NULL AUTO_INCREMENT,
    station_name VARCHAR(30)  NOT NULL,
    place_name   VARCHAR(100) NOT NULL,
    exit_no      VARCHAR(10)  NULL,
    distance_m   INT          NULL,
    walk_min     INT          NULL COMMENT '걸어서 걸리는 분',
    base_date    DATE         NOT NULL,
    PRIMARY KEY (id),
    KEY idx_subway_exit_place_station (station_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '광주 지하철 출구별 주변 장소';

-- 버스 정류장 (광주버스정보 API stationInfo). 좌표 없는 정류장은 넣지 않음 (data_source.skipped_count)
CREATE TABLE IF NOT EXISTS bus_stop (
    busstop_id   INT          NOT NULL COMMENT '도착 API의 BUSSTOP_ID',
    name         VARCHAR(50)  NOT NULL,
    name_en      VARCHAR(100) NULL,
    ars_id       VARCHAR(10)  NULL COMMENT '정류장 표지판 번호',
    next_busstop VARCHAR(50)  NULL COMMENT '다음 정류장 이름 (방향 구분용)',
    lon          DOUBLE       NOT NULL,
    lat          DOUBLE       NOT NULL,
    geom         POINT        NOT NULL SRID 0,
    fetched_at   DATE         NOT NULL,
    PRIMARY KEY (busstop_id),
    SPATIAL INDEX idx_bus_stop_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '광주 버스 정류장';

-- 버스 노선 (광주버스정보 API lineInfo)
CREATE TABLE IF NOT EXISTS bus_line (
    line_id       INT         NOT NULL,
    line_name     VARCHAR(30) NOT NULL COMMENT '도착 API의 LINE_NAME과 같은 이름 (예 01)',
    dir_up_name   VARCHAR(50) NULL,
    dir_down_name VARCHAR(50) NULL,
    first_run     TIME        NULL,
    last_run      TIME        NULL,
    run_interval  INT         NULL COMMENT '배차 간격 분',
    line_kind     TINYINT     NULL COMMENT '원본 LINE_KIND (1~8)',
    fetched_at    DATE        NOT NULL,
    PRIMARY KEY (line_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '광주 버스 노선';

-- 차량별 저상 여부 (공공데이터포털 전남광주통합특별시 저상버스 운행 현황, 2025-12-31)
-- 노선 이름이 '일곡28' 꼴이라 도착 API(LINE_NAME '05')와 바로 맞지 않음. 다음 버스가 저상인지는 도착 API의 LOW_BUS로 판단
CREATE TABLE IF NOT EXISTS lowfloor_bus (
    vehicle_no VARCHAR(20) NOT NULL COMMENT '차량 번호',
    low_floor  TINYINT     NOT NULL COMMENT '1 저상',
    company    VARCHAR(30) NULL,
    route_name VARCHAR(30) NULL,
    base_date  DATE        NOT NULL,
    PRIMARY KEY (vehicle_no),
    KEY idx_lowfloor_bus_route (route_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '광주 저상버스';
