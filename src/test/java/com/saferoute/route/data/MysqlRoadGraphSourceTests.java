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
    void sidewalkFromTags() {
        assertThat(MysqlRoadGraphSource.sidewalk("right", null, null, null)).isEqualTo("right");
        assertThat(MysqlRoadGraphSource.sidewalk("none", null, null, null)).isEqualTo("no");
        // sidewalk가 없으면 sidewalk:both·left·right. separate(따로 그림)도 그쪽에 인도가 있다
        assertThat(MysqlRoadGraphSource.sidewalk(null, "separate", null, null)).isEqualTo("both");
        assertThat(MysqlRoadGraphSource.sidewalk(null, null, "no", "separate")).isEqualTo("right");
        assertThat(MysqlRoadGraphSource.sidewalk(null, null, "no", "no")).isEqualTo("no");
        // 한쪽만 알면 다른 쪽을 몰라 정하지 않는다
        assertThat(MysqlRoadGraphSource.sidewalk(null, null, null, "yes")).isNull();
        assertThat(MysqlRoadGraphSource.sidewalk(null, null, null, null)).isNull();
    }

    @Test
    void parsesLineString() {
        assertThat(MysqlRoadGraphSource.parseLineString("LINESTRING(126.93 35.14,126.931 35.141)"))
                .containsExactly(new double[] {126.93, 35.14}, new double[] {126.931, 35.141});
    }

    @Test
    void badAttachEdgeIsSkippedNotFatal() {
        // 지하 출구 한 줄을 못 읽어도 도로망 전체를 읽는다 (그 출구만 잇지 않음)
        assertThat(MysqlRoadGraphSource.attachEdge("127396")).isEqualTo(127396L);
        assertThat(MysqlRoadGraphSource.attachEdge("12.5")).isNull();
        assertThat(MysqlRoadGraphSource.attachEdge("없음")).isNull();
        assertThat(MysqlRoadGraphSource.attachEdge(null)).isNull();
    }
}
