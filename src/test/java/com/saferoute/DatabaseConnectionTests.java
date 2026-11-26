package com.saferoute;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MySQL에 붙는지, 그리고 이 프로젝트에 필요한 공간 함수가 되는지 확인한다.
 * <p>어떻게: 가짜가 없다. @SpringBootTest 로 서버 설정 전체를 띄우고 DB_URL 이 가리키는 진짜 MySQL에 SQL을 보낸다.
 * CI에서는 ci.yml 이 띄운 빈 MySQL 8.0(학교 서버와 같은 판)에 붙는다. 건너뛰는 장치가 없어서,
 * 로컬에서 .env(DB_URL 등)가 없거나 DB에 못 붙으면 이 시험은 실패한다 (그때는 설정부터 본다).
 * <p>왜: 경로·신고·행사 SQL이 모두 ST_Distance_Sphere·MBRContains 같은 공간 함수를 쓴다.
 * DB가 공간 함수를 못 쓰면(예: 다른 DB나 옛 판) 서버는 떠도 길찾기·신고가 모두 SQL 오류로 실패한다.
 */
@SpringBootTest
@DisplayName("DB 연결")
class DatabaseConnectionTests {

    @Autowired
    JdbcTemplate jdbc;

    // 연결만 보면 공간 함수가 빠진 DB를 못 잡으므로 거리 계산까지 해 본다.
    // 버전을 찍어 두는 것은 CI 로그에서 어느 판 MySQL로 돌았는지 보려는 것이다
    @Test
    @DisplayName("DB에 연결되고 공간 함수를 쓸 수 있다")
    void connectsAndSupportsSpatialFunctions() {
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        System.out.println("MySQL 버전: " + version);

        // 조선대 정문 근처 두 점 사이 거리(m). 공간 함수가 되면 약 100m가 나온다.
        Double meters = jdbc.queryForObject(
                "SELECT ST_Distance_Sphere(POINT(126.9300, 35.1400), POINT(126.9311, 35.1400))",
                Double.class);
        System.out.println("공간 함수 거리(m): " + meters);

        // 경도 0.0011도 × 위도 35.14도에서 1도 약 91km ≈ 100m. 구면 계산 반올림을 생각해 ±10m 를 허용한다
        assertThat(meters).isBetween(90.0, 110.0);
    }
}
