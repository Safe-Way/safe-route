-- 비서가 기억하는 내 정보와 이동 기록 (2026-11-06, 교수님 피드백 "휴대폰이 개인화 장치, 쓴 기록이 다음 추천에 쓰여야 한다").
-- 비서가 하는 일 5가지 중 '기억하기'의 재료다. 로그인 없이 앱이 처음 실행 때 만든 게스트 번호(UUID)로 구분한다
-- (walk_profile·walk_speed_sample과 같은 번호).
--
-- 개인정보 규칙 (NFR-07을 바꾸는 안, 팀 확정 필요)
--   - 이동 기록은 사용자가 동의(consent_trips = 1)했을 때만 서버가 받는다. 동의하지 않으면 지금처럼 휴대폰에만 둔다.
--   - 출발지는 행정동 코드만 남긴다(좌표 없음). 도착지는 이름과 약 100m 단위로 반올림한 좌표만 남긴다(소수 셋째 자리).
--   - 시각은 날짜·요일·30분 단위 시간대만 남긴다(몇 시 몇 분 몇 초는 없음). 평소 습관을 알기에는 충분하다.
--   - 게스트마다 최근 200건까지만 두고, 그보다 오래된 것은 서버가 지운다.
--   - "내 기록 지우기"는 user_profile 줄을 지운다 → 이동 기록도 같이 지워진다. 걸음 속도(walk_profile)도 서버가 함께 지운다.
--   - 보호자 실시간 위치 공유는 여기에 두지 않는다(걷는 동안만, 도착하면 지움, 따로 만든다).
-- 칸 추가·새 표만 만들므로 돌고 있는 서버에 영향이 없다.
SET NAMES utf8mb4;

-- 내 정보: 비서가 매번 묻지 않아도 되는 것. 걸음 속도는 walk_profile(025)에 모드마다 따로 있다
CREATE TABLE IF NOT EXISTS user_profile (
    guest_id        CHAR(36)     NOT NULL COMMENT '앱이 처음 실행 때 만든 무작위 번호(UUID). walk_profile과 같은 번호',
    mode            VARCHAR(20)  NULL     COMMENT '평소 모드: adult, child, senior, wheelchair',
    mobility_aid    VARCHAR(20)  NULL     COMMENT '이동 보조: manual_wheelchair 수동 휠체어, power_wheelchair 전동, walker 보행기, cane 지팡이, stroller 유모차',
    stairs_ok       TINYINT      NULL     COMMENT '계단: 0 못 감, 1 몇 칸은 괜찮음, 2 괜찮음. NULL이면 모드 기준',
    max_detour      DECIMAL(3,2) NULL     COMMENT '받아들이는 우회 (최단의 몇 배까지, 예 1.20). NULL이면 모드 기준',
    rest_every_m    SMALLINT     NULL     COMMENT '쉬어야 하는 간격(m). 노인 모드의 쉴 곳 거리 대신 씀. NULL이면 모드 기준',
    prefer_transit  TINYINT      NULL     COMMENT '대중교통을 좋아하는지: 0 걷기 좋아함, 1 보통, 2 대중교통 좋아함. 기록으로 비서가 바꿀 수 있음',
    home_dong       VARCHAR(10)  NULL     COMMENT '사는 동네 (admin_area 행정동 코드). 정확한 집 위치는 휴대폰에만',
    consent_trips   TINYINT      NOT NULL DEFAULT 0 COMMENT '1이면 이동 기록을 서버에 남기는 데 동의함',
    consent_at      DATETIME     NULL     COMMENT '동의한 시각',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (guest_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '비서가 기억하는 내 정보 (게스트별)';

-- 이동 기록: 한 번 길을 찾아 출발한 것이 한 줄. 도착하거나 그만두면 결과를 채운다
CREATE TABLE IF NOT EXISTS trip_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    guest_id        CHAR(36)     NOT NULL,
    started_on      DATE         NOT NULL COMMENT '출발한 날짜',
    weekday         TINYINT      NOT NULL COMMENT '요일 1 월 ~ 7 일',
    start_slot      TIME         NOT NULL COMMENT '출발 시간대 (30분 단위로 내림, 예 08:00, 08:30)',
    from_dong       VARCHAR(10)  NULL     COMMENT '출발한 행정동 코드 (좌표는 남기지 않음)',
    to_name         VARCHAR(100) NOT NULL COMMENT '도착지 이름 (예 문화전당역, 집, 학교)',
    to_lat          DECIMAL(7,3) NOT NULL COMMENT '도착지 위도 (약 100m 단위로 반올림)',
    to_lon          DECIMAL(8,3) NOT NULL COMMENT '도착지 경도 (약 100m 단위로 반올림)',
    mode            VARCHAR(20)  NOT NULL COMMENT '그때 모드',
    travel          VARCHAR(10)  NOT NULL COMMENT 'walk 걸어서, transit 대중교통',
    route_kind      VARCHAR(20)  NULL     COMMENT '고른 길: safe 맞춤, shortest 최단, bus, subway, bus_subway',
    by_assistant    TINYINT      NOT NULL DEFAULT 0 COMMENT '1이면 비서가 추천한 길로 출발',
    weather         VARCHAR(30)  NULL     COMMENT '출발 때 날씨 표시 (쉼표: rain, heat, cold, dust). 없으면 맑음',
    night           TINYINT      NOT NULL DEFAULT 0 COMMENT '1이면 밤 기준',
    body_note       VARCHAR(30)  NULL     COMMENT '그날 몸 상태 (사용자가 말한 것을 짧게: 다리 아픔, 피곤함 등)',
    planned_min     SMALLINT     NULL     COMMENT '예상 시간(분)',
    actual_min      SMALLINT     NULL     COMMENT '실제 걸린 시간(분). 도착 못 하면 NULL',
    result          VARCHAR(10)  NOT NULL DEFAULT 'started' COMMENT 'started 가는 중, arrived 도착, stopped 그만둠',
    feedback        VARCHAR(10)  NULL     COMMENT '도착 뒤 한마디: good 좋음, bad 불편',
    feedback_reason VARCHAR(30)  NULL     COMMENT '불편한 이유: 턱, 계단, 어두움, 붐빔, 멀었음, 비 맞음 등',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_trip_log_guest (guest_id, started_on),
    CONSTRAINT fk_trip_log_profile FOREIGN KEY (guest_id) REFERENCES user_profile (guest_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '이동 기록 (동의한 게스트만, 최근 200건)';

-- 비서가 쓰는 습관 요약 ①: 같은 요일·시간대에 같은 곳을 몇 번 갔나 (3번 이상이면 "이 시간에 ○○ 가세요?" 먼저 제안)
CREATE OR REPLACE VIEW trip_habit AS
SELECT guest_id, weekday, start_slot, to_name, to_lat, to_lon,
       COUNT(*) AS times, MAX(started_on) AS last_on,
       SUM(travel = 'transit') AS by_transit, SUM(travel = 'walk') AS by_walk
FROM trip_log
WHERE result <> 'stopped'
GROUP BY guest_id, weekday, start_slot, to_name, to_lat, to_lon;

-- 비서가 쓰는 습관 요약 ②: 날씨에 따라 무엇을 골랐나 ("지난번 비 올 때도 지하철을 타셨어요")
CREATE OR REPLACE VIEW trip_weather_choice AS
SELECT guest_id,
       CASE WHEN weather LIKE '%rain%' THEN 'rain' WHEN weather LIKE '%heat%' THEN 'heat'
            WHEN weather LIKE '%cold%' THEN 'cold' WHEN weather LIKE '%dust%' THEN 'dust' ELSE 'clear' END AS weather,
       COUNT(*) AS trips, SUM(travel = 'transit') AS by_transit, SUM(travel = 'walk') AS by_walk,
       SUM(feedback = 'bad') AS bad_feedback, MAX(started_on) AS last_on
FROM trip_log
WHERE result <> 'stopped'
GROUP BY guest_id, 2;
