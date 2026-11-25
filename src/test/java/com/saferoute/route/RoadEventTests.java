package com.saferoute.route;

import static com.saferoute.route.TestRoutes.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
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
@DisplayName("행사·돌발상황 피하기")
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
    @DisplayName("노인은 행사 둘레를 우회 한도 안에서 피하고 피한 이유를 말한다")
    void seniorWalksAroundAFestival() {
        // route.yml senior.avoid-events
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
    @DisplayName("어른은 행사를 피하지 않고 지날 때 알리기만 한다")
    void adultIsOnlyToldAboutAFestival() {
        // route.yml adult.avoid-events
        RouteResult r = route("adult", 12, event("행사", "충장축제"));

        assertThat(r.routes()).singleElement().satisfies(safe -> assertThat(safe.events())
                .singleElement().satisfies(e -> assertThat(e.avoid()).isFalse()));
        assertThat(r.explanation().text())
                .isEqualTo("지금은 밤이 아니라 가장 짧은 길을 안내합니다 (알림: 충장축제 행사 구간을 지납니다)");
    }

    @Test
    @DisplayName("어린이는 행사를, 어른은 공사를 피한다")
    void childWalksAroundAFestivalAndAdultAroundRoadWork() {
        // route.yml avoid-events
        assertThat(route("child", 12, event("행사", "충장축제")).routes().get(1).events()).isEmpty();
        RouteResult adult = route("adult", 12, event("공사", "금남로 보도 공사"));
        assertThat(adult.routes()).hasSize(2);
        assertThat(adult.explanation().text()).endsWith("금남로 보도 공사 둘레를 피해 갑니다");
    }

    @Test
    @DisplayName("어른도 통제 구간은 피하고 낮에도 피한 이유를 말한다")
    void adultWalksAroundARoadClosure() {
        // route.yml adult.avoid-events
        RouteResult r = route("adult", 12, event("통제", "금남로 통제"));

        assertThat(r.routes()).hasSize(2);
        assertThat(r.routes().get(1).events()).isEmpty();
        // 낮 어른은 원래 가장 짧은 길이라, "가장 짧은 길을 안내합니다" 대신 피한 이유를 말한다
        assertThat(r.explanation().text())
                .isEqualTo("%dm 더 돌지만 금남로 통제 둘레를 피해 갑니다".formatted(r.explanation().extraM()));
    }

    @Test
    @DisplayName("'차 없는 거리' 같은 행사는 어느 모드도 피하지 않는다")
    void carFreeStreetIsNotAvoidedEvenBySeniors() {
        // route.yml events.walk-friendly
        RouteResult r = route("senior", 12, event("행사", "금남로 차없는 거리 걷자잉"));

        RouteResult.Route safe = r.routes().get(r.routes().size() - 1);
        assertThat(safe.events()).singleElement().satisfies(e -> assertThat(e.avoid()).isFalse());
        assertThat(r.explanation().text()).contains("(알림: 금남로 차없는 거리 걷자잉 행사 구간을 지납니다)");
    }

    @Test
    @DisplayName("행사를 피하는 길이 너무 멀면 지나며 주의를 알린다")
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
    @DisplayName("도착점이 행사 반경 안이면 피하지 않고 근처라고 알린다")
    void goingToTheFestivalIsNotAvoided() {
        // 도착점 T가 반경 안: 행사장에 가는 길이라 피하지 않고 알리기만 한다
        RoadEvent atEnd = new RoadEvent(2, "행사", "충장축제", null, null, T_P[0], T_P[1], 30, null);
        RouteResult r = route("senior", 12, atEnd);

        RouteResult.Route safe = r.routes().get(r.routes().size() - 1);
        assertThat(safe.events()).singleElement().satisfies(e -> {
            assertThat(e.avoid()).isFalse();
            assertThat(e.endsAt()).isNull();
            assertThat(e.near()).isEqualTo("end");
        });
        // "지난다"가 아니라 행사장 근처라고 한 번만 알린다 (2026-11-09)
        assertThat(r.explanation().notes()).singleElement()
                .isEqualTo(RouteResult.Note.info("event", "도착지 근처에서 충장축제 행사가 열리고 있어요"));
        assertThat(r.explanation().text()).doesNotContain("피해", "구간을 지납니다");
    }

    @Test
    @DisplayName("출발점을 붙인 길 점이 행사 반경 안이면 피하지 않고 알리기만 한다")
    void eventIsNotAvoidedWhenTheSnappedStartIsInside() {
        // JJ가 develop에서 고친 시험(반경 52m). 요청 출발점은 반경 밖(약 58m), 붙인 길 점(S–N 길, 약 50m)은 안
        RoadEvent ev = new RoadEvent(1, "행사", "충장축제", null, null, S_P[0], LAT - 0.0003, 52, null);
        RouteService s = TestRoutes.service(diamond(0.0004), TestRoutes.pois(List.of(), List.of(ev)), 12);
        RouteResult r = s.route(new RouteRequest(new Place(LAT + 0.0002, S_P[0] + 0.0002, "출발"),
                new Place(LAT, T_P[0], "도착"), "auto", "senior"));

        // "피해 가는 길은 너무 멉니다" 주의 없이, 행사 한 건이 알림으로만 붙는다
        assertThat(r.explanation().notes()).noneMatch(n -> n.level().equals("warn"));
        assertThat(r.routes().get(r.routes().size() - 1).events()).singleElement()
                .satisfies(e -> assertThat(e.avoid()).isFalse());
    }

    @Test
    @DisplayName("출발·도착 근처 행사는 한 번만 알리고 중간만 지나면 구간을 지난다고 한다")
    void festivalNearStartOrBothEndsIsToldOnce() {
        // "근처에서 열리고 있어요" / "구간을 지납니다" (2026-11-09)
        RoadEvent atStart = new RoadEvent(3, "행사", "충장축제", null, null, S_P[0], S_P[1], 30, null);
        RouteResult start = route("adult", 12, atStart);
        assertThat(start.routes().get(0).events()).singleElement().satisfies(e -> assertThat(e.near()).isEqualTo("start"));
        assertThat(start.explanation().notes()).extracting(RouteResult.Note::text)
                .containsExactly("출발지 근처에서 충장축제 행사가 열리고 있어요");

        // 반경이 출발·도착을 모두 덮으면 한 줄
        RoadEvent wide = new RoadEvent(4, "행사", "충장축제", null, null, M_P[0], M_P[1], 150, null);
        RouteResult both = route("adult", 12, wide);
        assertThat(both.explanation().notes()).extracting(RouteResult.Note::text)
                .containsExactly("출발지와 도착지 근처에서 충장축제 행사가 열리고 있어요");

        // 경로 중간에서만 지나면 지금처럼 "구간을 지납니다"
        RouteResult middle = route("adult", 12, event("행사", "충장축제"));
        assertThat(middle.routes().get(0).events()).singleElement().satisfies(e -> assertThat(e.near()).isNull());
        assertThat(middle.explanation().notes()).extracting(RouteResult.Note::text)
                .containsExactly("충장축제 행사 구간을 지납니다");
    }

    @Test
    @DisplayName("다른 이유로 돌아도 피한 공사는 알림으로 덧붙인다")
    void detourForAnotherReasonAlsoSaysTheEventWasAvoided() {
        // 밤 어른: 밝은 북쪽 길로 돌아 시설 차이를 이유로 말하고, 지름길의 공사도 피했으면 알림으로 덧붙인다
        RouteService service = TestRoutes.service(diamond(0.0006),
                TestRoutes.pois(brightSideFacilities(), List.of(event("공사", "금남로 보도 공사"))), 21);
        RouteResult r = service.route(new RouteRequest(new Place(LAT, S_P[0], "출발"), new Place(LAT, T_P[0], "도착"),
                "auto", "adult"));

        assertThat(safe(r).events()).isEmpty();
        assertThat(r.explanation().summary()).contains("가로등 +");
        assertThat(r.explanation().notes()).contains(RouteResult.Note.info("event", "금남로 보도 공사 둘레도 피했습니다"));
    }

    @Test
    @DisplayName("행사 구역에는 반경 원에 닿는 길만 든다")
    void zoneHasOnlyEdgesTouchingTheCircle() {
        // M에 닿는 지름길 두 개만. 북쪽 길은 M에서 약 40m
        assertThat(RoadEvents.zone(diamond(0.0004), List.of(event("행사", "축제")))).containsExactlyInAnyOrder(10L, 11L);
        assertThat(RoadEvents.stretch(event("행사", "축제"), List.of(S_P, new double[] {S_P[0], LAT + 0.001})))
                .isNull();
    }

    @Test
    @DisplayName("반경 안을 지나는 구간은 선의 처음부터 따라 재고 안에서 시작하면 0부터다")
    void stretchIsMeasuredAlongTheLineFromItsStart() {
        // RoadEvents.stretch, 10m 간격 표본
        double[] start = S_P;
        List<double[]> northward = List.of(start, north(start, 200));
        RoadEvent mid = new RoadEvent(7, "행사", "축제", null, null, start[0], north(start, 100)[1], 30, null);
        RoadEvent atStart = new RoadEvent(8, "행사", "축제", null, null, start[0], start[1], 30, null);

        assertThat(RoadEvents.stretch(mid, northward)).satisfies(s -> {
            assertThat(s[0]).isCloseTo(70, org.assertj.core.data.Offset.offset(10.0));
            assertThat(s[1]).isCloseTo(130, org.assertj.core.data.Offset.offset(10.0));
        });
        assertThat(RoadEvents.stretch(atStart, northward)[0]).isZero();
    }

    @Test
    @DisplayName("길·경로 선이 행사 반경 이하로 다가가야 그 행사에 걸린다")
    void eventRadiusIsTheLimit() {
        // radiusM (RoadEvents.zone·stretch)
        // 좌표 거리라 경계값 자체는 못 만들어 경계 ±0.01m로 본다. 행사는 직선 길 S–M–T의 M에서 정북
        double r = event("행사", "축제").radiusM();
        double[] inP = north(M_P, justBelow(r));
        double[] outP = north(M_P, justAbove(r));
        RoadEvent in = new RoadEvent(5, "행사", "축제", null, null, inP[0], inP[1], r, null);
        RoadEvent out = new RoadEvent(6, "행사", "축제", null, null, outP[0], outP[1], r, null);

        assertThat(RoadEvents.zone(lineWithFarNode(), List.of(in))).containsExactlyInAnyOrder(1L, 2L);
        assertThat(RoadEvents.zone(lineWithFarNode(), List.of(out))).isEmpty();
        assertThat(RoadEvents.stretch(in, List.of(S_P, T_P))).isNotNull();
        assertThat(RoadEvents.stretch(out, List.of(S_P, T_P))).isNull();
    }
}
