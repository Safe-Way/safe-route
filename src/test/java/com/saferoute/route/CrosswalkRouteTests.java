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
        Map<Long, double[]> nodes = Map.of(1L, at(-500, 0), 2L, at(0, 0), 3L, Y, 4L, at(600, 0),
                5L, at(0, 50), 6L, at(0, -50));
        List<RoadEdge> edges = List.of(edge(1, 1, 2, nodes, "primary"), edge(2, 2, 3, nodes, "primary"),
                edge(3, 3, 4, nodes, "primary"), edge(4, 2, 5, nodes, "residential"),
                edge(5, 2, 6, nodes, "residential"));
        var roads = new InMemoryRoadGraphSource(new RoadGraph(nodes, edges)) {
            @Override
            public List<double[]> crossingPoints(BBox box) {
                return List.of(Y);
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
}
