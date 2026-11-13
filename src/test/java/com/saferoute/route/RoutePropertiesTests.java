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
        // 골목길 기준 (2026-11-12): 노인 낮 쉼 곳 4 > 골목 2, 어린이 낮 골목 1, 밤은 둘 다 CCTV 다음 0.5
        assertThat(senior.day().weights()).containsOnlyKeys(Criterion.REST, Criterion.ALLEY).containsEntry(Criterion.ALLEY, 2.0);
        assertThat(senior.night().weights()).containsEntry(Criterion.ALLEY, 0.5);
        assertThat(child.day().weights()).containsEntry(Criterion.ALLEY, 1.0);
        assertThat(child.night().weights()).containsEntry(Criterion.ALLEY, 0.5);
        assertThat(senior.day().restM()).isEqualTo(120);
        assertThat(senior.night().helpM()).isEqualTo(120);
        assertThat(senior.night().weights()).doesNotContainKey(Criterion.REST);

        RouteProperties.Profile wheel = p.profile("wheelchair");
        assertThat(wheel.maxDetour()).isEqualTo(1.2);
        assertThat(wheel.exclude()).containsExactly(Penalty.STAIRS, Penalty.CURB, Penalty.FOOTBRIDGE, Penalty.TRAIL, Penalty.FORD,
                Penalty.PLAZA, Penalty.NO_SIDEWALK_ALLEY);
        assertThat(wheel.avoidUnpaved()).isTrue();
        assertThat(p.profile("senior").avoidUnpaved()).isFalse();
        // 골목길 기준 (2026-11-12): 낮은 큰 길만, 밤은 밝기 > 도움 시설 > CCTV > 골목길
        assertThat(wheel.day().weights()).containsOnlyKeys(Criterion.ALLEY).containsEntry(Criterion.ALLEY, 0.5);
        assertThat(wheel.night().weights()).containsOnlyKeys(Criterion.LIGHT, Criterion.HELP, Criterion.CCTV, Criterion.ALLEY)
                .containsEntry(Criterion.ALLEY, 0.5);
        assertThat(adult.night().weights()).doesNotContainKey(Criterion.ALLEY);
        assertThat(wheel.night().helpM()).isEqualTo(120);

        assertThat(p.coverage().lightM()).isEqualTo(30);
        assertThat(p.coverage().cctvM()).isEqualTo(50);
        assertThat(p.coverage().snapToRoadM()).isEqualTo(20);
        assertThat(p.countRadiusM().light()).isEqualTo(30);
        assertThat(p.searchMarginM()).isEqualTo(500);
        assertThat(p.passableSnapExtraM()).isEqualTo(50);
        assertThat(p.passableSnapOriginExtraM()).isEqualTo(100);
        assertThat(p.endAlleyM()).isEqualTo(200);
        assertThat(p.noSidewalkAlleyFallbackFactor()).isEqualTo(10);
        assertThat(p.sidewalkDataMinEdgeM()).isEqualTo(20);
        assertThat(p.narrowSidewalkM()).isEqualTo(1.5);
        assertThat(p.profile("wheelchair").avoidNarrowSidewalk()).isTrue();
        assertThat(p.profile("senior").avoidNarrowSidewalk()).isFalse();
        assertThat(p.darkStretch().mergeGapM()).isEqualTo(10);
        assertThat(p.darkStretch().minM()).isEqualTo(50);
        assertThat(p.turn().minAngle()).isEqualTo(45);
        assertThat(p.turn().lookM()).isEqualTo(15);
        assertThat(p.turn().linkM()).isEqualTo(20);
        assertThat(p.tooFarM()).isEqualTo(5000);
        assertThatThrownBy(() -> p.profile("elder")).isInstanceOf(IllegalArgumentException.class);
    }
}
