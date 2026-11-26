package com.saferoute.route;

import static com.saferoute.route.TestRoutes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.saferoute.route.data.NoRoadGraphSource;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.search.RouteFinder;

/** 도로망 모델, 출발·도착 붙이기, 경로 선 */
@DisplayName("도로망 모델·붙이기·경로 선")
class GraphTests {

    @Test
    @DisplayName("한쪽 노드가 없는 길은 도로망에서 뺀다")
    void edgeWithMissingNodeIsDropped() {
        // 범위 끝에서 한쪽 노드만 들어온 길
        RoadGraph g = new RoadGraph(Map.of(S, S_P, M, M_P),
                List.of(edge(1, S, M, S_P, M_P, EdgePenalty.NONE), edge(2, M, T, M_P, T_P, EdgePenalty.NONE)));

        assertThat(g.graph().edgeSet()).extracting(RoadEdge::id).containsExactly(1L);
    }

    @Test
    @DisplayName("가장 가까운 곳을 찾을 때 길이 없는 노드는 건너뛴다")
    void nearestSkipsNodesWithoutRoads() {
        Map<Long, double[]> nodes = new LinkedHashMap<>();
        nodes.put(S, S_P);
        nodes.put(M, M_P);
        nodes.put(99L, new double[] {126.93001, LAT}); // S 바로 옆이지만 길이 없는 노드
        RoadGraph g = new RoadGraph(nodes, List.of(edge(1, S, M, S_P, M_P, EdgePenalty.NONE)));

        RoadGraph.Snap snap = g.nearest(126.93001, LAT, e -> true);

        assertThat(snap.node()).isEqualTo(S);
        assertThat(snap.distanceM()).isBetween(0.5, 1.5);
    }

    @Test
    @DisplayName("계단으로만 닿는 곳은 큰 덩어리에서 빠진다")
    void mainPartLeavesOutAreaReachableOnlyByStairs() {
        // S–M 보통 길, M–T 계단. 계단을 빼면 T는 큰 덩어리 밖
        RoadGraph g = new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P), List.of(edge(1, S, M, S_P, M_P, EdgePenalty.NONE),
                edge(2, M, T, M_P, T_P, new EdgePenalty(Map.of(Penalty.STAIRS, 1.0)))));

        var part = g.mainPart(e -> !RouteFinder.isStairs(e));

        assertThat(part).containsExactlyInAnyOrder(S, M);
        assertThat(g.nearest(T_P[0], T_P[1], part, e -> !RouteFinder.isStairs(e)).node()).isEqualTo(M);
        assertThat(g.nearest(T_P[0], T_P[1], e -> true).node()).isEqualTo(T);
    }

    @Test
    @DisplayName("빈 도로망에서는 붙일 곳이 없다")
    void nearestOnEmptyGraphIsNull() {
        // null, isEmpty
        assertThat(new RoadGraph(Map.of(), List.of()).nearest(126.93, LAT, e -> true)).isNull();
        assertThat(new RoadGraph(Map.of(), List.of()).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("길은 번호만으로 같은지 가린다(성질이 달라도 같은 길)")
    void edgesAreEqualByIdOnly() {
        RoadEdge a = edge(7, S, M, S_P, M_P, EdgePenalty.NONE);
        RoadEdge b = edge(7, S, M, S_P, M_P, DARK);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    @DisplayName("길 성질에 없는 종류는 0이고 성질 값은 바꿀 수 없다")
    void penaltyMissingKindIsZeroAndCannotBeChanged() {
        EdgePenalty stairs = new EdgePenalty(Map.of(Penalty.STAIRS, 1.0));
        assertThat(stairs.get(Penalty.CURB)).isZero();
        assertThat(stairs.get(Penalty.STAIRS)).isEqualTo(1.0);
        assertThatThrownBy(() -> stairs.values().put(Penalty.CURB, 1.0)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("경로 선에 요청 출발·도착점은 노드와 다를 때만 덧붙인다")
    void lineFromAddsRequestedEndsOnlyWhenDifferent() {
        var path = RouteFinder.find(diamond(0.0006), S, T, null, 1.3, e -> true, null, List.of(), RoadEdge::lengthM)
                .shortest();
        double[] start = {126.92990, LAT}; // 노드에서 약 9m 서쪽
        double[] end = T_P;                 // 노드와 같은 점

        List<double[]> line = path.lineFrom(start, end);

        assertThat(line).hasSize(path.line().size() + 1);
        assertThat(line.get(0)).isEqualTo(start);
        assertThat(line.get(line.size() - 1)).isEqualTo(T_P);
    }

    @Test
    @DisplayName("시설을 세는 선은 요청 끝을 붙인 선을 지하 길에서 끊은 지상 조각이다")
    void surfaceLinesCutAtUndergroundAndKeepRequestedEnds() {
        // RoadPath.surfaceLines, 2026-11-09
        double[] x = {126.93160, LAT};
        RoadEdge sm = edge(1, S, M, S_P, M_P, EdgePenalty.NONE);
        RoadEdge hall = edge(2, M, 5, M_P, x, UNDER);
        RoadEdge xt = edge(3, 5, T, x, T_P, EdgePenalty.NONE);
        var path = new com.saferoute.route.engine.search.RoadPath(List.of(sm, hall, xt), List.of(S_P, M_P, x, T_P),
                sm.lengthM() + hall.lengthM() + xt.lengthM());
        double[] start = {126.92990, LAT};
        double[] end = {126.93230, LAT};

        List<List<double[]>> pieces = path.surfaceLines(start, end);

        assertThat(pieces).hasSize(2);
        assertThat(pieces.get(0)).containsExactly(start, S_P, M_P);
        assertThat(pieces.get(1)).containsExactly(x, T_P, end);
        // 요청 점이 노드와 같으면 한 번만
        assertThat(path.surfaceLines(S_P, T_P).get(0).get(0)).isSameAs(S_P);
        assertThat(path.surfaceLines(S_P, T_P).get(0)).hasSize(2);
        assertThat(path.surfaceLines(S_P, T_P).get(1)).containsExactly(x, T_P);
    }

    @Test
    @DisplayName("도로망이 없으면 어느 점도 범위 안으로 보지 않는다")
    void placeholderSourceCoversNothing() {
        // route.road-source=none, OUT_OF_AREA
        assertThat(new NoRoadGraphSource().covers(126.93, 35.14)).isFalse();
    }

    @Test
    @DisplayName("두 차로 큰 길에서는 건너편 말고 가까운 차로에 붙인다")
    void snapsToNearRoadNotAcrossDualCarriageway() {
        // 필문대로처럼 큰 길 두 차로. 서쪽 차로는 200m에 중간 노드 없음, 동쪽 차로는 점 옆에 노드 (현장 시험 2026-11-11 ④)
        Map<Long, double[]> nodes = Map.of(11L, xy(0, 0), 12L, xy(0, 200), 21L, xy(13, 0), 22L, xy(13, 100),
                23L, xy(13, 200));
        RoadGraph g = new RoadGraph(nodes, List.of(road(1, 11, 12, "primary", nodes), road(2, 21, 22, "primary", nodes),
                road(3, 22, 23, "primary", nodes), road(4, 11, 21, "footway", nodes), road(5, 12, 23, "footway", nodes)));

        RoadGraph.Snap snap = g.nearest(xy(-11, 100)[0], xy(-11, 100)[1], e -> true);

        assertThat(snap.node()).isNegative();
        assertThat(snap.distanceM()).isBetween(10.0, 12.0);
        assertThat(g.point(snap.node())[0]).isCloseTo(xy(0, 0)[0], offset(1e-7));
    }

    @Test
    @DisplayName("가까운 길이 큰 길 건너뿐이면 이쪽 가까운 노드에 붙인다")
    void onlyCandidateAcrossBigRoadFallsBackToNearestNode() {
        // 점 동쪽 5m에 걸을 수 없는 큰 길, 그 건너 15m에 인도. 큰 길 이쪽 35m에 노드 하나
        Map<Long, double[]> nodes = Map.of(11L, xy(0, -100), 12L, xy(0, 100), 21L, xy(10, -100), 22L, xy(10, 100),
                31L, xy(-40, 0), 32L, xy(-80, 0));
        RoadGraph g = new RoadGraph(nodes, List.of(road(1, 11, 12, "trunk", nodes), road(2, 21, 22, "footway", nodes),
                road(3, 31, 32, "footway", nodes)));

        RoadGraph.Snap snap = g.nearest(xy(-5, 0)[0], xy(-5, 0)[1], new HashSet<>(nodes.keySet()),
                e -> !"trunk".equals(e.highway()));

        assertThat(snap.node()).isEqualTo(31L);
        assertThat(snap.distanceM()).isBetween(34.0, 36.0);
    }

    @Test
    @DisplayName("못 쓰는 계단에는 붙이지 않고 다음 가까운 길에 붙인다")
    void neverSnapsOntoUnusableStairs() {
        // 5m 옆 계단, 20m 옆 보통 길
        Map<Long, double[]> nodes = Map.of(11L, xy(5, -50), 12L, xy(5, 50), 21L, xy(-20, -100), 22L, xy(-20, 100));
        RoadEdge stairs = new RoadEdge(1, 11, 12, 100, "steps", List.of(xy(5, -50), xy(5, 50)),
                new EdgePenalty(Map.of(Penalty.STAIRS, 1.0)));
        RoadGraph g = new RoadGraph(nodes, List.of(stairs, road(2, 21, 22, "footway", nodes)));

        RoadGraph.Snap snap = g.nearest(xy(0, 0)[0], xy(0, 0)[1], new HashSet<>(nodes.keySet()),
                e -> !RouteFinder.isStairs(e));

        assertThat(snap.node()).isNegative();
        assertThat(snap.distanceM()).isBetween(19.0, 21.0);
        assertThat(g.graph().edgesOf(snap.node())).noneMatch(RouteFinder::isStairs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"trunk", "tertiary"})
    @DisplayName("큰 길 교차로 노드에 붙는 것은 큰 길을 건너는 것으로 보지 않는다")
    void footOnIntersectionWithBigRoadIsNotCrossing(String big) {
        // 남북 큰 길이 노드 10을 지나고, 인도 30→10이 동쪽에서 그 노드로 들어온다. 점은 노드 10의 남서쪽 약 7m.
        // 점 남쪽 3m의 다른 큰 길 너머 노드 40(6m)이 더 가깝지만 건너야 하므로, 교차로 노드 10에 붙어야 한다
        Map<Long, double[]> nodes = Map.of(10L, xy(0, 0), 11L, xy(0, -100), 12L, xy(0, 100), 30L, xy(100, 0),
                21L, xy(-100, -8), 22L, xy(100, -8), 40L, xy(-5, -11), 41L, xy(-5, -100));
        RoadGraph g = new RoadGraph(nodes, List.of(road(1, 11, 10, big, nodes), road(2, 10, 12, big, nodes),
                road(3, 30, 10, "footway", nodes), road(4, 21, 22, big, nodes), road(5, 40, 41, "footway", nodes)));

        RoadGraph.Snap snap = g.nearest(xy(-5, -5)[0], xy(-5, -5)[1], new HashSet<>(nodes.keySet()),
                e -> !big.equals(e.highway()));

        assertThat(snap.node()).isEqualTo(10L);
        assertThat(snap.distanceM()).isBetween(6.5, 7.5);
    }

    @Test
    @DisplayName("두 선분은 끝이 서로 상대 선 양쪽에 있어야 엇갈리고 닿기만 하면 아니다")
    void crossingMeansSegmentsStrictlyCross() {
        // RoadGraph.crosses
        double[] a = xy(0, 0), b = xy(100, 100);
        // X자로 엇갈림: 어느 끝부터 그려도 같다
        double[] c = xy(80, 0), d = xy(0, 80);
        assertThat(RoadGraph.crosses(a, b, c, d)).isTrue();
        assertThat(RoadGraph.crosses(b, a, c, d)).isTrue();
        assertThat(RoadGraph.crosses(a, b, d, c)).isTrue();
        assertThat(RoadGraph.crosses(c, d, a, b)).isTrue();
        // 45° 선 바로 앞(2m)에서 멈춘 비스듬한 선분: 같은 쪽이라 안 엇갈린다
        assertThat(RoadGraph.crosses(a, b, xy(40, 0), xy(12, 10))).isFalse();
        assertThat(RoadGraph.crosses(a, b, xy(0, 40), xy(10, 12))).isFalse();
        assertThat(RoadGraph.crosses(xy(40, 0), xy(12, 10), a, b)).isFalse();
        assertThat(RoadGraph.crosses(xy(0, 40), xy(10, 12), a, b)).isFalse();
        // 선을 늘이면 만나지만 선분 밖
        assertThat(RoadGraph.crosses(a, b, xy(200, 0), xy(120, 80))).isFalse();
        // 동서 선 위에 끝점이 놓인 T자: 위·아래 어느 쪽에서 와도, 어느 선분을 먼저 줘도 건넘이 아니다
        double[] w = xy(-50, 0), e = xy(50, 0), on = xy(0, 0);
        for (double[] off : List.of(xy(0, 30), xy(0, -30))) {
            assertThat(RoadGraph.crosses(w, e, off, on)).isFalse();
            assertThat(RoadGraph.crosses(w, e, on, off)).isFalse();
            assertThat(RoadGraph.crosses(e, w, off, on)).isFalse();
            assertThat(RoadGraph.crosses(off, on, w, e)).isFalse();
            assertThat(RoadGraph.crosses(on, off, e, w)).isFalse();
        }
    }

    private static RoadEdge road(long id, long from, long to, String highway, Map<Long, double[]> nodes) {
        return TestRoutes.road(id, from, to, nodes.get(from), nodes.get(to), highway, null);
    }
}
