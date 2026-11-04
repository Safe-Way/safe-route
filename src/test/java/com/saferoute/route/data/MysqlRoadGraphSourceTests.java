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
    void surfaceFromRoadTypeWhenUnknown() {
        // 재질이 있으면 재질이 먼저
        assertThat(MysqlRoadGraphSource.classify("dirt", "residential", null, null)).isEqualTo(MysqlRoadGraphSource.Surface.UNPAVED);
        assertThat(MysqlRoadGraphSource.classify("concrete", "track", "grade3", null)).isEqualTo(MysqlRoadGraphSource.Surface.PAVED);
        // 재질 모름: 인도·골목·차도는 포장
        assertThat(MysqlRoadGraphSource.classify(null, "footway", null, null)).isEqualTo(MysqlRoadGraphSource.Surface.PAVED);
        assertThat(MysqlRoadGraphSource.classify(null, "residential", null, null)).isEqualTo(MysqlRoadGraphSource.Surface.PAVED);
        // 농로: grade1만 포장, grade2~5·없음은 비포장
        assertThat(MysqlRoadGraphSource.classify(null, "track", "grade1", null)).isEqualTo(MysqlRoadGraphSource.Surface.PAVED);
        assertThat(MysqlRoadGraphSource.classify(null, "track", "grade4", null)).isEqualTo(MysqlRoadGraphSource.Surface.UNPAVED);
        assertThat(MysqlRoadGraphSource.classify(null, "track", null, null)).isEqualTo(MysqlRoadGraphSource.Surface.UNPAVED);
        // 산길: 등산 난이도가 있으면 산행 길(재질과 상관없이), 없으면 확인 안 됨
        assertThat(MysqlRoadGraphSource.classify("asphalt", "path", null, "hiking")).isEqualTo(MysqlRoadGraphSource.Surface.TRAIL);
        assertThat(MysqlRoadGraphSource.classify(null, "path", null, null)).isEqualTo(MysqlRoadGraphSource.Surface.UNCONFIRMED);
    }

    @Test
    void fordMeansSteppingStonesOrWaterCrossing() {
        assertThat(MysqlRoadGraphSource.isFord("stepping_stones")).isTrue();
        assertThat(MysqlRoadGraphSource.isFord("yes")).isTrue();
        assertThat(MysqlRoadGraphSource.isFord("no")).isFalse();
        assertThat(MysqlRoadGraphSource.isFord(null)).isFalse();
    }

    @Test
    void parsesLineString() {
        assertThat(MysqlRoadGraphSource.parseLineString("LINESTRING(126.93 35.14,126.931 35.141)"))
                .containsExactly(new double[] {126.93, 35.14}, new double[] {126.931, 35.141});
    }
}
