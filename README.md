# 세잎지도 (safe-route)

[![CI/CD](https://github.com/Safe-Way/safe-route/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Safe-Way/safe-route/actions/workflows/ci.yml)

밤길·혼자 걷는 길에서 **가장 짧은 길 대신 더 안전한 길**을 알려 주는 도보 길찾기 서비스입니다.
가로등·보안등, CCTV, 비상벨, 편의점, 아동안전지킴이집 같은 공공데이터로 길마다 점수를 매기고,
조금 돌아가더라도 밝고 사람 눈이 닿는 길을 고릅니다.

- 대상: 전국 (시연 지역은 조선대학교 · 광주 동구)
- 사용자 모드: 어린이·청소년 · 청년·중년 · 노인 (모든 모드에서 '안심 강화'를 켤 수 있음)
- 요구사항: 문서 전용 저장소 [safe-route-docs](https://github.com/Safe-Way/safe-route-docs)의 [requirements.md](https://github.com/Safe-Way/safe-route-docs/blob/develop/requirements.md) (탭 7개로 나뉜 목차, 변경 이력: [CHANGELOG.md](https://github.com/Safe-Way/safe-route-docs/blob/develop/CHANGELOG.md))

## 주요 기능

| # | 기능 | 내용 |
|---|---|---|
| 1 | 이유를 설명하는 안전 경로 | 최단 경로와 안전 경로를 함께 보여 주고 "180m(2분) 더 돌지만 CCTV +12대"처럼 차이를 설명 |
| 2 | 상황 자동 판단 | 밤·비·폭염·한파·미세먼지를 알아서 판단해 길 고르는 기준을 바꿈 |
| 3 | 나이대 모드 | 어린이·청소년 / 청년·중년 / 노인, 그리고 모든 모드에서 켜는 '안심 강화' |
| 4 | 귀가 지킴이 | 걷는 동안 비상벨·CCTV까지 남은 거리 안내, SOS 경보음·보호자 문자·취소 |
| 5 | 전국 안전 데이터 | 공공데이터 약 160만 건으로 길마다 점수, 데이터가 빈 곳은 "데이터 부족" 표시 |
| 6 | 효과 증명 | 조선대 주변 30쌍에서 TMAP 경로와 비교 평가 |

자세한 내용은 [요구사항 분석서](https://github.com/Safe-Way/safe-route-docs/blob/develop/requirements.md)에 있습니다. 처음이라면 [0. 한눈에 보기](https://github.com/Safe-Way/safe-route-docs/blob/develop/requirements/00-overview.md)부터 보세요.

## 팀

- **팀:** 2팀
- **구성원:** 김재휘, 김채림, 정원일, 박민수
- **조직:** https://github.com/Safe-Way

## 어떻게 길을 고르나

길 한 구간의 비용은 길이에 상황별 감점을 더해 계산합니다.

```
비용 = 길이 × (1 + Σ 가중치(시간대·날씨·모드) × 감점)
```

- 감점 예: 어두움, CCTV 없음, 도움 받을 곳 멂, 계단, 큰 차도 횡단
- 최단 경로보다 너무 돌아가지 않도록 상한을 둡니다 (기본 1.3배, 어르신 1.2배, 여성 안심 1.4배)
- 비교 기준은 TMAP 도보 경로입니다 (카카오 도보 API는 제휴사 전용이라 쓸 수 없음)

## 기술 스택

| 구분 | 사용 |
|---|---|
| 서버 | Java 21, Spring Boot 4.1, Maven |
| DB | MySQL 8 (공간 인덱스, `ST_Distance_Sphere`) |
| 도로망 | OpenStreetMap |
| 데이터 수집 | Python 3 (표준 라이브러리만) |

## 데이터

| 데이터 | 출처 | 건수 |
|---|---|---|
| 가로등 · 보안등 · 경찰 | 재난안전데이터 공유플랫폼 (행정안전부 공통POI) | 보안등 약 95만, 가로등 약 225만 |
| 보안등 | 공공데이터포털 전국보안등정보표준데이터 (지자체 직접 등록) | 약 186만 |
| 한파쉼터 | 재난안전데이터 공유플랫폼 (DSSP-IF-10804) | 약 5.4만 |
| CCTV | 행정안전부 지방행정 인허가 데이터, 광주광역시 | 약 39만 |
| 비상벨 · 편의점 · 지킴이집 · 무더위쉼터 · 공중화장실 | 생활안전지도 | 약 26만 |

생활안전지도 데이터는 **공공누리 4유형**(출처표시 · 상업적 이용 금지 · 변경 금지)입니다.

## DB 표

시설은 종류마다 표 하나씩 있습니다. 모든 표에 공통 칸이 있고, 종류마다 필요한 칸이 붙습니다.

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
-- 조선대 주변 지구대·파출소
SELECT kind, name, phone, address FROM police
WHERE MBRContains(ST_GeomFromText('POLYGON((126.92 35.13, 126.94 35.13, 126.94 35.15, 126.92 35.15, 126.92 35.13))'), geom)
  AND kind IN ('지구대', '파출소');

-- 조선대 500m 안 시설 수
SELECT type, COUNT(*) FROM facility_all
WHERE ST_Distance_Sphere(geom, POINT(126.9306, 35.1408)) < 500 GROUP BY type;
```

## 실행 방법

### 1. 설정 파일 만들기

`.env.example`을 복사해 `.env`를 만들고 빈 값을 채웁니다. 키와 DB 비밀번호는 팀 채팅으로 받습니다.
**`.env`는 git에 올리지 않습니다.**

```bash
cp .env.example .env
```

- 기본은 **학교 DB**입니다. 학교 네트워크 안에서만 접속됩니다.
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
  SafeRouteApplication.java
  ingest/            공공데이터를 종류별 시설 표로 옮기는 코드
src/main/resources/
  application.yml          설정 (키는 .env에서 읽음)
  application-import.yml   데이터 적재용 프로필
  db/schema.sql            DB 표 (종류별 시설 표, 뷰, 적재 현황)
  db/migration/            한 번만 돌리는 DB 변경 기록
scripts/             데이터 내려받기 스크립트
data/raw/            내려받은 원본 (git에 올리지 않음)
조선대_데이터확인/    시연 지역 데이터 확인 기록
```

## 진행 상황

- [x] 공공데이터 수집 · DB 적재 (시설 약 160만 건)
- [ ] OSM 도로망 적재
- [ ] 안전 점수 계산 · 경로 탐색
- [ ] 길찾기 API
- [ ] 안드로이드 앱
- [ ] TMAP 도보 경로와 비교 평가
