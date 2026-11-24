package com.saferoute.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 지도 범위 글자 읽기 (시설·신고·행사 창구가 같이 씀) */
class BboxTests {

    @Test
    void readsFourNumbers() {
        assertThat(Bbox.parse("126.91, 35.14,126.93,35.15")).containsExactly(126.91, 35.14, 126.93, 35.15);
    }

    @Test
    void wrongShapeIsNull() {
        assertThat(Bbox.parse(null)).isNull();
        assertThat(Bbox.parse("126.9,35.1")).isNull();
        assertThat(Bbox.parse("126.9,35.1,127,abc")).isNull();
        assertThat(Bbox.parse("126.9,35.1,127,35.2,1")).isNull();
    }

    @Test
    void notANumberIsNull() {
        assertThat(Bbox.parse("NaN,35.1,127,35.2")).isNull();
        assertThat(Bbox.parse("126.9,Infinity,127,35.2")).isNull();
    }

    @Test
    void orderIsLeftToEachEndpoint() {
        assertThat(Bbox.parse("127,35.2,126.9,35.1")).isNotNull(); // 순서 검사는 창구마다 따로
    }
}
