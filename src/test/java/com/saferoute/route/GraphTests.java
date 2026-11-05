package com.saferoute.route;

import static com.saferoute.route.TestRoutes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.route.data.InMemoryRoadGraphSource;
import com.saferoute.route.data.NoRoadGraphSource;
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
                edge(2, M, T, M_P, T_P, new EdgePenalty(Map.of(Penalty.STAIRS, 1.0), false))));

        var part = g.mainPart(e -> !RouteFinder.isStairs(e));

        assertThat(part).containsExactlyInAnyOrder(S, M);
        assertThat(g.nearest(T_P[0], T_P[1], part).node()).isEqualTo(M);
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
        assertThat(DARK.get(Penalty.STAIRS)).isZero();
        assertThat(DARK.get(Penalty.LIGHT)).isEqualTo(1.0);
        assertThatThrownBy(() -> DARK.values().put(Penalty.LIGHT, 0.0)).isInstanceOf(UnsupportedOperationException.class);
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
}
