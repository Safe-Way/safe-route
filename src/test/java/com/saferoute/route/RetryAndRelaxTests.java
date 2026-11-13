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

import tools.jackson.databind.json.JsonMapper;

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

    /** 폭 1.0m 좁은 인도(보도 자료)가 양쪽에 있는 골목급 차도 S–T와, 북쪽 northM으로 돌아가는 보통 길 S–N–T (2026-11-13) */
    static RoadGraph narrowOrAround(double northM) {
        double[] n = north(new double[] {126.932, LAT}, northM);
        List<double[]> line = List.of(S, T);
        RoadEdge narrow = new RoadEdge(1, 1, 2, Geo.length(line), "residential", line,
                new EdgePenalty(Map.of(Penalty.NARROW_SIDEWALK, 1.0)), "both", null, null, null, null, 1.0);
        return new RoadGraph(Map.of(1L, S, 2L, T, 3L, n), List.of(narrow,
                edge(2, 1, 3, S, n, EdgePenalty.NONE), edge(3, 3, 2, n, T, EdgePenalty.NONE)));
    }

    @Test
    void wheelchairAvoidsNarrowSidewalkWithinLimit() {
        // 돌아가는 길 약 1.1배 ≤ 1.2 → 좁은 인도를 뺀 길, 주의 없음. 최단 경로 응답에는 좁은 인도 구간이 그대로 온다
        RouteResult r = service(new InMemoryRoadGraphSource(narrowOrAround(80))).route(request("wheelchair"));

        assertThat(r.routes()).extracting(RouteResult.Route::kind).containsExactly("shortest", "safe");
        assertThat(r.routes().get(1).narrows()).isEmpty();
        assertThat(r.routes().get(0).narrowM()).isBetween(360, 370);
        assertThat(r.explanation().text()).isEqualTo("33m(1분) 더 돌지만 좁은 인도를 피하는 길로 갑니다");
    }

    @Test
    void wheelchairGoesThroughNarrowSidewalkWithNoteWhenWayAroundIsTooLong() {
        // 돌아가는 길 약 1.9배 > 1.2 → 좁은 인도로 가고 알린다
        RouteResult r = service(new InMemoryRoadGraphSource(narrowOrAround(300))).route(request("wheelchair"));

        assertThat(r.routes()).hasSize(1);
        assertThat(r.explanation().notes()).anySatisfy(n -> {
            assertThat(n.kind()).isEqualTo("narrow");
            assertThat(n.text()).isEqualTo("폭 1.5m 안 되는 좁은 인도 약 364m를 지납니다");
        });
    }

    @Test
    void wheelchairAvoidsNarrowEvenWhenUnpavedCannotBeAvoided() {
        // S–M 비포장 100m(피하면 북쪽 400m로 +700m), M–T 좁은 인도 100m(피하면 북쪽 40m로 +28m).
        // 둘을 따로 피한다: 좁은 인도는 1.2배 안이라 피하고, 비포장은 못 피해 알린다. "비포장을 피하면"은 비포장만 피한 길로 잰다
        double[] m = {126.9311, LAT};
        double[] t = {126.9322, LAT};
        double[] u = north(new double[] {126.93055, LAT}, 400);
        double[] n = north(new double[] {126.93165, LAT}, 40);
        List<double[]> mt = List.of(m, t);
        RoadEdge narrow = new RoadEdge(2, 2, 3, Geo.length(mt), "residential", mt,
                new EdgePenalty(Map.of(Penalty.NARROW_SIDEWALK, 1.0)), "both", null, null, null, null, 1.0);
        RoadGraph g = new RoadGraph(Map.of(1L, S, 2L, m, 3L, t, 4L, u, 5L, n), List.of(
                edge(1, 1, 2, S, m, new EdgePenalty(Map.of(Penalty.UNPAVED, 1.0))), narrow,
                edge(3, 1, 4, S, u, EdgePenalty.NONE), edge(4, 4, 2, u, m, EdgePenalty.NONE),
                edge(5, 2, 5, m, n, EdgePenalty.NONE), edge(6, 5, 3, n, t, EdgePenalty.NONE)));

        RouteResult r = service(new InMemoryRoadGraphSource(g)).route(new RouteRequest(new Place(LAT, S[0], null),
                new Place(LAT, t[0], null), "auto", "wheelchair"));

        RouteResult.Route safe = r.routes().get(r.routes().size() - 1);
        assertThat(safe.narrows()).isEmpty();
        assertThat(safe.unpavedM()).isBetween(95, 105);
        assertThat(r.explanation().summary()).contains("좁은 인도를 피하는 길로 갑니다");
        // 비포장만 피한 길(S–U–M–T 약 906m) − 안내 경로(약 228m) ≈ 678m. 좁은 인도까지 피하는 길(약 934m)로 재면 706m
        assertThat(r.explanation().text()).contains("비포장길 약 100m를 지납니다").containsPattern("비포장을 피하면 6[67]\\dm");
        assertThat(r.explanation().notes()).noneMatch(x -> x.kind().equals("narrow"));
    }

    @Test
    void narrowsAndSidewalkWidthAreInEveryModesResult() {
        // 응답 칸 (앱 "좁은 인도 ○m"): 피하지 않는 어른도 받는다. 인도 폭은 sidewalks에, 차도 폭 widthM과 따로
        RouteResult.Route rt = service(new InMemoryRoadGraphSource(narrowOrAround(80))).route(request("adult"))
                .routes().get(0);

        assertThat(rt.narrows()).singleElement().satisfies(n -> {
            assertThat(n.from()).isZero();
            assertThat(n.to()).isEqualTo(1);
            assertThat(n.lengthM()).isBetween(360, 370);
            assertThat(n.widthM()).isEqualTo(1.0);
        });
        assertThat(rt.narrowM()).isEqualTo(rt.narrows().get(0).lengthM());
        assertThat(rt.sidewalks()).singleElement().satisfies(s -> {
            assertThat(s.sidewalkWidthM()).isEqualTo(1.0);
            assertThat(s.widthM()).isNull();
        });
        assertThat(service(new InMemoryRoadGraphSource(narrowOrAround(80))).route(request("adult")).explanation()
                .notes()).noneMatch(n -> n.kind().equals("narrow"));
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

    /** S에서 계단 구역 안 D로 (건물 안 도착). reroute: 길안내 중 다시 찾기 */
    static RouteRequest toward(double[] d, String mode, Boolean reroute) {
        return new RouteRequest(new Place(S[1], S[0], null), new Place(d[1], d[0], null), "auto", mode, null,
                null, null, null, reroute);
    }

    @Test
    void wheelchairDestinationSnapsUpTo100mFurther() {
        // 도착점도 100m까지 (운천역 → 유스퀘어 건물 안 도착, 2026-11-13)
        RouteResult r = service(new InMemoryRoadGraphSource(pocket(80))).route(toward(north(T, 80), "wheelchair"));

        assertThat(r.explanation().text()).endsWith("(주의: 도착점 근처 약 80m는 휠체어로 이어진 길이 확인되지 않았습니다)");
    }

    @Test
    void wheelchairDestinationGivesNoRouteWhenReachableRoadIsMoreThan100mFurther() {
        RouteService s = service(new InMemoryRoadGraphSource(pocket(130)));

        assertThatThrownBy(() -> s.route(toward(north(T, 130), "wheelchair")))
                .isInstanceOfSatisfying(RouteException.class, e -> assertThat(e.error()).isEqualTo(RouteError.NO_ROUTE));
    }

    @Test
    void wheelchairRerouteDestinationKeeps50m() {
        RouteService s = service(new InMemoryRoadGraphSource(pocket(80)));

        assertThatThrownBy(() -> s.route(toward(north(T, 80), "wheelchair", true)))
                .isInstanceOfSatisfying(RouteException.class, e -> assertThat(e.error()).isEqualTo(RouteError.NO_ROUTE));
    }

    /** 계단 구역 안 D에서 S로 (건물 안 출발). reroute: 길안내 중 다시 찾기 */
    static RouteRequest from(double[] d, Boolean reroute) {
        return new RouteRequest(new Place(d[1], d[0], null), new Place(S[1], S[0], null), "auto", "wheelchair", null,
                null, null, null, reroute);
    }

    @Test
    void wheelchairOriginSnapsUpTo100mFurther() {
        // 출발점도 100m까지 (유스퀘어 건물 안 출발, 2026-11-13)
        RouteResult r = service(new InMemoryRoadGraphSource(pocket(80))).route(from(north(T, 80), null));

        assertThat(r.explanation().text()).endsWith("(주의: 출발점 근처 약 80m는 휠체어로 이어진 길이 확인되지 않았습니다)");
    }

    @Test
    void appJsonCarriesReroute() {
        // 앱이 보내는 reroute가 버려지지 않는다 (호환 생성자가 여럿이라 확인)
        RouteRequest r = JsonMapper.builder().build().readValue("""
                { "origin": { "lat": 35.16, "lon": 126.879 }, "destination": { "lat": 35.15, "lon": 126.87 },
                  "mode": "wheelchair", "reroute": true }
                """, RouteRequest.class);
        assertThat(r.reroute()).isTrue();
    }

    @Test
    void wheelchairRerouteOriginKeeps50m() {
        RouteService s = service(new InMemoryRoadGraphSource(pocket(80)));

        assertThatThrownBy(() -> s.route(from(north(T, 80), true)))
                .isInstanceOfSatisfying(RouteException.class, e -> assertThat(e.error()).isEqualTo(RouteError.NO_ROUTE));
    }

    @Test
    void wheelchairOriginGivesNoRouteWhenReachableRoadIsMoreThan100mFurther() {
        RouteService s = service(new InMemoryRoadGraphSource(pocket(130)));

        assertThatThrownBy(() -> s.route(from(north(T, 130), null)))
                .isInstanceOfSatisfying(RouteException.class, e -> assertThat(e.error()).isEqualTo(RouteError.NO_ROUTE));
    }

    /** 등산로 지름길 S–T(약 365m)와, 북쪽 400m로 돌아가는 보통 길 S–N–T(약 880m, 2.4배) */
    static RoadGraph trailOrAround() {
        double[] n = north(new double[] {126.932, LAT}, 400);
        return new RoadGraph(Map.of(1L, S, 2L, T, 3L, n), List.of(
                edge(1, 1, 2, S, T, new EdgePenalty(Map.of(Penalty.TRAIL, 1.0))),
                edge(2, 1, 3, S, n, EdgePenalty.NONE), edge(3, 3, 2, n, T, EdgePenalty.NONE)));
    }

    static RouteRequest request(String mode, Boolean allowTrail) {
        return new RouteRequest(new Place(S[1], S[0], null), new Place(T[1], T[0], null), "auto", mode, null, null,
                null, allowTrail);
    }

    @Test
    void avoidsTrailWithoutDetourLimitByDefault() {
        RouteResult r = service(new InMemoryRoadGraphSource(trailOrAround())).route(request("adult"));

        // 어른 우회 한도 1.3배를 넘어도 등산로 없는 길로 (2026-11-12). 최단도 등산로 없는 길 중에서
        assertThat(r.routes()).allSatisfy(rt -> assertThat(rt.distanceM()).isGreaterThan(800));
        assertThat(r.explanation().text()).doesNotContain("등산로");
    }

    @Test
    void allowTrailUsesTrail() {
        RouteResult r = service(new InMemoryRoadGraphSource(trailOrAround())).route(request("adult", true));

        assertThat(r.routes().get(0).distanceM()).isLessThan(400);
        assertThat(r.explanation().text()).doesNotContain("등산로");
    }

    @Test
    void trailOnlyWayIsUsedWithWarning() {
        RouteResult r = service(new InMemoryRoadGraphSource(oneEdge(
                new EdgePenalty(Map.of(Penalty.TRAIL, 1.0))))).route(request("adult"));

        assertThat(r.routes().get(0).distanceM()).isLessThan(400);
        assertThat(r.explanation().notes()).anySatisfy(n -> {
            assertThat(n.kind()).isEqualTo("trail");
            assertThat(n.text()).isEqualTo("등산로 약 364m를 지납니다. 등산로를 피하는 길은 없습니다");
        });
    }

    @Test
    void wheelchairNeverUsesTrailEvenWhenAllowed() {
        RouteResult r = service(new InMemoryRoadGraphSource(trailOrAround())).route(request("wheelchair", true));

        assertThat(r.routes()).allSatisfy(rt -> assertThat(rt.distanceM()).isGreaterThan(800));
        RouteService only = service(new InMemoryRoadGraphSource(oneEdge(new EdgePenalty(Map.of(Penalty.TRAIL, 1.0)))));
        assertThatThrownBy(() -> only.route(request("wheelchair", true))).isInstanceOfSatisfying(RouteException.class,
                e -> assertThat(e.error()).isEqualTo(RouteError.NO_ROUTE));
    }

    /** trailOrAround에 출발 쪽 등산로 꼬리 S0–S를 붙인 도로망. S0은 S 남쪽 stubM */
    static RoadGraph trailStubAtStart(double stubM) {
        double[] s0 = north(S, -stubM);
        RoadGraph g = trailOrAround();
        Map<Long, double[]> nodes = new HashMap<>(g.nodes());
        nodes.put(4L, s0);
        List<RoadEdge> edges = new java.util.ArrayList<>(g.graph().edgeSet());
        edges.add(edge(4, 4, 1, s0, S, new EdgePenalty(Map.of(Penalty.TRAIL, 1.0))));
        return new RoadGraph(nodes, edges);
    }

    @Test
    void startOnTrailStubSnapsToNearbyRoadInsteadOfOpeningAllTrails() {
        RouteRequest fromStub = new RouteRequest(new Place(north(S, -30)[1], S[0], null), new Place(T[1], T[0], null),
                "auto", "adult");

        RouteResult r = service(new InMemoryRoadGraphSource(trailStubAtStart(30))).route(fromStub);

        // 등산로 꼬리에 붙으면 등산로 없이는 못 나가, 예전엔 지름길 등산로까지 열려 377m 등산로 길이 됐다 (리뷰 11-12)
        assertThat(r.routes()).allSatisfy(rt -> assertThat(rt.distanceM()).isGreaterThan(800));
        assertThat(r.explanation().notes()).noneSatisfy(n -> assertThat(n.kind()).isEqualTo("trail"));
        assertThat(r.explanation().text()).contains("출발점 근처 약 30m는 등산로 없이 이어진 길이 확인되지 않았습니다");

        // 등산로 없는 길이 50m 넘게 더 멀면(산속) 등산로에 붙여 등산로로 간다
        RouteRequest deep = new RouteRequest(new Place(north(S, -80)[1], S[0], null), new Place(T[1], T[0], null),
                "auto", "adult");
        RouteResult d = service(new InMemoryRoadGraphSource(trailStubAtStart(80))).route(deep);
        assertThat(d.explanation().notes()).anySatisfy(n -> assertThat(n.kind()).isEqualTo("trail"));
    }
}
