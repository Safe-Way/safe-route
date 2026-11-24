# 공개 배포 준비: 본인 확인·요청 제한과 DB 개선 방안

2026-11-23 · 많은 사람이 쓰게 될 때를 대비한 일. 1-1·1-2는 이 PR에서 만들었고, 2번은 방안이다.
학교 서버 한 대(DB·앱이 학교 서버에만 있음) 문제는 이 문서에서 뺐다.

## 1-1 게스트 본인 확인 (만듦)

- **문제**: 게스트 번호만 알면 그 사람의 내 정보·기록을 보거나 지울 수 있음 (`GET/DELETE /api/me?guestId=…`)
- **방법**: 앱이 게스트 번호와 함께 비밀 토큰을 보냄 → 서버는 그 번호로 처음 온 토큰을 등록 → 다음부터 같은 토큰인지 확인
- **코드**: `com.saferoute.guest.GuestAuthFilter` (모든 `/api` 앞), `GuestTokenStore`, 표 `guest_token` (037, 토큰 원문은 저장 안 하고 SHA-256만)
- **번호를 찾는 곳**: `X-Guest-Id` 헤더 · 주소·폼의 `guestId` · JSON 본문의 `guestId`. 둘 이상이 다르면 400 `GUEST_MISMATCH`
- **번호를 주소에서 빼기**: 헤더로만 와도 창구가 `guestId` 파라미터로 받음 → 창구 코드는 그대로, 서버 접속 기록에 번호가 안 남음

| 경우 | `enforce=false` (지금) | `enforce=true` (앱 바뀐 뒤) |
| --- | --- | --- |
| 토큰 맞음 / 처음 온 토큰 | 통과 (처음이면 등록) | 통과 |
| 토큰 없음 | 통과 | 401 `GUEST_TOKEN_REQUIRED` |
| 토큰 틀림 | 401 `GUEST_TOKEN_WRONG` | 401 `GUEST_TOKEN_WRONG` |
| `guest_token` 표 없음·DB 오류 | 통과 (경고 로그 한 번) | 통과 |
| JSON 본문 256KB 넘음 | 413 `TOO_LARGE` (본문 속 번호를 확인 없이 못 쓰게) | 같음 |

**켜는 순서**

1. 이 PR 배포 (`enforce=false`라 지금 앱은 그대로 동작)
2. 학교 DB에 guest_token 표 만들기: `db/flyway/V37__guest_token.sql`이 배포 때 Flyway로 들어감 (Flyway #315 먼저, 표가 생기기 전에는 확인을 건너뜀)
3. 앱(D, 민수): 아래 표대로 바꾼 버전 배포
4. 새 앱이 대부분 깔린 뒤 저장소 Settings → Variables에 `GUEST_AUTH_ENFORCE=true` → 다음 배포부터 토큰 없는 요청을 막음

**앱이 할 일**

| 할 일 | 내용 |
| --- | --- |
| 토큰 만들기 | 처음 실행 때 `SecureRandom` 32바이트 → base64url (43자). 게스트 번호와 같이 기기에만 저장 (암호화 저장 권장) |
| 헤더 보내기 | 모든 `/api` 요청에 `X-Guest-Id`, `X-Guest-Token` |
| 주소에서 빼기 | `/api/me?guestId=…`, `/api/walk-speed?guestId=…`, 오늘 제안 등 → 헤더로만 |
| 401 처리 | `GUEST_TOKEN_REQUIRED` → 업데이트 안내, `GUEST_TOKEN_WRONG` → 내 정보 화면을 비우고 다시 시작 안내 |
| 429 처리 | `Retry-After`(초) 동안 다시 보내지 않고 메시지 표시 |

**한계**

- 처음 쓰는 사람이 주인: 번호를 먼저 알아낸 사람이 토큰을 걸 수 있음 (번호가 무작위 UUID라 어렵고, 헤더로 옮기면 기록에도 안 남음)
- 앱 데이터를 지우면 토큰도 사라짐 → 새 게스트 번호로 처음부터

## 1-2 요청 수 제한 (만듦)

- **코드**: `com.saferoute.guest.RateLimitFilter`, `RateLimiter` (구간마다 세기, 서버 한 대 안에서)
- **세는 기준**: 게스트 번호마다 + IP마다 넉넉한 상한 (번호를 바꿔 가며 부르는 것 막기)
- **IP**: 학교 nginx가 넘긴 `X-Real-IP`. 바로 앞이 우리 프록시(사설·루프백 주소)일 때만 믿음
- **안 세는 것**: `/api` 밖, 서버 안에서 `X-Real-IP` 없이 부르는 요청(시험·점검)
- **넘으면**: 429 `TOO_MANY_REQUESTS` + `Retry-After`(초) + "요청이 너무 많아요. N초 뒤에 다시 해 주세요"
- **끄기**: 저장소 Variables `RATE_LIMIT_ENABLED=false`

| 창구 | 게스트마다 | IP마다 | 왜 |
| --- | --- | --- | --- |
| `/api` 전체 | 분당 120 | 분당 600 | 통신사 NAT로 여러 사람이 한 IP일 수 있어 IP는 넉넉히 |
| `POST /api/assistant` | 분당 20, 하루 300 | 분당 60 | 제미나이 비용 |
| `POST /api/reports` | 시간당 10 | 시간당 30 | 사진 3장·16MB까지 |

값은 `application.yml`의 `rate-limit`. 프록시가 같은 IP를 늘 같은 서버로 보내 거의 맞지만, 두 대를 합치면 최대 두 배까지 갈 수 있음.

## 2. DB 개선 방안 (학교 서버 한 대 문제 뺌)

학교 DB를 바꾸는 일(표·칸 변경, 지우기)은 적용 전에 팀 확인과 백업을 먼저 한다.

| # | 항목 | 지금 | 방안 | 크기 |
| --- | --- | --- | --- | --- |
| 1 | 자동 백업 | 없음 (워크플로는 ci·deploy·health뿐) | 학교 서버 cron 매일 03:30 `mysqldump --single-transaction` → gzip, 14일 보관 · 주 1회 서버 밖으로 복사 · 한 달에 한 번 빈 DB에 넣어 표 줄 수 비교(복구 시험) | 작음 |
| 2 | 사진 크기 | 원본 그대로 `road_report_photo.data`(mediumblob), 한 장 5MB·신고당 3장 | **먼저**: 받을 때 긴 변 1600px·JPEG 품질 80으로 줄여 저장 (수 MB → 수백 KB, 모델은 224px로 보므로 영향 없음) | 작음 |
| 3 | 사진 저장 위치 | DB 안 | **다음**: 오브젝트 스토리지(S3 호환)에 두고 DB에는 `storage_key`·크기·형식만 · 옛 사진 옮기는 스크립트 · 보기는 서명된 주소 · 신고 정리(04:30)가 스토리지 파일도 지움 | 큼 |
| 4 | 마이그레이션 | SQL 37개를 손으로 적용, 다시 돌리면 실패하는 것 있음(`ADD COLUMN`) | Flyway: 지금 학교 DB는 37로 baseline, 새 파일은 `V038__…`부터 · 서버 시작 때 자동 적용(두 대 중 한 대만, Flyway 잠금) · 새 DB는 `schema.sql` 뒤 같은 baseline | 중간 |
| 5 | 연결 풀 | Hikari 기본(대당 10), 설정 없음 | `maximum-pool-size`·`connection-timeout`(3초) 명시 · 학교 MySQL `max_connections` 확인 → 서버 수 × 풀 + 적재 스크립트 < 상한 · 느린 쿼리 로그 1주 모아 인덱스 추가 | 작음 |
| 6 | 게스트 번호 칸 | `CHAR(36)` utf8mb4 (비교가 대소문자 무시·무거움) | 먼저 모든 창구에서 소문자로 맞추기 → 대소문자만 다른 중복 확인 → 7개 표(`user_profile`·`walk_profile`·`walk_speed_sample`·`trip_log`·`user_map_layer`·`guardian_share`·`guest_token`) `CHAR(36) CHARACTER SET ascii COLLATE ascii_bin` · `BINARY(16)`은 코드를 다 바꿔야 해 나중 | 중간 |
| 7 | 값 검사 | `mode`는 서버 코드만 검사 | `CHECK (mode IN ('adult','child','senior','wheelchair'))` (`walk_profile`·`walk_speed_sample`·`user_profile`) · `walk_profile → user_profile` 외래 키는 부모 줄이 없을 수 있어 두지 않음 | 작음 |
| 8 | 기록 정리 | 함: 이동 기록 게스트당 200건, 걷기 측정 최근 몇 개, 신고 매일 04:30, 보호자 공유 1분마다 · 안 함: `assistant_unmet`, 오래 안 쓴 게스트, `odsay_call_count` | 매일 정리에 더함: `assistant_unmet` 90일 · 1년 동안 안 쓴 게스트(`guest_token.last_used_on`)는 내 기록 지우기와 같은 코드로 모두 지움(개인정보 보관 기간) · `odsay_call_count` 30일 | 작음 |

**추천 순서**: 1 백업 → 2 사진 줄이기 → 5 연결 풀·느린 쿼리 → 4 Flyway → 8 기록 정리 → 6·7 게스트 칸·값 검사 → 3 스토리지 이전.
백업이 먼저인 이유: 4·6·7은 학교 DB 구조를 바꾸므로 되돌릴 수 있어야 함.
