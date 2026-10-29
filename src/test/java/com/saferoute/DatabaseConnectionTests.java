package com.saferoute;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MySQL에 붙는지, 그리고 이 프로젝트에 필요한 공간 함수가 되는지 확인한다.
 */
@SpringBootTest
class DatabaseConnectionTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void connectsAndSupportsSpatialFunctions() {
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        System.out.println("MySQL 버전: " + version);

        // 조선대 정문 근처 두 점 사이 거리(m). 공간 함수가 되면 약 100m가 나온다.
        Double meters = jdbc.queryForObject(
                "SELECT ST_Distance_Sphere(POINT(126.9300, 35.1400), POINT(126.9311, 35.1400))",
                Double.class);
        System.out.println("공간 함수 거리(m): " + meters);

        assertThat(meters).isBetween(90.0, 110.0);
    }
}
