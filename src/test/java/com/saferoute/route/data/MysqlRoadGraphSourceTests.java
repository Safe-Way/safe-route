package com.saferoute.route.data;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 도로망 읽기에서 DB 없이 확인할 수 있는 부분 */
class MysqlRoadGraphSourceTests {

    @Test
    void unpavedOnlyWhenConfirmed() {
        assertThat(MysqlRoadGraphSource.isUnpaved("dirt")).isTrue();
        assertThat(MysqlRoadGraphSource.isUnpaved("Gravel ")).isTrue();
        assertThat(MysqlRoadGraphSource.isUnpaved("cobblestone")).isTrue();
        // 포장, 다진 흙, 모름은 지나간다
        assertThat(MysqlRoadGraphSource.isUnpaved("asphalt")).isFalse();
        assertThat(MysqlRoadGraphSource.isUnpaved("compacted")).isFalse();
        assertThat(MysqlRoadGraphSource.isUnpaved(null)).isFalse();
    }

    @Test
    void parsesLineString() {
        assertThat(MysqlRoadGraphSource.parseLineString("LINESTRING(126.93 35.14,126.931 35.141)"))
                .containsExactly(new double[] {126.93, 35.14}, new double[] {126.931, 35.141});
    }
}
