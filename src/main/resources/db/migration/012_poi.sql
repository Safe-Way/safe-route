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
