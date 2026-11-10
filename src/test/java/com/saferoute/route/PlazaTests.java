package com.saferoute.route;

import static com.saferoute.route.TestRoutes.LAT;
import static com.saferoute.route.TestRoutes.M_P;
import static com.saferoute.route.TestRoutes.S_P;
import static com.saferoute.route.TestRoutes.T_P;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;

/** 보행자 광장 가로지르기 길 (w13): 다른 모드는 평지 보도로 쓰고, 휠체어는 쓰지 않는다 (높낮이를 모르는 선, 2026-11-10) */
class PlazaTests {

    /** S–M–T 지름길은 광장 길, N을 거치는 길은 보통 보도 */
    static RoadGraph plazaShortcut() {
        double[] n = {126.93110, LAT + 0.0006};
        Map<Long, double[]> nodes = new LinkedHashMap<>();
        nodes.put(1L, S_P);
        nodes.put(2L, M_P);
        nodes.put(3L, T_P);
        nodes.put(4L, n);
        EdgePenalty plaza = new EdgePenalty(Map.of(Penalty.PLAZA, 1.0));
        List<RoadEdge> edges = new ArrayList<>();
        edges.add(TestRoutes.edge(10, 1, 2, S_P, M_P, plaza));
        edges.add(TestRoutes.edge(11, 2, 3, M_P, T_P, plaza));
        edges.add(TestRoutes.edge(12, 1, 4, S_P, n, EdgePenalty.NONE));
        edges.add(TestRoutes.edge(13, 4, 3, n, T_P, EdgePenalty.NONE));
        return new RoadGraph(nodes, edges);
    }

    static RouteResult route(String mode) {
        RouteService s = TestRoutes.service(plazaShortcut(), TestRoutes.pois(List.of()), 12);
        return s.route(new RouteRequest(new Place(S_P[1], S_P[0], null), new Place(T_P[1], T_P[0], null), "off", mode));
    }

    @Test
    void otherModesWalkAcrossThePlazaButWheelchairDoesNot() {
        for (String mode : List.of("adult", "child", "senior")) {
            RouteResult r = route(mode);
            assertThat(r.routes().get(r.routes().size() - 1).distanceM()).as(mode).isLessThan(230);
        }
        RouteResult w = route("wheelchair");
        assertThat(w.routes().get(w.routes().size() - 1).distanceM()).isGreaterThan(230);
    }
}
