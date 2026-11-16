-- ODsay 대중교통 길찾기를 하루에 몇 번 불렀는지 센다 (2026-11-16).
-- 전에는 서버 메모리에서 서버마다 따로 셌다. 서버가 두 대(app-1, app-2)라 합치면 하루 한도의 두 배까지 나갈 수 있었고,
-- 배포·재시작 때마다 0부터 다시 셌다. 이 표 한 줄을 두 서버가 같이 쓴다 (OdsayCallCounter).
-- 날짜는 한국 날짜. 한 줄 = 하루. 좌표·사용자 정보는 남기지 않는다.
-- 표가 없거나 DB를 못 쓰면 서버는 예전처럼 메모리로 센다. 새 표만 만들므로 돌고 있는 서버에 영향이 없다.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS odsay_call_count (
    day    DATE NOT NULL COMMENT '한국 날짜',
    calls  INT  NOT NULL DEFAULT 0 COMMENT '그날 ODsay를 부른 수 (두 서버 합)',
    PRIMARY KEY (day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'ODsay 하루 부른 수 (서버 두 대가 같이 셈)';
