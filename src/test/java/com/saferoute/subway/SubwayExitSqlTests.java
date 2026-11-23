package com.saferoute.subway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** 학교 DB 값 모양(11-23 확인)에 맞는지: 역 이름 가운뎃점, 버스 노선 "지원151(화순사평)" */
class SubwayExitSqlTests {

    /** exitBuses 정규식 (MySQL REGEXP와 Java 정규식이 이 범위에서는 같게 동작) */
    static boolean busMatches(String route, String bus) {
        return Pattern.compile(SubwayRepository.busRegex(bus)).matcher(route.replace(" ", "")).find();
    }

    @Test
    void busRouteNames() {
        assertThat(busMatches("지원151(화순사평)", "지원151")).isTrue();
        assertThat(busMatches("지원151(화순사평)", "151")).isTrue();
        assertThat(busMatches("첨단09(저)", "첨단09")).isTrue();
        assertThat(busMatches("운림51", "51")).isTrue();
        assertThat(busMatches("지원151(화순사평)", "51")).isFalse();
        assertThat(busMatches("화순217-1", "화순217")).isFalse();
        assertThat(busMatches("화순217-1", "화순217-1")).isTrue();
        assertThat(busMatches("지원45", "45")).isTrue();
    }

    @Test
    void stationNamesWithoutDots() {
        assertThat(SubwayRepository.flat("학동·증심사입구")).isEqualTo("학동증심사입구");
        assertThat(SubwayRepository.flat("학동 증심사입구")).isEqualTo("학동증심사입구");
    }
}
