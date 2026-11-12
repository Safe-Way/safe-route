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
        double[] nP = {126.93110, LAT + north};
        return new RoadGraph(Map.of(TestRoutes.S, TestRoutes.S_P, TestRoutes.M, TestRoutes.M_P, TestRoutes.T,
                TestRoutes.T_P, TestRoutes.N, nP), List.of(
                road(10, TestRoutes.S, TestRoutes.M, TestRoutes.S_P, TestRoutes.M_P, "residential"),
                road(11, TestRoutes.M, TestRoutes.T, TestRoutes.M_P, TestRoutes.T_P, "residential"),
                road(12, TestRoutes.S, TestRoutes.N, TestRoutes.S_P, nP, "tertiary"),
                road(13, TestRoutes.T, TestRoutes.N, TestRoutes.T_P, nP, "tertiary")));
    }

    static RoadEdge road(long id, long from, long to, double[] a, double[] b, String highway) {
        List<double[]> line = List.of(a, b);
        return new RoadEdge(id, from, to, Geo.length(line), highway, line, EdgePenalty.NONE);
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
}
