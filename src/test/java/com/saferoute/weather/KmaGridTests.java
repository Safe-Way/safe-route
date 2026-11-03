package com.saferoute.weather;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 기상청이 공개한 격자 번호와 맞는지 확인 */
class KmaGridTests {

    @Test
    void seoulCityHall() {
        assertThat(KmaGrid.from(37.5665, 126.9780)).isEqualTo(new KmaGrid(60, 127));
    }

    @Test
    void chosunUniversity() {
        assertThat(KmaGrid.from(35.1401, 126.9336)).isEqualTo(new KmaGrid(60, 74));
    }
}
