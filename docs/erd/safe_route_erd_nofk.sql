-- 세잎지도 학교 DB ERD용 DDL (2026-11-03, 학교 DB information_schema에서 생성)
-- Crow's Foot ERD 도구(ERDCloud·drawSQL·dbdiagram.io SQL 가져오기·MySQL Workbench 등)에 붙여 넣는 용도.
-- 실제 DB 그대로: 외래 키 없음. 관계선이 필요하면 safe_route_erd.sql(그림용 외래 키 6개)을 쓴다.
-- 가상 칸(VIRTUAL)·공간 인덱스·기본값은 뺐다. 표 정의의 원본은 src/main/resources/db/schema.sql.
--
-- 외래 키로 못 그린 관계:
--   시설 원본 13개 표 → poi : poi.sources(JSON)에 "표:id"로 합침 (외래 키로 못 그림)
--   시설 원본 표 → facility_unlocated : tbl·source·source_id로 원래 표를 가리킴
--   edge_stat.sgg_cd·dong_cd, region_density.sgg_cd → admin_area.code (level과 함께라야 유일)
--   subway_timetable·subway_transfer.station_code(100~119) ↔ subway_station.name (역 번호 1~20과 다름)
--   subway_exit_bus·subway_exit_place.station_name ↔ subway_station.name
--   subway_transfer.busstop_no ↔ bus_stop (정류장 번호)
--   lowfloor_bus.route_name ↔ bus_line.line_name (이름 꼴이 달라 바로 안 맞음)
--   poi_status 뷰 = poi + holiday로 지금 상태 계산, facility_all 뷰 = 시설 표 10개를 이어 붙임

CREATE TABLE admin_area (
  id bigint NOT NULL,
  level varchar(10) NOT NULL COMMENT 'sido, sigungu, dong',
  code varchar(10) NOT NULL COMMENT 'SGIS 행정구역 코드 (시도 2자리, 시군구 5자리, 행정동 8자리)',
  name varchar(50) NOT NULL,
  full_name varchar(100) COMMENT '예: 광주광역시 동구 충장동',
  base_date char(8) NOT NULL COMMENT '경계 기준일 (예: 20250630)',
  geom multipolygon NOT NULL COMMENT '경도·위도 (WGS84로 바꾼 값)',
  PRIMARY KEY (id)
) COMMENT = '행정구역 경계 (SGIS)';

CREATE TABLE bus_line (
  line_id int NOT NULL,
  line_name varchar(30) NOT NULL COMMENT '도착 API의 LINE_NAME과 같은 이름 (예 01)',
  dir_up_name varchar(50),
  dir_down_name varchar(50),
  first_run time,
  last_run time,
  run_interval int COMMENT '배차 간격 분',
  line_kind tinyint COMMENT '원본 LINE_KIND (1~8)',
  fetched_at date NOT NULL,
  PRIMARY KEY (line_id)
) COMMENT = '광주 버스 노선';

CREATE TABLE bus_stop (
  busstop_id int NOT NULL COMMENT '도착 API의 BUSSTOP_ID',
  name varchar(50) NOT NULL,
  name_en varchar(100),
  ars_id varchar(10) COMMENT '정류장 표지판 번호',
  next_busstop varchar(50) COMMENT '다음 정류장 이름 (방향 구분용)',
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  fetched_at date NOT NULL,
  PRIMARY KEY (busstop_id)
) COMMENT = '광주 버스 정류장';

CREATE TABLE cctv (
  id bigint NOT NULL,
  kind varchar(50) COMMENT '설치 목적 (생활방범, 어린이보호, 교통단속 등)',
  source varchar(20) NOT NULL,
  source_id varchar(64) NOT NULL,
  name varchar(200) COMMENT '관리 기관',
  address varchar(300),
  sido_code char(2),
  sgg_code char(5),
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  attrs json,
  camera_count smallint NOT NULL COMMENT '카메라 대수 (없으면 1)',
  pixels varchar(20) COMMENT '(가상 칸) 화소 (attrs에서 꺼낸 값)',
  direction varchar(100) COMMENT '(가상 칸) 촬영 방면 (attrs에서 꺼낸 값)',
  retention_days varchar(20) COMMENT '(가상 칸) 보관 일수 (attrs에서 꺼낸 값)',
  installed varchar(20) COMMENT '(가상 칸) 설치 시기 (attrs에서 꺼낸 값)',
  manager_phone varchar(30) COMMENT '(가상 칸) 관리 기관 전화 (attrs에서 꺼낸 값)',
  purpose_group varchar(10) NOT NULL COMMENT '설치 목적 분류 (위 목록)',
  purpose_from varchar(20) COMMENT '분류 근거: 원본 / 10m 안 행안부 파일 / 직접 입력',
  PRIMARY KEY (id)
) COMMENT = 'CCTV';

CREATE TABLE child_protection_zone (
  id bigint NOT NULL,
  kind varchar(50) COMMENT '시설 종류 (초등학교, 유치원, 어린이집 등)',
  source varchar(20) NOT NULL,
  source_id varchar(64) NOT NULL,
  name varchar(200) COMMENT '대상 시설 이름',
  address varchar(300),
  sido_code char(2),
  sgg_code char(5),
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  attrs json,
  road_width varchar(20) COMMENT '(가상 칸) 보호구역 도로 폭 m (원문, 예: 8, 5~12)',
  cctv_count varchar(10) COMMENT '(가상 칸) CCTV 대수 (대부분 비어 있음)',
  designated char(1) COMMENT '(가상 칸) 지정 여부 Y/N (대부분 비어 있음)',
  police varchar(50) COMMENT '(가상 칸) 관할 경찰서',
  PRIMARY KEY (id)
) COMMENT = '어린이보호구역 (대상 시설 위치)';

CREATE TABLE child_safety_house (
  id bigint NOT NULL,
  kind varchar(50),
  source varchar(20) NOT NULL,
  source_id varchar(64) NOT NULL,
  name varchar(200),
  address varchar(300),
  sido_code char(2),
  sgg_code char(5),
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  attrs json,
  phone varchar(30) COMMENT '(가상 칸) 전화번호 (attrs에서 꺼낸 값)',
  PRIMARY KEY (id)
) COMMENT = '아동안전지킴이집';

CREATE TABLE cold_shelter (
  id bigint NOT NULL,
  kind varchar(50) COMMENT '특정계층이용시설, 공공시설 등',
  source varchar(20) NOT NULL,
  source_id varchar(64) NOT NULL,
  name varchar(200),
  address varchar(300),
  sido_code char(2),
  sgg_code char(5),
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  attrs json,
  capacity varchar(10) COMMENT '(가상 칸) 이용 가능 인원 (attrs에서 꺼낸 값)',
  weekday_hours varchar(20) COMMENT '(가상 칸) 평일 운영 시간, 예: 0900~1800 (attrs에서 꺼낸 값)',
  saturday_hours varchar(20) COMMENT '(가상 칸) 토요일 운영 시간 (attrs에서 꺼낸 값)',
  sunday_hours varchar(20) COMMENT '(가상 칸) 일요일 운영 시간 (attrs에서 꺼낸 값)',
  holiday_hours varchar(20) COMMENT '(가상 칸) 공휴일 운영 시간 (attrs에서 꺼낸 값)',
  note varchar(500) COMMENT '(가상 칸) 비고: 특보 시 연장 운영 등 (attrs에서 꺼낸 값)',
  PRIMARY KEY (id)
) COMMENT = '한파쉼터';

CREATE TABLE convenience_store (
  id bigint NOT NULL,
  kind varchar(50),
  source varchar(20) NOT NULL,
  source_id varchar(64) NOT NULL,
  name varchar(200),
  address varchar(300),
  sido_code char(2),
  sgg_code char(5),
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  attrs json,
  PRIMARY KEY (id)
) COMMENT = '편의점';

CREATE TABLE crosswalk (
  id bigint NOT NULL,
  kind varchar(50) COMMENT '횡단보도 종류 코드 (01, 02, 03, 04, 99)',
  source varchar(20) NOT NULL,
  source_id varchar(64) NOT NULL,
  name varchar(200) COMMENT '도로명',
  address varchar(300),
  sido_code char(2),
  sgg_code char(5),
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  attrs json,
  curb_lowered tinyint COMMENT '(가상 칸) 보도턱 낮춤 1, 아님 0, 모름 NULL',
  braille_block tinyint COMMENT '(가상 칸) 점자블록 있음 1, 없음 0, 모름 NULL',
  sound_signal tinyint COMMENT '(가상 칸) 음향신호기 있음 1, 없음 0, 모름 NULL',
  traffic_light tinyint COMMENT '(가상 칸) 보행자 신호등 있음 1, 없음 0, 모름 NULL',
  raised tinyint COMMENT '(가상 칸) 고원식(턱 없이 높인 횡단보도) 1, 아님 0',
  spot_light tinyint COMMENT '(가상 칸) 집중조명 있음 1, 없음 0, 모름 NULL',
  green_seconds varchar(10) COMMENT '(가상 칸) 보행 신호 시간(초)',
  lanes varchar(10) COMMENT '(가상 칸) 차로 수',
  length_m varchar(10) COMMENT '(가상 칸) 횡단 길이 m',
  manager varchar(100) COMMENT '(가상 칸) 관리 기관',
  PRIMARY KEY (id)
) COMMENT = '횡단보도';

CREATE TABLE data_source (
  type varchar(30) NOT NULL,
  source varchar(20) NOT NULL,
  title varchar(100) NOT NULL,
  api varchar(200) NOT NULL COMMENT 'API 주소 또는 파일 이름',
  license varchar(100),
  total_count int COMMENT '출처가 알려 준 전체 건수',
  loaded_count int NOT NULL COMMENT 'DB에 들어간 건수',
  skipped_count int NOT NULL COMMENT '좌표가 없거나 이상해서 뺀 건수',
  complete tinyint(1) NOT NULL COMMENT '전체를 다 받았는지',
  loaded_at datetime,
  PRIMARY KEY (type, source)
) COMMENT = '데이터 출처별 적재 현황';

CREATE TABLE emergency_bell (
  id bigint NOT NULL,
  kind varchar(50) COMMENT '설치 장소 (가로변, 공원, 화장실, 주차장 등)',
  source varchar(20) NOT NULL,
  source_id varchar(64) NOT NULL,
  name varchar(200),
  address varchar(300),
  sido_code char(2),
  sgg_code char(5),
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  attrs json,
  purpose varchar(50) COMMENT '(가상 칸) 설치 목적 (attrs에서 꺼낸 값)',
  link_type varchar(30) COMMENT '(가상 칸) 연결 방식 (attrs에서 꺼낸 값)',
  police_linked varchar(1) COMMENT '(가상 칸) 경찰 연계 Y/N (attrs에서 꺼낸 값)',
  manager varchar(100) COMMENT '(가상 칸) 관리 기관 (attrs에서 꺼낸 값)',
  manager_phone varchar(30) COMMENT '(가상 칸) 관리 기관 전화 (attrs에서 꺼낸 값)',
  install_year varchar(10) COMMENT '(가상 칸) 설치 연도 (attrs에서 꺼낸 값)',
  last_inspected varchar(10) COMMENT '(가상 칸) 마지막 점검일 (attrs에서 꺼낸 값)',
  PRIMARY KEY (id)
) COMMENT = '안전 비상벨';

CREATE TABLE facility_unlocated (
  id bigint NOT NULL,
  tbl varchar(30) NOT NULL COMMENT '원래 시설 표 이름 (security_light, cctv 등)',
  source varchar(20) NOT NULL,
  source_id varchar(64) NOT NULL COMMENT '원래 표의 source_id (원본 번호가 없으면 줄 해시)',
  kind varchar(50),
  name varchar(200),
  address varchar(300),
  lon double COMMENT '원본 좌표 (틀린 값 그대로, 없으면 NULL)',
  lat double,
  reason varchar(100) NOT NULL COMMENT '뺀 이유 (좌표 몰림·주소로 못 찾음, 좌표 없음·번지 없는 주소 등)',
  tried varchar(300) COMMENT '찾아본 검색어·힌트',
  attrs json COMMENT '원래 줄의 attrs 또는 원본 줄 전체',
  moved_at datetime NOT NULL,
  PRIMARY KEY (id)
) COMMENT = '위치를 못 찾아 시설 표에서 뺀 줄';

CREATE TABLE footbridge (
  id bigint NOT NULL,
  kind varchar(50) COMMENT '장애인 편의시설 종류 (없음, 경사로, 엘리베이터 등)',
  source varchar(20) NOT NULL,
  source_id varchar(64) NOT NULL,
  name varchar(200),
  address varchar(300),
  sido_code char(2),
  sgg_code char(5),
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  attrs json,
  wheelchair_ok tinyint COMMENT '(가상 칸) 휠체어 통과 가능 1, 불가 0 (kind와 기타 시설에서 판단)',
  length_m varchar(10) COMMENT '(가상 칸) 육교 길이 m (attrs에서 꺼낸 값)',
  width_m varchar(10) COMMENT '(가상 칸) 육교 폭 m (attrs에서 꺼낸 값)',
  safe_grade varchar(10) COMMENT '(가상 칸) 안전 등급 (attrs에서 꺼낸 값)',
  use_limit varchar(20) COMMENT '(가상 칸) 이용 제한 (attrs에서 꺼낸 값)',
  manager varchar(100) COMMENT '(가상 칸) 관리 기관 (attrs에서 꺼낸 값)',
  PRIMARY KEY (id)
) COMMENT = '육교';

CREATE TABLE heat_shelter (
  id bigint NOT NULL,
  kind varchar(50) COMMENT '특정계층이용시설, 공공시설, 야외시설 등',
  source varchar(20) NOT NULL,
  source_id varchar(64) NOT NULL,
  name varchar(200),
  address varchar(300),
  sido_code char(2),
  sgg_code char(5),
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  attrs json,
  capacity varchar(10) COMMENT '(가상 칸) 이용 가능 인원 (attrs에서 꺼낸 값)',
  area_m2 varchar(10) COMMENT '(가상 칸) 면적 (attrs에서 꺼낸 값)',
  night_open varchar(1) COMMENT '(가상 칸) 야간 개방 Y/N (attrs에서 꺼낸 값)',
  weekend_open varchar(1) COMMENT '(가상 칸) 주말 개방 Y/N (attrs에서 꺼낸 값)',
  PRIMARY KEY (id)
) COMMENT = '무더위쉼터';

CREATE TABLE holiday (
  day date NOT NULL,
  name varchar(30) NOT NULL,
  PRIMARY KEY (day)
) COMMENT = '공휴일';

CREATE TABLE lowfloor_bus (
  vehicle_no varchar(20) NOT NULL COMMENT '차량 번호',
  low_floor tinyint NOT NULL COMMENT '1 저상',
  company varchar(30),
  route_name varchar(30),
  base_date date NOT NULL,
  PRIMARY KEY (vehicle_no)
) COMMENT = '광주 저상버스';

CREATE TABLE poi (
  id int NOT NULL,
  kind varchar(20) NOT NULL COMMENT '앱 아이콘 종류 (위 목록)',
  sub varchar(50) COMMENT '세부 종류 (파출소, 개방화장실, 무더위쉼터·한파쉼터, 승강기 등)',
  name varchar(200) NOT NULL COMMENT '화면에 보일 이름 (원본이 비면 종류 이름)',
  address varchar(300),
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  cnt smallint NOT NULL COMMENT '이 점에 합친 수 (CCTV 카메라 대수, 등 개수)',
  wheelchair tinyint COMMENT '휠체어: 육교 통과 1/0, 횡단보도 보도턱 낮춤 1/0, 화장실 장애인용 변기 1/0',
  open_hours varchar(100) COMMENT '여는 시간 (화면 표시용 글자, 화장실·쉼터)',
  open_from time COMMENT '여는 시각. 24시간이면 00:00 (모르면 NULL)',
  open_to time COMMENT '닫는 시각. 24시간이면 24:00, 밤을 넘기면 open_from보다 작음',
  open_days varchar(10) COMMENT '매일 / 평일 (평일이면 주말은 확인 안 됨)',
  season_from char(5) COMMENT '운영 기간 시작 MM-DD (무더위쉼터 05-20, 한파쉼터 11-15). NULL이면 늘',
  season_to char(5) COMMENT '운영 기간 끝 MM-DD (무더위쉼터 09-30, 한파쉼터 03-15, 해를 넘길 수 있음)',
  indoor tinyint COMMENT '1 = 화장실·건물 안이라 길에서 바로 못 씀 (비상벨), 0 = 길가·공원·주차장',
  place_type varchar(20) COMMENT '장소 종류: 쉼터(경로당·행정복지센터·은행·도서관 등), 지킴이집(편의점·문구점·약국 등), 경찰(치안센터 등)',
  biz_from time COMMENT '일반 업무·영업 시간 시작 (원본에 시간이 없어 종류별 일반값을 넣음)',
  biz_to time COMMENT '일반 업무 시간 끝',
  biz_days varchar(10) COMMENT '매일 / 평일',
  biz_sure tinyint COMMENT '1 = 기관이라 업무 시간 밖이면 닫힘, 0 = 가게라 시간 밖이면 닫혔을 수 있음(확인 안 됨)',
  phone varchar(30),
  modes set('common','child','senior','wheelchair') NOT NULL COMMENT '지도에 먼저 보일 모드',
  detail varchar(300) COMMENT '앱에 바로 띄울 짧은 설명',
  sgg_cd varchar(10) COMMENT '시군구 (admin_area SGIS 코드)',
  dong_cd varchar(10) COMMENT '행정동 (admin_area SGIS 코드)',
  sources json NOT NULL COMMENT '합친 원본 ["표:id", ...] (최대 20개)',
  built_at datetime NOT NULL,
  PRIMARY KEY (id)
) COMMENT = '정제 시설 (광주, 지도 표시용)';

CREATE TABLE police (
  id bigint NOT NULL,
  kind varchar(50) COMMENT '경찰서, 지구대, 파출소, 치안센터, 스마트 안심부스 등',
  source varchar(20) NOT NULL,
  source_id varchar(64) NOT NULL,
  name varchar(200),
  address varchar(300),
  sido_code char(2),
  sgg_code char(5),
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  attrs json,
  phone varchar(30) COMMENT '(가상 칸) 전화번호 (attrs에서 꺼낸 값)',
  PRIMARY KEY (id)
) COMMENT = '경찰 시설';

CREATE TABLE public_toilet (
  id bigint NOT NULL,
  kind varchar(50) COMMENT '공중화장실, 개방화장실, 간이화장실, 이동화장실',
  source varchar(20) NOT NULL,
  source_id varchar(64) NOT NULL,
  name varchar(200),
  address varchar(300),
  sido_code char(2),
  sgg_code char(5),
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  attrs json,
  phone varchar(30) COMMENT '(가상 칸) 전화번호 (attrs에서 꺼낸 값)',
  open_hours varchar(100) COMMENT '(가상 칸) 개방 시간 (attrs에서 꺼낸 값)',
  PRIMARY KEY (id)
) COMMENT = '공중화장실';

CREATE TABLE region_density (
  sgg_cd varchar(10) NOT NULL COMMENT 'SGIS 시군구 코드 (admin_area.code)',
  kind varchar(10) NOT NULL COMMENT 'light, cctv, help, guard, rest (data_gap 비트 1, 2, 4, 8, 16)',
  n int NOT NULL COMMENT '시군구 경계 안 시설 수',
  area_km2 double NOT NULL COMMENT '시군구 넓이(km², 위도 보정 근사)',
  density double NOT NULL COMMENT 'n ÷ area_km2',
  ratio double COMMENT 'density ÷ 전국 시군구 중간값',
  gap tinyint NOT NULL COMMENT 'ratio가 0.1 아래면 1 (데이터 부족)',
  computed_at datetime NOT NULL,
  PRIMARY KEY (sgg_cd, kind)
) COMMENT = '시군구별 시설 밀도와 데이터 부족 판정';

CREATE TABLE road_node (
  id bigint NOT NULL COMMENT 'OSM 노드 번호',
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  tags json COMMENT 'OSM 태그 (표시가 있는 점만)',
  highway varchar(30) COMMENT '(가상 칸) crossing, traffic_signals, elevator 등',
  crossing varchar(30) COMMENT '(가상 칸) traffic_signals, uncontrolled, marked, unmarked 등. NULL = 모름',
  kerb varchar(20) COMMENT '(가상 칸) 보도턱 (lowered, raised, flush 등)',
  PRIMARY KEY (id)
) COMMENT = '도로망 점 (OSM)';

CREATE TABLE security_light (
  id bigint NOT NULL,
  kind varchar(50) COMMENT '방범등, 보안등, 한전주, 전용주 등',
  source varchar(20) NOT NULL,
  source_id varchar(64) NOT NULL,
  name varchar(200),
  address varchar(300),
  sido_code char(2),
  sgg_code char(5),
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  attrs json,
  lamp_count smallint NOT NULL COMMENT '등 개수 (없으면 1)',
  install_year varchar(10) COMMENT '(가상 칸) 설치 연도 (attrs에서 꺼낸 값)',
  manager varchar(100) COMMENT '(가상 칸) 관리 기관 (attrs에서 꺼낸 값)',
  manager_phone varchar(30) COMMENT '(가상 칸) 관리 기관 전화 (attrs에서 꺼낸 값)',
  PRIMARY KEY (id)
) COMMENT = '보안등';

CREATE TABLE streetlight (
  id bigint NOT NULL,
  kind varchar(50),
  source varchar(20) NOT NULL,
  source_id varchar(64) NOT NULL,
  name varchar(200),
  address varchar(300),
  sido_code char(2),
  sgg_code char(5),
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  attrs json,
  PRIMARY KEY (id)
) COMMENT = '가로등';

CREATE TABLE subway_exit_bus (
  id int NOT NULL,
  station_name varchar(30) NOT NULL,
  exit_no varchar(10),
  bus_route varchar(50) NOT NULL,
  destination varchar(100) COMMENT '버스별 행선지',
  base_date date NOT NULL,
  PRIMARY KEY (id)
) COMMENT = '광주 지하철 출구별 연계 버스';

CREATE TABLE subway_exit_place (
  id int NOT NULL,
  station_name varchar(30) NOT NULL,
  place_name varchar(100) NOT NULL,
  exit_no varchar(10),
  distance_m int,
  walk_min int COMMENT '걸어서 걸리는 분',
  base_date date NOT NULL,
  PRIMARY KEY (id)
) COMMENT = '광주 지하철 출구별 주변 장소';

CREATE TABLE subway_station (
  station_id int NOT NULL COMMENT '광주교통공사 역 번호 1 평동 ~ 20 녹동',
  name varchar(30) NOT NULL,
  address varchar(100),
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  elevator_total int NOT NULL COMMENT '엘리베이터 전체',
  elevator_available int NOT NULL COMMENT '엘리베이터 이용 가능 (받은 때 기준)',
  elevator_disabled_gates varchar(50) COMMENT '엘리베이터가 고장 난 출입구 번호 (쉼표)',
  escalator_total int NOT NULL,
  escalator_available int NOT NULL,
  escalator_disabled_gates varchar(50),
  wheelchair_lift_total int NOT NULL,
  wheelchair_lift_available int NOT NULL,
  wheelchair_lift_disabled_gates varchar(50),
  accessible_toilet_total int NOT NULL COMMENT '장애인 화장실',
  accessible_toilet_available int NOT NULL,
  tactile_map_total int NOT NULL COMMENT '점자 안내도',
  tactile_map_available int NOT NULL,
  wheelchair_rental_total int NOT NULL COMMENT '휠체어 대여',
  wheelchair_rental_available int NOT NULL,
  voice_guide_total int NOT NULL COMMENT '음성 유도기',
  voice_guide_available int NOT NULL,
  wide_gate_total int NOT NULL COMMENT '넓은 개찰구',
  wide_gate_available int NOT NULL,
  wheelchair_charger_total int NOT NULL COMMENT '전동휠체어 충전기',
  wheelchair_charger_available int NOT NULL,
  osm_id bigint,
  coord_from varchar(50) COMMENT '좌표 출처 (OSM 또는 카카오 장소 이름)',
  fetched_at date NOT NULL,
  PRIMARY KEY (station_id)
) COMMENT = '광주 지하철역과 장애인 편의시설 수';

CREATE TABLE subway_timetable (
  id int NOT NULL,
  station_code int NOT NULL COMMENT '역사 코드 (녹동 100 ~ 평동 119). 원본에 한 칸 밀린 줄이 있어 역 이름으로 다시 붙임',
  station_name varchar(30) NOT NULL,
  day_type varchar(5) NOT NULL COMMENT '평일·토요일·휴일·명절',
  direction tinyint NOT NULL COMMENT '1 평동행, 2 소태·녹동행',
  end_station_code int NOT NULL,
  end_station_name varchar(30) NOT NULL,
  arrive_time time NOT NULL,
  line tinyint NOT NULL,
  base_date date NOT NULL,
  PRIMARY KEY (id)
) COMMENT = '광주 지하철 열차 시간표';

CREATE TABLE subway_transfer (
  id int NOT NULL,
  station_code int NOT NULL COMMENT '내리는 역 코드',
  station_name varchar(30) NOT NULL,
  prev_station_code int COMMENT '이전 역 코드 (오는 방향)',
  prev_station_name varchar(30),
  alight_pos varchar(10) COMMENT '내리는 위치 (칸-문, 예 1-2)',
  exit_no varchar(10),
  alight_to_exit_s int COMMENT '내리는 곳 → 출구 초',
  exit_to_stop_s int COMMENT '출구 → 정류장 초',
  transfer_s int COMMENT '환승 전체 초',
  busstop_no varchar(10) COMMENT '정류장 번호',
  bus_route varchar(50) COMMENT '노선 (원본 "역_정류장_노선"의 마지막 부분)',
  line tinyint NOT NULL,
  base_date date NOT NULL,
  PRIMARY KEY (id)
) COMMENT = '광주 지하철 역사별 환승';

CREATE TABLE road_edge (
  id bigint NOT NULL,
  way_id bigint NOT NULL COMMENT 'OSM 길 번호',
  seq int NOT NULL COMMENT '그 길 안에서 몇 번째 구간인지 (0부터)',
  from_node bigint NOT NULL,
  to_node bigint NOT NULL,
  length_m double NOT NULL,
  highway varchar(30) NOT NULL COMMENT 'OSM 길 종류 (footway, residential, primary, steps 등)',
  name varchar(200),
  footway varchar(30) COMMENT 'sidewalk(차도 옆 인도), crossing(횡단 구간) 등',
  steps tinyint NOT NULL COMMENT '계단이면 1',
  covered tinyint NOT NULL COMMENT '덮인 길(covered·tunnel·indoor)이면 1',
  bridge tinyint NOT NULL COMMENT '다리·육교 위면 1',
  surface varchar(30) COMMENT '바닥 재질 (asphalt, paving_stones 등)',
  tags json COMMENT '그 밖의 OSM 태그',
  geom linestring NOT NULL,
  wheelchair_ramp tinyint COMMENT '(가상 칸) 계단의 휠체어 경사로: 1 있음, 0 없음, NULL 모름 (OSM ramp:wheelchair·ramp)',
  PRIMARY KEY (id)
) COMMENT = '도로망 길 구간 (OSM)';

CREATE TABLE edge_stat (
  edge_id bigint NOT NULL COMMENT 'road_edge.id',
  light_n smallint NOT NULL COMMENT '길에서 30m 안 가로등·보안등 수 (같은 종류 10m 안 중복은 하나)',
  light_cover_m double NOT NULL COMMENT '길이 중 등 30m 안에 드는 길이(m). 5m 간격 점으로 잼',
  cctv_n smallint NOT NULL COMMENT '길에서 50m 안 CCTV 위치 수 (10m 안 중복은 하나)',
  cctv_cover_m double NOT NULL COMMENT '길이 중 CCTV 50m 안에 드는 길이(m)',
  help_m double COMMENT '길 가운데에서 가장 가까운 도움 지점(경찰·편의점·지킴이집·비상벨)까지 m. 2km 넘으면 NULL',
  help_night_m double COMMENT '가장 가까운 밤 도움 지점까지 m (24시간 경찰·길가 비상벨·편의점). 2km 넘으면 NULL',
  guard_m double COMMENT '가장 가까운 아동안전지킴이집까지 m. 2km 넘으면 NULL',
  rest_m double COMMENT '가장 가까운 쉼 곳(무더위·한파쉼터·공중화장실)까지 m. 2km 넘으면 NULL',
  heat_m double COMMENT '가장 가까운 무더위쉼터까지 m. 2km 넘으면 NULL',
  cold_m double COMMENT '가장 가까운 한파쉼터까지 m. 2km 넘으면 NULL',
  help_n_1km smallint NOT NULL COMMENT '길 가운데 반경 1km 안 도움 지점 수 (지역 기준 A5)',
  help_night_n_1km smallint NOT NULL COMMENT '반경 1km 안 밤 도움 지점 수',
  guard_n_1km smallint NOT NULL COMMENT '반경 1km 안 지킴이집 수',
  rest_n_1km smallint NOT NULL COMMENT '반경 1km 안 쉼 곳 수',
  heat_n_1km smallint NOT NULL COMMENT '반경 1km 안 무더위쉼터 수',
  cold_n_1km smallint NOT NULL COMMENT '반경 1km 안 한파쉼터 수',
  xing_signal tinyint NOT NULL COMMENT '이 길에서 건너는 횡단보도 중 신호 있음',
  xing_nosignal tinyint NOT NULL COMMENT '신호 없음',
  xing_unknown tinyint NOT NULL COMMENT '신호 모름',
  curb tinyint COMMENT '건너는 횡단보도 중 가장 나쁜 보도턱: 1 안 낮춤, 0 낮춤, NULL 모름',
  footbridge_ok tinyint COMMENT '육교 위 길이면 휠체어 가능 1, 불가 0. 육교가 아니거나 모르면 NULL',
  road_class varchar(30) COMMENT '큰 차도 판정용 길 종류. 차도는 자기 종류, 차도 옆 인도는 15m 안 가장 큰 차도 종류',
  sgg_cd varchar(10) COMMENT '길 시작점이 있는 시군구 (SGIS 코드, admin_area)',
  dong_cd varchar(10) COMMENT '길 시작점이 있는 행정동 (SGIS 코드)',
  data_gap smallint COMMENT '데이터 부족 비트 (1 밝기, 2 CCTV, 4 도움, 8 지킴이집, 16 쉼 곳, 32 횡단 신호, 64 보도턱). NULL = 아직 판정 안 함',
  computed_at datetime NOT NULL,
  school_zone_m double COMMENT '가장 가까운 어린이보호구역 대상 시설까지 m. 300m 안이면 보호구역 안. 2km 넘으면 NULL',
  PRIMARY KEY (edge_id)
) COMMENT = '길별 감점 원값';

CREATE TABLE edge_poi (
  edge_id bigint NOT NULL COMMENT 'road_edge.id',
  poi_id int NOT NULL COMMENT 'poi.id',
  kind varchar(20) NOT NULL COMMENT 'poi.kind (조회 편하게 같이 둠)',
  dist_m double NOT NULL COMMENT '길에서 시설까지 가장 가까운 거리 m (50m 안)',
  pos double NOT NULL COMMENT '길 위 위치: from_node 0 → to_node 1 (가장 가까운 점 기준)',
  PRIMARY KEY (edge_id, poi_id)
) COMMENT = '길별 주변 시설';

CREATE TABLE subway_entrance (
  osm_id bigint NOT NULL,
  station_id int COMMENT '400m 안에 역이 없으면 NULL',
  exit_no varchar(10) COMMENT 'OSM ref (출구 번호)',
  name varchar(100),
  wheelchair varchar(10) COMMENT 'OSM wheelchair 표시 (대부분 비어 있음)',
  lon double NOT NULL,
  lat double NOT NULL,
  geom point NOT NULL,
  dist_to_station_m int,
  fetched_at date NOT NULL,
  PRIMARY KEY (osm_id)
) COMMENT = '광주 지하철 출입구';
