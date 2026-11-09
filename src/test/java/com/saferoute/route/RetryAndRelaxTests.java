package com.saferoute.route;

import static com.saferoute.route.TestRoutes.edge;
import static com.saferoute.route.TestRoutes.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteError;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.InMemoryRoadGraphSource;
import com.saferoute.route.data.RoadGraphSource;
import com.saferoute.route.engine.explain.RouteResultAssembler;
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.score.RouteScorer;
import com.saferoute.situation.NightJudge;

/**
 * 경로를 못 찾았을 때 범위를 넓혀 다시 찾기, 휠체어 비포장 조건부 제외와 알림 (2026-11-04 결정).
 * 운영 테스트(RouteRandomCheck) 시드 20261104에서 찾은 경우를 작게 줄였다.
 */
class RetryAndRelaxTests {

    static final double LAT = 35.14;
    static final double M_PER_LAT = Geo.distance(0, 0, 0, 1);
    static final double[] S = {126.93, LAT};
    /** S 동쪽 약 365m */
    static final double[] T = {126.934, LAT};

    static double[] north(double[] p, double meters) {
        return new double[] {p[0], p[1] + meters / M_PER_LAT};
    }

    /** 범위 안 노드와, 두 끝이 모두 범위 안인 길만 주는 도로망 (DB가 사각형으로 읽는 것과 같게) */
    static RoadGraphSource clipped(RoadGraph all) {
        BBox area = BBox.around(all.nodes().values().stream().toList(), 0);
        return new RoadGraphSource() {
            @Override
            public boolean covers(double lon, double lat) {
                return area.contains(lon, lat);
            }

            @Override
            public RoadGraph load(BBox box) {
                Map<Long, double[]> nodes = new HashMap<>();
                all.nodes().forEach((id, p) -> {
                    if (box.contains(p[0], p[1])) {
                        nodes.put(id, p);
                    }
                });
                List<RoadEdge> edges = all.graph().edgeSet().stream()
                        .filter(e -> nodes.containsKey(e.from()) && nodes.containsKey(e.to())).toList();
                return new RoadGraph(nodes, edges);
            }
        };
    }

    static RouteService service(RoadGraphSource roads) {
        RouteProperties p = props();
        var now = TestRoutes.at(12);
        return new RouteService(roads, TestRoutes.pois(List.of()), new NightJudge(), new RouteScorer(p),
                new RouteResultAssembler(new FacilityCounter(p), p), p, Clock.fixed(now.toInstant(), now.getZone()));
    }

    static RouteRequest request(String mode) {
        return new RouteRequest(new Place(S[1], S[0], null), new Place(T[1], T[0], null), "auto", mode);
    }

    @Test
    void retriesWithWiderAreaWhenTheOnlyWayGoesAround() {
        // S와 T 사이에 바로 가는 길이 없고, 북쪽 1.5km 다리로 돌아가야 한다 (읽는 범위 500m 밖)
        double[] a = north(S, 1500);
        double[] b = north(T, 1500);
        RoadGraph g = new RoadGraph(Map.of(1L, S, 2L, a, 3L, b, 4L, T), List.of(
                edge(1, 1, 2, S, a, EdgePenalty.NONE), edge(2, 2, 3, a, b, EdgePenalty.NONE),
                edge(3, 3, 4, b, T, EdgePenalty.NONE)));

        RouteResult r = service(clipped(g)).route(request("adult"));

        assertThat(r.routes().get(0).distanceM()).isBetween(3300, 3400);
    }

    static RoadGraph oneEdge(EdgePenalty p) {
        return new RoadGraph(Map.of(1L, S, 2L, T), List.of(edge(1, 1, 2, S, T, p)));
    }

    @Test
    void wheelchairNeverRelaxesStairsEvenWithUnknownRamp() {
        for (Boolean ramp : new Boolean[] {null, false}) {
            RouteService s = service(new InMemoryRoadGraphSource(oneEdge(
                    new EdgePenalty(Map.of(Penalty.STAIRS, 1.0), ramp))));

            assertThatThrownBy(() -> s.route(request("wheelchair"))).isInstanceOfSatisfying(RouteException.class,
                    e -> assertThat(e.error()).isEqualTo(RouteError.NO_ROUTE));
        }
    }

    @Test
    void wheelchairUnpavedWithNoWayAroundSaysSo() {
        RouteResult r = service(new InMemoryRoadGraphSource(oneEdge(
                new EdgePenalty(Map.of(Penalty.UNPAVED, 1.0))))).route(request("wheelchair"));

        assertThat(r.explanation().text()).contains("비포장길 약 364m를 지납니다").contains("비포장을 피하는 길은 없습니다");
    }

    /** 비포장 지름길 S–T와, 북쪽 northM으로 돌아가는 포장길 S–N–T */
    static RoadGraph unpavedOrAround(double northM) {
        double[] n = north(new double[] {126.932, LAT}, northM);
        return new RoadGraph(Map.of(1L, S, 2L, T, 3L, n), List.of(
                edge(1, 1, 2, S, T, new EdgePenalty(Map.of(Penalty.UNPAVED, 1.0))),
                edge(2, 1, 3, S, n, EdgePenalty.NONE), edge(3, 3, 2, n, T, EdgePenalty.NONE)));
    }

    @Test
    void wheelchairAvoidsUnpavedWhenWayAroundIsWithinLimit() {
        // 돌아가는 길 약 1.1배 ≤ 1.2 → 포장길, 알림 없음
        RouteResult r = service(new InMemoryRoadGraphSource(unpavedOrAround(80))).route(request("wheelchair"));

        // 최단(비포장)과 안전(포장 우회) 두 경로를 주고, 안전 경로는 비포장을 지나지 않는다
        assertThat(r.routes()).extracting(RouteResult.Route::kind).containsExactly("shortest", "safe");
        assertThat(r.routes().get(1).distanceM()).isGreaterThan(390);
        assertThat(r.explanation().text()).isEqualTo("33m(1분) 더 돌지만 비포장을 피하는 길로 갑니다");
    }

    @Test
    void wheelchairTakesUnpavedWhenWayAroundIsTooLongAndSaysHowMuchLonger() {
        // 돌아가는 길 약 1.9배 > 1.2 → 비포장 지름길, 피하면 얼마나 더 걸리는지 알림
        RouteResult r = service(new InMemoryRoadGraphSource(unpavedOrAround(300))).route(request("wheelchair"));

        assertThat(r.routes()).hasSize(1);
        assertThat(r.routes().get(0).distanceM()).isBetween(360, 370);
        assertThat(r.explanation().text()).contains("비포장길 약 364m를 지납니다").containsPattern("비포장을 피하면 \\d+m\\(\\d+분\\) 더 걸립니다");
    }

    @Test
    void otherModesIgnoreUnpaved() {
        RouteResult r = service(new InMemoryRoadGraphSource(unpavedOrAround(80))).route(request("adult"));

        assertThat(r.routes().get(0).distanceM()).isBetween(360, 370);
        assertThat(r.explanation().text()).doesNotContain("비포장");
    }

    /**
     * 큰길 S–T와, T 북쪽에 계단으로만 드나드는 구역(아파트 단지 안 보행로 같은 곳).
     * T –계단 5m– P –보행로– D. 도착점은 D (운영 테스트 시드 7319 쌍 38을 줄임)
     */
    static RoadGraph pocket(double dNorthM) {
        double[] p = north(T, 5);
        double[] d = north(T, dNorthM);
        return new RoadGraph(Map.of(1L, S, 2L, T, 3L, p, 4L, d), List.of(
                edge(1, 1, 2, S, T, EdgePenalty.NONE),
                edge(2, 2, 3, T, p, new EdgePenalty(Map.of(Penalty.STAIRS, 1.0))),
                edge(3, 3, 4, p, d, EdgePenalty.NONE)));
    }

    static RouteRequest toward(double[] d, String mode) {
        return new RouteRequest(new Place(S[1], S[0], null), new Place(d[1], d[0], null), "auto", mode);
    }

    @Test
    void wheelchairStopsAtNearestReachableRoadWhenDestinationIsBehindStairs() {
        RouteService s = service(new InMemoryRoadGraphSource(pocket(20)));

        RouteResult w = s.route(toward(north(T, 20), "wheelchair"));
        RouteResult adult = s.route(toward(north(T, 20), "adult"));

        // 휠체어는 계단 아래 T까지 안내하고 남은 거리를 알린다. 어른은 계단을 지나 D까지
        assertThat(w.explanation().text()).endsWith("(주의: 도착점 근처 약 20m는 휠체어로 이어진 길이 확인되지 않았습니다)");
        assertThat(adult.explanation().text()).doesNotContain("휠체어로 이어진 길");
    }

    @Test
    void wheelchairGivesNoRouteWhenReachableRoadIsMoreThan50mFurther() {
        RouteService s = service(new InMemoryRoadGraphSource(pocket(70)));

        assertThatThrownBy(() -> s.route(toward(north(T, 70), "wheelchair")))
                .isInstanceOfSatisfying(RouteException.class, e -> assertThat(e.error()).isEqualTo(RouteError.NO_ROUTE));
    }
}
