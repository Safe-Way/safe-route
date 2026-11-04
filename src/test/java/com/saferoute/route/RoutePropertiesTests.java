package com.saferoute.route;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.saferoute.route.engine.score.Criterion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.saferoute.route.engine.graph.Penalty;

/** route.yml이 요구사항 6.2·6.3 값대로 읽히는지 */
class RoutePropertiesTests {

    @Test
    void readsRouteYml() {
        RouteProperties p = TestRoutes.props();

        RouteProperties.Profile adult = p.profile("adult");
        assertThat(adult.day().hasWeights()).isFalse();
        assertThat(adult.night().weights()).containsEntry(Criterion.LIGHT, 5.0)
                .containsEntry(Criterion.HELP, 2.0).containsEntry(Criterion.CCTV, 1.0);
        assertThat(adult.night().helpM()).isEqualTo(160);
        assertThat(adult.maxDetour()).isEqualTo(1.3);
        assertThat(adult.walkSpeedMPerMin()).isEqualTo(80);
        assertThat(p.profile(null)).isEqualTo(adult);

        RouteProperties.Profile child = p.profile("child");
        assertThat(child.nightLeadMin()).isEqualTo(60);
        assertThat(child.day().weights()).containsEntry(Criterion.SCHOOL, 3.0).containsEntry(Criterion.HELP, 1.0);
        assertThat(child.day().schoolM()).isEqualTo(300);
        assertThat(child.day().helpM()).isEqualTo(150);
        assertThat(child.night().weights()).doesNotContainKey(Criterion.SCHOOL);

        RouteProperties.Profile senior = p.profile("senior");
        assertThat(senior.avoidStairs()).isTrue();
        assertThat(senior.maxDetour()).isEqualTo(1.2);
        assertThat(senior.day().weights()).containsOnlyKeys(Criterion.REST);
        assertThat(senior.day().restM()).isEqualTo(120);
        assertThat(senior.night().helpM()).isEqualTo(120);
        assertThat(senior.night().weights()).doesNotContainKey(Criterion.REST);

        RouteProperties.Profile wheel = p.profile("wheelchair");
        assertThat(wheel.maxDetour()).isEqualTo(1.2);
        assertThat(wheel.exclude()).containsExactly(Penalty.STAIRS, Penalty.CURB, Penalty.FOOTBRIDGE, Penalty.TRAIL, Penalty.FORD);
        assertThat(wheel.avoidUnpaved()).isTrue();
        assertThat(p.profile("senior").avoidUnpaved()).isFalse();
        assertThat(wheel.day().hasWeights()).isFalse();
        assertThat(wheel.night().weights()).containsOnlyKeys(Criterion.LIGHT, Criterion.HELP, Criterion.CCTV);
        assertThat(wheel.night().helpM()).isEqualTo(120);

        assertThat(p.coverage().lightM()).isEqualTo(30);
        assertThat(p.coverage().cctvM()).isEqualTo(50);
        assertThat(p.coverage().snapToRoadM()).isEqualTo(20);
        assertThat(p.countRadiusM().light()).isEqualTo(30);
        assertThat(p.searchMarginM()).isEqualTo(500);
        assertThat(p.tooFarM()).isEqualTo(5000);
        assertThatThrownBy(() -> p.profile("elder")).isInstanceOf(IllegalArgumentException.class);
    }
}
