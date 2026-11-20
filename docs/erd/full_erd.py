"""schema.sql → 전체 ERD 그림 (docs/erd/safe_route_full_erd.png).

  .venv/bin/python docs/erd/full_erd.py            # 기본: src/main/resources/db/schema.sql → docs/erd/safe_route_full_erd.png
  .venv/bin/python docs/erd/full_erd.py <schema.sql> <out.png>

표를 더하면 COLS(어느 열에 둘지)·GROUP(색)·DESC(한 줄 설명)에 넣고, 이어지는 칸은 REL에 넣는다.
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


T = read_schema(SRC)
SC = 2
FONT = '/System/Library/Fonts/AppleSDGothicNeo.ttc'
def F(size, w=0): return ImageFont.truetype(FONT, int(size * SC), index=w)
f_row, f_rowb, f_type = F(13), F(13, 4), F(11.5)
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
        if (t, c) in UUID: note = (note + ' · ' if note else '') + 'UUID'
        out.append((c, short(ty), k, note))
    return out

PAD, RH, HH, GAP, GUT, TOP = 10 * SC, 21 * SC, 44 * SC, 16 * SC, 92 * SC, 134 * SC
tmp = ImageDraw.Draw(Image.new('RGB', (10, 10)))
def tw(s, f): return tmp.textlength(s, font=f)
def box_w(t):
    w = max(tw(t if t != '__common__' else '시설 공통 칸', f_head), tw(DESC.get(t, ''), f_sub)) + 2 * PAD
    for n, ty, k, note in rows_of(t):
        w = max(w, 34 * SC + tw(n, f_row) + (tw('  ' + note, f_type) if note else 0) + tw(ty, f_type) + 3 * PAD)
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
d.text((x, 95 * SC), 'PK 기본 키 · UUID 앱이 만든 게스트 번호', font=f_leg, fill='#333')

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
    d.text((x0 + PAD, y0 + 4 * SC), '시설 공통 칸' if t == '__common__' else t, font=f_head, fill='#FFFFFF')
    d.text((x0 + PAD, y0 + 24 * SC), '모든 시설 원본 표에 있음' if t == '__common__' else DESC.get(t, ''), font=f_sub, fill='#F0F0F0')
    for i, (n, ty, k, note) in enumerate(rows_of(t)):
        ry = y0 + HH + i * RH + 3 * SC
        if i % 2: d.rectangle([x0 + 2 * SC, ry - 1 * SC, x1 - 2 * SC, ry + RH - 1 * SC], fill='#F6F7F9')
        cx = x0 + PAD
        if k and 'pk' in k: d.text((cx, ry + 3 * SC), 'PK', font=F(10, 6), fill='#B8860B')
        elif k and 'fk' in k: d.text((cx, ry + 3 * SC), 'FK', font=F(10, 6), fill='#333')
        elif k and 'lk' in k: d.text((cx + 3 * SC, ry + 1 * SC), '→', font=f_rowb, fill='#777')
        fn = f_rowb if k and k != 'note' else f_row
        if k == 'note': d.text((cx, ry), n, font=f_type, fill='#888')
        else: d.text((cx + 26 * SC, ry), n, font=fn, fill='#1A1A1A')
        if note:
            nc = '#6A1B9A' if 'UUID' in note else G[GROUP.get(FK_SRC.get((t, n), ('',))[0], GROUP[t])][0]
            d.text((cx + 26 * SC + tw(n, fn) + 6 * SC, ry + 1 * SC), note, font=f_type, fill=nc)
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
img.save(OUT, optimize=True)
print(OUT, img.size, '표', len([t for t in pos if t != '__common__']), '상자', len(pos))
