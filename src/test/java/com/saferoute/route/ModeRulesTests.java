package com.saferoute.route;

import static com.saferoute.route.TestRoutes.LAT;
import static com.saferoute.route.TestRoutes.at;
import static com.saferoute.route.TestRoutes.props;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 모드별 낮·밤 규칙 (결정 문서 「경로 엔진 결정 사항 정리」 2026-11-02·03 확정)을 요청부터 응답까지 확인한다.
 * 작은 마름모 도로망: S–M–T 지름길(200m)과 S–N–T 북쪽 길.
 */
class ModeRulesTests {

    static final double[] N_NEAR = {126.93110, LAT + 0.0004}; // 북쪽 길 약 1.09배일 때 N 위치
    /** 북쪽 길 바깥(지름길에서 약 100m). 반경 80m면 북쪽 길만 덮는다 */
    static final double[] BEYOND_N = {126.93110, LAT + 0.0009};

    static RouteResult route(RoadGraph g, List<Poi> pois, ZonedDateTime now, String mode, String night,
            String... overrides) {
        RouteProperties p = props(overrides);
        RouteService s = TestRoutes.service(g, TestRoutes.pois(pois), now, p);
        return s.route(new RouteRequest(new Place(LAT, 126.93000, "출발"), new Place(LAT, 126.93220, "도착"), night, mode));
    }

    static boolean north(RouteResult r) {
        RouteResult.Route safe = r.routes().get(r.routes().size() - 1);
        return safe.geometry().stream().anyMatch(p -> p[1] > LAT + 0.0001);
    }

    static RoadGraph diamond(double north) {
        return RouteFinderTests.diamond(north, EdgePenalty.NONE);
    }

    @Test
    void adultDayIsShortest() {
        RouteResult r = route(diamond(0.0004), List.of(Poi.at("school_zone", N_NEAR[0], N_NEAR[1])), at(12), "adult", "auto");
        assertThat(north(r)).isFalse();
        assertThat(r.routes()).hasSize(1);
    }

    @Test
    void childDayPrefersSchoolZoneOverHelpPoint() {
        // 북쪽 N 옆에 학교(보호구역), 지름길 M 옆에 편의점(도움 시설). 반경을 줄여 작은 도로망에서 차이를 만든다
        List<Poi> pois = List.of(Poi.at("school_zone", BEYOND_N[0], BEYOND_N[1]),
                Poi.at("store", TestRoutes.M_P[0], TestRoutes.M_P[1]));
        RouteResult r = route(diamond(0.0006), pois, at(12), "child", "auto",
                "route.profiles.child.day.school-m=80", "route.profiles.child.day.help-m=40");

        assertThat(r.conditions().night()).isFalse();
        assertThat(north(r)).isTrue();
        assertThat(r.explanation().text()).endsWith("어린이보호구역과 도움을 청할 곳이 가까운 길로 갑니다");
    }

    @Test
    void childNightIgnoresSchoolZone() {
        List<Poi> pois = List.of(Poi.at("school_zone", N_NEAR[0], N_NEAR[1]));
        RouteResult r = route(diamond(0.0004), pois, at(21), "child", "auto",
                "route.profiles.child.day.school-m=80");

        assertThat(r.conditions().night()).isTrue();
        assertThat(north(r)).isFalse();
    }

    @Test
    void childNightStartsAnHourBeforeSunset() {
        // 2026-10-30 광주 일몰 약 17:40 → 17:00은 어른은 낮, 어린이는 밤
        RouteResult adult = route(diamond(0.0004), List.of(), at(17), "adult", "auto");
        RouteResult child = route(diamond(0.0004), List.of(), at(17), "child", "auto");

        assertThat(adult.conditions().night()).isFalse();
        assertThat(child.conditions().night()).isTrue();
        assertThat(child.conditions().nightSource()).isEqualTo("auto");
    }

    @Test
    void nightPrefersLightOverDarkShortcut() {
        // 북쪽 길에만 등 → 밤에는 북쪽, 낮(어른)에는 지름길
        List<Poi> lights = List.of(Poi.at("light", 126.93055, LAT + 0.0002), Poi.at("light", N_NEAR[0], N_NEAR[1]),
                Poi.at("light", 126.93165, LAT + 0.0002));
        assertThat(north(route(diamond(0.0004), lights, at(21), "adult", "auto"))).isTrue();
        assertThat(north(route(diamond(0.0004), lights, at(12), "adult", "auto"))).isFalse();
    }

    @Test
    void seniorDayAvoidsStairsAndPrefersOpenRestPlace() {
        RoadGraph stairs = RouteFinderTests.diamond(0.0004, RouteFinderTests.STAIRS);
        assertThat(north(route(stairs, List.of(), at(12), "senior", "auto"))).isTrue();
        assertThat(north(route(stairs, List.of(), at(12), "adult", "auto"))).isFalse();

        // 북쪽에 09~18시 화장실. 6월은 19시도 낮인데 화장실은 닫혀서 지름길
        Poi toilet = new Poi("toilet", "공중화장실", null, BEYOND_N[0], BEYOND_N[1], 9 * 60, 18 * 60, "매일",
                null, null, null, null, null, null);
        ZonedDateTime juneNoon = ZonedDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneId.of("Asia/Seoul"));
        assertThat(north(route(diamond(0.0005), List.of(toilet), juneNoon, "senior", "auto",
                "route.profiles.senior.day.rest-m=80"))).isTrue();
        RouteResult evening = route(diamond(0.0005), List.of(toilet), juneNoon.withHour(19), "senior", "auto",
                "route.profiles.senior.day.rest-m=80");
        assertThat(evening.conditions().night()).isFalse();
        assertThat(north(evening)).isFalse();
    }

    @Test
    void wheelchairDayTakesShortestPassableRoute() {
        // 지름길에 확인된 턱 → 북쪽 길(약 1.58배)이 지날 수 있는 길 중 최단이라 한도와 관계없이 그 길로
        RoadGraph curb = RouteFinderTests.diamond(0.0011, RouteFinderTests.CURB);
        RouteResult r = route(curb, List.of(), at(12), "wheelchair", "auto");

        assertThat(north(r)).isTrue();
        assertThat(r.routes()).hasSize(1);
        assertThat(r.explanation().text()).isEqualTo("휠체어로 지날 수 있는 길 중 가장 짧은 길입니다");
    }

    @Test
    void wheelchairNightPrefersLightWithinDetourLimit() {
        // 북쪽 길에만 등. 밤에는 1.2배 안이면 북쪽, 낮에는 지름길
        List<Poi> lights = List.of(Poi.at("light", 126.93055, LAT + 0.0002), Poi.at("light", N_NEAR[0], N_NEAR[1]),
                Poi.at("light", 126.93165, LAT + 0.0002));
        assertThat(north(route(diamond(0.0004), lights, at(21), "wheelchair", "auto"))).isTrue();
        assertThat(north(route(diamond(0.0004), lights, at(12), "wheelchair", "auto"))).isFalse();
        // 북쪽 길이 1.2배를 넘으면 밤에도 지름길
        assertThat(north(route(diamond(0.0008), lights, at(21), "wheelchair", "auto"))).isFalse();
    }

    @Test
    void wheelchairAvoidsConfirmedUnpavedRoad() {
        RoadGraph dirt = RouteFinderTests.diamond(0.0004, new EdgePenalty(Map.of(Penalty.UNPAVED, 1.0)));
        assertThat(north(route(dirt, List.of(), at(12), "wheelchair", "auto"))).isTrue();
        assertThat(north(route(dirt, List.of(), at(12), "adult", "auto"))).isFalse();
        // 비포장 없는 북쪽 길이 1.2배를 넘으면 비포장 지름길로 가고 알린다
        RoadGraph far = RouteFinderTests.diamond(0.0008, new EdgePenalty(Map.of(Penalty.UNPAVED, 1.0)));
        RouteResult r = route(far, List.of(), at(12), "wheelchair", "auto");
        assertThat(north(r)).isFalse();
        assertThat(r.explanation().text()).contains("비포장을 피하면");
    }

    @Test
    void wheelchairUsesStairsOnlyWithRamp() {
        RoadGraph stairs = RouteFinderTests.diamond(0.0004, RouteFinderTests.STAIRS);
        assertThat(north(route(stairs, List.of(), at(12), "wheelchair", "auto"))).isTrue();

        // 경사로가 있는 계단: 휠체어는 지름길, 노인은 여전히 계단으로 보고 피한다
        RoadGraph ramp = RouteFinderTests.diamond(0.0004, new EdgePenalty(Map.of(Penalty.STAIRS, 1.0), true));
        assertThat(north(route(ramp, List.of(), at(12), "wheelchair", "auto"))).isFalse();
        assertThat(north(route(ramp, List.of(), at(12), "senior", "auto"))).isTrue();
    }

    /** 지름길 S–M–T는 인도 없는 골목(residential), 북쪽 S–N–T는 큰 길(tertiary) */
    static RoadGraph alleyDiamond(double north) {
        return alleyDiamond(north, null);
    }

    /** 골목의 인도 태그(sidewalk)만 바꾼 alleyDiamond. null 모름, no 없음(휠체어 제외), both 있음(큰 길) */
    static RoadGraph alleyDiamond(double north, String sidewalk) {
        double[] nP = {126.93110, LAT + north};
        return new RoadGraph(Map.of(TestRoutes.S, TestRoutes.S_P, TestRoutes.M, TestRoutes.M_P, TestRoutes.T,
                TestRoutes.T_P, TestRoutes.N, nP), List.of(
                road(10, TestRoutes.S, TestRoutes.M, TestRoutes.S_P, TestRoutes.M_P, "residential", sidewalk),
                road(11, TestRoutes.M, TestRoutes.T, TestRoutes.M_P, TestRoutes.T_P, "residential", sidewalk),
                road(12, TestRoutes.S, TestRoutes.N, TestRoutes.S_P, nP, "tertiary", null),
                road(13, TestRoutes.T, TestRoutes.N, TestRoutes.T_P, nP, "tertiary", null)));
    }

    static RoadEdge road(long id, long from, long to, double[] a, double[] b, String highway, String sidewalk) {
        List<double[]> line = List.of(a, b);
        return new RoadEdge(id, from, to, Geo.length(line), highway, line, EdgePenalty.NONE, sidewalk);
    }

    @Test
    void wheelchairDayPrefersBigRoadOverAlleyWithinDetourLimit() {
        // 큰 길(북쪽) 약 1.09배 ≤ 1.2 → 휠체어 낮은 큰 길, 어른 낮은 골목 지름길 (2026-11-12 카카오맵 현장 비교)
        RouteResult r = route(alleyDiamond(0.0004), List.of(), at(12), "wheelchair", "auto");
        assertThat(north(r)).isTrue();
        RouteResult.Route shortest = r.routes().get(0);
        RouteResult.Route safe = r.routes().get(1);
        assertThat(shortest.alleyM()).isEqualTo(200);
        assertThat(shortest.alleys()).hasSize(1);
        assertThat(safe.alleyM()).isZero();
        assertThat(safe.alleys()).isEmpty();
        assertThat(r.explanation().summary()).endsWith("더 돌지만 큰 길로 가서 골목길을 200m 줄여요");
        assertThat(r.explanation().notes()).noneMatch(n -> n.kind().equals("alley"));
        assertThat(safe.covered()).isEmpty();

        RouteResult adult = route(alleyDiamond(0.0004), List.of(), at(12), "adult", "auto");
        assertThat(north(adult)).isFalse();
        assertThat(adult.routes().get(0).alleyM()).isEqualTo(200);
        assertThat(adult.explanation().notes()).isEmpty();

        // 들를 곳이 있으면 경로를 다시 만드는데(withStops) 골목길 칸도 그대로 온다
        RouteService svc = TestRoutes.service(alleyDiamond(0.0004), TestRoutes.pois(List.of()), at(12), props());
        RouteResult withStop = svc.route(new RouteRequest(new Place(LAT, 126.93000, "출발"), new Place(LAT, 126.93220, "도착"),
                "auto", "adult", null, null, List.of(new Place(LAT, TestRoutes.M_P[0], "가게")), null));
        assertThat(withStop.routes().get(0).stops()).hasSize(1);
        assertThat(withStop.routes().get(0).alleyM()).isEqualTo(200);
        assertThat(withStop.routes().get(0).alleys()).hasSize(1);
    }

    @Test
    void wheelchairDayKeepsAlleyWhenBigRoadIsTooFar() {
        // 큰 길 약 1.34배 > 1.2 → 골목 지름길로 가고 남은 골목길을 알린다
        RouteResult r = route(alleyDiamond(0.0008), List.of(), at(12), "wheelchair", "auto");
        assertThat(north(r)).isFalse();
        assertThat(r.routes()).hasSize(1);
        assertThat(r.routes().get(0).alleyM()).isEqualTo(200);
        assertThat(r.explanation().text())
                .isEqualTo("휠체어로 지날 수 있는 길 중 가장 짧은 길입니다 (알림: 골목길 약 200m를 지납니다)");
    }

    @Test
    void wheelchairNeverUsesNoSidewalkAlleyEvenPastDetourLimit() {
        // 큰 길 약 1.34배 > 1.2여도 인도 없다고 확인된 골목은 안 쓴다 (2026-11-13). 노인은 감점만 하고 골목 지름길로
        RouteResult r = route(alleyDiamond(0.0008, "no"), List.of(), at(12), "wheelchair", "auto");
        assertThat(north(r)).isTrue();
        assertThat(r.routes()).allMatch(x -> x.alleyM() == 0);
        assertThat(r.explanation().notes()).noneMatch(n -> n.kind().equals("no_sidewalk_alley"));

        RouteResult senior = route(alleyDiamond(0.0008, "no"), List.of(), at(12), "senior", "auto");
        assertThat(north(senior)).isFalse();
        assertThat(senior.routes().get(0).alleyM()).isEqualTo(200);
    }

    @Test
    void wheelchairUsesNoSidewalkAlleyOnlyWhenNothingElseConnects() {
        // 보행로 S–A, 인도 없는 골목 A–B(약 255m, 출발·도착 골목 200m 규칙 밖), 보행로 B–T. 골목을 빼면 이어지지 않아
        // 골목을 넣어 다시 찾고 주의를 붙인다 (2026-11-13)
        double[] a = {126.93050, LAT};
        double[] b = {126.93330, LAT};
        double[] t = {126.93380, LAT};
        RoadGraph g = new RoadGraph(Map.of(1L, TestRoutes.S_P, 2L, a, 3L, b, 4L, t), List.of(
                TestRoutes.edge(1, 1, 2, TestRoutes.S_P, a, EdgePenalty.NONE),
                road(2, 2, 3, a, b, "residential", "no"),
                TestRoutes.edge(3, 3, 4, b, t, EdgePenalty.NONE)));
        RouteService s = TestRoutes.service(g, TestRoutes.pois(List.of()), at(12), props());

        RouteResult r = s.route(new RouteRequest(new Place(LAT, TestRoutes.S_P[0], "출발"), new Place(LAT, t[0], "도착"),
                "auto", "wheelchair"));

        assertThat(r.routes().get(0).alleyM()).isBetween(250, 260);
        assertThat(r.explanation().notes()).anySatisfy(n -> {
            assertThat(n.kind()).isEqualTo("no_sidewalk_alley");
            assertThat(n.level()).isEqualTo("warn");
            assertThat(n.text()).matches("인도 없는 골목 약 25\\dm를 지납니다");
        });
    }

    @Test
    void wheelchairFallbackUsesLeastNoSidewalkAlley() {
        // 보행로 S–A, B–T 사이를 잇는 길은 인도 없는 골목뿐: A–B 곧은 골목 300m(전체 약 400m)와
        // 북쪽 150m 보행로로 돌아 C–D 골목 40m만 지나는 길(약 590m). 골목 1m = 10m라 낮·밤 모두 40m 쪽 (배수 1이면 300m 쪽, 2026-11-13)
        double north = 150 / 111_195.0;
        double[] a = {126.93055, LAT};
        double[] b = {126.93385, LAT};
        double[] t = {126.93440, LAT};
        double[] c = {126.93055, LAT + north};
        double[] d = {126.93099, LAT + north};
        RoadGraph g = new RoadGraph(Map.of(1L, TestRoutes.S_P, 2L, a, 3L, b, 4L, t, 5L, c, 6L, d), List.of(
                TestRoutes.edge(1, 1, 2, TestRoutes.S_P, a, EdgePenalty.NONE),
                road(2, 2, 3, a, b, "residential", "no"),
                TestRoutes.edge(3, 3, 4, b, t, EdgePenalty.NONE),
                TestRoutes.edge(4, 2, 5, a, c, EdgePenalty.NONE),
                road(5, 5, 6, c, d, "residential", "no"),
                TestRoutes.edge(6, 6, 3, d, b, EdgePenalty.NONE)));
        for (int hour : new int[] {12, 21}) {
            RouteService s = TestRoutes.service(g, TestRoutes.pois(List.of()), at(hour), props());
            RouteResult r = s.route(new RouteRequest(new Place(LAT, TestRoutes.S_P[0], "출발"), new Place(LAT, t[0], "도착"),
                    "auto", "wheelchair"));

            assertThat(r.routes()).allSatisfy(x -> assertThat(x.alleyM()).isBetween(38, 42));
            // 응답 거리는 실제 길이 (부풀린 비용이 아님)
            assertThat(r.routes().get(0).distanceM()).isBetween(570, 610);
            assertThat(r.explanation().notes()).anySatisfy(n -> assertThat(n.text()).matches("인도 없는 골목 약 4\\dm를 지납니다"));
        }
    }

    /** 출발점 기준 동쪽 x m, 북쪽 y m */
    static double[] xy(double x, double y) {
        return new double[] {126.93 + x / (111_195.0 * Math.cos(Math.toRadians(LAT))), LAT + y / 111_195.0};
    }

    /** 출발점 남쪽 25m에 인도 없는 골목(동서 200m), 북쪽 roadM에 보행로. 둘은 양 끝 보행로로 이어지고 도착은 보행로 동쪽 300m */
    static RouteResult alleyOrRoad(double roadM) {
        double[] a1 = xy(-100, -25);
        double[] a2 = xy(100, -25);
        double[] f1 = xy(-100, roadM);
        double[] f0 = xy(0, roadM);
        double[] f2 = xy(100, roadM);
        double[] f3 = xy(300, roadM);
        RoadGraph g = new RoadGraph(Map.of(1L, a1, 2L, a2, 3L, f1, 4L, f0, 5L, f2, 6L, f3), List.of(
                road(1, 1, 2, a1, a2, "residential", "no"),
                TestRoutes.edge(2, 1, 3, a1, f1, EdgePenalty.NONE), TestRoutes.edge(3, 2, 5, a2, f2, EdgePenalty.NONE),
                TestRoutes.edge(4, 3, 4, f1, f0, EdgePenalty.NONE), TestRoutes.edge(5, 4, 5, f0, f2, EdgePenalty.NONE),
                TestRoutes.edge(6, 5, 6, f2, f3, EdgePenalty.NONE)));
        RouteService s = TestRoutes.service(g, TestRoutes.pois(List.of()), at(12), props());
        return s.route(new RouteRequest(new Place(LAT, 126.93, "출발"), new Place(f3[1], f3[0], "도착"), "auto", "wheelchair"));
    }

    @Test
    void wheelchairSnapsToRoadUnlessAlleyIsMuchCloser() {
        // 골목 25m, 지날 수 있는 길 45m: 50m(passable-snap-extra-m) 안이라 길에 붙는다 (2026-11-13, 4번 출구)
        RouteResult road = alleyOrRoad(45);
        assertThat(road.routes().get(0).alleyM()).isZero();
        assertThat(road.routes().get(0).geometry().get(1)[1]).isGreaterThan(LAT); // 0번은 요청 출발점
        // 길이 120m면 골목에 붙고 골목을 따라 나간다 (출발·도착 골목 200m)
        RouteResult alley = alleyOrRoad(120);
        assertThat(alley.routes().get(0).alleyM()).isBetween(95, 105);
        assertThat(alley.routes().get(0).geometry().get(1)[1]).isLessThan(LAT);
    }

    @Test
    void wheelchairKeepsAlleyWithUnknownOrBothSidewalk() {
        // 인도 모름: 예전대로 감점만 (1.34배라 골목으로). 인도 있음: 큰 길로 보고 1.09배여도 지름길
        assertThat(north(route(alleyDiamond(0.0008, null), List.of(), at(12), "wheelchair", "auto"))).isFalse();
        RouteResult both = route(alleyDiamond(0.0004, "both"), List.of(), at(12), "wheelchair", "auto");
        assertThat(north(both)).isFalse();
        assertThat(both.routes()).hasSize(1);
        assertThat(both.routes().get(0).alleyM()).isZero();
    }

    /** 출발 S에서 골목을 따라 동쪽 90m (인도 없는 골목 안). 지날 수 있는 큰 길(S–N)까지 약 60m라 50m 넘게 멀어 골목에 붙는다 */
    static final double[] IN_ALLEY = {126.93000 + 0.00099, LAT};

    static RouteResult fromAlley(String... overrides) {
        RouteService s = TestRoutes.service(alleyDiamond(0.0008, "no"), TestRoutes.pois(List.of()), at(12), props(overrides));
        return s.route(new RouteRequest(new Place(IN_ALLEY[1], IN_ALLEY[0], "출발"), new Place(LAT, 126.93220, "도착"),
                "auto", "wheelchair"));
    }

    @Test
    void wheelchairStartsInsideNoSidewalkAlley() {
        // 출발점이 인도 없는 골목 안이면 그 골목에 붙여 골목을 따라 나간다 (end-alley-m 200m 안: 10m + 100m로 T까지)
        RouteResult r = fromAlley();
        RouteResult.Route route = r.routes().get(0);
        assertThat(north(r)).isFalse();
        assertThat(route.geometry().get(0)[0]).isCloseTo(IN_ALLEY[0], org.assertj.core.data.Offset.offset(1e-6));
        assertThat(route.alleyM()).isBetween(105, 115);
        assertThat(r.explanation().notes()).noneMatch(n -> n.kind().equals("snap"));

        // 50m까지만 열면 골목을 못 나가 예전처럼 큰 길에 다시 붙이고(약 60m) 알린다
        RouteResult short50 = fromAlley("route.end-alley-m=50");
        assertThat(north(short50)).isTrue();
        assertThat(short50.explanation().notes()).anyMatch(n -> n.kind().equals("snap"));
    }

    @Test
    void wheelchairStopInsideNoSidewalkAlley() {
        // 들를 곳(M)이 인도 없는 골목 안: 그 골목으로 드나든다. 다른 경로 없이 NO_ROUTE가 되지 않는다
        RouteService svc = TestRoutes.service(alleyDiamond(0.0008, "no"), TestRoutes.pois(List.of()), at(12), props());
        RouteResult r = svc.route(new RouteRequest(new Place(LAT, 126.93000, "출발"), new Place(LAT, 126.93220, "도착"),
                "auto", "wheelchair", null, null, List.of(new Place(LAT, TestRoutes.M_P[0], "가게")), null));
        assertThat(r.routes().get(0).stops()).hasSize(1);
        assertThat(r.routes().get(0).alleyM()).isEqualTo(200);
        assertThat(r.explanation().notes()).noneMatch(n -> n.kind().equals("snap"));
    }
}
