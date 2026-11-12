package com.saferoute.route.engine.explain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteResult.Crossing;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.search.RoadPath;

class SideChangesTests {

    static final double LAT = 35.14;

    /** 동서 큰길 위 x(m), 북쪽 y(m) 점 */
    static double[] at(double x, double y) {
        return new double[] {126.93 + x / Geo.distance(0, LAT, 1, LAT), LAT + y / Geo.distance(0, 0, 0, 1)};
    }

    // 큰길 A(-100) – B(0) – C(100) – D(200), B 북쪽 골목 N(50m)
    static final double[] A = at(-100, 0), B = at(0, 0), C = at(100, 0), D = at(200, 0), N = at(0, 50);
    static final RoadEdge AB = edge(1, 1, 2, "primary", A, B);
    static final RoadEdge BC = edge(2, 2, 3, "primary", B, C);
    static final RoadEdge CD = edge(3, 3, 4, "primary", C, D);
    static final RoadEdge BN = edge(4, 2, 5, "residential", B, N);
    static final List<RoadEdge> ROADS = List.of(AB, BC, CD, BN);

    /** B(노드 2)에서 왼쪽 → 오른쪽으로 B–C 갈래를 건넌 A → B → C */
    static final RoadPath.Cross AT_B = new RoadPath.Cross(100, 2, 2, "left", "right");

    @Test
    void crossingOnBigRoadUsesCrosswalkOnThatRoad() {
        double[] onRoad = at(10, 1);

        List<Crossing> out = SideChanges.along(path(List.of(AB, BC), AT_B), 0, graph(ROADS), List.of(onRoad));

        assertThat(out).singleElement().satisfies(c -> {
            assertThat(c.fromM()).isEqualTo(100).isEqualTo(c.toM());
            assertThat(c.lat()).isEqualTo(onRoad[1]);
            assertThat(c.lon()).isEqualTo(onRoad[0]);
            assertThat(c.kind()).isEqualTo("crossing");
            assertThat(c.fromSide()).isEqualTo("left");
            assertThat(c.toSide()).isEqualTo("right");
        });
        // 출발에서 경로 첫 점까지 거리를 더한다
        assertThat(SideChanges.along(path(List.of(AB, BC), AT_B), 7, graph(ROADS), List.of(onRoad)))
                .extracting(Crossing::fromM).containsExactly(107);
    }

    @Test
    void noCrossingGivesNothing() {
        // 걷는 쪽 이름이 바뀌어도(모퉁이) 건넌 곳이 없으면 항목이 없다
        assertThat(SideChanges.along(path(List.of(AB, BC)), 0, graph(ROADS), List.of(at(10, 1)))).isEmpty();
    }

    @Test
    void crosswalkOnSideAlleyIsNotUsed() {
        // 골목 B–N 선 위 20m 점: 큰길 선에서 20m라 그 큰길을 건너는 횡단보도가 아니다. 쪽만 보낸다
        List<Crossing> out = SideChanges.along(path(List.of(AB, BC), AT_B), 0, graph(ROADS), List.of(at(0, 20)));

        assertThat(out).singleElement().satisfies(c -> {
            assertThat(c.lat()).isNull();
            assertThat(c.lon()).isNull();
            assertThat(c.kind()).isNull();
            assertThat(c.fromSide()).isEqualTo("left");
            assertThat(c.toSide()).isEqualTo("right");
        });
    }

    @Test
    void fallsBackToCrosswalkFootwayCentre() {
        // 횡단보도 점은 없고 B에서 15m 동쪽에 큰길 B–C를 가로지르는 횡단보도 길만
        RoadEdge zebra = new RoadEdge(9, 7, 8, 16, "footway", List.of(at(15, -8), at(15, 8)),
                new EdgePenalty(Map.of(Penalty.CROSSWALK, 1.0)));
        List<RoadEdge> all = new ArrayList<>(ROADS);
        all.add(zebra);

        List<Crossing> out = SideChanges.along(path(List.of(AB, BC), AT_B), 0, graph(all), List.of());

        assertThat(out).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo("footway");
            assertThat(Geo.distance(new double[] {c.lon(), c.lat()}, at(15, 0))).isLessThan(0.01);
        });
    }

    @Test
    void footwayCrossingStretchKeepsDistancesAndMergesByDistance() {
        // A→B 왼쪽, B→C 오른쪽, C에서 횡단보도 길 C–Z(16m)를 건넘. 출발 점이 첫 점에서 5m
        double[] z = at(100, -16);
        RoadEdge cz = new RoadEdge(9, 3, 9, 16, "footway", List.of(C, z), new EdgePenalty(Map.of(Penalty.CROSSWALK, 1.0)));
        RoadPath p = new RoadPath(List.of(AB, BC, cz), List.of(A, B, C, z), 216, List.of(AT_B));

        List<Crossing> out = RouteResultAssembler.crossings(p, at(-100, 5), graph(List.of(AB, BC, CD, cz)), List.of());

        assertThat(out).extracting(Crossing::fromM, Crossing::toM, Crossing::fromSide)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(105, 105, "left"),
                        org.assertj.core.groups.Tuple.tuple(205, 221, null));
        assertThat(out.get(1).lat()).isNull();
        assertThat(out.get(1).kind()).isNull();
        assertThat(out.get(1).toSide()).isNull();
    }

    private static RoadPath path(List<RoadEdge> edges, RoadPath.Cross... crosses) {
        List<double[]> line = new ArrayList<>();
        long at = edges.get(0).from();
        line.add(edges.get(0).geometry().get(0));
        double len = 0;
        for (RoadEdge e : edges) {
            boolean fwd = e.from() == at;
            line.add(fwd ? e.geometry().get(1) : e.geometry().get(0));
            at = fwd ? e.to() : e.from();
            len += e.lengthM();
        }
        return new RoadPath(edges, line, len, List.of(crosses));
    }

    private static RoadEdge edge(long id, long from, long to, String highway, double[] p, double[] q) {
        List<double[]> g = List.of(p, q);
        return new RoadEdge(id, from, to, Math.round(Geo.length(g)), highway, g, EdgePenalty.NONE);
    }

    private static RoadGraph graph(List<RoadEdge> edges) {
        Map<Long, double[]> nodes = new HashMap<>();
        for (RoadEdge e : edges) {
            nodes.put(e.from(), e.geometry().get(0));
            nodes.put(e.to(), e.geometry().get(e.geometry().size() - 1));
        }
        return new RoadGraph(nodes, edges);
    }
}
