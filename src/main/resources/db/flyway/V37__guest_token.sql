-- 게스트 본인 확인 토큰 (2026-11-23, 공개 배포 준비 1-1).
-- 지금은 게스트 번호(UUID)만 알면 그 사람의 내 정보·기록을 보거나 지울 수 있다(/api/me?guestId=…).
-- 앱이 처음 실행 때 게스트 번호와 함께 비밀 토큰(무작위 32바이트 이상)을 만들어 X-Guest-Token 헤더로 보내고,
-- 서버는 그 게스트 번호로 처음 온 토큰을 등록한 뒤(처음 쓰는 사람이 주인) 다음부터 같은 토큰인지 확인한다.
-- 토큰 원문은 저장하지 않고 SHA-256 16진수만 둔다. 서버 설정 guest-auth.enforce=false 인 동안은
-- 토큰이 없는 요청도 받는다(지금 앱이 토큰을 보내기 전까지). 틀린 토큰은 그때도 막는다.
-- 새 표만 만들므로 돌고 있는 서버에 영향이 없다. 이 표가 없으면 서버는 확인을 건너뛴다(경고 로그).
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS guest_token (
    guest_id     CHAR(36)  NOT NULL COMMENT '앱이 처음 실행 때 만든 무작위 번호(UUID)',
    token_hash   CHAR(64)  NOT NULL COMMENT 'X-Guest-Token 의 SHA-256 16진수 (원문은 저장 안 함)',
    created_at   DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '처음 등록한 시각',
    last_used_on DATE      NULL     COMMENT '마지막으로 확인한 날짜 (하루 한 번만 고침)',
    PRIMARY KEY (guest_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '게스트 본인 확인 토큰 (037)';
