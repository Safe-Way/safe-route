package com.saferoute.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.InMemoryRoadGraphSource;
import com.saferoute.route.engine.explain.RouteResultAssembler;
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.score.RouteScorer;
import com.saferoute.situation.NightJudge;

/** 큰길은 횡단보도·신호에서만 건너기 (specs/011): 요청부터 응답까지 */
class CrosswalkRouteTests {

    static final double LAT = 35.14;
    static final double M_PER_LAT = Geo.distance(0, 0, 0, 1);
    static final double M_PER_LON = Geo.distance(0, LAT, 1, LAT);

    /** 동서 큰길 위 x(m), 북쪽 y(m) 점 */
    static double[] at(double x, double y) {
        return new double[] {126.93 + x / M_PER_LON, LAT + y / M_PER_LAT};
    }

    static final double[] Y = at(100, 0);

    /**
     * 큰길 W(-500m) – X(0) – Y(100m) – E(600m), X 북쪽 골목 NX·남쪽 골목 SX (각 50m). Y에 신호등 점.
     * 나누지 않은 도로망이면 NX → SX는 X에서 곧장 100m
     */
    static RouteService service() {
        return service(List.of());
    }

    /** crosswalks: 횡단보도 점 (큰 길 건너는 자리, specs/013) */
    static RouteService service(List<double[]> crosswalks) {
        Map<Long, double[]> nodes = Map.of(1L, at(-500, 0), 2L, at(0, 0), 3L, Y, 4L, at(600, 0),
                5L, at(0, 50), 6L, at(0, -50));
        List<RoadEdge> edges = List.of(edge(1, 1, 2, nodes, "primary"), edge(2, 2, 3, nodes, "primary"),
                edge(3, 3, 4, nodes, "primary"), edge(4, 2, 5, nodes, "residential"),
                edge(5, 2, 6, nodes, "residential"));
        return service(new RoadGraph(nodes, edges), List.of(Y), crosswalks);
    }

    /** crossable: 건널 수 있는 곳 점 (횡단보도·신호), crosswalks: 응답 crossings 자리에 쓰는 횡단보도 점 */
    static RouteService service(RoadGraph graph, List<double[]> crossable, List<double[]> crosswalks) {
        var roads = new InMemoryRoadGraphSource(graph) {
            @Override
            public List<double[]> crossingPoints(BBox box) {
                return crossable;
            }

            @Override
            public List<double[]> crosswalkPoints(BBox box) {
                return crosswalks;
            }
        };
        RouteProperties p = TestRoutes.props();
        var now = TestRoutes.at(12);
        return new RouteService(roads, TestRoutes.pois(List.of()), new NightJudge(), new RouteScorer(p),
                new RouteResultAssembler(new FacilityCounter(p), p), p, Clock.fixed(now.toInstant(), now.getZone()));
    }

    static RoadEdge edge(long id, long from, long to, Map<Long, double[]> nodes, String highway) {
        List<double[]> line = List.of(nodes.get(from), nodes.get(to));
        return new RoadEdge(id, from, to, Geo.length(line), highway, line, EdgePenalty.NONE);
    }

    static RouteResult.Route route(double[] from, double[] to) {
        return service().route(new RouteRequest(new Place(from[1], from[0], null), new Place(to[1], to[0], null),
                "auto", "adult")).routes().get(0);
    }

    static boolean passesY(RouteResult.Route r) {
        return r.geometry().stream().anyMatch(p -> Geo.distance(p, Y) < 0.01);
    }

    @Test
    void oppositeSideCrossesAtTheCrosswalk() {
        RouteResult.Route r = route(at(0, 50), at(0, -50));

        // X에서 곧장 건너지 않고 Y 신호등까지 갔다 온다: 50 + 100 + 100 + 50
        assertThat(passesY(r)).isTrue();
        assertThat((double) r.distanceM()).isCloseTo(300, within(2.0));
    }

    @Test
    void sameSideDestinationOnTheBigRoadDoesNotCross() {
        // 도착점이 W–X 큰길 가운데의 북쪽 3m: 큰길 위에 붙지만 북쪽 줄에서 끝난다 (50 + 250, 붙인 3m 더함)
        RouteResult.Route north = route(at(0, 50), at(-250, 3));
        // 남쪽 3m면 건너야 해서 Y 신호등을 거친다 (50 + 100 + 100 + 250 + 3)
        RouteResult.Route south = route(at(0, 50), at(-250, -3));

        assertThat(passesY(north)).isFalse();
        assertThat((double) north.distanceM()).isCloseTo(303, within(2.0));
        assertThat(passesY(south)).isTrue();
        assertThat((double) south.distanceM()).isCloseTo(503, within(2.0));
    }

    @Test
    void sideChangeOnTheBigRoadTellsWhereToCross() {
        // 서쪽 북쪽 줄(동쪽으로 걸으니 왼쪽)에서 동쪽 남쪽 줄(오른쪽)로: Y에서 건너고, Y 옆 8m 횡단보도 점을 준다
        double[] zebra = at(108, 0);
        RouteResult.Route r = service(List.of(zebra)).route(new RouteRequest(new Place(at(-250, 3)[1],
                at(-250, 3)[0], null), new Place(at(350, -3)[1], at(350, -3)[0], null), "auto", "adult")).routes().get(0);

        assertThat(r.crossings()).singleElement().satisfies(c -> {
            // 출발에서 길까지 3m + 250 + 100
            assertThat((double) c.fromM()).isCloseTo(353, within(2.0));
            assertThat(c.toM()).isEqualTo(c.fromM());
            assertThat(c.lat()).isEqualTo(zebra[1]);
            assertThat(c.lon()).isEqualTo(zebra[0]);
            assertThat(c.kind()).isEqualTo("crossing");
            assertThat(c.fromSide()).isEqualTo("left");
            assertThat(c.toSide()).isEqualTo("right");
        });
    }

    @Test
    void straightAlongTheBigRoadHasNoCrossing() {
        // 같은 북쪽 줄로 X를 지나 곧장: 쪽이 안 바뀐다
        RouteResult.Route r = service(List.of(at(108, 0))).route(new RouteRequest(
                new Place(at(-250, 3)[1], at(-250, 3)[0], null), new Place(at(350, 3)[1], at(350, 3)[0], null),
                "auto", "adult")).routes().get(0);

        assertThat(r.crossings()).isEmpty();
    }

    /**
     * 큰길 W(0) – C1(100) – S1(200) – C2(300) – S2(400) – C3(500) – S3(600) – E(700), S1 북쪽·S2 남쪽 골목. C1·C2·C3에 횡단보도 점.
     * 길을 번갈아 거꾸로 그려 L·R 복사 순서가 섞이게 했다. 건널 곳에서 L·R 줄이 공짜로 만나면 같은 길이라 탐색이 쪽을 오갔다
     */
    static RouteResult.Route alongCrosswalks(double[] from, double[] to) {
        Map<Long, double[]> nodes = new java.util.HashMap<>();
        List<RoadEdge> edges = new java.util.ArrayList<>();
        for (long k = 0; k <= 7; k++) {
            nodes.put(k + 1, at(100 * k, 0));
            if (k > 0) {
                edges.add(k % 2 == 0 ? edge(k, k, k + 1, nodes, "primary") : edge(k, k + 1, k, nodes, "primary"));
            }
        }
        nodes.put(9L, at(200, 50));
        nodes.put(10L, at(400, -50));
        edges.add(edge(9, 3, 9, nodes, "residential"));
        edges.add(edge(10, 5, 10, nodes, "residential"));
        List<double[]> zebras = List.of(at(100, 0), at(300, 0), at(500, 0));
        return service(new RoadGraph(nodes, edges), zebras, zebras).route(new RouteRequest(
                new Place(from[1], from[0], null), new Place(to[1], to[0], null), "auto", "adult")).routes().get(0);
    }

    @Test
    void sameSideAlongSeveralCrosswalksDoesNotZigzag() {
        assertThat(alongCrosswalks(at(50, 3), at(650, 3)).crossings()).isEmpty();
        assertThat(alongCrosswalks(at(50, -3), at(650, -3)).crossings()).isEmpty();
    }

    @Test
    void otherSideAlongSeveralCrosswalksCrossesOnceAtACrosswalk() {
        RouteResult.Route r = alongCrosswalks(at(50, -3), at(650, 3));

        assertThat(r.crossings()).singleElement().satisfies(c -> {
            // 출발에서 길까지 3m + C1·C2·C3 중 한 곳까지
            assertThat(List.of(53, 253, 453)).anySatisfy(m -> assertThat((double) c.fromM()).isCloseTo(m, within(2.0)));
            assertThat(c.toM()).isEqualTo(c.fromM());
            assertThat(c.kind()).isEqualTo("crossing");
            assertThat(c.fromSide()).isEqualTo("right");
            assertThat(c.toSide()).isEqualTo("left");
        });
        assertThat((double) r.distanceM()).isCloseTo(606, within(2.0));
    }

    /** 네거리 J(0, 0)에 큰길 네 갈래 W·E·N·S (각 300m). 횡단보도 점은 W 갈래 위 J에서 8m */
    static RouteResult.Route junction(double[] from, double[] to) {
        return junction(from, to, List.of(at(-8, 0)));
    }

    static RouteResult.Route junction(double[] from, double[] to, List<double[]> crosswalks) {
        Map<Long, double[]> nodes = Map.of(1L, at(0, 0), 2L, at(-300, 0), 3L, at(300, 0), 4L, at(0, 300),
                5L, at(0, -300));
        List<RoadEdge> edges = List.of(edge(1, 2, 1, nodes, "primary"), edge(2, 1, 3, nodes, "primary"),
                edge(3, 1, 4, nodes, "secondary"), edge(4, 5, 1, nodes, "secondary"));
        return route(new RoadGraph(nodes, edges), crosswalks, from, to);
    }

    static RouteResult.Route route(RoadGraph graph, List<double[]> crosswalks, double[] from, double[] to) {
        return service(graph, List.of(), crosswalks).route(new RouteRequest(
                new Place(from[1], from[0], null), new Place(to[1], to[0], null), "auto", "adult")).routes().get(0);
    }

    @Test
    void straightThroughJunctionOnTheSameSideHasNoCrossing() {
        // 북쪽 인도로 네거리를 곧장: N 갈래(가로 큰길)는 건너지만 걷는 큰길의 쪽은 그대로
        RouteResult.Route r = junction(at(-200, 3), at(200, 3));

        assertThat(r.crossings()).isEmpty();
        assertThat((double) r.distanceM()).isCloseTo(406, within(2.0));
    }

    @Test
    void rightTurnAtJunctionWithoutCrossingHasNoCrossing() {
        // S 갈래 동쪽 인도로 북쪽으로 와서 E 갈래 남쪽 인도로 오른쪽으로 꺾음: 같은 남동 모퉁이
        RouteResult.Route r = junction(at(3, -200), at(200, -3));

        assertThat(r.crossings()).isEmpty();
        assertThat((double) r.distanceM()).isCloseTo(406, within(2.0));
    }

    @Test
    void crossingOneArmAtJunctionIsOneItemThere() {
        // W 갈래 북쪽 인도에서 S 갈래 서쪽 인도로: W 갈래를 한 번 건넌다 (출발에서 길까지 3m + 200m)
        RouteResult.Route r = junction(at(-200, 3), at(-3, -200));

        assertThat(r.crossings()).singleElement().satisfies(c -> {
            assertThat((double) c.fromM()).isCloseTo(203, within(2.0));
            assertThat(c.toM()).isEqualTo(c.fromM());
            assertThat(c.kind()).isEqualTo("crossing");
            assertThat(c.lon()).isEqualTo(at(-8, 0)[0]);
            assertThat(c.fromSide()).isEqualTo("left");
            assertThat(c.toSide()).isEqualTo("right");
        });
    }

    @Test
    void crossingUsesCrosswalkOnTheArmCrossedNotTheNearest() {
        // W 갈래를 건넌다: 더 가까운 E 갈래 6m 점이 아니라 W 갈래 8m 점
        RouteResult.Route r = junction(at(-200, 3), at(-3, -200), List.of(at(-8, 0), at(6, 0)));

        assertThat(r.crossings()).singleElement().satisfies(c -> assertThat(c.lon()).isEqualTo(at(-8, 0)[0]));
    }

    @Test
    void turningAcrossTheSideArmAtTJunctionIsACrossing() {
        // T자 W–J–E 큰길에 S 갈래. S 갈래 동쪽 인도로 북쪽으로 와 W 갈래 북쪽 인도로: E 갈래(W–E 큰길)를 한 번 건넌다
        // 9: 길 없는 점. 요청 지역(노드 범위)이 북쪽 인도까지 들게
        Map<Long, double[]> nodes = Map.of(1L, at(0, 0), 2L, at(-300, 0), 3L, at(300, 0), 5L, at(0, -300),
                9L, at(-300, 50));
        List<RoadEdge> edges = List.of(edge(1, 2, 1, nodes, "primary"), edge(2, 1, 3, nodes, "primary"),
                edge(4, 5, 1, nodes, "secondary"));

        RouteResult.Route r = route(new RoadGraph(nodes, edges), List.of(), at(3, -200), at(-200, 3));

        assertThat(r.crossings()).singleElement()
                .satisfies(c -> assertThat((double) c.fromM()).isCloseTo(203, within(2.0)));
    }

    @Test
    void crossingOnACrosswalkWayIsOneItem() {
        // 큰길 W–X–E를 X에 붙은 횡단보도 길 N–X–S(각 30m)로 건넘: 횡단보도 길 구간 하나만 (건넌 곳 항목과 겹치지 않게)
        Map<Long, double[]> nodes = Map.of(1L, at(-300, 0), 2L, at(0, 0), 3L, at(300, 0), 4L, at(0, 30),
                5L, at(0, -30));
        EdgePenalty zebra = new EdgePenalty(Map.of(com.saferoute.route.engine.graph.Penalty.CROSSWALK, 1.0));
        List<RoadEdge> edges = List.of(edge(1, 1, 2, nodes, "primary"), edge(2, 2, 3, nodes, "primary"),
                new RoadEdge(4, 4, 2, 30, "footway", List.of(nodes.get(4L), nodes.get(2L)), zebra),
                new RoadEdge(5, 2, 5, 30, "footway", List.of(nodes.get(2L), nodes.get(5L)), zebra));

        RouteResult.Route r = route(new RoadGraph(nodes, edges), List.of(), at(0, 30), at(0, -30));

        assertThat(r.crossings()).singleElement().satisfies(c -> {
            assertThat(c.fromM()).isZero();
            assertThat(c.toM()).isEqualTo(60);
        });
    }
}
