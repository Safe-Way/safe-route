-- 보호자 실시간 위치 보기 (2026-11-06 교수님 피드백 "걷는 사람이 지금 어디 가는지 보호자에게 보여야 한다").
-- 길안내를 시작할 때 "보호자에게 위치 보내기"를 누르면 서버가 공유 번호(token)를 만들고, 앱이 보호자에게 링크를 문자로 보낸다.
-- 앱은 걷는 동안 10초마다 위치를 올리고, 보호자는 링크(웹 지도)로 지금 위치·도착 예상을 본다.
-- 서버가 두 대(프록시 뒤 app-1·app-2)라 서버 메모리에 두면 서로 모르므로 이 표에 잠깐 둔다.
--
-- 개인정보 규칙
--   - 위치는 마지막 한 점만 둔다(지나온 길은 남기지 않음). 새 위치가 오면 덮어쓴다.
--   - 도착·그만두기·만료(시작 3시간 뒤, 또는 30분 동안 위치가 안 옴) 때 위치 칸을 비우고 ended_at을 적는다.
--   - 끝난 지 10분이 지난 줄은 서버가 지운다(보호자가 "도착했어요"를 볼 시간만 남김).
--   - token은 추측할 수 없는 무작위 32자. 링크를 아는 보호자만 본다.
-- 새 표만 만들므로 돌고 있는 서버에 영향이 없다.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS guardian_share (
    token       CHAR(32)     NOT NULL COMMENT '보호자 링크에 들어가는 무작위 32자 (서버가 만듦)',
    guest_id    CHAR(36)     NULL     COMMENT '공유한 게스트 번호 (내 기록 지우기 때 같이 지움)',
    walker_name VARCHAR(30)  NULL     COMMENT '보호자 화면에 보일 이름 (예 "엄마", 사용자가 정함)',
    dest_name   VARCHAR(100) NULL     COMMENT '가는 곳 이름',
    dest_lat    DECIMAL(9,6) NULL,
    dest_lon    DECIMAL(9,6) NULL,
    mode        VARCHAR(20)  NULL     COMMENT '그때 모드 (보호자 화면 안내 문구용)',
    started_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at  DATETIME     NOT NULL COMMENT '이때가 지나면 끝 (시작 3시간 뒤)',
    last_lat    DECIMAL(9,6) NULL     COMMENT '마지막 위치 (한 점만, 끝나면 비움)',
    last_lon    DECIMAL(9,6) NULL,
    last_at     DATETIME     NULL     COMMENT '마지막 위치를 받은 시각. 30분 동안 안 오면 끝',
    remain_m    INT          NULL     COMMENT '남은 거리(m)',
    eta_min     SMALLINT     NULL     COMMENT '도착까지 남은 시간(분, 내 걸음 속도로)',
    off_route   TINYINT      NOT NULL DEFAULT 0 COMMENT '1이면 정한 길에서 벗어남 (보호자 화면에 표시)',
    ended_at    DATETIME     NULL     COMMENT '끝난 시각',
    end_reason  VARCHAR(10)  NULL     COMMENT 'arrived 도착, stopped 그만둠, expired 만료, sos SOS를 눌러 끝남',
    PRIMARY KEY (token),
    KEY idx_guardian_share_guest (guest_id),
    KEY idx_guardian_share_ended (ended_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '보호자 실시간 위치 공유 (걷는 동안만, 마지막 한 점)';
