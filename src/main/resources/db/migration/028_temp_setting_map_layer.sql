-- 말로 바꾸는 설정과 기간 (2026-11-06, 비서 '기억하기'·쉬운 사용).
--   예) "다리를 다쳐서 휠체어 타고 다녀" → 비서가 "언제까지 휠체어를 타세요?" 되묻고 그날까지만 휠체어 모드
--       "장애인 화장실이랑 보도턱 낮춘 횡단보도 보여 줘, 이번 달까지" → 그 시설을 지도에 더함(기간 끝나면 원래대로)
--       "여기 사고 났어", "공사 중이야" → 말로 불편 신고 (road_report, 사진 없이)
-- 칸 추가·새 표만 만들므로 돌고 있는 서버에 영향이 없다.
SET NAMES utf8mb4;

-- ① 잠깐 바뀐 몸 상태: 기간 동안만 평소 모드 대신 씀
ALTER TABLE user_profile
    ADD COLUMN temp_mode   VARCHAR(20) NULL COMMENT '잠깐 쓰는 모드 (예 다리를 다쳐 wheelchair). temp_until까지만' AFTER home_dong,
    ADD COLUMN temp_aid    VARCHAR(20) NULL COMMENT '잠깐 쓰는 이동 보조 (mobility_aid와 같은 값)' AFTER temp_mode,
    ADD COLUMN temp_reason VARCHAR(50) NULL COMMENT '이유 한마디 (다리 다침, 수술 뒤 회복 등)' AFTER temp_aid,
    ADD COLUMN temp_until  DATE        NULL COMMENT '이 날짜까지(그날 포함). 모르면 비서가 되묻고, 끝까지 모르면 7일 뒤로 두고 그때 다시 물음' AFTER temp_reason,
    ADD COLUMN next_ask_on DATE        NULL COMMENT '비서가 먼저 다시 물어볼 날 (임시 모드가 끝나는 날 "다리는 괜찮아지셨어요?", 설정을 모르는 칸이 남았을 때 등)' AFTER temp_until,
    ADD COLUMN setup_done  TINYINT     NOT NULL DEFAULT 0 COMMENT '1이면 처음 묻고 답하는 설정을 마침 (0이면 비서가 첫 질문부터)' AFTER next_ask_on;

-- 지금 쓸 모드·이동 보조: 기간 안이면 잠깐 바뀐 값, 지나면 평소 값 (학교 DB는 한국 시각)
CREATE OR REPLACE VIEW user_profile_now AS
SELECT guest_id,
       IF(temp_mode IS NOT NULL AND temp_until >= CURDATE(), temp_mode, mode) AS mode_now,
       IF(temp_aid IS NOT NULL AND temp_until >= CURDATE(), temp_aid, mobility_aid) AS aid_now,
       (temp_mode IS NOT NULL OR temp_aid IS NOT NULL) AND temp_until >= CURDATE() AS temp_active,
       temp_reason, temp_until, mode, mobility_aid, stairs_ok, max_detour, rest_every_m, prefer_transit,
       setup_done, (next_ask_on IS NOT NULL AND next_ask_on <= CURDATE()) OR setup_done = 0 AS ask_today
FROM user_profile;

-- ② 지도 시설 사전: 비서가 "장애인 화장실", "엘리베이터 육교" 같은 말을 지도 시설(poi)에 맞추는 표
CREATE TABLE IF NOT EXISTS map_layer (
    layer      VARCHAR(40)  NOT NULL COMMENT '시설 묶음 이름 (종류:조건). 앱·서버가 이 이름으로 주고받음',
    name       VARCHAR(50)  NOT NULL COMMENT '화면·비서가 쓰는 이름',
    aliases    VARCHAR(200) NULL     COMMENT '같은 뜻으로 알아들을 말 (쉼표)',
    kind       VARCHAR(20)  NOT NULL COMMENT 'poi.kind',
    poi_filter VARCHAR(200) NOT NULL COMMENT 'poi에서 고르는 조건 (WHERE 뒤에 붙일 SQL, 서버만 씀)',
    note       VARCHAR(100) NULL,
    PRIMARY KEY (layer)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '지도 시설 사전 (말 → 시설 묶음)';

INSERT INTO map_layer (layer, name, aliases, kind, poi_filter, note) VALUES
('toilet', '화장실', '공중화장실,개방화장실', 'toilet', "kind = 'toilet'", NULL),
('toilet:wheelchair', '장애인 화장실', '휠체어 화장실,장애인용 화장실,장애인 화장실', 'toilet', "kind = 'toilet' AND wheelchair = 1", '휠체어로 쓸 수 있는 칸이 있는 곳'),
('crosswalk', '횡단보도', '건널목', 'crosswalk', "kind = 'crosswalk'", NULL),
('crosswalk:curb_low', '보도턱 낮춘 횡단보도', '턱 없는 횡단보도,보도턱 낮은 횡단보도,경사로 횡단보도', 'crosswalk', "kind = 'crosswalk' AND wheelchair = 1", '횡단보도 표준데이터의 보도턱 낮춤'),
('footbridge', '육교', '보행육교', 'footbridge', "kind = 'footbridge'", NULL),
('footbridge:wheelchair', '휠체어로 지나는 육교', '경사로 육교,휠체어 육교', 'footbridge', "kind = 'footbridge' AND wheelchair = 1", '경사로나 승강기가 있음'),
('footbridge:elevator', '엘리베이터 육교', '승강기 육교,엘베 육교', 'footbridge', "kind = 'footbridge' AND sub LIKE '%승강기%'", NULL),
('subway_entrance:elevator', '엘리베이터 있는 지하철 출구', '엘베 출구,엘리베이터 출구', 'subway_entrance', "kind = 'subway_entrance' AND wheelchair = 1", '출구 쪽 지상 엘리베이터 (결정 51)'),
('stairs', '계단', '계단 있는 곳', 'stairs', "kind = 'stairs'", '피할 곳으로 보여 줌'),
('shelter', '쉼터', '무더위쉼터,한파쉼터,쉴 곳', 'shelter', "kind = 'shelter'", NULL),
('bell', '비상벨', '안전비상벨', 'bell', "kind = 'bell'", NULL),
('police', '경찰서·파출소', '경찰,파출소,지구대', 'police', "kind = 'police'", NULL),
('store', '편의점', NULL, 'store', "kind = 'store'", NULL),
('guard_house', '아동안전지킴이집', '지킴이집', 'guard_house', "kind = 'guard_house'", NULL),
('cctv', 'CCTV', '씨씨티비,방범카메라', 'cctv', "kind = 'cctv'", NULL),
('school_zone', '어린이보호구역', '스쿨존', 'school_zone', "kind = 'school_zone'", NULL)
ON DUPLICATE KEY UPDATE name = VALUES(name), aliases = VALUES(aliases), kind = VALUES(kind),
    poi_filter = VALUES(poi_filter), note = VALUES(note);

-- ③ 내 지도 설정: 모드 기본에 더하거나 뺀 시설 묶음과 기간
CREATE TABLE IF NOT EXISTS user_map_layer (
    guest_id   CHAR(36)    NOT NULL,
    layer      VARCHAR(40) NOT NULL COMMENT 'map_layer.layer',
    shown      TINYINT     NOT NULL COMMENT '1 더해서 보임, 0 빼서 안 보임',
    until      DATE        NULL     COMMENT '이 날짜까지(그날 포함). NULL이면 바꿀 때까지',
    reason     VARCHAR(50) NULL     COMMENT '이유 한마디 (다리 다침 등)',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (guest_id, layer),
    CONSTRAINT fk_user_map_layer_profile FOREIGN KEY (guest_id) REFERENCES user_profile (guest_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '내 지도 시설 설정 (기간 있음)';

-- 지금 적용되는 내 지도 설정 (기간이 지난 것은 빠짐)
CREATE OR REPLACE VIEW user_map_layer_now AS
SELECT u.guest_id, u.layer, l.name, u.shown, u.until, u.reason
FROM user_map_layer u JOIN map_layer l ON l.layer = u.layer
WHERE u.until IS NULL OR u.until >= CURDATE();

-- ④ 말로 하는 불편 신고: 사고도 받고, 사진 신고와 말 신고를 구분
ALTER TABLE road_report
    MODIFY COLUMN report_type VARCHAR(20) NOT NULL COMMENT '유형: steep 급경사, construction 공사, curb 턱, broken 보도 파손, obstacle 막는 물건, accident 사고, other 기타',
    ADD COLUMN via VARCHAR(10) NOT NULL DEFAULT 'photo' COMMENT 'photo 화면에서 사진과 함께, voice 비서에게 말로 (사진 없음)' AFTER memo;
