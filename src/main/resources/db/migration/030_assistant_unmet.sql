-- 비서가 도구가 없어 못 한 요청 모으기 (2026-11-06). "사용자가 원하는데 아직 없는 기능" 목록이 되어 다음에 만들 도구를 정한다.
-- 예) "택시 불러 줘" 12번, "약국 영업시간" 8번 → 다음 도구 1순위. 발표에서 '비서가 쓸수록 필요한 것을 알려 준다'로 보여 준다.
-- 서버(비서 창구)가 넣는다: 도구 없음(③ 못 하는 일), 길찾기와 상관없음(⑤), 알아듣지 못함 때.
-- 개인정보: 게스트 번호·위치·시각은 남기지 않는다. 날짜와 모드만. 요청 말은 전화번호·이름처럼 보이는 것을 서버가 지우고 넣는다.
-- 급한 일(④ 넘어짐·위험)은 이 표가 아니라 바로 112·보호자 안내로 처리한다(여기에 남기지 않음).
-- 새 표만 만들므로 돌고 있는 서버에 영향이 없다.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS assistant_unmet (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    asked_on    DATE         NOT NULL COMMENT '요청한 날짜 (시각은 남기지 않음)',
    mode        VARCHAR(20)  NULL     COMMENT '그때 모드',
    text        VARCHAR(200) NOT NULL COMMENT '요청 말 (전화번호·이름처럼 보이는 것은 지운 것)',
    kind        VARCHAR(20)  NOT NULL COMMENT 'no_tool 할 도구가 없음, off_topic 길찾기와 상관없음, not_understood 못 알아들음',
    topic       VARCHAR(30)  NULL     COMMENT '비서가 붙인 짧은 주제 (택시, 병원 예약, 약국 영업시간 등). 같은 주제끼리 셈',
    offered     VARCHAR(100) NULL     COMMENT '대신 제안한 것 (예: 가까운 택시 타는 곳)',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_assistant_unmet_topic (topic, asked_on)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '비서가 못 한 요청 (다음 기능 후보)';

-- 다음에 만들 도구 후보: 주제별로 몇 번 나왔나
CREATE OR REPLACE VIEW assistant_unmet_top AS
SELECT topic, kind, COUNT(*) AS times, MIN(asked_on) AS first_on, MAX(asked_on) AS last_on
FROM assistant_unmet
WHERE topic IS NOT NULL
GROUP BY topic, kind;
