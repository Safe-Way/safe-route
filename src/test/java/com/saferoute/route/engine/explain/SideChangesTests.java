package com.saferoute.route.engine.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.search.RoadPath;

/** 큰 길을 건너는 곳과 횡단보도 자리 (specs/014) */
class SideChangesTests {

    private static final double LAT = 35.14;

    // 가운데 노드 0에서 동서남북 100m
    private static final Map<Long, double[]> NODES = Map.of(0L, at(0, 0), 1L, at(-100, 0), 2L, at(100, 0),
            3L, at(0, 100), 4L, at(0, -100));

    @Test
    void leftToRightUsesCrosswalkOnTheBigRoad() {
        for (String highway : List.of("primary", "tertiary")) {
            RoadEdge in = road(1, 1, 0, highway, "left");
            RoadEdge out = road(2, 0, 2, highway, "right");
            double[] cw = at(8, 0);

            assertThat(SideChanges.along(path(in, out), 5, graph(in, out), List.of(cw)))
                    .singleElement().satisfies(c -> {
                        // 경로 선 첫 점이 응답 geometry 5번: 가운데 노드는 6번
                        assertThat(c.at()).isEqualTo(6);
                        assertThat(c.lat()).isEqualTo(cw[1]);
                        assertThat(c.lon()).isEqualTo(cw[0]);
                        assertThat(c.kind()).isEqualTo("crossing");
                        // 횡단보도 점은 양 끝을 모른다
                        assertThat(c.from()).isNull();
                        assertThat(c.to()).isNull();
                    });
        }
    }

    @Test
    void edgeWalkedAgainstDrawnDirectionFlipsSide() {
        // 동쪽 길을 2→0으로 그렸고 인도는 그린 방향 왼쪽: 0→2로 걸으면 오른쪽
        RoadEdge in = road(1, 1, 0, "primary", "left");
        RoadEdge out = road(2, 2, 0, "primary", "left");
        RoadPath p = new RoadPath(List.of(in, out), List.of(at(-100, 0), at(0, 0), at(100, 0)),
                in.lengthM() + out.lengthM());
        double[] cw = at(8, 0);

        assertThat(SideChanges.along(p, 0, graph(in, out), List.of(cw))).singleElement().satisfies(c -> {
            assertThat(c.at()).isEqualTo(1);
            assertThat(c.lon()).isEqualTo(cw[0]);
        });
    }

    @Test
    void turningBackOnSameEdgeIsNotACrossing() {
        // 들를 곳(노드 0)에서 같은 길로 되돌아간다: 걷는 쪽은 right → left로 바뀌지만 건너지 않았다
        RoadEdge in = road(1, 1, 0, "primary", "right");
        RoadPath p = new RoadPath(List.of(in, in), List.of(at(-100, 0), at(0, 0), at(-100, 0)), 2 * in.lengthM());

        assertThat(SideChanges.along(p, 0, graph(in), List.of(at(-8, 0)))).isEmpty();
    }

    @Test
    void noCrosswalkWithin30mOrOnlyOffTheLineSendsNothing() {
        RoadEdge in = road(1, 1, 0, "primary", "left");
        RoadEdge out = road(2, 0, 2, "primary", "right");

        assertThat(SideChanges.along(path(in, out), 0, graph(in, out), List.of(at(40, 0)))).isEmpty();
        // 20m 안이지만 큰 길 선에서 10m: 옆 골목을 건너는 횡단보도
        assertThat(SideChanges.along(path(in, out), 0, graph(in, out), List.of(at(3, 10)))).isEmpty();
    }

    @Test
    void bothSidesOnlyWhenTurningAtBigJunction() {
        RoadEdge w = road(1, 1, 0, "primary", null);
        RoadEdge e = road(2, 0, 2, "primary", null);
        RoadEdge n = road(3, 0, 3, "primary", null);
        RoadEdge s = road(4, 0, 4, "primary", null);
        RoadGraph junction = graph(w, e, n, s);
        // 북쪽 갈래 위와 동쪽 갈래 위 횡단보도
        List<double[]> cw = List.of(at(0, 8), at(8, 0));

        assertThat(SideChanges.along(path(w, e), 0, junction, cw)).isEmpty();
        assertThat(SideChanges.along(path(w, n), 0, junction, cw)).singleElement().satisfies(c -> {
            assertThat(c.at()).isEqualTo(1);
            assertThat(c.lat()).isEqualTo(at(0, 8)[1]);
        });
        // 큰 길 2개뿐인 모퉁이는 꺾어도 건너지 않는다
        assertThat(SideChanges.along(path(w, n), 0, graph(w, n), cw)).isEmpty();
    }

    @Test
    void footwayCrossingMiddleWhenNoPoint() {
        RoadEdge in = road(1, 1, 0, "primary", "left");
        RoadEdge out = road(2, 0, 2, "primary", "right");
        RoadEdge zebra = crosswalk(10, 5, 6, at(10, -8), at(10, 8));

        assertThat(SideChanges.along(path(in, out), 0, graph(in, out, zebra), List.of()))
                .singleElement().satisfies(c -> {
                    assertThat(c.kind()).isEqualTo("footway");
                    assertThat(c.lon()).isCloseTo(at(10, 0)[0], within(1e-9));
                    assertThat(c.lat()).isCloseTo(at(10, 0)[1], within(1e-9));
                    // 양 끝은 횡단보도 길의 첫 점·끝 점 [위도, 경도]
                    assertThat(c.from()).containsExactly(at(10, -8)[1], at(10, -8)[0]);
                    assertThat(c.to()).containsExactly(at(10, 8)[1], at(10, 8)[0]);
                });
    }

    @Test
    void atCountsEveryGeometryPointOfEarlierEdges() {
        // 들어오는 큰 길이 꺾임점 하나를 더 가진 선: 서쪽 끝(0번) – 꺾임점(1번) – 가운데 노드(2번)
        double[] bend = at(-50, 2);
        List<double[]> line = List.of(at(-100, 0), bend, at(0, 0));
        RoadEdge in = new RoadEdge(1, 1, 0, Geo.length(line), "primary", line, EdgePenalty.NONE, "left");
        RoadEdge out = road(2, 0, 2, "primary", "right");
        RoadPath p = path(in, out);

        assertThat(SideChanges.along(p, 0, graph(in, out), List.of(at(8, 0)))).singleElement()
                .satisfies(c -> assertThat(p.line().get(c.at())).isEqualTo(at(0, 0)));
    }

    /** (126.93, LAT)에서 동쪽·북쪽으로 m만큼 떨어진 점 */
    private static double[] at(double eastM, double northM) {
        return new double[] {126.93 + eastM / (111_195.0 * Math.cos(Math.toRadians(LAT))), LAT + northM / 111_195.0};
    }

    private static RoadEdge road(long id, long from, long to, String highway, String sidewalk) {
        return road(id, from, to, highway, sidewalk, NODES);
    }

    private static RoadEdge road(long id, long from, long to, String highway, String sidewalk,
            Map<Long, double[]> nodes) {
        List<double[]> line = List.of(nodes.get(from), nodes.get(to));
        return new RoadEdge(id, from, to, Geo.length(line), highway, line, EdgePenalty.NONE, sidewalk);
    }

    private static RoadEdge crosswalk(long id, long from, long to, double[] a, double[] b) {
        List<double[]> line = List.of(a, b);
        return new RoadEdge(id, from, to, Geo.length(line), "footway", line,
                new EdgePenalty(Map.of(Penalty.CROSSWALK, 1.0)));
    }

    private static RoadGraph graph(RoadEdge... edges) {
        Map<Long, double[]> nodes = new HashMap<>();
        for (RoadEdge e : edges) {
            nodes.put(e.from(), e.geometry().get(0));
            nodes.put(e.to(), e.geometry().get(e.geometry().size() - 1));
        }
        return new RoadGraph(nodes, List.of(edges));
    }

    /** 길마다 from → to로 차례로 지나는 경로 */
    private static RoadPath path(RoadEdge... edges) {
        List<double[]> line = new ArrayList<>(List.of(edges[0].geometry().get(0)));
        double len = 0;
        for (RoadEdge e : edges) {
            line.addAll(e.geometry().subList(1, e.geometry().size()));
            len += e.lengthM();
        }
        return new RoadPath(List.of(edges), line, len);
    }
}
