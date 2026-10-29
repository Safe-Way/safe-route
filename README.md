# 세잎지도 (safe-route)

밤길·혼자 걷는 길에서 **가장 짧은 길 대신 더 안전한 길**을 알려 주는 도보 길찾기 서비스입니다.
가로등·보안등, CCTV, 비상벨, 편의점, 아동안전지킴이집 같은 공공데이터로 길마다 점수를 매기고,
조금 돌아가더라도 밝고 사람 눈이 닿는 길을 고릅니다.

- 대상: 전국 (시연 지역은 조선대학교 · 광주 동구)
- 사용자 모드: 기본 · 여성 안심 · 아이 · 어르신

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
| CCTV | 행정안전부 지방행정 인허가 데이터, 광주광역시 | 약 39만 |
| 비상벨 · 편의점 · 지킴이집 · 무더위쉼터 · 한파쉼터 · 공중화장실 | 생활안전지도 | 약 27만 |

생활안전지도 데이터는 **공공누리 4유형**(출처표시 · 상업적 이용 금지 · 변경 금지)입니다.

## 실행 방법

### 1. 설정 파일 만들기

프로젝트 폴더에 `.env` 파일을 만들고 아래 값을 채웁니다. **`.env`는 git에 올리지 않습니다.**

```properties
DB_URL=jdbc:mysql://localhost:3306/safe_route
DB_USERNAME=safe_route
DB_PASSWORD=

DATA_GO_KR_KEY=
SAFEMAP_KEY=
KAKAO_REST_KEY=
SAFETYDATA_POLICE_KEY=
SAFETYDATA_SECURITYLIGHT_KEY=
SAFETYDATA_STREETLIGHT_KEY=
```

### 2. DB 표 만들기

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

### 5. 테스트

```bash
./mvnw test
```

## 폴더 구조

```
src/main/java/com/saferoute/
  SafeRouteApplication.java
  ingest/            공공데이터를 facility 표로 옮기는 코드
src/main/resources/
  application.yml          설정 (키는 .env에서 읽음)
  application-import.yml   데이터 적재용 프로필
  db/schema.sql            DB 표
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
