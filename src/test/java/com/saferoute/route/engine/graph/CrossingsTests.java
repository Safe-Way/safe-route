package com.saferoute.route.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.search.RoadPath;
import com.saferoute.route.engine.search.RouteFinder;

/** 큰길은 횡단보도·신호에서만 건너기: 큰길 노드를 양쪽으로 나눈 탐색용 도로망 (specs/011) */
class CrossingsTests {

    static final double LAT = 35.14;
    static final double M_PER_LAT = Geo.distance(0, 0, 0, 1);
    static final double M_PER_LON = Geo.distance(0, LAT, 1, LAT);

    /** 동서 큰길 위 x(m, 경도 126.93 기준), 북쪽 y(m) 점 */
    static double[] at(double x, double y) {
        return new double[] {126.93 + x / M_PER_LON, LAT + y / M_PER_LAT};
    }

    /** 노드와 길을 차례로 넣어 만드는 작은 도로망 */
    static final class Net {
        final Map<Long, double[]> nodes = new HashMap<>();
        final List<RoadEdge> edges = new ArrayList<>();

        Net node(long id, double x, double y) {
            nodes.put(id, at(x, y));
            return this;
        }

        Net edge(long id, long from, long to, String highway) {
            return edge(id, from, to, highway, EdgePenalty.NONE);
        }

        Net edge(long id, long from, long to, String highway, EdgePenalty p) {
            List<double[]> line = List.of(nodes.get(from), nodes.get(to));
            edges.add(new RoadEdge(id, from, to, Geo.length(line), highway, line, p));
            return this;
        }

        RoadGraph graph() {
            return new RoadGraph(nodes, edges);
        }
    }

    /**
     * 큰길 W(1) –500m– X(2) –100m– Y(3) –500m– E(4). X 북쪽 골목 NX(5), 남쪽 골목 SX(6), Y 북쪽 NY(7)·남쪽 SY(8), 각 50m.
     * X–Y 길은 일부러 Y → X로 거꾸로 그렸다
     */
    static RoadGraph road() {
        return new Net().node(1, -500, 0).node(2, 0, 0).node(3, 100, 0).node(4, 600, 0)
                .node(5, 0, 50).node(6, 0, -50).node(7, 100, 50).node(8, 100, -50)
                .edge(1, 1, 2, "primary").edge(2, 3, 2, "primary").edge(3, 3, 4, "primary")
                .edge(4, 2, 5, "residential").edge(5, 2, 6, "residential")
                .edge(6, 3, 7, "residential").edge(7, 3, 8, "residential").graph();
    }

    static RoadPath shortest(Crossings c, long from, long to) {
        return RouteFinder.find(c.graph(), from, to, null, 0, e -> true, (java.util.function.Predicate<RoadEdge>) null)
                .shortest();
    }

    static List<Long> ids(RoadPath p) {
        return p.edges().stream().map(RoadEdge::id).toList();
    }

    @Test
    void withoutCrosswalkOnlySameSideIsJoined() {
        Crossings c = new Crossings(road(), List.of());

        // 같은 쪽 골목끼리는 큰길을 따라 바로 간다 (거꾸로 그린 X–Y 길을 지나도 북쪽 그대로)
        assertThat(ids(shortest(c, 5, 7))).containsExactly(4L, 2L, 6L);
        assertThat(ids(shortest(c, 6, 8))).containsExactly(5L, 2L, 7L);
        // 건너편은 나누지 않은 큰길 끝(W)까지 가서 돌아온다: X에서 곧장도, X에서 Y로 따라 걷다 반대쪽 골목으로도 못 빠진다
        assertThat(ids(shortest(c, 5, 6))).containsExactly(4L, 1L, 1L, 5L);
        assertThat(ids(shortest(c, 5, 8))).containsExactly(4L, 1L, 1L, 2L, 7L);
    }

    @Test
    void crosswalkOnTheBigRoadLetsYouCross() {
        // 신호등 점이 Y에서 큰길 선 위 10m (15m 안·선 5m 안)
        Crossings c = new Crossings(road(), List.of(at(110, 1)));

        // 건널 곳도 양쪽으로 나누고 잇는 길로 잇는다: GPS 쪽 노드에서 시작하고, 잇는 길은 경로에 안 나온다
        assertThat(c.place(3, at(100, 50))).isNotEqualTo(3).isNotEqualTo(c.place(3, at(100, -50)));
        assertThat(ids(shortest(c, 5, 8))).containsExactly(4L, 2L, 7L);
        // X에서 건너편은 Y 횡단보도까지 갔다 온다
        assertThat(ids(shortest(c, 5, 6))).containsExactly(4L, 2L, 2L, 5L);
    }

    @Test
    void crosswalkOnlyOnTheSideStreetDoesNotOpenTheBigRoad() {
        // Y 북쪽 골목 위 10m: 노드 15m 안이지만 큰길 선에서 10m라 소로를 건너는 횡단보도다
        Crossings side = new Crossings(road(), List.of(at(100, 10)));
        // 큰길 선 위지만 Y에서 20m: 길 중간 횡단보도는 이번에 안 쓴다 (D3b)
        Crossings mid = new Crossings(road(), List.of(at(120, 0)));

        assertThat(ids(shortest(side, 5, 8))).containsExactly(4L, 1L, 1L, 2L, 7L);
        assertThat(ids(shortest(mid, 5, 8))).containsExactly(4L, 1L, 1L, 2L, 7L);
    }

    @Test
    void crosswalkWayAtTheNodeLetsYouCross() {
        RoadGraph g = new Net().node(1, -500, 0).node(2, 0, 0).node(3, 500, 0).node(5, 0, 50).node(6, 0, -50)
                .edge(1, 1, 2, "primary").edge(2, 2, 3, "primary")
                .edge(4, 2, 5, "footway", new EdgePenalty(Map.of(Penalty.CROSSWALK, 1.0)))
                .edge(5, 2, 6, "footway").graph();

        Crossings c = new Crossings(g, List.of());

        // 횡단보도 길은 붙은 쪽(북쪽) 노드에 이어지고, 남쪽 골목으로는 잇는 길을 건너 간다
        assertThat(ids(shortest(c, 5, 6))).containsExactly(4L, 5L);
        assertThat(shortest(c, 5, 6).lengthM()).isEqualTo(100, org.assertj.core.api.Assertions.within(0.5));
    }

    @Test
    void junctionIsSplitIntoSectorsButBigRoadEndIsNot() {
        // X: 큰길 3개 (T자 교차로, 북쪽 갈래), W·E·N: 큰길 1개 (끝). 남쪽 골목은 X의 남쪽 칸에 붙는다
        RoadGraph g = new Net().node(1, -500, 0).node(2, 0, 0).node(3, 500, 0).node(4, 0, 300).node(5, 30, -50)
                .edge(1, 1, 2, "primary").edge(2, 2, 3, "primary").edge(3, 2, 4, "secondary")
                .edge(4, 2, 5, "residential").graph();

        Crossings c = new Crossings(g, List.of());

        long south = c.place(2, at(0, -10));
        assertThat(south).isNotEqualTo(2).isNotEqualTo(c.place(2, at(-10, 10))).isNotEqualTo(c.place(2, at(10, 10)));
        assertThat(c.graph().graph().edgesOf(south)).extracting(e -> c.graph().original(e).id()).contains(4L);
        assertThat(c.place(1, at(-500, -10))).isEqualTo(1);
        // 교차로는 횡단보도 점이 없어도 갈래마다 건넌다: 북서 칸에서 북동 칸으로 N 갈래를 건너 30m
        RoadPath p = shortest(c, c.place(2, at(-10, 10)), c.place(2, at(10, 10)));
        assertThat(p.edges()).isEmpty();
        assertThat(p.crosses()).singleElement().satisfies(x -> assertThat(x.node()).isEqualTo(2));
    }

    @Test
    void degenerateTangentOrSelfLoopIsNotSplit() {
        // 같은 이웃 E로 가는 중복 큰길 두 개: 두 길을 따라 4m 간 점이 같아 쪽을 못 정한다
        RoadGraph twin = new Net().node(2, 0, 0).node(3, 100, 0).node(5, 0, 50).node(6, 0, -50)
                .edge(1, 2, 3, "primary").edge(2, 3, 2, "primary")
                .edge(4, 2, 5, "residential").edge(5, 2, 6, "residential").graph();
        // 큰길 2개에 자기 루프 골목이 붙은 노드
        RoadGraph loop = new Net().node(1, -500, 0).node(2, 0, 0).node(3, 500, 0)
                .edge(1, 1, 2, "primary").edge(2, 2, 3, "primary").edge(9, 2, 2, "footway").graph();

        // 교차로의 두 갈래가 5° 차이: 사이 칸이 의미 없다
        RoadGraph narrow = new Net().node(1, -500, 0).node(2, 0, 0).node(3, 500, 0).node(4, 500, 44)
                .edge(1, 1, 2, "primary").edge(2, 2, 3, "primary").edge(3, 2, 4, "secondary").graph();

        assertThat(new Crossings(twin, List.of()).place(2, at(0, 10))).isEqualTo(2);
        assertThat(new Crossings(loop, List.of()).place(2, at(0, 10))).isEqualTo(2);
        assertThat(new Crossings(narrow, List.of()).place(2, at(0, 10))).isEqualTo(2);
    }

    @Test
    void placePicksTheSideOfTheGpsPoint() {
        Crossings c = new Crossings(road(), List.of());

        long north = c.place(2, at(0, 3));
        long south = c.place(2, at(0, -3));

        assertThat(north).isNotEqualTo(south).isNotEqualTo(2);
        // 북쪽 골목(4)은 GPS가 북쪽일 때 고른 노드에, 남쪽 골목(5)은 남쪽 노드에 붙어 있다
        assertThat(c.graph().graph().edgesOf(north)).extracting(e -> c.graph().original(e).id()).contains(4L)
                .doesNotContain(5L);
        assertThat(c.graph().graph().edgesOf(south)).extracting(e -> c.graph().original(e).id()).contains(5L)
                .doesNotContain(4L);
        assertThat(c.graph().point(north)).isEqualTo(road().point(2));
    }

    @Test
    void sideIsMeasuredAlongTheSideStreetNotItsFirstVertex() {
        // 북쪽 골목의 첫 꼭짓점이 큰길과 거의 평행하게 1m 남쪽으로 꺾였다가 북쪽으로 간다: 4m 지점은 북쪽
        Map<Long, double[]> nodes = new HashMap<>(Map.of(1L, at(-500, 0), 2L, at(0, 0), 3L, at(500, 0),
                5L, at(30, 50), 6L, at(0, -50)));
        List<double[]> bent = List.of(at(0, 0), at(3, -0.1), at(3, 30), at(30, 50));
        RoadGraph g = new RoadGraph(nodes, List.of(
                new RoadEdge(1, 1, 2, 500, "primary", List.of(at(-500, 0), at(0, 0)), EdgePenalty.NONE),
                new RoadEdge(2, 2, 3, 500, "primary", List.of(at(0, 0), at(500, 0)), EdgePenalty.NONE),
                new RoadEdge(4, 2, 5, Geo.length(bent), "residential", bent, EdgePenalty.NONE),
                new RoadEdge(5, 2, 6, 50, "residential", List.of(at(0, 0), at(0, -50)), EdgePenalty.NONE)));

        Crossings c = new Crossings(g, List.of());

        assertThat(c.graph().graph().edgesOf(c.place(2, at(0, 3)))).extracting(e -> c.graph().original(e).id())
                .contains(4L);
    }
}
