-- 개인 보행 속도 (2026-11-05 팀 결정, 추가안): 길안내 때 실제로 걸은 속도를 저장해 도착 시간을 그 사람 기준으로 계산한다.
-- 로그인이 없으므로 앱이 처음 실행될 때 만든 무작위 게스트 번호(UUID)로 구분한다. 전화번호·기기 번호는 쓰지 않는다.
-- 위치 기록은 서버에 저장하지 않는다는 원칙(만들지 않음 목록)을 지키려고 속도 숫자만 둔다.
--   좌표·걸은 길·출발지·도착지·시각은 저장하지 않는다. 측정 날짜만 남긴다(언제 다니는지 남지 않게).
-- 측정 규칙 (앱이 재고 서버가 한 번 더 거른다)
--   - 길안내(FR-29) 중 300m 이상 걸었을 때만 잰다. 신호 대기처럼 멈춘 시간은 뺀다(moving_s).
--   - 분당 20~150m 밖은 버스·자전거를 탔거나 GPS가 튄 것으로 보고 받지 않는다.
--   - 쓰는 속도(speed_m_min)는 최근 측정 10개의 중간값. 3개가 모이기 전에는 모드 기본 속도(6.2: 일반 80, 어린이 70, 노인 60, 휠체어 50)를 쓴다.
--   - 게스트·모드마다 측정은 최근 20개만 남기고 오래된 것은 지운다.
-- "내 기록 지우기"를 누르면 walk_profile 줄을 지우고, 측정 줄도 같이 지워진다.
-- 새 표만 만들므로 돌고 있는 서버에 영향이 없다.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS walk_profile (
    guest_id    CHAR(36)     NOT NULL COMMENT '앱이 처음 실행 때 만든 무작위 번호(UUID). 사람을 알아볼 수 있는 값이 아님',
    mode        VARCHAR(20)  NOT NULL COMMENT 'adult, child, senior, wheelchair (가족이 대신 찾을 수 있어 모드마다 따로)',
    speed_m_min DECIMAL(5,1) NULL     COMMENT '지금 쓰는 걸음 속도(분당 m) = 최근 측정 10개 중간값. 측정 3개 전에는 NULL(모드 기본값 사용)',
    samples     SMALLINT     NOT NULL DEFAULT 0 COMMENT '지금까지 받은 측정 수',
    walked_m    INT          NOT NULL DEFAULT 0 COMMENT '지금까지 잰 거리 합 (참고)',
    moving_s    INT          NOT NULL DEFAULT 0 COMMENT '지금까지 움직인 시간 합(초, 멈춘 시간 뺌)',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (guest_id, mode)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '개인 보행 속도 (게스트·모드별)';

-- 한 번 잰 값. 좌표와 시각 없이 거리·움직인 시간·날짜만
CREATE TABLE IF NOT EXISTS walk_speed_sample (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    guest_id    CHAR(36)     NOT NULL,
    mode        VARCHAR(20)  NOT NULL,
    distance_m  INT          NOT NULL COMMENT '잰 거리 (300m 이상)',
    moving_s    INT          NOT NULL COMMENT '움직인 시간(초, 멈춘 시간 뺌)',
    speed_m_min DECIMAL(5,1) NOT NULL COMMENT 'distance_m / moving_s * 60 (분당 20~150m만 받음)',
    measured_on DATE         NOT NULL COMMENT '잰 날짜 (시각은 적지 않음)',
    PRIMARY KEY (id),
    KEY idx_walk_speed_sample_guest (guest_id, mode, id),
    CONSTRAINT fk_walk_speed_sample_profile FOREIGN KEY (guest_id, mode)
        REFERENCES walk_profile (guest_id, mode) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '보행 속도 측정 (최근 20개)';
