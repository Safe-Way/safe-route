package com.saferoute.route;

import static com.saferoute.route.TestRoutes.LAT;
import static com.saferoute.route.TestRoutes.M;
import static com.saferoute.route.TestRoutes.M_P;
import static com.saferoute.route.TestRoutes.N;
import static com.saferoute.route.TestRoutes.S;
import static com.saferoute.route.TestRoutes.S_P;
import static com.saferoute.route.TestRoutes.T;
import static com.saferoute.route.TestRoutes.T_P;
import static com.saferoute.route.TestRoutes.edge;
import static com.saferoute.route.TestRoutes.justAbove;
import static com.saferoute.route.TestRoutes.justBelow;
import static com.saferoute.route.TestRoutes.north;
import static com.saferoute.route.TestRoutes.props;
import static com.saferoute.route.TestRoutes.road;
import static com.saferoute.route.TestRoutes.safe;
import static com.saferoute.route.TestRoutes.xy;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 골목길 (인도 없는 골목급 차도, 2026-11-12)과 인도가 없다고 확인된 골목 (2026-11-13).
 * 휠체어 낮은 1.2배 안이면 큰 길로, 인도 없는 골목은 휠체어가 늘 빼고 노인·어린이는 감점만 한다.
 */
@DisplayName("골목길 규칙")
class AlleyTests {

    /** 지름길 S–M–T는 골목(residential, 인도 태그 sidewalk), 북쪽 S–N–T는 큰 길(tertiary). sidewalk null 모름, no 없음, both 있음 */
    static RoadGraph alleyDiamond(double north, String sidewalk) {
        double[] nP = {126.93110, LAT + north};
        return new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P, N, nP), List.of(
                road(10, S, M, S_P, M_P, "residential", sidewalk),
                road(11, M, T, M_P, T_P, "residential", sidewalk),
                road(12, S, N, S_P, nP, "tertiary", null),
                road(13, T, N, T_P, nP, "tertiary", null)));
    }

    static RouteService service(RoadGraph g, String... overrides) {
        return TestRoutes.service(g, TestRoutes.pois(List.of()), TestRoutes.at(12), props(overrides));
    }

    static RouteResult route(RoadGraph g, String mode) {
        return service(g).route(new RouteRequest(new Place(LAT, S_P[0], "출발"), new Place(LAT, T_P[0], "도착"), "auto", mode));
    }

    @Test
    @DisplayName("휠체어 낮은 1.2배 안이면 골목 대신 큰 길로 간다")
    void wheelchairDayPrefersBigRoadOverAlleyWithinDetourLimit() {
        // 큰 길(북쪽) 약 1.09배 ≤ 1.2 → 휠체어 낮은 큰 길 (2026-11-12 카카오맵 현장 비교)
        RouteResult r = route(alleyDiamond(0.0004, null), "wheelchair");
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

        // 돌아가는 길에도 골목(S–N)이 남으면 줄인 길이는 최단 골목길에서 남은 골목길을 뺀 값이다
        double[] nP = {126.93110, LAT + 0.0004};
        RouteResult part = route(new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P, N, nP), List.of(
                road(10, S, M, S_P, M_P, "residential", null), road(11, M, T, M_P, T_P, "residential", null),
                road(12, S, N, S_P, nP, "residential", null), road(13, T, N, T_P, nP, "tertiary", null))), "wheelchair");
        assertThat(north(part)).isTrue();
        int left = part.routes().get(1).alleyM();
        assertThat(left).isPositive();
        assertThat(part.explanation().summary())
                .contains("큰 길로 가서 골목길을 %dm 줄여요".formatted(part.routes().get(0).alleyM() - left));
    }

    @Test
    @DisplayName("어른은 골목길 기준이 없어 골목 지름길로 가고 골목 알림도 없다")
    void adultDoesNotAvoidAlleys() {
        // route.yml adult, 2026-11-12
        RouteResult adult = route(alleyDiamond(0.0004, null), "adult");
        assertThat(north(adult)).isFalse();
        assertThat(adult.routes().get(0).alleyM()).isEqualTo(200);
        assertThat(adult.explanation().notes()).isEmpty();
    }

    @Test
    @DisplayName("휠체어 낮에 큰 길이 1.2배를 넘으면 골목 지름길로 가고 골목길을 알린다")
    void wheelchairDayKeepsAlleyWhenBigRoadIsTooFar() {
        // 큰 길 약 1.34배 > 1.2 → 골목 지름길로 가고 남은 골목길을 알린다
        RouteResult r = route(alleyDiamond(0.0008, null), "wheelchair");
        assertThat(north(r)).isFalse();
        assertThat(r.routes()).hasSize(1);
        assertThat(r.routes().get(0).alleyM()).isEqualTo(200);
        assertThat(r.explanation().text())
                .isEqualTo("휠체어로 지날 수 있는 길 중 가장 짧은 길입니다 (알림: 골목길 약 200m를 지납니다)");
    }

    @Test
    @DisplayName("휠체어는 한도를 넘어도 인도 없는 골목을 쓰지 않고 노인은 감점만 한다")
    void wheelchairNeverUsesNoSidewalkAlleyEvenPastDetourLimit() {
        // 큰 길 약 1.34배 > 1.2여도 인도 없다고 확인된 골목은 안 쓴다 (2026-11-13). 노인은 감점만 하고 골목 지름길로
        RouteResult r = route(alleyDiamond(0.0008, "no"), "wheelchair");
        assertThat(north(r)).isTrue();
        assertThat(r.routes()).allMatch(x -> x.alleyM() == 0);
        assertThat(r.explanation().notes()).noneMatch(n -> n.kind().equals("no_sidewalk_alley"));

        RouteResult senior = route(alleyDiamond(0.0008, "no"), "senior");
        assertThat(north(senior)).isFalse();
        assertThat(senior.routes().get(0).alleyM()).isEqualTo(200);
    }

    @Test
    @DisplayName("휠체어는 다른 길로 이어지지 않을 때만 인도 없는 골목을 쓰고 주의를 붙인다")
    void wheelchairUsesNoSidewalkAlleyOnlyWhenNothingElseConnects() {
        // 보행로 S–A, 인도 없는 골목 A–B(약 255m, 출발·도착 골목 200m 규칙 밖), 보행로 B–T. 골목을 빼면 이어지지 않아
        // 골목을 넣어 다시 찾고 주의를 붙인다 (2026-11-13)
        double[] a = {126.93050, LAT};
        double[] b = {126.93330, LAT};
        double[] t = {126.93380, LAT};
        RoadGraph g = new RoadGraph(Map.of(1L, S_P, 2L, a, 3L, b, 4L, t), List.of(
                edge(1, 1, 2, S_P, a, EdgePenalty.NONE),
                road(2, 2, 3, a, b, "residential", "no"),
                edge(3, 3, 4, b, t, EdgePenalty.NONE)));

        RouteResult r = service(g).route(new RouteRequest(new Place(LAT, S_P[0], "출발"), new Place(LAT, t[0], "도착"),
                "auto", "wheelchair"));

        assertThat(r.routes().get(0).alleyM()).isBetween(250, 260);
        assertThat(r.explanation().notes()).anySatisfy(n -> {
            assertThat(n.kind()).isEqualTo("no_sidewalk_alley");
            assertThat(n.level()).isEqualTo("warn");
            assertThat(n.text()).matches("인도 없는 골목 약 25\\dm를 지납니다");
        });
    }

    @Test
    @DisplayName("도로망에 인도 없는 골목밖에 없어도 골목을 넣어 다시 찾고 주의를 붙인다")
    void wheelchairUsesNoSidewalkAlleyWhenItIsTheOnlyRoad() {
        // 골목 600m 하나: 출발·도착 골목(end-alley-m)보다 길어 처음 찾기에서는 이어지지 않는다
        double[] t = TestRoutes.east(S_P, 600);
        RoadGraph g = new RoadGraph(Map.of(1L, S_P, 2L, t), List.of(road(1, 1, 2, S_P, t, "residential", "no")));

        RouteResult r = service(g).route(new RouteRequest(new Place(LAT, S_P[0], "출발"), new Place(LAT, t[0], "도착"),
                "auto", "wheelchair"));

        assertThat(r.explanation().notes()).anySatisfy(n -> assertThat(n.kind()).isEqualTo("no_sidewalk_alley"));
    }

    @Test
    @DisplayName("골목을 써야 하면 휠체어는 인도 없는 골목을 가장 적게 지나는 길로 간다")
    void wheelchairFallbackUsesLeastNoSidewalkAlley() {
        // 보행로 S–A, B–T 사이를 잇는 길은 인도 없는 골목뿐: A–B 곧은 골목 300m(전체 약 400m)와
        // 북쪽 150m 보행로로 돌아 C–D 골목 40m만 지나는 길(약 590m). 골목 1m = 10m라 낮·밤 모두 40m 쪽 (배수 1이면 300m 쪽, 2026-11-13)
        double north = 150 / 111_195.0;
        double[] a = {126.93055, LAT};
        double[] b = {126.93385, LAT};
        double[] t = {126.93440, LAT};
        double[] c = {126.93055, LAT + north};
        double[] d = {126.93099, LAT + north};
        RoadGraph g = new RoadGraph(Map.of(1L, S_P, 2L, a, 3L, b, 4L, t, 5L, c, 6L, d), List.of(
                edge(1, 1, 2, S_P, a, EdgePenalty.NONE),
                road(2, 2, 3, a, b, "residential", "no"),
                edge(3, 3, 4, b, t, EdgePenalty.NONE),
                edge(4, 2, 5, a, c, EdgePenalty.NONE),
                road(5, 5, 6, c, d, "residential", "no"),
                edge(6, 6, 3, d, b, EdgePenalty.NONE)));
        for (int hour : new int[] {12, 21}) {
            RouteService s = TestRoutes.service(g, TestRoutes.pois(List.of()), TestRoutes.at(hour), props());
            RouteResult r = s.route(new RouteRequest(new Place(LAT, S_P[0], "출발"), new Place(LAT, t[0], "도착"),
                    "auto", "wheelchair"));

            assertThat(r.routes()).allSatisfy(x -> assertThat(x.alleyM()).isBetween(38, 42));
            // 응답 거리는 실제 길이 (부풀린 비용이 아님)
            assertThat(r.routes().get(0).distanceM()).isBetween(570, 610);
            assertThat(r.explanation().notes()).anySatisfy(n -> assertThat(n.text()).matches("인도 없는 골목 약 4\\dm를 지납니다"));
        }
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
                edge(2, 1, 3, a1, f1, EdgePenalty.NONE), edge(3, 2, 5, a2, f2, EdgePenalty.NONE),
                edge(4, 3, 4, f1, f0, EdgePenalty.NONE), edge(5, 4, 5, f0, f2, EdgePenalty.NONE),
                edge(6, 5, 6, f2, f3, EdgePenalty.NONE)));
        return service(g).route(new RouteRequest(new Place(LAT, S_P[0], "출발"), new Place(f3[1], f3[0], "도착"), "auto",
                "wheelchair"));
    }

    @Test
    @DisplayName("휠체어 출발점은 골목이 훨씬 가깝지 않으면 지날 수 있는 길에 붙인다")
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
    @DisplayName("인도를 모르거나 인도가 있는 골목은 휠체어도 빼지 않는다")
    void wheelchairKeepsAlleyWithUnknownOrBothSidewalk() {
        // 인도 모름: 감점만 (1.34배라 골목으로). 인도 있음: 큰 길로 보고 1.09배여도 지름길
        assertThat(north(route(alleyDiamond(0.0008, null), "wheelchair"))).isFalse();
        RouteResult both = route(alleyDiamond(0.0004, "both"), "wheelchair");
        assertThat(north(both)).isFalse();
        assertThat(both.routes()).hasSize(1);
        assertThat(both.routes().get(0).alleyM()).isZero();
    }

    /** 출발 S에서 골목을 따라 동쪽 90m (인도 없는 골목 안). 지날 수 있는 큰 길(S–N)까지 약 60m라 50m 넘게 멀어 골목에 붙는다 */
    static final double[] IN_ALLEY = {126.93000 + 0.00099, LAT};

    static RouteResult fromAlley(String... overrides) {
        return service(alleyDiamond(0.0008, "no"), overrides).route(new RouteRequest(new Place(IN_ALLEY[1], IN_ALLEY[0], "출발"),
                new Place(LAT, T_P[0], "도착"), "auto", "wheelchair"));
    }

    @Test
    @DisplayName("출발점이 인도 없는 골목 안이면 그 골목에 붙여 골목을 따라 나간다")
    void wheelchairStartsInsideNoSidewalkAlley() {
        // 출발점이 인도 없는 골목 안이면 그 골목에 붙여 골목을 따라 나간다 (end-alley-m 200m 안: 10m + 100m로 T까지)
        RouteResult r = fromAlley();
        RouteResult.Route route = r.routes().get(0);
        assertThat(north(r)).isFalse();
        assertThat(route.geometry().get(0)[0]).isCloseTo(IN_ALLEY[0], org.assertj.core.data.Offset.offset(1e-6));
        assertThat(route.alleyM()).isBetween(105, 115);
        assertThat(r.explanation().notes()).noneMatch(n -> n.kind().equals("snap"));

        // 50m까지만 열면 골목을 못 나가 큰 길에 다시 붙이고(약 60m) 알린다
        RouteResult short50 = fromAlley("route.end-alley-m=50");
        assertThat(north(short50)).isTrue();
        assertThat(short50.explanation().notes()).anyMatch(n -> n.kind().equals("snap"));
    }

    @Test
    @DisplayName("들를 곳이 인도 없는 골목 안이면 그 골목으로 드나든다")
    void wheelchairStopInsideNoSidewalkAlley() {
        // 들를 곳(M)이 인도 없는 골목 안: 그 골목으로 드나든다. 다른 경로 없이 NO_ROUTE가 되지 않는다
        RouteResult r = service(alleyDiamond(0.0008, "no")).route(new RouteRequest(new Place(LAT, S_P[0], "출발"),
                new Place(LAT, T_P[0], "도착"), "auto", "wheelchair", null, null, List.of(new Place(LAT, M_P[0], "가게")), null));
        assertThat(r.routes().get(0).stops()).hasSize(1);
        assertThat(r.routes().get(0).alleyM()).isEqualTo(200);
        assertThat(r.explanation().notes()).noneMatch(n -> n.kind().equals("snap"));
    }

    /**
     * 노드 1에 붙인 휠체어가 인도 없는 골목 1–2(firstM), 2–3(secondM)을 따라 나가 3에서 보행로 3–4를 만난다.
     * 길이는 좌표 대신 직접 줘 경계값 자체를 만든다. 돌려주는 값은 연 골목 번호
     */
    static Set<Long> openAlleys(double firstM, double secondM) {
        double[] p1 = xy(0, 0);
        double[] p2 = xy(firstM, 0);
        double[] p3 = xy(firstM + secondM, 0);
        double[] p4 = xy(firstM + secondM + 50, 0);
        RoadGraph g = new RoadGraph(Map.of(1L, p1, 2L, p2, 3L, p3, 4L, p4), List.of(
                new RoadEdge(1, 1, 2, firstM, "residential", List.of(p1, p2), EdgePenalty.NONE, "no"),
                new RoadEdge(2, 2, 3, secondM, "residential", List.of(p2, p3), EdgePenalty.NONE, "no"),
                edge(3, 3, 4, p3, p4, EdgePenalty.NONE)));
        return Snapper.endAlleys(g, List.of(new RoadGraph.Snap(1, 0)), props().profile("wheelchair"), props().endAlleyM());
    }

    @Test
    @DisplayName("휠체어 출발·도착·들를 곳이 골목 안이면 정한 길이까지만 골목을 지난다")
    void endAlleyIsOpenUpToEndAlleyM() {
        // end-alley-m 이하 (route.yml end-alley-m, Snapper.endAlleys)
        double rest = props().endAlleyM() - 50;
        assertThat(openAlleys(rest, justBelow(50))).containsExactlyInAnyOrder(1L, 2L);
        assertThat(openAlleys(rest, 50)).containsExactlyInAnyOrder(1L, 2L);
        assertThat(openAlleys(rest, justAbove(50))).containsExactly(1L);
    }

    /** 보행로 S–A(50m), 인도 태그 없는 골목 A–B(alleyM, 길이를 직접 줌), 보행로 B–T(50m) 한 줄로 휠체어 낮 경로 */
    static RouteResult throughAlley(double alleyM) {
        double[] a = xy(50, 0);
        double[] b = xy(50 + alleyM, 0);
        double[] t = xy(100 + alleyM, 0);
        RoadGraph g = new RoadGraph(Map.of(1L, S_P, 2L, a, 3L, b, 4L, t), List.of(
                edge(1, 1, 2, S_P, a, EdgePenalty.NONE),
                new RoadEdge(2, 2, 3, alleyM, "residential", List.of(a, b), EdgePenalty.NONE, null),
                edge(3, 3, 4, b, t, EdgePenalty.NONE)));
        return service(g).route(new RouteRequest(new Place(LAT, S_P[0], "출발"), new Place(t[1], t[0], "도착"), "auto",
                "wheelchair"));
    }

    @Test
    @DisplayName("골목길 기준을 쓸 때 남은 골목길이 30m 이상이면 알린다")
    void alleyNoteFromThirtyMeters() {
        // RouteResultAssembler.ALLEY_NOTE_MIN_M, 다른 패키지라 숫자로 둠
        // 골목 길이는 m 정수로 반올림해 비교하므로 바로 아래 값은 29m다
        int min = 30;
        assertThat(throughAlley(min).explanation().notes())
                .contains(RouteResult.Note.info("alley", "골목길 약 %dm를 지납니다".formatted(min)));
        assertThat(throughAlley(min - 1).explanation().notes()).noneMatch(n -> n.kind().equals("alley"));
    }
}
