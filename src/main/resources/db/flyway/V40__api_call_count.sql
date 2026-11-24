-- 외부 API 하루 호출 수 (2026-11-24, 공개 배포 준비: 사용량 알림).
-- 서버 두 대가 같은 줄에 더해 합친 수를 센다. ODsay 는 한도를 지키는 odsay_call_count(035)를 그대로 쓰고,
-- 여기는 제미나이(gemini)·카카오(kakao)처럼 세기만 하는 것. 한도의 80%가 넘으면 health.yml 이 usage-high 이슈를 연다.
-- 매일 정리(RecordCleanup)가 90일 지난 줄을 지운다.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS api_call_count (
    day   DATE        NOT NULL COMMENT '한국 날짜',
    api   VARCHAR(20) NOT NULL COMMENT 'gemini, kakao',
    calls INT         NOT NULL DEFAULT 0 COMMENT '그날 부른 수 (서버 두 대 합)',
    PRIMARY KEY (day, api)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '외부 API 하루 호출 수 (V40)';
