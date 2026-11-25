package com.saferoute.route;

import static com.saferoute.route.TestRoutes.LAT;
import static com.saferoute.route.TestRoutes.M_P;
import static com.saferoute.route.TestRoutes.STAIRS;
import static com.saferoute.route.TestRoutes.S_P;
import static com.saferoute.route.TestRoutes.T_P;
import static com.saferoute.route.TestRoutes.assertError;
import static com.saferoute.route.TestRoutes.assertNoRoute;
import static com.saferoute.route.TestRoutes.east;
import static com.saferoute.route.TestRoutes.edge;
import static com.saferoute.route.TestRoutes.justAbove;
import static com.saferoute.route.TestRoutes.justBelow;
import static com.saferoute.route.TestRoutes.north;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import com.saferoute.route.api.RouteError;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.event.RoadEvent;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.search.RoadPath;

/** 걷기 들를 곳 (진행표 w10, plan 005, 2026-11-10). 구간마다 찾아 이은 뒤 응답을 한 번만 만든다 */
@DisplayName("들를 곳")
class RouteStopsTests {

    static Place place(double[] p, String name) {
        return new Place(p[1], p[0], name);
    }

    static RouteRequest request(double[] from, double[] to, String mode, Place... stops) {
        return new RouteRequest(place(from, "출발"), place(to, "도착"), "auto", mode, null, null, List.of(stops), null);
    }

    static RouteService service(RoadGraph g, int hour) {
        return TestRoutes.service(g, TestRoutes.pois(List.of()), hour);
    }

    /** 노드 번호를 1부터 차례로 매긴 도로망 */
    static RoadGraph graph(List<double[]> nodes, List<RoadEdge> edges) {
        Map<Long, double[]> m = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            m.put(i + 1L, nodes.get(i));
        }
        return new RoadGraph(m, edges);
    }

    static RoadEdge road(long id, long from, long to, double[] a, double[] b, String sidewalk) {
        return TestRoutes.road(id, from, to, a, b, "residential", sidewalk);
    }

    /** 경로 선을 따라 그 점(의 수직의 발)까지 간 거리 */
    static double along(double[] p, List<double[]> geometry) {
        return Geo.along(p, geometry).atM();
    }

    @Test
    @DisplayName("경로는 들를 곳을 지나고 atM은 출발부터 경로 선을 따라 잰 거리다")
    void oneStopIsOnTheRouteAndAtMIsMeasuredFromTheStart() {
        // 진행표 w10, plan 005
        double spacing = 100;
        RoadGraph g = TestRoutes.grid(5, spacing, (r, c) -> false);
        double[] origin = g.point(0);
        double[] dest = g.point(4); // 0행 4열
        // 2행 2열과 3열 사이 길 한가운데에서 북쪽 10m: 길을 나눈 임시 노드에 붙는다
        double[] foot = east(g.point(2 * 5 + 2), spacing / 2);
        double[] shop = north(foot, 10);

        RouteResult r = service(g, 12).route(request(origin, dest, "adult", place(shop, "다이소")));

        RouteResult.Route safe = r.routes().get(0);
        assertThat(r.routes()).hasSize(1);
        assertThat(safe.stops()).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("다이소");
            assertThat(s.lat()).isEqualTo(shop[1]);
            assertThat(s.lon()).isEqualTo(shop[0]);
            // 출발이 노드 위라 첫 구간 길이 그대로: 2칸 위 + 2.5칸 옆
            assertThat(s.atM()).isBetween(445, 455);
            assertThat((double) s.atM()).isCloseTo(along(shop, safe.geometry()), org.assertj.core.data.Offset.offset(1.0));
        });
        // 경로가 들를 곳을 붙인 길 위 점을 지난다
        assertThat(safe.geometry()).anySatisfy(p -> assertThat(Geo.distance(p, foot)).isLessThan(0.5));
        // 전체 거리 = 들를 곳까지 + 들를 곳부터 도착까지 (2칸 아래 + 1.5칸 옆)
        assertThat(safe.distanceM()).isBetween(795, 805);
        assertThat(safe.durationMin()).isEqualTo(Math.round(safe.distanceM() / 80f));

        String json = JsonMapper.builder().build().writeValueAsString(r);
        assertThat(json).contains("\"stops\":[{\"name\":\"다이소\",\"lat\":" + shop[1] + ",\"lon\":" + shop[0]
                + ",\"atM\":" + safe.stops().get(0).atM() + "}]");
    }

    @Test
    @DisplayName("최단·안전 경로는 들를 곳 자리(atM)를 각자 잰다")
    void shortestAndSafeHaveTheirOwnAtM() {
        // 다이아몬드(밤에 밝은 북쪽 길로 도는 S → T) 뒤에 동쪽 100m 길 T–U. 들를 곳 T, 도착 U
        double[] u = east(T_P, 100);
        RoadGraph g = TestRoutes.plus(TestRoutes.diamond(0.0006), Map.of(5L, u),
                edge(14, TestRoutes.T, 5, T_P, u, EdgePenalty.NONE));
        RouteService s = TestRoutes.service(g,
                TestRoutes.pois(TestRoutes.brightSideFacilities()), 21);

        RouteResult r = s.route(request(S_P, u, "adult", place(T_P, "편의점")));

        assertThat(r.routes()).extracting(RouteResult.Route::kind).containsExactly("shortest", "safe");
        RouteResult.Route shortest = r.routes().get(0);
        RouteResult.Route safe = r.routes().get(1);
        int shortAt = shortest.stops().get(0).atM();
        int safeAt = safe.stops().get(0).atM();
        assertThat(shortAt).isBetween(195, 205);
        assertThat(safeAt).isGreaterThan(shortAt + 20);
        // 두 경로 모두 들를 곳 뒤로 같은 100m가 남는다
        assertThat(shortest.distanceM() - shortAt).isBetween(99, 101);
        assertThat(safe.distanceM() - safeAt).isBetween(99, 101);
    }

    @Test
    @DisplayName("들를 곳이 너무 많거나 lat·lon이 없으면 입력 오류다")
    void tooManyStopsOrMissingCoordinatesIsBadParam() {
        // RouteService.MAX_STOPS곳까지 (진행표 w10). 같은 곳 두 번은 stopsCloserThan1mAreTheSameStop
        RouteService s = service(TestRoutes.diamond(0.0006), 12);
        double[] n = {126.93110, LAT + 0.0006};

        // 지름길 M–T 위 10m 간격으로 MAX_STOPS곳은 받고, 한 곳 더하면 거절한다
        List<Place> max = new ArrayList<>();
        for (int i = 0; i < RouteService.MAX_STOPS; i++) {
            max.add(place(east(M_P, 10 * i), String.valueOf(i + 1)));
        }
        List<Place> over = new ArrayList<>(max);
        over.add(place(n, "넘침"));
        assertThat(s.route(request(S_P, T_P, "adult", max.toArray(Place[]::new))).routes()).isNotEmpty();
        assertThatThrownBy(() -> s.route(request(S_P, T_P, "adult", over.toArray(Place[]::new))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("stops는 최대 %d곳입니다".formatted(RouteService.MAX_STOPS));
        assertThatThrownBy(() -> s.route(request(S_P, T_P, "adult", new Place(null, M_P[0], "편의점"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("stops의 lat·lon이 필요합니다");
    }

    @Test
    @DisplayName("들를 곳 둘이 1m 미만이면 같은 곳으로 보고 거절한다")
    void stopsCloserThan1mAreTheSameStop() {
        // RouteService.SAME_STOP_M (사용자 결정 11-10)
        // 좌표 거리라 경계값 자체는 못 만들어 경계 ±0.01m로 본다
        RouteService s = service(TestRoutes.diamond(0.0006), 12);
        double[] n = {126.93110, LAT + 0.0006};

        assertThatThrownBy(() -> s.route(request(S_P, T_P, "adult", place(M_P, "1"), place(n, "2"),
                place(east(M_P, justBelow(RouteService.SAME_STOP_M)), "3")))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("같은 들를 곳이 두 번 있습니다");
        assertThat(s.route(request(S_P, T_P, "adult", place(M_P, "1"), place(n, "2"),
                place(east(M_P, justAbove(RouteService.SAME_STOP_M)), "3")))
                .routes()).isNotEmpty();
    }

    @Test
    @DisplayName("왕복은 SAME_POINT이고 들를 곳이 출발·도착 가까이 있는 것은 안내한다")
    void roundTripIsSamePointButAStopNextToAPointIsRouted() {
        // 사용자 결정 11-10
        RouteService s = service(TestRoutes.diamond(0.0006), 12);

        // 왕복은 받지 않는다 (사용자 결정 11-10)
        assertError(() -> s.route(request(S_P, S_P, "adult", place(T_P, "편의점"))), RouteError.SAME_POINT);
        // 들를 곳이 출발·도착과 50m 안이어도 안내한다 (사용자 결정 11-10). 길이 0에 가까운 구간은 잇기에서 빠진다
        for (double[] near : List.of(east(S_P, 30), east(T_P, -30), S_P, T_P)) {
            var r = s.route(request(S_P, T_P, "adult", place(near, "편의점")));
            var safe = r.routes().get(r.routes().size() - 1);
            assertThat(safe.stops()).hasSize(1);
            assertThat(safe.stops().get(0).atM()).isBetween(0, safe.distanceM());
        }
    }

    @Test
    @DisplayName("TOO_FAR는 구간 직선거리 합으로 재고 들를 곳이 도로망 밖이면 OUT_OF_AREA다")
    void tooFarAddsUpEveryLeg() {
        // RouteService.validate
        RouteService s = service(TestRoutes.diamond(0.0006), 12);

        // 구간은 3km씩이지만 합이 6km
        assertError(() -> s.route(request(S_P, east(S_P, 100), "adult", place(east(S_P, 3000), "편의점"))),
                RouteError.TOO_FAR);
        // 들를 곳이 도로망 범위 밖
        assertError(() -> s.route(request(S_P, T_P, "adult", place(north(M_P, 500), "편의점"))),
                RouteError.OUT_OF_AREA);
    }

    @Test
    @DisplayName("들를 곳이 행사 반경 안이면 행사를 피하지 않는다")
    void festivalAtAStopIsNotAvoided() {
        // RoadEventTests와 같은 다이아몬드: 지름길 한가운데 M에 반경 30m 행사. 노인은 원래 북쪽으로 돌아 피한다.
        // 남쪽 아무 길 없는 곳에 노드 하나를 둬 도로망 범위를 넓힌다
        RoadGraph g = TestRoutes.plus(TestRoutes.diamond(0.0004), Map.of(99L, north(M_P, -300)));
        RoadEvent fest = new RoadEvent(1, "행사", "충장축제", null, null, M_P[0], M_P[1], 30, null);
        RouteService s = TestRoutes.service(g, TestRoutes.pois(List.of(), List.of(fest)), 12);

        // M 그대로(요청 좌표가 반경 안), M 남쪽 35m(요청 좌표는 반경 밖이지만 붙인 길 점 M은 안)
        for (double[] stop : List.of(M_P, north(M_P, -35))) {
            RouteResult r = s.route(request(S_P, T_P, "senior", place(stop, "광장")));

            RouteResult.Route safe = r.routes().get(r.routes().size() - 1);
            assertThat(safe.events()).singleElement().satisfies(e -> assertThat(e.avoid()).isFalse());
            assertThat(r.explanation().text()).doesNotContain("너무 멉니다", "피해 갑니다");
            assertThat(safe.stops().get(0).atM()).isBetween(95, 105);
        }
    }

    /**
     * 큰길 S–T(약 365m)와 T 남쪽 150m U. T 북쪽에 계단으로만 드나드는 구역: T –계단 5m– P –보행로– D(T 북쪽 dNorthM).
     * U–T는 거꾸로 저장한 차도(인도 왼쪽)
     */
    static RoadGraph pocket(double dNorthM) {
        double[] s = {126.93, LAT};
        double[] t = east(s, 365);
        double[] p = north(t, 5);
        double[] d = north(t, dNorthM);
        double[] u = north(t, -150);
        return graph(List.of(s, t, p, d, u), List.of(
                edge(1, 1, 2, s, t, EdgePenalty.NONE),
                edge(2, 2, 3, t, p, STAIRS),
                edge(3, 3, 4, p, d, EdgePenalty.NONE),
                road(4, 5, 2, u, t, "left")));
    }

    @Test
    @DisplayName("길을 못 찾은 구간은 오류 문장에 그 들를 곳·도착지 이름을 넣는다")
    void blockedLegNamesTheStop() {
        RoadGraph g = pocket(70);
        RouteService s = service(g, 12);
        double[] start = g.point(1);
        double[] d = g.point(4);
        double[] u = g.point(5);

        // 휠체어: D는 계단으로만 닿고, 지날 수 있는 길(T)은 50m 넘게 더 멀다 (들를 곳은 50m까지)
        assertNoRoute(() -> s.route(request(start, u, "wheelchair", place(d, "다이소"))),
                "다이소까지 가는 길을 찾지 못했어요");
        assertNoRoute(() -> s.route(request(start, u, "wheelchair", place(d, " "))),
                "들를 곳 1까지 가는 길을 찾지 못했어요");
        // 도착점은 100m까지 붙으므로 (2026-11-13) 130m 뒤로
        RoadGraph far = pocket(130);
        RouteService fs = service(far, 12);
        double[] fd = far.point(4);
        assertNoRoute(() -> fs.route(request(start, fd, "wheelchair", place(u, "편의점"))),
                "도착지까지 가는 길을 찾지 못했어요");
        // 들를 곳이 없으면 지금 문장 그대로
        assertNoRoute(() -> fs.route(request(start, fd, "wheelchair")), RouteError.NO_ROUTE.message());
    }

    @Test
    @DisplayName("들를 곳이 길에서 너무 멀어 못 붙여도 그 이름으로 알린다")
    void stopFarFromAnyRoadNamesTheStop() {
        double[] a = {126.93, LAT};
        double[] b = east(a, 365);
        // 길이 없는 노드 하나로 도로망 범위만 북쪽으로 넓힌다
        RoadGraph g = graph(List.of(a, b, north(a, 600)), List.of(edge(1, 1, 2, a, b, EdgePenalty.NONE)));

        assertNoRoute(() -> service(g, 12).route(request(a, b, "adult", place(north(east(a, 180), 400), "공원"))),
                "공원까지 가는 길을 찾지 못했어요");
    }

    @Test
    @DisplayName("휠체어는 들를 곳 구간에서도 계단을 지나지 않는다")
    void wheelchairNeverTakesStairsOnAStopLeg() {
        // S –계단 200m– X – D(동쪽 200m). 계단 없이는 북쪽 N으로 돈다
        double[] s = {126.93, LAT};
        double[] x = east(s, 200);
        double[] dest = east(x, 200);
        double[] n = north(east(s, 100), 100);
        RoadGraph g = graph(List.of(s, x, dest, n), List.of(
                edge(1, 1, 2, s, x, STAIRS),
                edge(2, 2, 3, x, dest, EdgePenalty.NONE),
                edge(3, 1, 4, s, n, EdgePenalty.NONE),
                edge(4, 4, 2, n, x, EdgePenalty.NONE)));

        RouteResult w = service(g, 12).route(request(s, dest, "wheelchair", place(x, "약국")));
        RouteResult adult = service(g, 12).route(request(s, dest, "adult", place(x, "약국")));

        assertThat(w.routes()).allSatisfy(rt -> {
            assertThat(rt.stairs()).isEmpty();
            assertThat(rt.stops().get(0).atM()).isBetween(280, 286); // 2 × √(100² + 100²)
        });
        assertThat(adult.routes().get(0).stairs()).singleElement().satisfies(st -> assertThat(st.fromM()).isZero());
        assertThat(adult.routes().get(0).stops().get(0).atM()).isBetween(199, 201);
    }

    @Test
    @DisplayName("계단·꺾는 곳 자리는 구간을 이은 전체 경로를 따라 잰다")
    void stairsAndTurnsAreMeasuredAlongTheWholeRoute() {
        // S – X(200m, 들를 곳) –계단 100m– Y – D(Y 북쪽 100m). 계단과 꺾는 곳은 둘째 구간에 있다
        double[] s = {126.93, LAT};
        double[] x = east(s, 200);
        double[] y = east(x, 100);
        double[] dest = north(y, 100);
        RoadGraph g = graph(List.of(s, x, y, dest), List.of(
                edge(1, 1, 2, s, x, EdgePenalty.NONE),
                edge(2, 2, 3, x, y, STAIRS),
                edge(3, 3, 4, y, dest, EdgePenalty.NONE)));

        RouteResult.Route r = service(g, 12).route(request(s, dest, "adult", place(x, "가게"))).routes().get(0);

        int stopAt = r.stops().get(0).atM();
        assertThat(stopAt).isBetween(199, 201);
        assertThat(r.stairs()).singleElement().satisfies(st -> {
            assertThat(st.fromM()).isEqualTo(stopAt);
            assertThat(st.toM()).isBetween(299, 301);
        });
        assertThat(r.turns()).singleElement().satisfies(t -> {
            assertThat(t.atM()).isBetween(299, 301);
            assertThat(t.direction()).isEqualTo("left");
        });
        assertThat(r.distanceM()).isBetween(399, 401);
    }

    @Test
    @DisplayName("휠체어 들를 곳을 다시 붙여도 이은 경로의 꺾는 방향·인도 쪽이 맞다")
    void wheelchairMovedStopKeepsDirectionsOnTheJoinedRoute() {
        // 들를 곳 D는 계단 뒤 20m: 휠체어는 T에 다시 붙인다. 그다음 구간 T → U는 거꾸로 저장한 차도
        RoadGraph g = pocket(20);
        double[] start = g.point(1);
        double[] u = g.point(5);

        RouteResult r = service(g, 12).route(request(start, u, "wheelchair", place(g.point(4), "편의점")));

        RouteResult.Route safe = r.routes().get(r.routes().size() - 1);
        assertThat(safe.stops().get(0).atM()).isBetween(364, 366);
        // 동쪽으로 가다 남쪽으로: 오른쪽. 저장 방향(U → T)과 반대로 걸어 인도 왼쪽이 걷는 방향 오른쪽
        assertThat(safe.turns()).singleElement().satisfies(t -> {
            assertThat(t.direction()).isEqualTo("right");
            assertThat(t.atM()).isBetween(364, 366);
        });
        assertThat(safe.sidewalks()).extracting(RouteResult.Sidewalk::from, RouteResult.Sidewalk::to, RouteResult.Sidewalk::side)
                .containsExactly(tuple(1, 2, "right"));
        assertThat(r.explanation().notes()).extracting(RouteResult.Note::text)
                .contains("편의점 근처 약 20m는 휠체어로 이어진 길이 확인되지 않았습니다");
    }

    @Test
    @DisplayName("막다른 들를 곳에서 되돌아와도 걷는 방향의 인도 쪽이 맞다")
    void uTurnAtADeadEndStopKeepsDirections() {
        // S – J(200m) – D(동쪽 200m), J 북쪽 100m 막다른 길 끝 X가 들를 곳. J–X는 인도 왼쪽 차도
        double[] s = {126.93, LAT};
        double[] j = east(s, 200);
        double[] x = north(j, 100);
        double[] dest = east(j, 200);
        RoadGraph g = graph(List.of(s, j, x, dest), List.of(
                edge(1, 1, 2, s, j, EdgePenalty.NONE),
                road(2, 2, 3, j, x, "left"),
                edge(3, 2, 4, j, dest, EdgePenalty.NONE)));

        RouteResult.Route r = service(g, 12).route(request(s, dest, "adult", place(x, "택배함"))).routes().get(0);

        assertThat(r.stops().get(0).atM()).isBetween(299, 301);
        assertThat(r.distanceM()).isBetween(599, 601);
        // 같은 길을 갔다가 되돌아온다: 갈 때 왼쪽, 올 때 오른쪽
        assertThat(r.sidewalks()).extracting(RouteResult.Sidewalk::from, RouteResult.Sidewalk::to, RouteResult.Sidewalk::side)
                .containsExactly(tuple(1, 2, "left"), tuple(2, 3, "right"));
    }

    @Test
    @DisplayName("구간을 이을 때 길마다 걷는 방향(forward)을 지키고 이음 점은 한 번만 넣는다")
    void joinedPathKeepsDirectionAcrossLegs() {
        double[] a = {126.93, LAT};
        double[] b = east(a, 100);
        double[] c = east(b, 100);
        RoadEdge ab = edge(1, 1, 2, a, b, EdgePenalty.NONE);
        RoadEdge cb = edge(2, 3, 2, c, b, EdgePenalty.NONE); // 거꾸로 저장

        RoadPath joined = RoadPath.join(List.of(new RoadPath(List.of(ab), List.of(a, b), ab.lengthM()),
                new RoadPath(List.of(cb), List.of(b, c), cb.lengthM())));
        assertThat(joined.edges()).containsExactly(ab, cb);
        assertThat(joined.line()).containsExactly(a, b, c);
        assertThat(joined.lengthM()).isEqualTo(ab.lengthM() + cb.lengthM());
        assertThat(joined.forward()).containsExactly(true, false);

        // 같은 길로 되돌아오기 (U턴)
        RoadPath back = RoadPath.join(List.of(new RoadPath(List.of(ab), List.of(a, b), ab.lengthM()),
                new RoadPath(List.of(ab), List.of(b, a), ab.lengthM())));
        assertThat(back.line()).containsExactly(a, b, a);
        assertThat(back.forward()).containsExactly(true, false);
    }

    @Test
    @DisplayName("빈 들를 곳 목록은 들를 곳이 없는 요청과 응답이 같다")
    void noStopsIsTheSameAsBefore() {
        RouteService s = TestRoutes.service(TestRoutes.diamond(0.0006),
                TestRoutes.pois(TestRoutes.brightSideFacilities()), 21);
        JsonMapper json = JsonMapper.builder().build();
        Place from = place(S_P, "출발");
        Place to = place(T_P, "도착");

        RouteResult before = s.route(new RouteRequest(from, to, "auto", "adult", null, null));
        RouteResult empty = s.route(new RouteRequest(from, to, "auto", "adult", null, null, List.of(), null));

        assertThat(json.writeValueAsString(empty)).isEqualTo(json.writeValueAsString(before));
        assertThat(before.routes()).allSatisfy(rt -> assertThat(rt.stops()).isEmpty());
    }

    @Test
    @DisplayName("들를 곳 3곳이면 네 구간을 이어 atM이 차례로 늘고 선이 끊기지 않는다")
    void threeStopsJoinFourLegs() {
        RoadGraph g = TestRoutes.grid(6, 100, (r, c) -> false);
        double[] origin = g.point(0);
        double[] dest = g.point(5); // 0행 5열
        // 2행 1열 → 4행 3열 → 5행 5열: 구간 300m·400m·300m·500m
        double[] a = g.point(2 * 6 + 1);
        double[] b = g.point(4 * 6 + 3);
        double[] c = g.point(5 * 6 + 5);

        RouteResult.Route r = service(g, 12).route(request(origin, dest, "adult", place(a, "가"), place(b, "나"),
                place(c, "다"))).routes().get(0);

        assertThat(r.stops()).extracting(RouteResult.StopMark::name).containsExactly("가", "나", "다");
        assertThat(r.stops()).extracting(RouteResult.StopMark::atM).satisfiesExactly(
                m -> assertThat(m).isBetween(298, 302), m -> assertThat(m).isBetween(697, 703),
                m -> assertThat(m).isBetween(996, 1004));
        assertThat(r.stops().get(2).atM()).isLessThan(r.distanceM());
        assertThat(r.distanceM()).isBetween(1495, 1505);
        // 이은 선: 출발에서 시작해 도착에서 끝나고, 구간 경계 점이 두 번 들어가지 않으며, 들를 곳을 모두 지난다
        List<double[]> line = r.geometry();
        assertThat(line.get(0)).isEqualTo(origin);
        assertThat(line.get(line.size() - 1)).isEqualTo(dest);
        for (int i = 1; i < line.size(); i++) {
            assertThat(Geo.distance(line.get(i - 1), line.get(i))).isGreaterThan(1);
        }
        assertThat(Geo.length(line)).isCloseTo(r.distanceM(), org.assertj.core.data.Offset.offset(1.0));
        for (double[] p : List.of(a, b, c)) {
            assertThat(line).anySatisfy(q -> assertThat(Geo.distance(p, q)).isLessThan(0.5));
        }
    }

    @Test
    @DisplayName("비포장을 피하면 더 걷는 거리는 구간을 이은 전체 경로로 잰다")
    void unpavedDetourIsMeasuredOverTheWholeJoinedRoute() {
        // 휠체어 밤. 첫 구간 S → X: 비포장 지름길 100m, 포장 길로 돌면 약 130m(한도 1.2배를 넘어 비포장을 지난다).
        // 둘째 구간 X → D: 어두운 지름길 600m, 등이 줄지은 북쪽 길 약 690m(1.15배, 밝은 길로 돈다)
        double[] s = {126.93, LAT};
        double[] x = east(s, 100);
        double[] a = north(east(s, 50), Math.sqrt(65 * 65 - 50 * 50));
        double[] dest = east(x, 600);
        double[] b = north(east(x, 300), Math.sqrt(345 * 345 - 300 * 300));
        RoadGraph g = graph(List.of(s, x, a, dest, b), List.of(
                edge(1, 1, 2, s, x, new EdgePenalty(Map.of(Penalty.UNPAVED, 1.0))),
                edge(2, 1, 3, s, a, EdgePenalty.NONE),
                edge(3, 3, 2, a, x, EdgePenalty.NONE),
                edge(4, 2, 4, x, dest, EdgePenalty.NONE),
                edge(5, 2, 5, x, b, EdgePenalty.NONE),
                edge(6, 5, 4, b, dest, EdgePenalty.NONE)));
        List<com.saferoute.route.engine.facility.Poi> lights = new ArrayList<>();
        for (int i = 1; i < 12; i++) {
            double t = i / 12.0;
            lights.add(TestRoutes.plain("light", i, x[0] + (b[0] - x[0]) * t, x[1] + (b[1] - x[1]) * t, 1));
            lights.add(TestRoutes.plain("light", 100 + i, b[0] + (dest[0] - b[0]) * t, b[1] + (dest[1] - b[1]) * t, 1));
        }
        RouteService service = TestRoutes.service(g, TestRoutes.pois(lights), 21);

        RouteResult r = service.route(request(s, dest, "wheelchair", place(x, "편의점")));

        RouteResult.Route safe = r.routes().get(r.routes().size() - 1);
        assertThat(safe.unpavedM()).isBetween(99, 101);
        assertThat(safe.distanceM()).isBetween(785, 795); // 둘째 구간은 밝은 길로 돌았다
        // 피하는 길 = 첫 구간 포장 길(+30m) + 둘째 구간 같은 밝은 길
        assertThat(r.explanation().notes()).filteredOn(n -> n.kind().equals("unpaved")).singleElement()
                .satisfies(n -> assertThat(n.text()).matches("비포장길 약 100m를 지납니다\\. 비포장을 피하면 (29|30|31)m 더 걸립니다"));
    }

    @Test
    @DisplayName("조건부 제외 없이 가는 길은 구간마다 다 있을 때만 있다고 본다")
    void stairFreeWayMustExistOnEveryLeg() {
        // RouteService.join
        // 노인, 첫 구간 S → T는 계단 없음, 둘째 구간 T → U는 계단뿐
        double[] u = east(T_P, 60);
        RoadGraph g = TestRoutes.plus(TestRoutes.diamond(0.0004), Map.of(5L, u), edge(14, TestRoutes.T, 5, T_P, u, STAIRS));

        RouteResult r = service(g, 12).route(request(S_P, u, "senior", place(T_P, "편의점")));

        assertThat(r.explanation().notes()).contains(RouteResult.Note.warn("stairs", "계단 1곳을 지납니다. 계단을 피하는 길은 없습니다"));
    }

    @Test
    @DisplayName("들를 곳으로 구간을 다시 이어도 골목길 칸이 그대로 온다")
    void alleyFieldsSurviveJoiningStops() {
        // 들를 곳이 있으면 경로를 구간마다 찾아 다시 잇는데(withStops) 골목길 칸도 그대로 온다
        double[] nP = {126.93110, LAT + 0.0004};
        RoadGraph g = new RoadGraph(Map.of(1L, S_P, 2L, M_P, 3L, T_P, 4L, nP), List.of(
                road(10, 1, 2, S_P, M_P, null), road(11, 2, 3, M_P, T_P, null),
                TestRoutes.road(12, 1, 4, S_P, nP, "tertiary", null), TestRoutes.road(13, 3, 4, T_P, nP, "tertiary", null)));

        RouteResult r = service(g, 12).route(request(S_P, T_P, "adult", place(M_P, "가게")));

        assertThat(r.routes().get(0).stops()).hasSize(1);
        assertThat(r.routes().get(0).alleyM()).isEqualTo(200);
        assertThat(r.routes().get(0).alleys()).hasSize(1);
    }

    @Test
    @DisplayName("SAME_POINT는 들를 곳이 있든 없든 직선거리로만 정한다")
    void samePointIsJudgedByStraightDistanceWithOrWithoutStops() {
        // 큰길 S–T 가운데 북쪽 30~60m의 막다른 보행로 P1–P2. 80m 떨어진 두 점이 모두 P2에 붙는다.
        // SAME_POINT는 직선거리로만 정하므로 들를 곳이 있어도 똑같이 안내한다
        double[] s = {126.93, LAT};
        double[] t = {126.934, LAT};
        double[] mid = {(s[0] + t[0]) / 2, LAT};
        double[] p1 = north(mid, 30);
        double[] p2 = north(mid, 60);
        RoadGraph g = graph(List.of(s, t, p1, p2, east(t, 180), east(s, -180)), List.of(
                edge(1, 1, 2, s, t, EdgePenalty.NONE), edge(4, 2, 5, t, east(t, 180), EdgePenalty.NONE),
                edge(5, 6, 1, east(s, -180), s, EdgePenalty.NONE),
                edge(2, 2, 3, t, p1, STAIRS), edge(3, 3, 4, p1, p2, EdgePenalty.NONE)));
        Place a = place(east(p2, 40), null);
        Place b = place(east(p2, -40), null);
        RouteService svc = service(g, 12);

        RouteResult direct = svc.route(new RouteRequest(a, b, "off", "adult"));
        RouteResult via = svc.route(new RouteRequest(a, b, "off", "adult", null, null,
                List.of(new Place(b.lat(), b.lon() + 0.00001, "가게")), null));

        assertThat(via.routes().get(0).distanceM()).isEqualTo(direct.routes().get(0).distanceM()).isBetween(75, 85);
    }
}
