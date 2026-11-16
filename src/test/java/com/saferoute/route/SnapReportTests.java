package com.saferoute.route;

import static com.saferoute.route.RouteReportTests.report;
import static com.saferoute.route.TestRoutes.LAT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.event.RoadReport;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 출발·도착을 길에 붙이는 곧은 줄이 피할 신고 반경을 지나면 다른 후보에 붙인다 (2026-11-16).
 * 출발점은 늘 (0, 0), 좌표는 그곳에서 동쪽·북쪽 m
 */
class SnapReportTests {

    static final double D_LAT = 1 / 111_195.0;
    static final double D_LON = D_LAT / Math.cos(Math.toRadians(LAT));

    static double[] xy(double eastM, double northM) {
        return new double[] {126.93 + eastM * D_LON, LAT + northM * D_LAT};
    }

    /** 노드 번호 → 자리, 길은 두 노드 쌍 (번호 100부터). penalty가 있으면 마지막 길에만 */
    static RoadGraph graph(Map<Long, double[]> nodes, long[][] pairs, EdgePenalty last) {
        List<RoadEdge> edges = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i++) {
            long a = pairs[i][0];
            long b = pairs[i][1];
            edges.add(TestRoutes.edge(100 + i, a, b, nodes.get(a), nodes.get(b),
                    i == pairs.length - 1 && last != null ? last : EdgePenalty.NONE));
        }
        return new RoadGraph(new HashMap<>(nodes), edges);
    }

    static RouteResult route(RoadGraph g, double[] to, String mode, RoadReport report) {
        RouteService s = TestRoutes.service(g, TestRoutes.pois(List.of(), List.of(), List.of(report)), 12);
        double[] from = xy(0, 0);
        return s.route(new RouteRequest(new Place(from[1], from[0], "출발"), new Place(to[1], to[0], "도착"), "auto", mode));
    }

    /**
     * 남쪽 길 28m, 북쪽 길 28m(남쪽이 먼저 보이게 같은 거리). 신고 (-16, -14)는 출발점·남쪽 발 (0, -28)에서 모두 21m(반경 밖)인데
     * 그 사이 곧은 줄에서는 16m라, 줄이 반경을 지나 너머로 간다
     */
    static RouteResult twoRoads(String type) {
        RoadGraph g = graph(Map.of(1L, xy(-50, -28), 2L, xy(250, -28), 3L, xy(-50, 28.5), 4L, xy(250, 28.5)),
                new long[][] {{1, 2}, {3, 4}, {1, 3}, {2, 4}}, null);
        return route(g, xy(200, 28.5), "adult", report(type, xy(-16, -14)));
    }

    @Test
    void connectorThroughAvoidedReportAttachesToTheOtherRoad() {
        RouteResult r = twoRoads("construction");
        // 0번은 요청 출발점, 1번이 붙인 곳
        assertThat(r.routes().get(0).geometry().get(1)[1]).isGreaterThan(LAT);
        assertThat(r.explanation().notes()).noneMatch(n -> n.kind().equals("snap_through_report"));
    }

    @Test
    void costedOnlyReportKeepsTheNearestRoad() {
        // 어른에게 턱은 비용만이라 붙일 곳을 바꾸지 않는다
        RouteResult r = twoRoads("curb");
        assertThat(r.routes().get(0).geometry().get(1)[1]).isLessThan(LAT);
        assertThat(r.explanation().notes()).noneMatch(n -> n.kind().equals("snap_through_report"));
    }

    @Test
    void noOtherCandidateKeepsTheSnapAndWarns() {
        // 길 A(-10,-45)–B(10,-45)–C(10,-200)뿐, 신고는 (0, -22): 어느 노드·발로 가도 반경을 지나 너머로 간다.
        // 9번은 출발점이 범위 안이게 둔 외딴 노드
        RoadGraph g = graph(Map.of(1L, xy(-10, -45), 2L, xy(10, -45), 3L, xy(10, -200), 9L, xy(0, 10)),
                new long[][] {{1, 2}, {2, 3}}, null);
        RouteResult r = route(g, xy(10, -200), "adult", report("construction", xy(0, -22)));

        double[] at = r.routes().get(0).geometry().get(1);
        assertThat(at[1]).isCloseTo(xy(0, -45)[1], org.assertj.core.data.Offset.offset(1e-6));
        assertThat(at[0]).isCloseTo(xy(0, -45)[0], org.assertj.core.data.Offset.offset(1e-6));
        assertThat(r.explanation().notes())
                .contains(RouteResult.Note.warn("snap_through_report", "출발점 근처 곧은 연결 구간에 공사 신고가 있어요"));
    }

    /**
     * 문화전당역 5번 → 4번 출구 휠체어 (2026-11-16 현장): 출발점은 계단으로만 나가는 길(섬) 위라 지날 수 있는 덩어리에 다시 붙인다.
     * 가장 가까운 곳은 북쪽 길 (0, 60)인데 곧은 줄이 북쪽 30m 공사 신고를 지난다. 서쪽 길 (-60, 0)은 지나지 않고 100m 한도 안이다
     * <pre>
     *   W(-60,60) ——— K(10,60) ——— E(100,60)
     *     |              | 계단
     *     |   신고(0,30)  |
     *     |   O  I1———I2(10,-3)
     *   SW(-60,-30)
     * </pre>
     */
    @Test
    void wheelchairResnapSkipsTheConnectorThroughTheReport() {
        RoadGraph g = graph(Map.of(1L, xy(-60, 60), 2L, xy(10, 60), 3L, xy(100, 60), 4L, xy(-60, -30), 5L, xy(-10, -3),
                6L, xy(10, -3)), new long[][] {{1, 2}, {2, 3}, {1, 4}, {5, 6}, {6, 2}}, RouteFinderTests.STAIRS);
        RouteResult r = route(g, xy(100, 60), "wheelchair", report("construction", xy(0, 30)));

        double[] at = r.routes().get(0).geometry().get(1);
        assertThat(at[0]).isCloseTo(xy(-60, 0)[0], org.assertj.core.data.Offset.offset(1e-6));
        assertThat(r.explanation().notes()).anyMatch(n -> n.kind().equals("snap"))
                .noneMatch(n -> n.kind().equals("snap_through_report"));
    }
}
