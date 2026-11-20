"""schema.sql → 전체 ERD 그림 (docs/erd/safe_route_full_erd.png). 칸마다 논리명(한글 뜻)·물리명(DB 칸 이름)·자료형.

  .venv/bin/python docs/erd/full_erd.py            # 기본: src/main/resources/db/schema.sql → docs/erd/safe_route_full_erd.png
  .venv/bin/python docs/erd/full_erd.py <schema.sql> <out.png>

표를 더하면 COLS(어느 열에 둘지)·GROUP(색)·DESC(표 논리명)에 넣고, 이어지는 칸은 REL에, 새 칸의 논리명은 D(또는 표마다 다르면 O)에 넣는다.
PIL 과 macOS 글꼴(AppleSDGothicNeo)이 필요하다.
"""
import json, re, sys
from PIL import Image, ImageDraw, ImageFont

SRC = sys.argv[1] if len(sys.argv) > 1 else 'src/main/resources/db/schema.sql'
OUT = sys.argv[2] if len(sys.argv) > 2 else 'docs/erd/safe_route_full_erd.png'


def read_schema(path):
    src = open(path, encoding='utf-8').read()
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    tables = {}
    for m in re.finditer(r'CREATE TABLE (?:IF NOT EXISTS )?`?(\w+)`?\s*\((.*?)\)\s*(?:ENGINE|COMMENT|DEFAULT|;)', src, re.S | re.I):
        name, body = m.group(1), m.group(2)
        lines = [l.strip() for l in body.split('\n')]
        lines = [re.sub(r'\s*--.*$', '', l) for l in lines]
        cols, pk = [], []
        for l in lines:
            if not l: continue
            mm = re.match(r'PRIMARY KEY\s*\(([^)]*)\)', l, re.I)
            if mm: pk += [c.strip(' `') for c in mm.group(1).split(',')]; continue
            if re.match(r'(UNIQUE|KEY|INDEX|SPATIAL|FULLTEXT|CONSTRAINT|FOREIGN|CHECK)\b', l, re.I): continue
            mm = re.match(r'`?(\w+)`?\s+([A-Za-z]+(?:\([^)]*\))?)', l)
            if not mm: continue
            c, t = mm.group(1), mm.group(2)
            cm = re.search(r"COMMENT\s+'((?:[^']|'')*)'", l)
            if 'PRIMARY KEY' in l.upper(): pk.append(c)
            cols.append([c, t.lower(), (cm.group(1) if cm else '')])
        tables[name] = {'cols': cols, 'pk': pk}
    return tables


# 칸 논리명(한글). D = 칸 이름 기본값, O = 표마다 뜻이 다른 칸
D = {
 'id':'번호','kind':'세부 종류','source':'출처','source_id':'원본 번호','name':'이름','address':'주소',
 'sido_code':'시도 코드','sgg_code':'시군구 코드','lon':'경도','lat':'위도','geom':'위치(공간)','attrs':'원본 나머지 값',
 'lamp_count':'등 개수','install_year':'설치 연도','manager':'관리 기관','manager_phone':'관리 기관 전화',
 'camera_count':'카메라 대수','pixels':'화소','direction':'촬영 방향','retention_days':'보관 일수','installed':'설치 시기',
 'purpose_group':'설치 목적 분류','purpose_from':'목적 분류 근거','phone':'전화번호','purpose':'설치 목적',
 'link_type':'연결 방식','police_linked':'경찰 연계 여부','last_inspected':'마지막 점검일','capacity':'수용 인원',
 'area_m2':'면적(㎡)','night_open':'야간 개방','weekend_open':'주말 개방','weekday_hours':'평일 운영 시간',
 'saturday_hours':'토요일 운영 시간','sunday_hours':'일요일 운영 시간','holiday_hours':'공휴일 운영 시간',
 'note':'비고','open_hours':'개방 시간','wheelchair_ok':'휠체어 통과 가능','length_m':'길이(m)','width_m':'폭(m)',
 'safe_grade':'안전 등급','use_limit':'이용 제한','curb_lowered':'보도턱 낮춤','braille_block':'점자블록',
 'sound_signal':'음향신호기','traffic_light':'보행 신호등','raised':'고원식(높인) 횡단보도','spot_light':'집중조명',
 'green_seconds':'보행 신호 시간(초)','lanes':'차로 수','road_width':'도로 폭','cctv_count':'CCTV 대수',
 'designated':'지정 여부','police':'관할 경찰서','tags':'OSM 태그','highway':'길 종류(OSM)','crossing':'횡단 종류',
 'kerb':'보도턱 종류','way_id':'OSM 길 번호','seq':'구간 순서','from_node':'시작 점','to_node':'끝 점',
 'footway':'보행로 종류','steps':'계단 여부','covered':'지붕·지하 여부','bridge':'다리·육교 위 여부','surface':'바닥 재질',
 'wheelchair_ramp':'계단 휠체어 경사로','level':'단계(시도·시군구·동)','code':'행정구역 코드','full_name':'전체 이름',
 'base_date':'기준일','edge_id':'길 구간 번호','light_n':'가로등·보안등 수','light_cover_m':'불빛 닿는 길이(m)',
 'cctv_n':'CCTV 수','cctv_cover_m':'CCTV 닿는 길이(m)','help_m':'도움 지점까지(m)','help_night_m':'밤 도움 지점까지(m)',
 'guard_m':'지킴이집까지(m)','rest_m':'쉴 곳까지(m)','heat_m':'무더위쉼터까지(m)','cold_m':'한파쉼터까지(m)',
 'help_n_1km':'1km 안 도움 지점 수','help_night_n_1km':'1km 안 밤 도움 지점 수','guard_n_1km':'1km 안 지킴이집 수',
 'rest_n_1km':'1km 안 쉴 곳 수','heat_n_1km':'1km 안 무더위쉼터 수','cold_n_1km':'1km 안 한파쉼터 수',
 'xing_signal':'신호 있는 횡단 수','xing_nosignal':'신호 없는 횡단 수','xing_unknown':'신호 모르는 횡단 수',
 'curb':'건너는 곳 보도턱','footbridge_ok':'육교 휠체어 가능','road_class':'큰 차도 판정 종류','sgg_cd':'시군구 코드',
 'dong_cd':'행정동 코드','data_gap':'데이터 부족 표시','computed_at':'계산한 시각','school_zone_m':'어린이보호구역까지(m)',
 'reason':'이유','excluded_at':'뺀 시각','n':'시설 수','area_km2':'넓이(㎢)','density':'밀도','ratio':'전국 대비 비율',
 'gap':'데이터 부족 여부','sub':'더 작은 종류','cnt':'합친 개수','wheelchair':'휠체어 이용','open_from':'여는 시각',
 'open_to':'닫는 시각','open_days':'여는 날','season_from':'운영 시작일','season_to':'운영 끝일','indoor':'건물 안 여부',
 'place_type':'장소 종류','biz_from':'업무 시작 시각','biz_to':'업무 끝 시각','biz_days':'업무 요일',
 'biz_sure':'업무 시간 확실 여부','modes':'먼저 보일 모드','detail':'짧은 설명','sources':'합친 원본 목록',
 'built_at':'만든 시각','day':'날짜','poi_id':'시설 번호','dist_m':'길에서 거리(m)','pos':'길 위 위치(0~1)',
 'tbl':'원래 시설 표','tried':'찾아본 검색어','moved_at':'옮긴 시각','station_id':'역 번호',
 'elevator_total':'엘리베이터 수','elevator_available':'엘리베이터 이용 가능','elevator_disabled_gates':'엘리베이터 고장 출구',
 'escalator_total':'에스컬레이터 수','escalator_available':'에스컬레이터 이용 가능','escalator_disabled_gates':'에스컬레이터 고장 출구',
 'wheelchair_lift_total':'휠체어 리프트 수','wheelchair_lift_available':'휠체어 리프트 이용 가능',
 'wheelchair_lift_disabled_gates':'휠체어 리프트 고장 출구','accessible_toilet_total':'장애인 화장실 수',
 'accessible_toilet_available':'장애인 화장실 이용 가능','tactile_map_total':'점자 안내도 수',
 'tactile_map_available':'점자 안내도 이용 가능','wheelchair_rental_total':'휠체어 대여 수',
 'wheelchair_rental_available':'휠체어 대여 가능','voice_guide_total':'음성 유도기 수','voice_guide_available':'음성 유도기 이용 가능',
 'wide_gate_total':'넓은 개찰구 수','wide_gate_available':'넓은 개찰구 이용 가능','wheelchair_charger_total':'전동휠체어 충전기 수',
 'wheelchair_charger_available':'전동휠체어 충전기 이용 가능','osm_id':'OSM 번호','coord_from':'좌표 출처',
 'fetched_at':'받은 시각','exit_no':'출구 번호','elevator':'출구 엘리베이터','elevator_source':'엘리베이터 근거',
 'dist_to_station_m':'역까지 거리(m)','station_code':'역 코드','station_name':'역 이름','day_type':'요일 종류',
 'end_station_code':'종착역 코드','end_station_name':'종착역 이름','arrive_time':'도착 시각','line':'노선',
 'prev_station_code':'이전 역 코드','prev_station_name':'이전 역 이름','alight_pos':'내리는 위치(칸-문)',
 'alight_to_exit_s':'내려서 출구까지(초)','exit_to_stop_s':'출구에서 정류장까지(초)','transfer_s':'환승 전체(초)',
 'busstop_no':'정류장 번호','bus_route':'버스 노선','destination':'행선지','place_name':'장소 이름','distance_m':'거리(m)',
 'walk_min':'걸어서(분)','busstop_id':'정류장 번호','name_en':'영문 이름','ars_id':'정류장 표지판 번호',
 'next_busstop':'다음 정류장','line_id':'노선 번호','line_name':'노선 이름','dir_up_name':'상행 방향','dir_down_name':'하행 방향',
 'first_run':'첫차','last_run':'막차','run_interval':'배차 간격(분)','line_kind':'노선 종류','vehicle_no':'차량 번호',
 'low_floor':'저상버스 여부','company':'운수회사','route_name':'노선 이름','report_type':'신고 종류','mode':'모드',
 'memo':'한 줄 설명','via':'신고 방법(사진·말)','accuracy_m':'GPS 정확도(m)','status':'상태',
 'reporter_hash':'신고자 구분값(HMAC)','photo_label':'사진 판단 결과','created_at':'만든 시각','report_id':'신고 번호',
 'content_type':'파일 형식','size_bytes':'파일 크기','data':'사진 원본','guest_id':'게스트 번호(UUID)',
 'speed_m_min':'걸음 속도(분당 m)','samples':'측정 수','walked_m':'잰 거리 합(m)','moving_s':'움직인 시간(초)',
 'updated_at':'고친 시각','measured_on':'잰 날짜','title':'제목','place':'장소 설명','radius_m':'영향 반경(m)',
 'starts_at':'시작 시각','ends_at':'끝 시각','lanes_blocked':'막힌 차로','message':'안내 문구','url':'홈페이지',
 'mobility_aid':'이동 보조기구','stairs_ok':'계단 가능 정도','max_detour':'받아들이는 우회(배)','rest_every_m':'쉬는 간격(m)',
 'prefer_transit':'대중교통 선호','home_dong':'사는 동네','temp_mode':'임시 모드','temp_aid':'임시 이동 보조기구',
 'temp_reason':'임시 모드 이유','temp_until':'임시 모드 끝날','next_ask_on':'다시 물어볼 날','setup_done':'첫 설정 마침',
 'consent_trips':'이동 기록 동의','consent_at':'동의한 시각','started_on':'출발 날짜','weekday':'요일',
 'start_slot':'출발 시간대','from_dong':'출발 동네','to_name':'도착지 이름','to_lat':'도착지 위도(약 100m)',
 'to_lon':'도착지 경도(약 100m)','travel':'이동 방법','route_kind':'고른 길 종류','by_assistant':'비서 추천 여부',
 'weather':'출발 때 날씨','night':'밤 여부','body_note':'그날 몸 상태','planned_min':'예상 시간(분)',
 'actual_min':'실제 시간(분)','result':'결과','feedback':'도착 뒤 평가','feedback_reason':'불편한 이유',
 'layer':'시설 묶음 이름','aliases':'같은 뜻의 말','poi_filter':'시설 고르는 조건','shown':'보이기 여부',
 'until':'끝날','token':'공유 링크 값(32자)','walker_name':'보일 이름','dest_name':'가는 곳 이름','dest_lat':'가는 곳 위도',
 'dest_lon':'가는 곳 경도','started_at':'시작 시각','expires_at':'만료 시각','last_lat':'마지막 위도','last_lon':'마지막 경도',
 'last_at':'마지막 위치 시각','remain_m':'남은 거리(m)','eta_min':'남은 시간(분)','off_route':'길 벗어남',
 'ended_at':'끝난 시각','end_reason':'끝난 이유','asked_on':'요청한 날짜','text':'요청한 말','topic':'주제',
 'offered':'대신 제안한 것','type':'시설 표 이름','api':'API·파일','license':'이용 조건','total_count':'전체 건수',
 'loaded_count':'넣은 건수','skipped_count':'뺀 건수','complete':'다 받았는지','loaded_at':'넣은 시각',
 'keywords':'찾는 말','body':'설명 글','material':'재질','bicycle':'자전거도로 함께','made_on':'원본 갱신일',
 'left_ratio':'왼쪽 인도 비율','right_ratio':'오른쪽 인도 비율','side':'인도 있는 쪽','calls':'부른 수',
}
O = {
 ('road_node','id'):'점 번호(OSM)', ('road_node_excluded','id'):'점 번호(OSM)', ('road_edge','id'):'길 구간 번호',
 ('road_edge_excluded','id'):'뺀 줄 번호', ('poi','id'):'시설 번호', ('road_report','id'):'신고 번호',
 ('road_event','id'):'행사 번호', ('admin_area','id'):'구역 번호', ('sidewalk','id'):'인도 번호',
 ('subway_station','name'):'역 이름', ('bus_stop','name'):'정류장 이름', ('admin_area','name'):'구역 이름',
 ('map_layer','name'):'보일 이름', ('holiday','name'):'공휴일 이름', ('road_event','source'):'출처(축제·돌발)',
 ('road_event','kind'):'종류', ('sidewalk','kind'):'종류(선·면)', ('edge_poi','kind'):'시설 종류', ('map_layer','kind'):'종류',
 ('assistant_unmet','kind'):'요청 종류', ('poi','kind'):'시설 종류', ('road_report','mode'):'올린 모드',
 ('walk_profile','mode'):'모드', ('user_profile','mode'):'평소 모드', ('trip_log','mode'):'그때 모드',
 ('assistant_unmet','mode'):'모드', ('guardian_share','mode'):'모드', ('road_report','created_at'):'올린 시각',
 ('road_report_photo','seq'):'사진 순서', ('road_report','status'):'상태(보임·확인·숨김)', ('road_edge','name'):'길 이름',
 ('road_edge_excluded','name'):'길 이름', ('subway_entrance','name'):'출구 이름', ('subway_entrance','note'):'비고',
 ('data_source','source'):'출처', ('data_source','title'):'출처 이름', ('assistant_knowledge','source'):'근거 출처',
 ('subway_station','address'):'역 주소', ('road_event','address'):'주소', ('edge_sidewalk','source'):'인도 출처',
 ('sidewalk','source'):'출처', ('child_protection_zone','name'):'대상 시설 이름', ('subway_timetable','direction'):'방향',
 ('facility_unlocated','id'):'번호', ('facility_unlocated','reason'):'못 찾은 이유',
 ('road_edge_excluded','reason'):'뺀 이유', ('road_node_excluded','reason'):'뺀 이유', ('user_map_layer','reason'):'이유',
 ('edge_stat','curb'):'건너는 곳 보도턱', ('subway_entrance','wheelchair'):'휠체어 출입',
 ('region_density','kind'):'시설 종류', ('edge_stat','edge_id'):'길 구간 번호', ('road_report','edge_id'):'가까운 길 구간',
}
def logical(t, c): return O.get((t, c)) or D.get(c, c)


T = read_schema(SRC)
SC = 2
FONT = '/System/Library/Fonts/AppleSDGothicNeo.ttc'
def F(size, w=0): return ImageFont.truetype(FONT, int(size * SC), index=w)
f_row, f_rowb, f_type, f_phys = F(13), F(13, 4), F(11.5), F(11.5)
f_head, f_sub, f_title, f_leg = F(14.5, 6), F(11.5, 2), F(26, 6), F(13)

G = {  # 묶음: 색, 이름
    'fac': ('#2E7D32', '시설 원본 (공공데이터)'), 'road': ('#1565C0', '도로망·길 점수'),
    'rep': ('#C62828', '신고·행사'), 'user': ('#6A1B9A', '게스트(UUID)·기록'),
    'tr': ('#E65100', '대중교통'), 'etc': ('#455A64', '비서·관리'),
}
DESC = {
 'streetlight':'가로등','security_light':'보안등','cctv':'CCTV','police':'경찰 시설','emergency_bell':'안전 비상벨',
 'convenience_store':'편의점','child_safety_house':'아동안전지킴이집','heat_shelter':'무더위쉼터','cold_shelter':'한파쉼터',
 'public_toilet':'공중화장실','footbridge':'육교','crosswalk':'횡단보도','child_protection_zone':'어린이보호구역',
 'facility_unlocated':'위치를 못 찾아 뺀 시설 줄','data_source':'출처별 적재 현황',
 'road_node':'도로망 점 (OSM)','road_edge':'도로망 길 구간 (OSM)','road_node_excluded':'도로망에서 뺀 점',
 'road_edge_excluded':'도로망에서 뺀 길 (자동차전용도로)','edge_stat':'길별 감점 원값 (1:1)','edge_stat_excluded':'뺀 길의 감점 원값',
 'edge_sidewalk':'길마다 옆 인도 (034)','sidewalk':'인도 원본 (국토지리정보원)','admin_area':'행정구역 경계',
 'region_density':'시군구별 시설 밀도','edge_poi':'길별 주변 시설','poi':'정제 시설 (지도·길찾기)','holiday':'공휴일',
 'road_report':'불편 신고 (사진 판단 036)','road_report_photo':'신고 사진','road_event':'행사·돌발상황',
 'user_profile':'게스트 한 명 = 한 줄 (내 정보)','trip_log':'이동 기록 (동의한 사람만)','user_map_layer':'내 지도 시설 설정',
 'map_layer':'지도 시설 사전','walk_profile':'게스트·모드별 걷는 속도','walk_speed_sample':'걷는 속도 측정',
 'guardian_share':'보호자 위치 공유','subway_station':'지하철역·편의시설 수','subway_entrance':'지하철 출입구',
 'subway_timetable':'열차 시간표','subway_transfer':'역사별 환승','subway_exit_bus':'출구별 연계 버스',
 'subway_exit_place':'출구별 주변 장소','bus_stop':'버스 정류장','bus_line':'버스 노선','lowfloor_bus':'저상버스',
 'odsay_call_count':'ODsay 하루 부른 수','assistant_unmet':'비서가 못 한 요청','assistant_knowledge':'비서 지식 조각',
}
COMMON = ['id','kind','source','source_id','name','address','sido_code','sgg_code','lon','lat','geom','attrs']
FAC = ['streetlight','security_light','cctv','police','emergency_bell','convenience_store','child_safety_house',
       'heat_shelter','cold_shelter','public_toilet','footbridge','crosswalk','child_protection_zone']
COLS = [
 ['__common__','streetlight','security_light','cctv','police','emergency_bell','convenience_store','child_safety_house'],
 ['heat_shelter','cold_shelter','public_toilet','footbridge','crosswalk','child_protection_zone','facility_unlocated','data_source'],
 ['edge_stat','edge_stat_excluded','edge_sidewalk','sidewalk','admin_area','region_density'],
 ['road_node','road_edge','road_node_excluded','road_edge_excluded'],
 ['edge_poi','poi','road_report','road_report_photo','road_event'],
 ['user_profile','walk_profile','walk_speed_sample','trip_log','user_map_layer','map_layer','guardian_share'],
 ['subway_station','subway_entrance','subway_timetable','subway_transfer','subway_exit_bus','subway_exit_place'],
 ['bus_stop','bus_line','lowfloor_bus','odsay_call_count','assistant_unmet','assistant_knowledge','holiday'],
]
GROUP = {t: 'fac' for t in FAC + ['__common__', 'facility_unlocated', 'data_source']}
GROUP.update({t: 'road' for t in ['road_node','road_edge','road_node_excluded','road_edge_excluded','edge_stat','edge_stat_excluded',
              'edge_sidewalk','sidewalk','admin_area','region_density','edge_poi','poi']})
GROUP.update({t: 'rep' for t in ['road_report','road_report_photo','road_event']})
GROUP.update({t: 'user' for t in ['user_profile','walk_profile','walk_speed_sample','trip_log','user_map_layer','map_layer','guardian_share']})
GROUP.update({t: 'tr' for t in ['subway_station','subway_entrance','subway_timetable','subway_transfer','subway_exit_bus',
              'subway_exit_place','bus_stop','bus_line','lowfloor_bus','odsay_call_count']})
GROUP.update({t: 'etc' for t in ['assistant_unmet','assistant_knowledge','holiday']})

# (출발 표, 칸, 도착 표, 칸, 실제 외래 키?)
REL = [
 ('road_edge','from_node','road_node','id',0), ('road_edge','to_node','road_node','id',0),
 ('edge_stat','edge_id','road_edge','id',0), ('edge_sidewalk','edge_id','road_edge','id',0),
 ('edge_stat_excluded','edge_id','road_edge_excluded','edge_id',0),
 ('edge_poi','edge_id','road_edge','id',0), ('edge_poi','poi_id','poi','id',0),
 ('road_report','edge_id','road_edge','id',0), ('road_report_photo','report_id','road_report','id',1),
 ('walk_profile','guest_id','user_profile','guest_id',0), ('walk_speed_sample','guest_id','walk_profile','guest_id',1),
 ('trip_log','guest_id','user_profile','guest_id',1), ('user_map_layer','guest_id','user_profile','guest_id',1),
 ('user_map_layer','layer','map_layer','layer',0), ('guardian_share','guest_id','user_profile','guest_id',0),
 ('subway_entrance','station_id','subway_station','station_id',0),
]
NOTE = {('poi','sources'): '← 시설 원본 표들', ('facility_unlocated','tbl'): '어느 시설 표',
        ('road_report','reporter_hash'): 'UUID의 HMAC', ('guardian_share','token'): '서버가 만든 무작위'}
UUID = {(t, 'guest_id') for t in ['user_profile','walk_profile','walk_speed_sample','trip_log','user_map_layer','guardian_share']}
FK_SRC = {(a, b): (c, r) for a, b, c, d, r in REL}

def short(t): return t.split('(')[0].replace(' unsigned', '')
def rows_of(t):
    if t == '__common__':
        return [(c, short(dict((x[0], x[1]) for x in T['streetlight']['cols'])[c]), 'pk' if c == 'id' else None, None) for c in COMMON]
    if t == 'edge_stat_excluded':
        return [('edge_id', 'bigint', 'lk', '→ road_edge_excluded'), ('… edge_stat 칸 27개 그대로', '', 'note', None)]
    cols = [c for c in T[t]['cols'] if c[0] not in ('WHEN', 'REFERENCES', 'END', 'CASE')]
    pk = set(T[t]['pk']); out = []
    if t in FAC:
        out.append(('공통 칸 12개 (왼쪽 위)', '', 'note', None)); cols = [c for c in cols if c[0] not in COMMON]
        if not cols: return out
    if t == 'road_edge_excluded':
        keep = ['id', 'edge_id', 'reason', 'excluded_at']
        out = [(c[0], short(c[1]), 'pk' if c[0] in pk else None, None) for c in cols if c[0] in keep[:2]]
        out.append(('… road_edge 칸 그대로', '', 'note', None))
        out += [(c[0], short(c[1]), None, None) for c in cols if c[0] in keep[2:]]
        return out
    for c, ty, _ in cols:
        k = 'pk' if c in pk else None; note = NOTE.get((t, c))
        if (t, c) in FK_SRC:
            dst, real = FK_SRC[(t, c)]; k = (k + '+' if k else '') + ('fk' if real else 'lk'); note = '→ ' + dst
        if (t, c) in UUID: note = (note + ' · ' if note else '') + 'UUID 36글자'
        out.append((c, short(ty), k, note))
    return out

PAD, RH, HH, GAP, GUT, TOP = 10 * SC, 21 * SC, 46 * SC, 16 * SC, 92 * SC, 214 * SC
tmp = ImageDraw.Draw(Image.new('RGB', (10, 10)))
def tw(s, f): return tmp.textlength(s, font=f)
def lt(t): return 'streetlight' if t == '__common__' else t
def head_of(t):
    if t == '__common__': return '시설 공통 칸', '모든 시설 원본 표에 있는 칸 12개'
    return DESC.get(t, t), t
def box_w(t):
    w = max(tw(head_of(t)[0], f_head), tw(head_of(t)[1], f_sub)) + 2 * PAD
    for n, ty, k, note in rows_of(t):
        lw = tw(n, f_type) if k == 'note' else tw(logical(lt(t), n), f_rowb) + 8 * SC + tw(n, f_phys)
        w = max(w, 34 * SC + lw + (tw('  ' + note, f_type) if note else 0) + tw(ty, f_type) + 3 * PAD)
    return w
colw = [max(box_w(t) for t in col) for col in COLS]
X = []; x = 40 * SC
for w in colw: X.append(x); x += w + GUT
W = int(x - GUT + 40 * SC)
pos, rowy = {}, {}
H = 0
for ci, col in enumerate(COLS):
    y = TOP
    for t in col:
        rs = rows_of(t); h = HH + len(rs) * RH + 6 * SC
        pos[t] = (X[ci], y, X[ci] + colw[ci], y + h, ci)
        for i, r in enumerate(rs): rowy[(t, r[0])] = y + HH + i * RH + RH // 2
        y += h + GAP
    H = max(H, y)
H = int(H + 30 * SC)
img = Image.new('RGB', (W, H), '#FFFFFF'); d = ImageDraw.Draw(img)

d.text((40 * SC, 28 * SC), '세잎지도 DB 전체 ERD', font=f_title, fill='#111')
d.text((40 * SC, 66 * SC), '표 50개 · 2026-11-20 schema.sql 기준 · 실제 DB에는 외래 키 4개뿐이고 나머지는 칸 값으로 이어짐 (데이터 갱신 때 표를 통째로 다시 넣기 때문)',
       font=f_leg, fill='#555')
lx = W - 40 * SC
items = [(G[k][0], G[k][1]) for k in G]
def legend_item(x, label, color, kind):
    if kind == 'box': d.rectangle([x, 96 * SC, x + 16 * SC, 112 * SC], fill=color)
    elif kind == 'solid': d.line([x, 104 * SC, x + 30 * SC, 104 * SC], fill='#333', width=2 * SC)
    else:
        for s in range(0, 30, 8): d.line([x + s * SC, 104 * SC, x + min(s + 4, 30) * SC, 104 * SC], fill='#333', width=2 * SC)
    w0 = 16 * SC if kind == 'box' else 30 * SC
    d.text((x + w0 + 6 * SC, 95 * SC), label, font=f_leg, fill='#333')
    return x + w0 + 6 * SC + tw(label, f_leg) + 22 * SC
x = 40 * SC
for c, n in items: x = legend_item(x, n, c, 'box')
x = legend_item(x + 20 * SC, '실제 외래 키 (FK)', None, 'solid')
x = legend_item(x, '칸 값으로 이어짐 (→)', None, 'dash')
HOW = [
    ('읽는 법', '상자 하나 = 표 하나 (위: 한글 이름, 아래: DB 이름)  ·  줄 하나 = 칸 하나: 왼쪽 검은 글씨 = 논리명(뜻), 회색 영문 = 물리명(DB에 실제로 쓰는 이름), 오른쪽 = 자료형'),
    ('표시', 'PK = 표에서 한 줄을 콕 집어 가리키는 칸(같은 값이 두 번 없음)  ·  FK·실선 = DB가 직접 지키는 연결  ·  →·점선 = 같은 값을 적어 이어 둔 연결  ·  UUID = 앱이 처음 켤 때 만든 36글자 무작위 번호(사람을 알아볼 수 없음)'),
    ('자료형', 'int·bigint·smallint = 정수  ·  tinyint = 작은 정수(0/1이면 예·아니오)  ·  decimal·double = 소수  ·  varchar·char = 글자  ·  date = 날짜  ·  datetime = 날짜+시각  ·  time = 시각  ·  point·linestring·geometry = 위치·선·면  ·  json·set = 여러 값 묶음  ·  mediumblob = 파일(사진)'),
]
for i, (k, v) in enumerate(HOW):
    yy = (128 + i * 24) * SC
    d.text((40 * SC, yy), k, font=F(13, 6), fill='#222')
    d.text((100 * SC, yy), v, font=f_leg, fill='#444')

def dashed(p, q, color, wdt):
    (x1, y1), (x2, y2) = p, q; L = max(abs(x2 - x1), abs(y2 - y1)); step = 7 * SC
    n = int(L // step)
    for i in range(0, n + 1, 2):
        a, b = i / max(n, 1), min((i + 1) / max(n, 1), 1)
        d.line([x1 + (x2 - x1) * a, y1 + (y2 - y1) * a, x1 + (x2 - x1) * b, y1 + (y2 - y1) * b], fill=color, width=wdt)
def poly(pts, real, color):
    for p, q in zip(pts, pts[1:]):
        if real: d.line([p, q], fill=color, width=2 * SC)
        else: dashed(p, q, color, int(1.6 * SC))
    (x1, y1), (x2, y2) = pts[-2], pts[-1]; s = 1 if x2 > x1 else -1; a = 8 * SC
    d.polygon([(x2, y2), (x2 - s * a, y2 - a * 0.55), (x2 - s * a, y2 + a * 0.55)], fill=color)

# 표 상자
for t, (x0, y0, x1, y1, ci) in pos.items():
    col = G[GROUP[t]][0]
    d.rounded_rectangle([x0, y0, x1, y1], radius=6 * SC, fill='#FFFFFF', outline=col, width=2 * SC)
    d.rounded_rectangle([x0, y0, x1, y0 + HH], radius=6 * SC, fill=col)
    d.rectangle([x0, y0 + HH - 8 * SC, x1, y0 + HH], fill=col)
    hn, hs = head_of(t)
    d.text((x0 + PAD, y0 + 4 * SC), hn, font=f_head, fill='#FFFFFF')
    d.text((x0 + PAD, y0 + 25 * SC), hs, font=f_sub, fill='#F0F0F0')
    for i, (n, ty, k, note) in enumerate(rows_of(t)):
        ry = y0 + HH + i * RH + 3 * SC
        if i % 2: d.rectangle([x0 + 2 * SC, ry - 1 * SC, x1 - 2 * SC, ry + RH - 1 * SC], fill='#F6F7F9')
        cx = x0 + PAD
        if k and 'pk' in k: d.text((cx, ry + 3 * SC), 'PK', font=F(10, 6), fill='#B8860B')
        elif k and 'fk' in k: d.text((cx, ry + 3 * SC), 'FK', font=F(10, 6), fill='#333')
        elif k and 'lk' in k: d.text((cx + 3 * SC, ry + 1 * SC), '→', font=f_rowb, fill='#777')
        if k == 'note':
            d.text((cx, ry), n, font=f_type, fill='#888'); nx = cx + tw(n, f_type)
        else:
            ln = logical(lt(t), n); fn = f_rowb if k else f_row
            d.text((cx + 26 * SC, ry), ln, font=fn, fill='#1A1A1A')
            px = cx + 26 * SC + tw(ln, fn) + 8 * SC
            d.text((px, ry + 1 * SC), n, font=f_phys, fill='#8A8A8A'); nx = px + tw(n, f_phys)
        if note:
            nc = '#6A1B9A' if 'UUID' in note else G[GROUP.get(FK_SRC.get((t, n), ('',))[0], GROUP[t])][0]
            d.text((nx + 8 * SC, ry + 1 * SC), note, font=f_type, fill=nc)
        if ty: d.text((x1 - PAD - tw(ty, f_type), ry + 1 * SC), ty, font=f_type, fill='#8A8A8A')

# 연결선: 같은 열이면 오른쪽 틈, 옆 열이면 그 사이 틈. 틈마다 차선을 나눠 겹치지 않게
lanes = {}
for a, ca, b, cb, real in REL:
    xa0, _, xa1, _, ia = pos[a]; xb0, _, xb1, _, ib = pos[b]
    ya, yb = rowy[(a, ca)], rowy[(b, cb)]
    color = '#333333' if real else '#7A7A7A'
    if ia == ib:
        g = ia; n = lanes.get(g, 0); lanes[g] = n + 1; lx = xa1 + (12 + n * 11) * SC
        poly([(xa1, ya), (lx, ya), (lx, yb), (xb1, yb)], real, color)
    elif ib == ia + 1:
        g = ia; n = lanes.get(g, 0); lanes[g] = n + 1; lx = xa1 + (12 + n * 11) * SC
        poly([(xa1, ya), (lx, ya), (lx, yb), (xb0, yb)], real, color)
    elif ib == ia - 1:
        g = ib; n = lanes.get(g, 0); lanes[g] = n + 1; lx = xb1 + (GUT / SC - 12 - n * 11) * SC
        poly([(xa0, ya), (lx, ya), (lx, yb), (xb1, yb)], real, color)
    else:
        raise SystemExit('멀리 떨어진 연결: %s → %s' % (a, b))
img = img.resize((W // SC * 3 // 2, H // SC * 3 // 2), Image.LANCZOS)  # 1.5배로 저장 (선명함·용량 사이)
img = img.convert('RGB').quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)  # 글자는 그대로, 용량 약 40%
img.save(OUT, optimize=True)
print(OUT, img.size, '표', len([t for t in pos if t != '__common__']), '상자', len(pos))
