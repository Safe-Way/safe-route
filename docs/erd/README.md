# ERD

학교 DB(`cf_u34_d3`)의 ERD 파일이다. 전체 그림(`safe_route_full_erd.png`)은 2026-11-20 `schema.sql`의 표 50개로 그렸다.
아래 `.sql`·`.dbml` 파일은 2026-11-03에 학교 DB의 `information_schema`에서 만든 표 32개 판이다(그 뒤 생긴 신고·게스트·인도 등 표는 없음).
표 정의의 원본은 `src/main/resources/db/schema.sql`이고, 이 폴더는 그림용이다.

실제 DB의 외래 키는 **4개뿐이다**(신고 사진 → 신고, 걷기 측정 → 걷기 속도, 이동 기록·내 지도 설정 → 내 정보). 공공데이터 표는 갱신할 때 통째로 지우고 다시 넣기 때문에 외래 키를 두지 않는다.
대신 경로 계산 표들은 칸 값으로 이어진다(`road_edge.from_node` = `road_node.id` 등).

| 파일 | 무엇 | 언제 쓰나 |
| --- | --- | --- |
| `safe_route_full_erd.png` | **표 50개 전체** (2026-11-20, schema.sql 기준). 표·칸마다 **논리명(한글 뜻)과 물리명(DB 이름)**, 자료형, 표 아래 **인덱스**(UQ·IX·SP·FT 108개). 관계선은 **IE 표기**: 실선 = 식별, 점선 = 비식별, 끝은 세발 표기. 진한 선 = 실제 외래 키 4개, 회색 = 칸 값 연결. `guest_id`(UUID 36글자) 표시. 맨 위에 읽는 법·자료형 뜻 | README·발표 |
| `full_erd.py` | 위 그림을 schema.sql에서 다시 그림 (`.venv/bin/python docs/erd/full_erd.py`). 새 표는 COLS·GROUP·DESC(표 논리명)·REL에, 새 칸의 논리명은 D(표마다 다르면 O)에 넣기 | 표가 바뀌었을 때 |
| `route_core_erd.png` | 경로 계산에 쓰는 표 8개의 관계 그림. 선마다 ①~⑦ 번호와 왜 연결되는지 짧게 적음 | 발표·문서 (요구사항 8.2에 같은 그림과 ①~⑦ 설명 표) |
| `route_core_erd.mmd` | 위 그림의 mermaid 원본 | 그림을 고칠 때 |
| `safe_route_erd_nofk.sql` | 표 32개, 외래 키 없음 (실제 DB 그대로) | ERD 도구에 실제 구조를 넣을 때 |
| `safe_route_erd.sql` | 표 32개 + 그림용 외래 키 6개 | ERD 도구에서 관계선까지 그릴 때. **실제 DB에 실행하지 말 것** |
| `safe_route_erd_compat.sql`, `safe_route_erd_nofk_compat.sql` | 위 두 파일에서 공간 타입(`POINT` 등)·`json`·`set`을 흔한 타입으로 바꾼 판 | 공간 타입을 모르는 ERD 도구에 넣을 때 (원래 타입은 칸 설명에) |
| `safe_route_erd.dbml` | DBML (관계 6개 포함) | dbdiagram.io |

그림용 관계 6개: `road_edge.from_node`·`to_node` → `road_node.id`, `edge_stat.edge_id` → `road_edge.id`(1:1),
`edge_poi.edge_id` → `road_edge.id`, `edge_poi.poi_id` → `poi.id`, `subway_entrance.station_id` → `subway_station.station_id`.
