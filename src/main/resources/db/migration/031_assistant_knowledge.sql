-- 비서 지식 조각 (비서 토큰 줄이기 R4 증강검색, 2026-11-09, docs/assistant/tool_selection_rag.md).
-- "내 기록 어디에 저장돼?", "AI가 내 위치 알아?", "휠체어 모드는 뭐가 달라?"처럼 도구가 아니라 우리가 정해 둔 설명으로
-- 답해야 하는 질문을 위해, 설명을 규칙(SYSTEM)에 다 넣지 않고 따로 두었다가 필요할 때만 찾아 붙인다.
-- 서버(C)가 MATCH(keywords, body) AGAINST(…)로 점수가 기준을 넘는 것 3개까지, 합 900자 이하만 [참고]로 붙인다.
-- 내용은 docs/assistant/knowledge.json 에 두고 scripts/load_knowledge.py 로 넣는다 (공용 설명만, 사용자 개인 자료는 넣지 않음).
-- 새 표만 만들므로 돌고 있는 서버에 영향이 없다.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS assistant_knowledge (
    id          INT          NOT NULL COMMENT '번호 (knowledge.json 의 id)',
    topic       VARCHAR(40)  NOT NULL COMMENT '짧은 주제 (예: 기록 저장, AI에 보내는 것, 휠체어 기준)',
    keywords    VARCHAR(300) NOT NULL COMMENT '찾을 때 쓸 말 (쉼표로 나눔)',
    body        VARCHAR(400) NOT NULL COMMENT '비서가 그대로 근거로 쓸 설명 (300자 이하로 씀)',
    source      VARCHAR(200) NOT NULL COMMENT '근거 문서 (consent.md 1번, route-weights.md 2절 등)',
    updated_at  DATE         NOT NULL COMMENT '고친 날',
    PRIMARY KEY (id),
    FULLTEXT INDEX ft_assistant_knowledge (keywords, body) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '비서 지식 조각 (앱에 대한 설명, 증강검색)';
