package com.saferoute.route.engine.score;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.saferoute.route.engine.facility.Poi;

/** 밤 도움 시설 고르기 (A파트 정의: 지킴이집·건물 안 비상벨 제외) */
class RouteScorerTests {

    static Poi bell(String sub, Integer indoor) {
        return new Poi("bell", sub, indoor, 0, 0, 0, 1440, "매일", null, null, null, null, null, null);
    }

    @Test
    void nightHelpLeavesOutShopsAndIndoorBells() {
        assertThat(RouteScorer.nightHelp(Poi.at("guard_house", 0, 0))).isFalse();
        assertThat(RouteScorer.nightHelp(bell("화장실", 1))).isFalse();
        assertThat(RouteScorer.nightHelp(bell("기타", null))).isFalse();
        assertThat(RouteScorer.nightHelp(bell("공원", 0))).isTrue();
        assertThat(RouteScorer.nightHelp(Poi.at("store", 0, 0))).isTrue();
        assertThat(RouteScorer.nightHelp(Poi.at("police", 0, 0))).isTrue();
    }
}
