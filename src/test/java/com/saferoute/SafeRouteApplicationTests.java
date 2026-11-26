package com.saferoute;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 서버 전체 띄우기
 * <p>무엇을: 모든 빈(컨트롤러·저장소·설정)이 서로 맞물려 스프링 컨텍스트가 뜨는지. 본문은 비어 있고 뜨는 것 자체가 시험이다.
 * <p>어떻게: 가짜가 없다. @SpringBootTest 로 진짜 설정과 DB_URL 이 가리키는 진짜 MySQL을 쓴다 (건너뛰는 장치 없음, DB가 없으면 실패).
 * CI(ci.yml)는 빈 MySQL을 띄우고 FLYWAY_ENABLED=true 로 이 시험만 먼저 돌린다. 컨텍스트가 뜨는 동안 Flyway가 DB를 쓰는 빈들보다
 * 먼저 db/flyway 의 V 파일(V36 바탕부터 차례로)을 빈 DB에 적용해 표를 만들고, 그 뒤 schema.sql 과 같은지 검사한 다음
 * 나머지 시험(./mvnw verify)이 그 표 위에서 돈다. 로컬은 Flyway가 기본으로 꺼져 있어 표를 만들지 않는다.
 * <p>왜: 배포도 같은 길(앱이 뜰 때 Flyway 적용)로 학교 DB를 바꾼다. V 파일에 SQL 오류가 있거나 빈 설정·빈 연결이 틀리면
 * 여기서 먼저 깨지고, 그대로 main 에 머지되면 배포한 서버가 안 뜬다.
 */
@SpringBootTest
@DisplayName("서버 전체 띄우기")
class SafeRouteApplicationTests {

    @Test
    @DisplayName("서버가 뜬다 (CI는 이때 Flyway로 DB 표를 만든다)")
    void contextLoads() {
        // 일부러 비워 둔다: 컨텍스트가 못 뜨면 이 메서드에 오기 전에 실패한다
    }
}
