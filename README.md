# 세잎지도 (safe-route)

[![CI/CD](https://github.com/Safe-Way/safe-route/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Safe-Way/safe-route/actions/workflows/ci.yml)

**상황 인식 기반 안전 도보 경로 추천 서비스.** 어린이·노인·휠체어 사용자에게 가장 빠른 길 대신 **그 사람이 실제로 걸을 수 있고 안전한 길**을 골라 주고, 왜 그 길인지 쉬운 말로 설명합니다.
화면 조작이 어려운 분도 쓸 수 있게 "문화전당 가자"처럼 **말로 묻는 AI 비서**로 동작합니다.

- 서비스: https://safe-route.java21.net (학교 서버, `main`에 머지하면 자동 배포)
- 데이터 범위: 광주광역시 전체 · 시연 지역: 국립아시아문화전당 일대
- 앱: [safe-route-android](https://github.com/Safe-Way/safe-route-android) (안드로이드, Kotlin)
- 요구사항: [safe-route-docs](https://github.com/Safe-Way/safe-route-docs)의 [requirements.md](https://github.com/Safe-Way/safe-route-docs/blob/main/requirements.md) (결정 59건, 변경 이력 [CHANGELOG.md](https://github.com/Safe-Way/safe-route-docs/blob/main/CHANGELOG.md))
- 비서 설계 문서: [docs/assistant/](docs/assistant/) (동의 문구, 도구 고르기, 시험 질문, 사용성 점검표)

## 주요 기능

| # | 기능 | 내용 |
|---|---|---|
| 1 | 말로 묻는 비서 | 휴대폰이 말을 글자로 바꿔 서버 비서(`POST /api/assistant`)로 보냄. 급한 말·들를 곳·못 하는 일은 서버 규칙이 먼저 답하고, 나머지는 구글 제미나이가 장소 찾기·길 추천 같은 도구를 고름. 길·시간·거리 계산은 우리 서버가 함 |
| 2 | 모드별 맞춤 길 | 어린이·청소년, 노인, 휠체어, 기본(청년·중년). 휠체어는 계단·확인된 턱·못 지나는 육교를 빼고, 노인은 계단을 피하고 쉴 곳을 가깝게, 어린이는 지킴이집·CCTV가 많고 큰길을 덜 건너는 길 |
| 3 | 상황 판단 | 밤에는 가로등·CCTV가 많은 밝은 길, 비가 오면 지붕 있는 길과 지하 길(역 대합실·금남지하상가), 행사·공사·사고는 피함 |
| 4 | 이유 설명 | "계단 2곳을 피해 210m 더 가요"처럼 최단 길과의 차이를 말함. 대중교통은 "정류장까지 5분, 버스 12분, 내려서 3분"처럼 구간 순서대로 |
| 5 | 걷기와 대중교통 비교 | 걷기와 버스·지하철(저상버스 도착 포함)을 날씨·몸 상태·기록으로 비교해 하나를 추천. 들를 곳은 걷기 3곳·대중교통 1곳까지 |
| 6 | 신고된 곳 | 앱 사진 신고·말 신고를 같은 곳(같은 종류·30m)으로 묶어 1건부터 피함. 종류별 1·7·30일 유지, 끝난 신고·사진은 매일 정리 (결정 57·59) |
| 7 | 보호자 위치 공유·급한 말 | 마지막 한 점만 공유하고 도착하면 링크를 닫음. "누가 따라와" 같은 말에는 112·보호자·가까운 파출소 버튼 |
| 8 | 기억하기 | 로그인 없이 앱이 만든 무작위 번호로 모드·설정을 기억. 다닌 기록은 동의한 사람만 남기고 위치는 약 100m로 반올림 |

## 팀 (등불지기)

| 파트 | 이름 | 맡은 일 |
|---|---|---|
| A 데이터·DB · 기획(PO) | 김재휘 | 공공데이터 수집·정제, 광주 도로망·지하 길·광장 길, DB 설계, 요구사항 문서, CI/CD·배포, 비서 점검 |
| B 경로 엔진 | 정원일 | 모드별 비용과 A* 탐색, 우회 한도, 신고·비·지하 길 반영, 길 설명 문장 |
| C 서버 | 김채림 | API, AI 비서(제미나이 도구 호출·규칙·토큰 줄이기), 대중교통·버스 도착, 신고·보호자 공유 |
| D 앱 | 박민수 | 안드로이드 화면·지도·길안내, 말로 대화, 보호자 공유·신고 화면 |

조직: https://github.com/Safe-Way

## 구조

```
안드로이드 앱 ──(음성 인식·읽기는 휴대폰 기본 기능)
   │  HTTPS
   ▼
Spring Boot 서버 (학교 서버, 두 대가 번갈아 받음)
   ├─ /api/assistant   비서: 규칙 → 제미나이(도구 고르기) → 우리 도구가 계산
   ├─ /api/route       걷기 길찾기 ── 경로 엔진 (OSM 도로망 그래프, A*)
   ├─ /api/transit     대중교통 (ODsay, 광주 버스 도착)
   ├─ /api/reports     신고 (사진은 DB에)
   └─ /api/places · /api/pois · /api/events · /api/guardian/shares · /api/me · /api/ai/* …
   ▼
MySQL 8 (공간 인덱스) — 시설·도로망·길별 점수·신고·내 정보
```

## 어떻게 길을 고르나

길 한 구간의 비용은 길이에 상황별 감점을 더해 계산합니다 (자세히: [docs/route-weights.md](docs/route-weights.md)).

```
비용 = 길이 × (1 + Σ 가중치(시간대·날씨·모드) × 감점)
```

- 감점 예: 어두움, CCTV 없음, 도움 받을 곳 멂, 계단, 큰 차도 횡단, 비포장, 신고된 곳
- 휠체어는 감점이 아니라 **뺍니다**: 계단(경사로 없는 것), 확인된 턱, 엘리베이터 없는 육교
- 최단 경로보다 너무 돌지 않게 상한을 둡니다: 기본·어린이 1.3배, 노인·휠체어 1.2배
- 비교 기준은 TMAP 도보 경로입니다 (카카오 도보 API는 제휴사 전용)

## 기술 스택

| 구분 | 사용 |
|---|---|
| 서버 | Java 21, Spring Boot 4.1, Maven, JGraphT(A*) |
| DB | MySQL 8 (공간 인덱스, `ST_Distance_Sphere`, 뷰) |
| AI | Google Gemini (함수 호출). 규칙으로 답할 수 있는 말은 부르지 않고, 필요한 도구만 보내 입력 토큰 약 55% 줄임 |
| 앱 | Android (Kotlin, Jetpack Compose, 카카오맵 SDK) |
| 외부 API | 카카오 로컬(장소), 기상청·에어코리아, ODsay(대중교통), 광주 버스 도착, TMAP 보행자(비교용) |
| 도로망 | OpenStreetMap (광주 81,709구간) |
| 배포 | GitHub Actions → Docker → 학교 서버 (`main` 머지 = 배포, 서버 상태 감시) |
| 데이터 스크립트 | Python 3 (표준 라이브러리만) |

## 데이터

광주 길찾기에 쓰는 정제 시설과 도로망 (2026-11 기준):

| 데이터 | 건수 | 쓰는 곳 |
|---|---|---|
| 가로등·보안등 | 85,754 | 밤 밝은 길 |
| CCTV (단속용 뺌) | 4,514 | 밤·어린이 길 |
| 쉼터 · 화장실 · 비상벨 | 2,936 · 1,205 · 219 | 노인 쉴 곳, 도움 받을 곳 |
| 횡단보도 | 3,539 | 신호·보도턱 |
| 지하철 출구 엘리베이터 | 92곳 확인 | 휠체어 대중교통 |
| 도로망 (OpenStreetMap) | 81,709구간 | 경로 엔진. 자동차전용도로는 빼고, 지하 길·보행자 광장 길을 따로 보관 |

전국 원본 (처음 받은 것):

| 데이터 | 출처 | 건수 |
|---|---|---|
| 가로등 · 보안등 · 경찰 | 재난안전데이터 공유플랫폼 (행정안전부 공통POI) | 보안등 약 95만, 가로등 약 225만 |
| 보안등 | 공공데이터포털 전국보안등정보표준데이터 (지자체 직접 등록) | 약 186만 |
| 한파쉼터 | 재난안전데이터 공유플랫폼 (DSSP-IF-10804) | 약 5.4만 |
| CCTV | 행정안전부 지방행정 인허가 데이터, 광주광역시 | 약 39만 |
| 비상벨 · 편의점 · 지킴이집 · 무더위쉼터 · 공중화장실 | 생활안전지도 | 약 26만 |

생활안전지도 데이터는 **공공누리 4유형**(출처표시 · 상업적 이용 금지 · 변경 금지)입니다.

## DB 표

원본 시설은 종류마다 표 하나씩 있습니다. 길찾기는 이를 정제한 `poi`·`edge_stat`(길별 점수)·`edge_poi`(길별 주변 시설)를 씁니다. 전체 표는 [ERD](docs/erd)와 `db/schema.sql`에 있습니다. 모든 표에 공통 칸이 있고, 종류마다 필요한 칸이 붙습니다.

| 표 | 내용 | 종류별 칸 |
|---|---|---|
| `streetlight` | 가로등 | |
| `security_light` | 보안등 | `lamp_count`, `install_year`, `manager`, `manager_phone` |
| `cctv` | CCTV | `camera_count`, `pixels`, `direction`, `retention_days`, `installed`, `manager_phone` |
| `police` | 경찰서 · 지구대 · 파출소 등 | `phone` |
| `emergency_bell` | 안전 비상벨 | `purpose`, `police_linked`, `manager`, `manager_phone`, `last_inspected` 등 |
| `convenience_store` | 편의점 | |
| `child_safety_house` | 아동안전지킴이집 | `phone` |
| `heat_shelter` | 무더위쉼터 | `capacity`, `area_m2`, `night_open`, `weekend_open` |
| `cold_shelter` | 한파쉼터 | `capacity`, `weekday_hours`, `saturday_hours`, `sunday_hours`, `holiday_hours`, `note` |
| `public_toilet` | 공중화장실 | `phone`, `open_hours` |
| `facility_all` (뷰) | 위 표 전체를 공통 칸만 모아 보기 | |
| `data_source` | 출처별 적재 현황 (건수, 이용 조건, 받은 날) | |

- **공통 칸:** `kind`(세부 종류), `source`, `source_id`, `name`, `address`, `sido_code`, `sgg_code`, `lon`, `lat`, `geom`, `attrs`
- **인덱스:** 위치(`geom` 공간 인덱스), 출처, 세부 종류, 시군구 코드, 주소·이름 앞부분

```sql
-- 문화전당 주변 지구대·파출소
SELECT kind, name, phone, address FROM police
WHERE MBRContains(ST_GeomFromText('POLYGON((126.91 35.14, 126.93 35.14, 126.93 35.155, 126.91 35.155, 126.91 35.14))'), geom)
  AND kind IN ('지구대', '파출소');

-- 문화전당 500m 안 시설 수
SELECT type, COUNT(*) FROM facility_all
WHERE ST_Distance_Sphere(geom, POINT(126.9204, 35.1466)) < 500 GROUP BY type;
```

## 실행 방법

### 1. 설정 파일 만들기

`.env.example`을 복사해 `.env`를 만들고 빈 값을 채웁니다. 키와 DB 비밀번호는 팀 채팅으로 받습니다.
**`.env`는 git에 올리지 않습니다.**

```bash
cp .env.example .env
```

- 기본은 **학교 DB**입니다.
- 학교 밖에서는 `.env`에서 학교 DB 세 줄을 주석 처리하고 로컬 MySQL 세 줄을 풀어서 씁니다.

### 2. DB 표 만들기

학교 DB에는 이미 표와 데이터가 있습니다. 로컬 MySQL을 새로 쓸 때만 실행합니다.

```bash
mysql -u safe_route -p safe_route < src/main/resources/db/schema.sql
```

### 3. 데이터 내려받기

API마다 하루 호출 한도(1,000번)가 있어서, 가로등처럼 큰 데이터는 며칠에 걸쳐 받습니다.
이미 받은 쪽은 건너뛰므로 다시 실행해도 됩니다.

```bash
python3 scripts/download_national.py          # 전부
python3 scripts/download_national.py police   # 하나만
python3 scripts/download_national.py --wait   # 한도가 차면 다음 날까지 기다렸다 이어 받기
```

CSV로만 받는 파일은 직접 내려받아 넣습니다.

- `data/raw/national/cctv_national.csv`
- `data/raw/regional/gwangju_cctv.csv`
- `data/raw/regional/donggu_security_light.csv`

### 4. DB에 넣기

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=import
./mvnw spring-boot:run -Dspring-boot.run.profiles=import -Dspring-boot.run.arguments=--types=cctv,police
```

같은 종류를 다시 넣으면 지우고 새로 넣으므로 여러 번 실행해도 됩니다.

### 5. 한 번에 갱신하기 (FR-08)

3·4번과 좌표 고치기, 대중교통, 길 점수 계산까지 16단계를 정해진 순서로 한 번에 돌립니다(광주).

```bash
python3 scripts/refresh_data.py --dry-run       # 할 일 목록만 보기
python3 scripts/refresh_data.py                 # 전부: 받기 → 넣기 → 계산
python3 scripts/refresh_data.py --only compute  # 길 점수만 다시 계산
python3 scripts/refresh_data.py --from load     # 받기는 건너뛰고 넣기부터
python3 scripts/refresh_data.py --fresh crosswalk  # 이 데이터는 처음부터 다시 받기
python3 scripts/refresh_data.py --roads         # OSM 도로망도 다시 받기 (수십 분)
```

명령 하나로 돌리면 좋은 점

- **순서를 외우지 않아도 됩니다.** 데이터 스크립트들은 앞뒤가 얽혀 있습니다.
  - 대중교통을 다시 넣으면 손으로 확인한 출구 엘리베이터 값이 비어서, 바로 뒤에 `subway_entrance_elevator.sql`을 넣어야 합니다.
  - 정제 시설(`poi`)을 다시 만들면 id가 바뀌어서, 길별 주변 시설(`edge_poi`)을 그 뒤에 다시 만들어야 합니다.
  - 하나라도 빠뜨리면 지도와 경로에 틀린 값이 나옵니다. 이 명령이 그 순서를 지킵니다.
- **누가 돌려도 결과가 같습니다.** A파트가 없어도 팀원 누구나 시연 전에 최신 데이터로 맞출 수 있습니다.
- **끝나면 스스로 점검합니다.**
  - 길마다 원값이 있는지, 없는 길·시설을 가리키는 줄이 없는지, 확인 안 된 지하철 출구가 없는지, 같은 원본이 두 번 들어갔는지 봅니다.
  - 주요 표 22개의 줄 수를 지난번과 비교합니다. 줄 수가 같으면 중복 없이 같은 결과이고, 달라졌으면 새 원본이 들어왔거나 무언가 빠진 것입니다.
- **실패하면 멈추고 이어서 돌립니다.** 실패한 단계의 기록이 `data/raw/refresh_logs/<시각>/`에 남습니다. 고친 뒤 `--from <단계>`로 이어 돌립니다.
- **두 사람이 동시에 돌리지 못합니다.** 돌고 있으면 `data/raw/refresh.lock`이 있어서, 두 번째 실행은 바로 멈춥니다.

서버는 길찾기 요청마다 DB를 읽으므로, 갱신이 끝나면 서버를 다시 띄우지 않아도 반영됩니다.
도는 동안에는 경로가 "확인 안 됨"으로 나올 수 있으니 시연·테스트가 없을 때 돌립니다.
직접 받는 CSV(위 3번 목록, 공중화장실 CSV, `data/raw/regional/` 광주 CSV)는 새 파일로 바꿔 둔 뒤 돌립니다.

### 6. 테스트

```bash
./mvnw test
```

## 폴더 구조

```
src/main/java/com/saferoute/
  assistant/   비서 (규칙, 제미나이, 도구, 들를 곳)
  route/       걷기 길찾기와 경로 엔진 (engine/: 그래프·A*·점수·설명)
  transit/ bus/ subway/   대중교통·버스 도착·지하철
  report/      신고와 매일 정리
  guardian/    보호자 위치 공유
  me/          내 정보·이동 기록 (동의한 사람만)
  place/ facility/ event/ situation/ weather/ air/ walk/ tmap/   장소·시설·행사·날씨·미세먼지·외부 API
  appchat/     앱 화면 AI를 서버가 대신 부르는 창구
  ingest/      공공데이터를 종류별 시설 표로 옮기는 코드
src/main/resources/
  application.yml   설정 (키는 .env에서 읽음)
  route.yml         모드별 가중치·우회 한도
  db/schema.sql     DB 표 전체
  db/migration/     DB 변경 기록 (001~033)
docs/
  assistant/        비서 설계 (동의 문구, 도구 고르기, 시험 질문 41개, 사용성 점검표)
  route-weights.md  가중치 기준
  erd/              ERD
scripts/            데이터 받기·넣기·점검 스크립트 (refresh_data.py, eval_assistant.py, review_reports.py …)
data/raw/           내려받은 원본·점검 기록 (git에 올리지 않음)
```

## 점검

```bash
./mvnw test                                   # 서버 테스트
python3 scripts/eval_assistant.py             # 학교 서버 비서에 시험 질문 41개 (저장되는 질문은 --write일 때만)
python3 scripts/check_report_active.py        # 신고 기간 규칙 시험 (DB에 안 넣고 계산만)
python3 scripts/check_report_retention.py     # 신고 보관 규칙 시험
python3 scripts/review_reports.py             # 새 신고·사진 확인, --hide로 숨기기
```

## 진행 상황

- [x] 공공데이터 수집·정제, 광주 도로망 (지하 길·광장 길 포함)
- [x] 경로 엔진: 모드별 길, 밤·비·행사·신고 반영, 이유 설명
- [x] 서버 API와 학교 서버 자동 배포
- [x] 안드로이드 앱: 지도·길안내·말로 대화·보호자 공유·신고
- [x] AI 비서: 규칙 + 제미나이 도구 호출, 토큰 줄이기, 매일 점검
- [x] 대중교통·저상버스, 들를 곳(경유지)
- [ ] TMAP 도보 경로와 현장 비교 (조선대·문화전당 일대 코스)
- [ ] 유모차 모드 (추후)
