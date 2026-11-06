package com.saferoute.route;

import static com.saferoute.route.TestRoutes.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.event.RoadEvent;
import com.saferoute.route.engine.event.RoadEvents;

/**
 * 행사·돌발상황 (2026-11-06). 지름길 S–M–T 한가운데 M에 반경 30m 행사를 두고,
 * 북쪽으로 도는 길(약 1.1배)은 반경 밖이다.
 */
class RoadEventTests {

    static final LocalDateTime ENDS = LocalDateTime.of(2026, 11, 7, 23, 59, 59);

    static RoadEvent event(String kind, String title) {
        return new RoadEvent(1, kind, title, "금남로 일원", "공연", M_P[0], M_P[1], 30, ENDS);
    }

    static RouteResult route(String mode, int hour, RoadEvent... events) {
        RouteService service = TestRoutes.service(diamond(0.0004), TestRoutes.pois(List.of(), List.of(events)), hour);
        return service.route(new RouteRequest(new Place(LAT, S_P[0], "출발"), new Place(LAT, T_P[0], "도착"), "auto",
                mode));
    }

    @Test
    void seniorWalksAroundAFestival() {
        RouteResult r = route("senior", 12, event("행사", "충장축제"));

        assertThat(r.routes()).extracting(RouteResult.Route::kind).containsExactly("shortest", "safe");
        RouteResult.Route shortest = r.routes().get(0);
        RouteResult.Route safe = r.routes().get(1);
        assertThat(safe.events()).isEmpty();
        assertThat(shortest.events()).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("행사");
            assertThat(e.avoid()).isTrue();
            assertThat(e.endsAt()).isEqualTo("2026-11-07 23:59");
            // M(약 100m 지점) 앞뒤 30m
            assertThat(e.fromM()).isBetween(65, 75);
            assertThat(e.toM()).isBetween(125, 135);
        });
        // 쉼 곳 등 다른 차이 없이 행사만 피해 돌았다
        assertThat(r.explanation().text())
                .isEqualTo("%dm(1분) 더 돌지만 충장축제 행사 둘레를 피해 갑니다".formatted(r.explanation().extraM()));
    }

    @Test
    void adultIsOnlyToldAboutAFestival() {
        RouteResult r = route("adult", 12, event("행사", "충장축제"));

        assertThat(r.routes()).singleElement().satisfies(safe -> assertThat(safe.events())
                .singleElement().satisfies(e -> assertThat(e.avoid()).isFalse()));
        assertThat(r.explanation().text())
                .isEqualTo("지금은 밤이 아니라 가장 짧은 길을 안내합니다 (알림: 충장축제 행사 구간을 지납니다)");
    }

    @Test
    void childWalksAroundAFestivalAndAdultAroundRoadWork() {
        assertThat(route("child", 12, event("행사", "충장축제")).routes().get(1).events()).isEmpty();
        RouteResult adult = route("adult", 12, event("공사", "금남로 보도 공사"));
        assertThat(adult.routes()).hasSize(2);
        assertThat(adult.explanation().text()).endsWith("금남로 보도 공사 둘레를 피해 갑니다");
    }

    @Test
    void adultWalksAroundARoadClosure() {
        RouteResult r = route("adult", 12, event("통제", "금남로 통제"));

        assertThat(r.routes()).hasSize(2);
        assertThat(r.routes().get(1).events()).isEmpty();
        // 낮 어른은 원래 가장 짧은 길이라, "가장 짧은 길을 안내합니다" 대신 피한 이유를 말한다
        assertThat(r.explanation().text())
                .isEqualTo("%dm 더 돌지만 금남로 통제 둘레를 피해 갑니다".formatted(r.explanation().extraM()));
    }

    @Test
    void carFreeStreetIsNotAvoidedEvenBySeniors() {
        RouteResult r = route("senior", 12, event("행사", "금남로 차없는 거리 걷자잉"));

        RouteResult.Route safe = r.routes().get(r.routes().size() - 1);
        assertThat(safe.events()).singleElement().satisfies(e -> assertThat(e.avoid()).isFalse());
        assertThat(r.explanation().text()).contains("(알림: 금남로 차없는 거리 걷자잉 행사 구간을 지납니다)");
    }

    @Test
    void tooFarToAvoidSoItWarns() {
        // 북쪽 길이 약 1.9배라 노인 한도(1.2배)를 넘는다
        RouteService service = TestRoutes.service(diamond(0.0015),
                TestRoutes.pois(List.of(), List.of(event("행사", "충장축제"))), 12);
        RouteResult r = service.route(new RouteRequest(new Place(LAT, S_P[0], "출발"), new Place(LAT, T_P[0], "도착"),
                "auto", "senior"));

        assertThat(r.routes()).singleElement().satisfies(safe -> assertThat(safe.events())
                .singleElement().satisfies(e -> assertThat(e.avoid()).isTrue()));
        assertThat(r.explanation().text()).endsWith("(주의: 충장축제 행사 구간을 지납니다. 피해 가는 길은 너무 멉니다)");
    }

    @Test
    void goingToTheFestivalIsNotAvoided() {
        // 도착점 T가 반경 안: 행사장에 가는 길이라 피하지 않고 알리기만 한다
        RoadEvent atEnd = new RoadEvent(2, "행사", "충장축제", null, null, T_P[0], T_P[1], 30, null);
        RouteResult r = route("senior", 12, atEnd);

        RouteResult.Route safe = r.routes().get(r.routes().size() - 1);
        assertThat(safe.events()).singleElement().satisfies(e -> {
            assertThat(e.avoid()).isFalse();
            assertThat(e.endsAt()).isNull();
        });
        assertThat(r.explanation().text()).doesNotContain("피해").endsWith("(알림: 충장축제 행사 구간을 지납니다)");
    }

    @Test
    void zoneHasOnlyEdgesTouchingTheCircle() {
        // M에 닿는 지름길 두 개만. 북쪽 길은 M에서 약 40m
        assertThat(RoadEvents.zone(diamond(0.0004), List.of(event("행사", "축제")))).containsExactlyInAnyOrder(10L, 11L);
        assertThat(RoadEvents.stretch(event("행사", "축제"), List.of(S_P, new double[] {S_P[0], LAT + 0.001})))
                .isNull();
    }
}
