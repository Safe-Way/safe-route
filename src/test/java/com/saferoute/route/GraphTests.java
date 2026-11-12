package com.saferoute.route;

import static com.saferoute.route.TestRoutes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.saferoute.route.data.InMemoryRoadGraphSource;
import com.saferoute.route.data.NoRoadGraphSource;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.search.RouteFinder;

/** 도로망 모델, 출발·도착 붙이기, 경로 선 */
class GraphTests {

    @Test
    void edgeWithMissingNodeIsDropped() {
        // 범위 끝에서 한쪽 노드만 들어온 길
        RoadGraph g = new RoadGraph(Map.of(S, S_P, M, M_P),
                List.of(edge(1, S, M, S_P, M_P, EdgePenalty.NONE), edge(2, M, T, M_P, T_P, EdgePenalty.NONE)));

        assertThat(g.graph().edgeSet()).extracting(RoadEdge::id).containsExactly(1L);
    }

    @Test
    void nearestSkipsNodesWithoutRoads() {
        Map<Long, double[]> nodes = new LinkedHashMap<>();
        nodes.put(S, S_P);
        nodes.put(M, M_P);
        nodes.put(99L, new double[] {126.93001, LAT}); // S 바로 옆이지만 길이 없는 노드
        RoadGraph g = new RoadGraph(nodes, List.of(edge(1, S, M, S_P, M_P, EdgePenalty.NONE)));

        RoadGraph.Snap snap = g.nearest(126.93001, LAT);

        assertThat(snap.node()).isEqualTo(S);
        assertThat(snap.distanceM()).isBetween(0.5, 1.5);
    }

    @Test
    void mainPartLeavesOutAreaReachableOnlyByStairs() {
        // S–M 보통 길, M–T 계단. 계단을 빼면 T는 큰 덩어리 밖
        RoadGraph g = new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P), List.of(edge(1, S, M, S_P, M_P, EdgePenalty.NONE),
                edge(2, M, T, M_P, T_P, new EdgePenalty(Map.of(Penalty.STAIRS, 1.0)))));

        var part = g.mainPart(e -> !RouteFinder.isStairs(e));

        assertThat(part).containsExactlyInAnyOrder(S, M);
        assertThat(g.nearest(T_P[0], T_P[1], part, e -> !RouteFinder.isStairs(e)).node()).isEqualTo(M);
        assertThat(g.nearest(T_P[0], T_P[1]).node()).isEqualTo(T);
    }

    @Test
    void nearestOnEmptyGraphIsNull() {
        assertThat(new RoadGraph(Map.of(), List.of()).nearest(126.93, LAT)).isNull();
        assertThat(new RoadGraph(Map.of(), List.of()).isEmpty()).isTrue();
    }

    @Test
    void edgesAreEqualByIdOnly() {
        RoadEdge a = edge(7, S, M, S_P, M_P, EdgePenalty.NONE);
        RoadEdge b = edge(7, S, M, S_P, M_P, DARK);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void penaltyMissingKindIsZeroAndCannotBeChanged() {
        EdgePenalty stairs = new EdgePenalty(Map.of(Penalty.STAIRS, 1.0));
        assertThat(stairs.get(Penalty.CURB)).isZero();
        assertThat(stairs.get(Penalty.STAIRS)).isEqualTo(1.0);
        assertThatThrownBy(() -> stairs.values().put(Penalty.CURB, 1.0)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void lineFromAddsRequestedEndsOnlyWhenDifferent() {
        var path = RouteFinder.find(diamond(0.0006), S, T, null, 1.3, e -> true, false).shortest();
        double[] start = {126.92990, LAT}; // 노드에서 약 9m 서쪽
        double[] end = T_P;                 // 노드와 같은 점

        List<double[]> line = path.lineFrom(start, end);

        assertThat(line).hasSize(path.line().size() + 1);
        assertThat(line.get(0)).isEqualTo(start);
        assertThat(line.get(line.size() - 1)).isEqualTo(T_P);
    }

    @Test
    void inMemorySourceCoversOnlyItsArea() {
        var source = new InMemoryRoadGraphSource(diamond(0.0006));

        assertThat(source.covers(126.9311, 35.1402)).isTrue();
        assertThat(source.covers(126.95, 35.16)).isFalse();
    }

    @Test
    void placeholderSourceCoversNothing() {
        assertThat(new NoRoadGraphSource().covers(126.93, 35.14)).isFalse();
    }

    @Test
    void snapsToNearRoadNotAcrossDualCarriageway() {
        // 필문대로처럼 큰 길 두 차로. 서쪽 차로는 200m에 중간 노드 없음, 동쪽 차로는 점 옆에 노드 (현장 시험 2026-11-11 ④)
        Map<Long, double[]> nodes = Map.of(11L, at(0, 0), 12L, at(0, 200), 21L, at(13, 0), 22L, at(13, 100),
                23L, at(13, 200));
        RoadGraph g = new RoadGraph(nodes, List.of(road(1, 11, 12, "primary", nodes), road(2, 21, 22, "primary", nodes),
                road(3, 22, 23, "primary", nodes), road(4, 11, 21, "footway", nodes), road(5, 12, 23, "footway", nodes)));

        RoadGraph.Snap snap = g.nearest(at(-11, 100)[0], at(-11, 100)[1]);

        // 예전 방식은 동쪽 노드 22에 24m로 붙었다
        assertThat(snap.node()).isNegative();
        assertThat(snap.distanceM()).isBetween(10.0, 12.0);
        assertThat(g.point(snap.node())[0]).isCloseTo(at(0, 0)[0], offset(1e-7));
    }

    @Test
    void onlyCandidateAcrossBigRoadFallsBackToNearestNode() {
        // 점 동쪽 5m에 걸을 수 없는 큰 길, 그 건너 15m에 인도. 큰 길 이쪽 35m에 노드 하나
        Map<Long, double[]> nodes = Map.of(11L, at(0, -100), 12L, at(0, 100), 21L, at(10, -100), 22L, at(10, 100),
                31L, at(-40, 0), 32L, at(-80, 0));
        RoadGraph g = new RoadGraph(nodes, List.of(road(1, 11, 12, "trunk", nodes), road(2, 21, 22, "footway", nodes),
                road(3, 31, 32, "footway", nodes)));

        RoadGraph.Snap snap = g.nearest(at(-5, 0)[0], at(-5, 0)[1], new HashSet<>(nodes.keySet()),
                e -> !"trunk".equals(e.highway()));

        assertThat(snap.node()).isEqualTo(31L);
        assertThat(snap.distanceM()).isBetween(34.0, 36.0);
    }

    @Test
    void neverSnapsOntoUnusableStairs() {
        // 5m 옆 계단, 20m 옆 보통 길
        Map<Long, double[]> nodes = Map.of(11L, at(5, -50), 12L, at(5, 50), 21L, at(-20, -100), 22L, at(-20, 100));
        RoadEdge stairs = new RoadEdge(1, 11, 12, 100, "steps", List.of(at(5, -50), at(5, 50)),
                new EdgePenalty(Map.of(Penalty.STAIRS, 1.0)));
        RoadGraph g = new RoadGraph(nodes, List.of(stairs, road(2, 21, 22, "footway", nodes)));

        RoadGraph.Snap snap = g.nearest(at(0, 0)[0], at(0, 0)[1], new HashSet<>(nodes.keySet()),
                e -> !RouteFinder.isStairs(e));

        assertThat(snap.node()).isNegative();
        assertThat(snap.distanceM()).isBetween(19.0, 21.0);
        assertThat(g.graph().edgesOf(snap.node())).noneMatch(RouteFinder::isStairs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"trunk", "tertiary"})
    void footOnIntersectionWithBigRoadIsNotCrossing(String big) {
        // 남북 큰 길이 노드 10을 지나고, 인도 30→10이 동쪽에서 그 노드로 들어온다. 점은 노드 10의 남서쪽 약 7m.
        // 점 남쪽 3m의 다른 큰 길 너머 노드 40(6m)이 더 가깝지만 건너야 하므로, 교차로 노드 10에 붙어야 한다
        Map<Long, double[]> nodes = Map.of(10L, at(0, 0), 11L, at(0, -100), 12L, at(0, 100), 30L, at(100, 0),
                21L, at(-100, -8), 22L, at(100, -8), 40L, at(-5, -11), 41L, at(-5, -100));
        RoadGraph g = new RoadGraph(nodes, List.of(road(1, 11, 10, big, nodes), road(2, 10, 12, big, nodes),
                road(3, 30, 10, "footway", nodes), road(4, 21, 22, big, nodes), road(5, 40, 41, "footway", nodes)));

        RoadGraph.Snap snap = g.nearest(at(-5, -5)[0], at(-5, -5)[1], new HashSet<>(nodes.keySet()),
                e -> !big.equals(e.highway()));

        assertThat(snap.node()).isEqualTo(10L);
        assertThat(snap.distanceM()).isBetween(6.5, 7.5);
    }

    /** (126.93, LAT)에서 동쪽·북쪽으로 m만큼 떨어진 점 */
    private static double[] at(double eastM, double northM) {
        return new double[] {126.93 + eastM / (111_195.0 * Math.cos(Math.toRadians(LAT))), LAT + northM / 111_195.0};
    }

    private static RoadEdge road(long id, long from, long to, String highway, Map<Long, double[]> nodes) {
        List<double[]> line = List.of(nodes.get(from), nodes.get(to));
        return new RoadEdge(id, from, to, Geo.length(line), highway, line, EdgePenalty.NONE);
    }
}
