package com.saferoute.route;

import static com.saferoute.route.TestRoutes.LAT;
import static com.saferoute.route.TestRoutes.edge;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.score.Coverage;
import com.saferoute.route.engine.score.Coverage.Layer;
import com.saferoute.route.engine.score.Criterion;

/** 커버율: 길 따라(조명·CCTV)와 직선 반경(도움 시설 등) */
class CoverageTests {

    // 위도 0.0003도 ≈ 33m 떨어진 평행한 두 길 A·B. 서로 이어지지 않는다 (사이에 건물)
    static final double[] A1 = {126.93000, LAT};
    static final double[] A2 = {126.93220, LAT};
    static final double[] B1 = {126.93000, LAT + 0.0003};
    static final double[] B2 = {126.93220, LAT + 0.0003};

    static RoadGraph twoStreets() {
        return new RoadGraph(Map.of(1L, A1, 2L, A2, 3L, B1, 4L, B2), List.of(
                edge(1, 1, 2, A1, A2, EdgePenalty.NONE),
                edge(2, 3, 4, B1, B2, EdgePenalty.NONE)));
    }

    static RoadEdge street(RoadGraph g, long id) {
        return g.graph().edgeSet().stream().filter(e -> e.id() == id).findFirst().orElseThrow();
    }

    @Test
    void alongRoadDoesNotReachTheStreetBehindABuilding() {
        RoadGraph g = twoStreets();
        double[] light = {126.93110, LAT - 0.00004}; // A 가운데에서 약 4m
        Coverage road = Coverage.compute(g, Map.of(Criterion.LIGHT, new Layer(List.of(light), 50, true)), 20, 10);
        Coverage straight = Coverage.compute(g, Map.of(Criterion.HELP, new Layer(List.of(light), 50, false)), 20, 10);

        // 길 따라: A의 가운데 ±50m만, B는 0
        assertThat(road.covered(street(g, 1), Criterion.LIGHT)).isCloseTo(100.0 / 200, within(0.06));
        assertThat(road.covered(street(g, 2), Criterion.LIGHT)).isZero();
        // 직선 반경이면 건물 너머 B도 일부 덮인다 (과대 계산)
        assertThat(straight.covered(street(g, 2), Criterion.HELP)).isGreaterThan(0.2);
    }

    @Test
    void facilityFarFromAnyRoadIsIgnoredAlongRoad() {
        RoadGraph g = twoStreets();
        double[] farLight = {126.93110, LAT - 0.0003}; // A에서 약 33m (붙이는 한도 20m 밖)
        Coverage road = Coverage.compute(g, Map.of(Criterion.LIGHT, new Layer(List.of(farLight), 30, true)), 20, 10);

        assertThat(road.covered(street(g, 1), Criterion.LIGHT)).isZero();
    }

    @Test
    void lightSpreadsThroughJunctionToTheNextStreet() {
        // A–C가 이어진 꺾인 길. A 끝(모퉁이) 근처 등은 C 앞부분도 비춘다
        double[] c2 = {126.93220, LAT + 0.0018};
        RoadGraph g = new RoadGraph(Map.of(1L, A1, 2L, A2, 5L, c2), List.of(
                edge(1, 1, 2, A1, A2, EdgePenalty.NONE),
                edge(3, 2, 5, A2, c2, EdgePenalty.NONE)));
        double[] corner = {126.93215, LAT};
        Coverage road = Coverage.compute(g, Map.of(Criterion.LIGHT, new Layer(List.of(corner), 30, true)), 20, 10);

        double cLen = street(g, 3).lengthM();
        assertThat(road.covered(street(g, 3), Criterion.LIGHT)).isCloseTo(25.0 / cLen, within(10.0 / cLen));
    }

    @Test
    void missingIsOneMinusCovered() {
        RoadGraph g = twoStreets();
        Coverage none = Coverage.compute(g, Map.of(Criterion.REST, new Layer(List.of(), 120, false)), 20, 10);
        assertThat(none.missing(street(g, 1), Criterion.REST)).isEqualTo(1.0);
    }
}
