package com.saferoute;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@DisplayName("서버 전체 띄우기")
class SafeRouteApplicationTests {

    @Test
    @DisplayName("서버가 뜬다 (CI는 이때 Flyway로 DB 표를 만든다)")
    void contextLoads() {
    }
}
