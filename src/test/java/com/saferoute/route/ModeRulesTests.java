package com.saferoute.route;

import static com.saferoute.route.TestRoutes.LAT;
import static com.saferoute.route.TestRoutes.at;
import static com.saferoute.route.TestRoutes.props;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.facility.Poi;
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
        RouteService s = TestRoutes.service(g, RouteServiceTests.facilities(List.of()), TestRoutes.pois(pois), now, p);
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
    void wheelchairTakesShortestPassableRouteWithoutDetourLimit() {
        RoadGraph curb = RouteFinderTests.diamond(0.0011, RouteFinderTests.CURB);
        RouteResult r = route(curb, List.of(), at(21), "wheelchair", "auto");

        assertThat(north(r)).isTrue();
        assertThat(r.routes()).hasSize(1);
        assertThat(r.explanation().text()).isEqualTo("휠체어로 지날 수 있는 길 중 가장 짧은 길입니다");
    }
}
