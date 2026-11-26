package com.saferoute.route.engine.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.route.RouteProperties;
import com.saferoute.route.RouteTestProps;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;

/** 길마다 더할 비용과 시설 고르기 (A파트 정의: 밤 도움 시설에서 지킴이집·건물 안 비상벨 제외) */
@DisplayName("길 비용과 시설 고르기")
class RouteScorerTests {

    static final RouteScorer SCORER = new RouteScorer(RouteTestProps.load());
    /** 밤 비중 밝기 5 : 도움 시설 2 : CCTV 1 */
    static final RouteProperties.Phase NIGHT = new RouteProperties.Phase(
            Map.of(Criterion.LIGHT, 5.0, Criterion.HELP, 2.0, Criterion.CCTV, 1.0), 160, 0, 0, null);

    static Poi bell(String sub, Integer indoor) {
        return new Poi("bell", sub, indoor, 0, 0, 0, 1440, "매일", null, null, null, null, null, null);
    }

    /** 동서로 곧은 길 하나 (약 100m) */
    static RoadEdge street(long id, EdgePenalty p) {
        List<double[]> line = List.of(new double[] {126.930, 35.14}, new double[] {126.931, 35.14});
        return new RoadEdge(id, 1, 2, Geo.length(line), "footway", line, p);
    }

    /** 길 하나뿐인 도로망에서 기준별 커버율. lights가 있으면 길 전체를 비춘다 */
    static Coverage coverage(RoadEdge e, List<double[]> lights) {
        RoadGraph g = new RoadGraph(Map.of(1L, e.geometry().get(0), 2L, e.geometry().get(1)), List.of(e));
        return Coverage.compute(g, Map.of(Criterion.LIGHT, new Coverage.Layer(lights, 200, true),
                Criterion.HELP, new Coverage.Layer(List.of(), 160, false),
                Criterion.CCTV, new Coverage.Layer(List.of(), 50, true)), 20, 10);
    }

    @Test
    @DisplayName("밤 도움 시설에서 지킴이집과 건물 안·안팎 모르는 비상벨을 뺀다")
    void nightHelpLeavesOutShopsAndIndoorBells() {
        // route.yml 기준 이름 help
        assertThat(RouteScorer.nightHelp(Poi.at("guard_house", 0, 0))).isFalse();
        assertThat(RouteScorer.nightHelp(bell("화장실", 1))).isFalse();
        assertThat(RouteScorer.nightHelp(bell("기타", null))).isFalse();
        assertThat(RouteScorer.nightHelp(bell("공원", 0))).isTrue();
        assertThat(RouteScorer.nightHelp(Poi.at("store", 0, 0))).isTrue();
        assertThat(RouteScorer.nightHelp(Poi.at("police", 0, 0))).isTrue();
    }

    @Test
    @DisplayName("추가 비용은 길이 × Σ 비중 × 범위 밖 비율이다")
    void extraCostIsLengthTimesWeightedMissingShare() {
        // route.yml 머리 설명
        RoadEdge e = street(1, EdgePenalty.NONE);
        double[] middle = {126.9305, 35.14};

        double dark = SCORER.extraCost(coverage(e, List.of()), NIGHT).applyAsDouble(e);
        double lit = SCORER.extraCost(coverage(e, List.of(middle)), NIGHT).applyAsDouble(e);

        assertThat(dark).isCloseTo(e.lengthM() * (5 + 2 + 1), within(1e-9));
        assertThat(lit).isCloseTo(e.lengthM() * (2 + 1), within(1e-9));

        // 길 서쪽 끝의 등(길 따라 30m)이 길 일부만 비추면 밝기 부담도 그 비율만큼
        RoadGraph g = new RoadGraph(Map.of(1L, e.geometry().get(0), 2L, e.geometry().get(1)), List.of(e));
        Coverage part = Coverage.compute(g, Map.of(Criterion.LIGHT, new Coverage.Layer(List.of(e.geometry().get(0)), 30, true),
                Criterion.HELP, new Coverage.Layer(List.of(), 160, false),
                Criterion.CCTV, new Coverage.Layer(List.of(), 50, true)), 20, 10);
        double share = part.missing(e, Criterion.LIGHT);
        assertThat(share).isStrictlyBetween(0.0, 1.0);
        assertThat(SCORER.extraCost(part, NIGHT).applyAsDouble(e)).isCloseTo(e.lengthM() * (5 * share + 2 + 1), within(1e-9));
    }

    @Test
    @DisplayName("기준마다 그 종류 시설만 세고 등은 CCTV 범위를 만들지 않는다")
    void eachCriterionCountsOnlyItsOwnFacilities() {
        // RouteScorer.coverage
        RoadEdge e = street(1, EdgePenalty.NONE);
        RoadGraph g = new RoadGraph(Map.of(1L, e.geometry().get(0), 2L, e.geometry().get(1)), List.of(e));
        double[] middle = {126.9305, 35.14};
        java.time.LocalDateTime night = java.time.LocalDateTime.of(2026, 11, 4, 21, 0);

        Coverage lights = SCORER.coverage(g, NIGHT, true, new com.saferoute.route.engine.facility.Nearby(
                List.of(Poi.at("light", middle[0], middle[1])), java.util.Set.of(), night, null));
        Coverage cameras = SCORER.coverage(g, NIGHT, true, new com.saferoute.route.engine.facility.Nearby(
                List.of(Poi.at("cctv", middle[0], middle[1])), java.util.Set.of(), night, null));

        assertThat(lights.covered(e, Criterion.LIGHT)).isPositive();
        assertThat(lights.covered(e, Criterion.CCTV)).isZero();
        assertThat(cameras.covered(e, Criterion.CCTV)).isPositive();
        assertThat(cameras.covered(e, Criterion.LIGHT)).isZero();
    }

    @Test
    @DisplayName("비 오면 지붕 없는 길에 길이와 기준 부담 합의 지붕 비중만큼 더한다")
    void rainMultipliesTheWholeUncoveredBurden() {
        // 지붕 비중 0.4 (walk-more). 지붕 없는 길은 (길이 + 기준 부담) × 0.4를 더하고, 지붕 길은 기준 부담만
        RouteProperties.Phase rainyNight = new RouteProperties.Phase(
                Map.of(Criterion.LIGHT, 5.0, Criterion.HELP, 2.0, Criterion.CCTV, 1.0, Criterion.ROOF, 0.4), 160, 0, 0, null);
        RoadEdge open = street(1, EdgePenalty.NONE);
        RoadEdge roofed = street(2, new EdgePenalty(Map.of(Penalty.COVERED, 1.0)));
        double base = open.lengthM() * (5 + 2 + 1);

        assertThat(SCORER.extraCost(coverage(open, List.of()), rainyNight).applyAsDouble(open))
                .isCloseTo(base + 0.4 * (open.lengthM() + base), within(1e-9));
        assertThat(SCORER.extraCost(coverage(roofed, List.of()), rainyNight).applyAsDouble(roofed))
                .isCloseTo(base, within(1e-9));
    }

    @Test
    @DisplayName("도움 시설 반경은 그때 기준(phase)의 help-m을 쓴다")
    void helpRadiusComesFromThePhase() {
        // route.yml profiles.*.help-m
        assertThat(SCORER.radius(Criterion.HELP, NIGHT)).isEqualTo(160);
        assertThat(SCORER.radius(Criterion.HELP, new RouteProperties.Phase(NIGHT.weights(), 120, 0, 0, null)))
                .isEqualTo(120);
    }
}
