package com.saferoute.route;

import static com.saferoute.route.TestRoutes.LAT;
import static com.saferoute.route.TestRoutes.M_P;
import static com.saferoute.route.TestRoutes.STAIRS;
import static com.saferoute.route.TestRoutes.S_P;
import static com.saferoute.route.TestRoutes.T_P;
import static com.saferoute.route.TestRoutes.assertNoRoute;
import static com.saferoute.route.TestRoutes.edge;
import static com.saferoute.route.TestRoutes.justAbove;
import static com.saferoute.route.TestRoutes.justBelow;
import static com.saferoute.route.TestRoutes.north;
import static com.saferoute.route.TestRoutes.report;
import static com.saferoute.route.TestRoutes.safe;
import static com.saferoute.route.TestRoutes.xy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.data.Offset.offset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.event.RoadReport;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 출발·들를 곳·도착을 길에 붙이기: 300m 한도, 길 위 수직의 발, 이어지지 않은 조각,
 * 휠체어가 지날 수 있는 길로 이어진 곳에 다시 붙이기(출발·도착 100m, 길안내 중 다시 찾기 50m), 피할 신고를 지나는 곧은 줄.
 */
@DisplayName("출발점 붙이기(스냅)")
class SnapTests {

    static RouteRequest request(double[] from, double[] to, String mode) {
        return new RouteRequest(new Place(from[1], from[0], null), new Place(to[1], to[0], null), "auto", mode);
    }

    // ── 처음 붙이기 ──

    @Test
    @DisplayName("가장 가까운 길이 300m 이하면 붙이고 넘으면 NO_ROUTE다")
    void snapUpTo300m() {
        // snap-max-m (route.yml snap-max-m, Snapper.snap)
        RouteService s = TestRoutes.service(TestRoutes.lineWithFarNode(), TestRoutes.pois(List.of()), 12);
        double max = TestRoutes.props().snapMaxM();
        // 좌표 거리라 경계값 자체는 못 만들어 경계 ±0.01m로 본다. 도착점에서 가장 가까운 길 점은 S (T는 대각선이라 더 멀다)
        assertThatCode(() -> s.route(request(T_P, north(S_P, justBelow(max)), "adult"))).doesNotThrowAnyException();
        assertNoRoute(() -> s.route(request(T_P, north(S_P, justAbove(max)), "adult")));
    }

    @Test
    @DisplayName("길 위 수직의 발에 붙여 길 끝 노드까지 가지 않는다")
    void snapsToTheFootOnTheRoad() {
        // 200m 길 하나(S–T). 도착은 길 한가운데(출발에서 100m)에서 북쪽 약 11m: 길 끝 노드까지 가지 않는다.
        // 99는 시험 지도 범위를 북쪽으로 넓히려는 길 없는 점
        RoadGraph g = new RoadGraph(Map.of(TestRoutes.S, S_P, TestRoutes.T, T_P, 99L, new double[] {S_P[0], LAT + 0.0005}),
                List.of(edge(1, TestRoutes.S, TestRoutes.T, S_P, T_P, EdgePenalty.NONE)));
        RouteService s = TestRoutes.service(g, TestRoutes.pois(List.of()), 12);

        RouteResult r = s.route(new RouteRequest(new Place(LAT, S_P[0], null), new Place(LAT + 0.0001, M_P[0], null),
                null));
        assertThat(r.routes().get(0).distanceM()).isBetween(105, 115);

        // 출발·도착이 같은 길 중간이면 그 사이만 걷는다 (약 11 + 100 + 11m)
        RouteResult both = s.route(new RouteRequest(new Place(LAT + 0.0001, S_P[0] + 0.0006, null),
                new Place(LAT + 0.0001, S_P[0] + 0.0017, null), null));
        assertThat(both.routes().get(0).distanceM()).isBetween(115, 130);
    }

    @Test
    @DisplayName("계단을 빼는 사람은 처음부터 지날 수 있는 길에만 붙인다")
    void wheelchairNextToTheMiddleOfStairsSnapsToThePassableEndWithoutWarning() {
        // Snapper.snap
        // S–M 보통 길, M–T 계단. 휠체어 도착은 계단 아래쪽(M에서 약 30m) 계단 바로 옆: 계단을 나눠 붙지 않고 M에 붙어 주의가 없다
        RoadGraph g = new RoadGraph(Map.of(TestRoutes.S, S_P, TestRoutes.M, M_P, TestRoutes.T, T_P,
                99L, new double[] {S_P[0], LAT + 0.0005}),
                List.of(edge(1, TestRoutes.S, TestRoutes.M, S_P, M_P, EdgePenalty.NONE),
                        edge(2, TestRoutes.M, TestRoutes.T, M_P, T_P, STAIRS)));
        RouteResult r = TestRoutes.service(g, TestRoutes.pois(List.of()), 12).route(new RouteRequest(
                new Place(LAT, S_P[0], null), new Place(LAT + 0.00005, 126.93140, null), null, "wheelchair"));

        assertThat(r.explanation().notes()).noneMatch(n -> n.kind().equals("snap"));
        assertThat(r.routes().get(0).stairs()).isEmpty();

        // 계단을 못 가는 사람 설정(stairsOk 0)의 어른도 같다 (광장 길을 쓰는 모드라 지상 덩어리를 따로 구하지 않는 경우)
        RouteResult person = TestRoutes.service(new com.saferoute.route.data.InMemoryRoadGraphSource(g),
                TestRoutes.pois(List.of()), TestRoutes.at(12), TestRoutes.props(),
                id -> new com.saferoute.route.data.UserPreferences(0, null, null), null).route(new RouteRequest(
                        new Place(LAT, S_P[0], null), new Place(LAT + 0.00005, 126.93140, null), null, "adult", "guest-1"));
        assertThat(person.explanation().notes()).noneMatch(n -> n.kind().equals("snap"));
        assertThat(person.routes().get(0).stairs()).isEmpty();
    }

    @Test
    @DisplayName("다른 길과 이어지지 않은 조각에는 붙이지 않는다")
    void isolatedPieceIsSkippedWhenSnapping() {
        // S–M–T 도로망 옆에 다른 길과 이어지지 않은 조각(출발점 바로 옆). 조각이 아니라 큰 도로망에 붙인다
        double[] a = {126.92995, LAT + 0.0002};
        double[] b = {126.92990, LAT + 0.0003};
        RoadGraph g = new RoadGraph(Map.of(TestRoutes.S, S_P, TestRoutes.M, M_P, TestRoutes.T, T_P, 8L, a, 9L, b),
                List.of(edge(1, TestRoutes.S, TestRoutes.M, S_P, M_P, EdgePenalty.NONE),
                        edge(2, TestRoutes.M, TestRoutes.T, M_P, T_P, EdgePenalty.NONE), edge(3, 8, 9, a, b, EdgePenalty.NONE)));

        RouteResult r = TestRoutes.service(g, TestRoutes.pois(List.of()), 12).route(
                new RouteRequest(new Place(a[1], a[0], null), new Place(LAT, T_P[0], null), null));

        assertThat(r.routes()).hasSize(1);
        assertThat(r.routes().get(0).distanceM()).isGreaterThan(200);
    }

    // ── 지하 길을 넣은 도로망 (비 오는 날) ──

    /**
     * 비 오는 날 출발 (0, 0) 근처. 보행로 y=45 (-100..300, 0·±60에 노드), 지하 통로 y=20 (-60..60, 0에 노드)는 (±60, 45)에서
     * 엘리베이터로 보행로와 이어진다. near가 "alley"면 남쪽 25m에 인도 없는 골목(양 끝 보행로로 이어짐), "pocket"이면
     * 북쪽 5m에 계단으로만 (0, 45)와 이어진 보행로, 그 밖이면 30m 안에 지상 길이 없다. 도착은 (300, 45). 9는 지도 범위를 넓히는 점
     */
    static RouteResult besideUnderground(String near, String mode) {
        Map<Long, double[]> n = new HashMap<>(Map.of(1L, xy(-100, 45), 2L, xy(-60, 45), 3L, xy(0, 45), 4L, xy(60, 45),
                5L, xy(100, 45), 6L, xy(300, 45), 11L, xy(-60, 20), 12L, xy(0, 20), 13L, xy(60, 20), 9L, xy(0, -50)));
        List<RoadEdge> edges = new ArrayList<>(List.of(edge(1, 1, 2, n.get(1L), n.get(2L), EdgePenalty.NONE),
                edge(2, 2, 3, n.get(2L), n.get(3L), EdgePenalty.NONE), edge(3, 3, 4, n.get(3L), n.get(4L), EdgePenalty.NONE),
                edge(4, 4, 5, n.get(4L), n.get(5L), EdgePenalty.NONE), edge(5, 5, 6, n.get(5L), n.get(6L), EdgePenalty.NONE),
                edge(11, 2, 11, n.get(2L), n.get(11L), TestRoutes.UNDER), edge(12, 11, 12, n.get(11L), n.get(12L), TestRoutes.UNDER),
                edge(13, 12, 13, n.get(12L), n.get(13L), TestRoutes.UNDER), edge(14, 13, 4, n.get(13L), n.get(4L), TestRoutes.UNDER)));
        if (near.equals("alley")) {
            n.put(21L, xy(-100, -25));
            n.put(22L, xy(100, -25));
            edges.add(TestRoutes.road(21, 21, 22, n.get(21L), n.get(22L), "residential", "no"));
            edges.add(edge(22, 21, 1, n.get(21L), n.get(1L), EdgePenalty.NONE));
            edges.add(edge(23, 22, 5, n.get(22L), n.get(5L), EdgePenalty.NONE));
        } else if (near.equals("pocket")) {
            n.put(31L, xy(-10, 5));
            n.put(32L, xy(10, 5));
            edges.add(edge(31, 31, 32, n.get(31L), n.get(32L), EdgePenalty.NONE));
            edges.add(edge(32, 32, 3, n.get(32L), n.get(3L), STAIRS));
        }
        double[] from = xy(0, 0);
        double[] to = n.get(6L);
        return service(new RoadGraph(n, edges)).route(new RouteRequest(new Place(from[1], from[0], null),
                new Place(to[1], to[0], null), "auto", mode, null, "on"));
    }

    @Test
    @DisplayName("지하 길을 넣은 도로망에서도 출발·도착은 지상 길에만 붙인다")
    void startIsNeverSnappedOntoTheUndergroundWhenItIsLoaded() {
        // 처음 붙일 때도, 휠체어가 인도 없는 골목에서 옮길 때도, 계단 구역에서 다시 붙일 때도
        // 더 가까운 지하 통로(20m)가 아니라 지상 보행로(45m)다 (RouteService.attempt, Snapper, 2026-11-09)
        double[] road = xy(0, 45);
        for (String[] c : new String[][] {{"none", "adult"}, {"alley", "wheelchair"}, {"pocket", "wheelchair"}}) {
            double[] at = besideUnderground(c[0], c[1]).routes().get(0).geometry().get(1); // 0번은 요청 출발점
            assertThat(at[1]).as(c[0]).isCloseTo(road[1], offset(1e-6));
            assertThat(at[0]).as(c[0]).isCloseTo(road[0], offset(1e-6));
        }
    }

    // ── 휠체어 다시 붙이기 ──

    /** 큰길 A–B (약 365m) */
    static final double[] A = {126.93, LAT};
    static final double[] B = {126.934, LAT};

    /**
     * 큰길 A–B와, B 북쪽에 계단으로만 드나드는 구역(아파트 단지 안 보행로 같은 곳).
     * B –계단 5m– P –보행로– D. D는 B 북쪽 dNorthM. 9는 시험 지도 범위를 북쪽으로 넓히려는 길 없는 점
     */
    static RoadGraph pocket(double dNorthM) {
        double[] p = north(B, 5);
        double[] d = north(B, dNorthM);
        return new RoadGraph(Map.of(1L, A, 2L, B, 3L, p, 4L, d, 9L, north(A, 500)), List.of(
                edge(1, 1, 2, A, B, EdgePenalty.NONE),
                edge(2, 2, 3, B, p, STAIRS),
                edge(3, 3, 4, p, d, EdgePenalty.NONE)));
    }

    static RouteService service(RoadGraph g) {
        return TestRoutes.service(g, TestRoutes.pois(List.of()), 12);
    }

    /** A에서 계단 구역 안 D로 (건물 안 도착). reroute: 길안내 중 다시 찾기 */
    static RouteRequest toward(double dNorthM, String mode, Boolean reroute) {
        return toward(dNorthM, 0, mode, reroute);
    }

    /** A에서 D보다 pastM 더 북쪽(길 밖)으로 */
    static RouteRequest toward(double dNorthM, double pastM, String mode, Boolean reroute) {
        double[] d = north(B, dNorthM + pastM);
        return new RouteRequest(new Place(A[1], A[0], null), new Place(d[1], d[0], null), "auto", mode, null,
                null, null, null, reroute);
    }

    /** 계단 구역 안 D에서 A로 (건물 안 출발) */
    static RouteRequest from(double dNorthM, Boolean reroute) {
        return from(dNorthM, 0, reroute);
    }

    /** D보다 pastM 더 북쪽(길 밖)에서 A로 */
    static RouteRequest from(double dNorthM, double pastM, Boolean reroute) {
        double[] d = north(B, dNorthM + pastM);
        return new RouteRequest(new Place(d[1], d[0], null), new Place(A[1], A[0], null), "auto", "wheelchair", null,
                null, null, null, reroute);
    }

    @Test
    @DisplayName("휠체어 도착이 계단 뒤면 이어진 길 끝까지 안내하고 남은 거리를 알린다")
    void wheelchairStopsAtNearestReachableRoadWhenDestinationIsBehindStairs() {
        // 다른 모드는 계단을 지난다 (2026-11-04)
        RouteService s = service(pocket(20));

        RouteResult w = s.route(toward(20, "wheelchair", null));
        RouteResult adult = s.route(toward(20, "adult", null));

        // 휠체어는 계단 아래 B까지 안내하고 남은 거리를 알린다. 어른은 계단을 지나 D까지
        assertThat(w.explanation().text()).endsWith("(주의: 도착점 근처 약 20m는 휠체어로 이어진 길이 확인되지 않았습니다)");
        assertThat(adult.explanation().text()).doesNotContain("휠체어로 이어진 길");
    }

    @Test
    @DisplayName("휠체어 도착점은 100m까지 더 먼 지날 수 있는 길에 붙이고 알린다")
    void wheelchairDestinationSnapsUpTo100mFurther() {
        // 건물 안 도착 (운천역 → 유스퀘어, 2026-11-13)
        RouteResult r = service(pocket(80)).route(toward(80, "wheelchair", null));

        assertThat(r.explanation().text()).endsWith("(주의: 도착점 근처 약 80m는 휠체어로 이어진 길이 확인되지 않았습니다)");
    }

    @Test
    @DisplayName("휠체어 출발점은 100m까지 더 먼 지날 수 있는 길에 붙이고 알린다")
    void wheelchairOriginSnapsUpTo100mFurther() {
        // 건물 안 출발 (유스퀘어, 2026-11-13)
        RouteResult r = service(pocket(80)).route(from(80, null));

        assertThat(r.explanation().text()).endsWith("(주의: 출발점 근처 약 80m는 휠체어로 이어진 길이 확인되지 않았습니다)");
    }

    @Test
    @DisplayName("휠체어 출발·도착점은 지날 수 있는 길이 100m 이하로 더 멀 때만 붙인다")
    void wheelchairEndResnapLimitIs100m() {
        // passable-snap-origin-extra-m (route.yml, Snapper.place)
        // 좌표 거리라 경계값 자체는 못 만들어 경계 ±0.01m로 본다. 요청 점은 D 북쪽 10m라 가장 가까운 길은 D(10m),
        // 지날 수 있는 길은 B(D까지 + 10m). 한도는 가장 가까운 길보다 더 먼 거리로 잰다
        double in = justBelow(TestRoutes.props().passableSnapOriginExtraM());
        double out = justAbove(TestRoutes.props().passableSnapOriginExtraM());
        assertThatCode(() -> service(pocket(in)).route(toward(in, 10, "wheelchair", null))).doesNotThrowAnyException();
        assertThatCode(() -> service(pocket(in)).route(from(in, 10, null))).doesNotThrowAnyException();
        assertNoRoute(() -> service(pocket(out)).route(toward(out, 10, "wheelchair", null)));
        assertNoRoute(() -> service(pocket(out)).route(from(out, 10, null)));
    }

    @Test
    @DisplayName("길안내 중 다시 찾기는 50m 이하로만 다시 붙인다")
    void wheelchairRerouteResnapLimitIs50m() {
        // reroute, passable-snap-extra-m (route.yml, Snapper.place)
        // 좌표 거리라 경계값 자체는 못 만들어 경계 ±0.01m로 본다. 요청 점은 D 북쪽 10m (위와 같음)
        double in = justBelow(TestRoutes.props().passableSnapExtraM());
        double out = justAbove(TestRoutes.props().passableSnapExtraM());
        assertThatCode(() -> service(pocket(in)).route(toward(in, 10, "wheelchair", true))).doesNotThrowAnyException();
        assertThatCode(() -> service(pocket(in)).route(from(in, 10, true))).doesNotThrowAnyException();
        assertNoRoute(() -> service(pocket(out)).route(toward(out, 10, "wheelchair", true)));
        assertNoRoute(() -> service(pocket(out)).route(from(out, 10, true)));
    }

    @Test
    @DisplayName("다시 붙여 나눈 길에도 밝기 계산이 남아 어두운 구간이 나온다")
    void resnappedRoadKeepsLightCoverage() {
        // 큰길 W–A–B–E와, A–B 가운데 북쪽 30~60m의 막다른 보행로 P1–P2(B에서 계단으로만 이어짐).
        // 그 보행로 위 점에 휠체어는 A–B 가운데로 다시 붙어 A–B를 나눈다. 나눈 길에도 밝기 계산이 있어야 밤 어두운 구간이 나온다
        double[] mid = {(A[0] + B[0]) / 2, LAT};
        double[] p1 = north(mid, 30);
        double[] p2 = north(mid, 60);
        double[] w = {A[0] - 0.002, LAT};
        double[] e = {B[0] + 0.002, LAT};
        RoadGraph g = new RoadGraph(Map.of(1L, A, 2L, B, 3L, p1, 4L, p2, 5L, e, 6L, w), List.of(
                edge(1, 1, 2, A, B, EdgePenalty.NONE), edge(4, 2, 5, B, e, EdgePenalty.NONE),
                edge(5, 6, 1, w, A, EdgePenalty.NONE), edge(2, 2, 3, B, p1, STAIRS),
                edge(3, 3, 4, p1, p2, EdgePenalty.NONE)));
        RouteService s = service(g);
        double[] d = north(mid, 45);

        for (String mode : List.of("adult", "wheelchair")) {
            RouteResult r = s.route(new RouteRequest(new Place(A[1], A[0], null), new Place(d[1], d[0], null), "on", mode));
            assertThat(safe(r).dark()).as(mode).isNotEmpty();
        }
    }

    // ── 붙이는 곧은 줄이 피할 신고를 지나면 (2026-11-16). 출발점은 늘 (0, 0), 좌표는 그곳에서 동쪽·북쪽 m ──

    /** 노드 번호 → 자리, 길은 두 노드 쌍 (번호 100부터). last가 있으면 마지막 길에만 */
    static RoadGraph graph(Map<Long, double[]> nodes, long[][] pairs, EdgePenalty last) {
        List<RoadEdge> edges = new ArrayList<>();
        for (int i = 0; i < pairs.length; i++) {
            long a = pairs[i][0];
            long b = pairs[i][1];
            edges.add(edge(100 + i, a, b, nodes.get(a), nodes.get(b),
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
     * 남쪽 길 28m, 북쪽 길 28.5m. 신고 (-16, -14)는 출발점·남쪽 발 (0, -28)에서 모두 21m(반경 밖)인데
     * 그 사이 곧은 줄에서는 16m라, 줄이 반경을 지나 너머로 간다
     */
    static RouteResult twoRoads(String type) {
        RoadGraph g = graph(Map.of(1L, xy(-50, -28), 2L, xy(250, -28), 3L, xy(-50, 28.5), 4L, xy(250, 28.5)),
                new long[][] {{1, 2}, {3, 4}, {1, 3}, {2, 4}}, null);
        return route(g, xy(200, 28.5), "adult", report(type, xy(-16, -14)));
    }

    @Test
    @DisplayName("붙이는 곧은 줄이 피할 신고를 지나면 다른 길에 붙이고 알림은 없다")
    void connectorThroughAvoidedReportAttachesToTheOtherRoad() {
        RouteResult r = twoRoads("construction");
        // 0번은 요청 출발점, 1번이 붙인 곳
        assertThat(r.routes().get(0).geometry().get(1)[1]).isGreaterThan(LAT);
        assertThat(r.explanation().notes()).noneMatch(n -> n.kind().equals("snap_through_report"));
    }

    @Test
    @DisplayName("비용만 더하는 신고는 붙일 곳을 바꾸지 않는다")
    void costedOnlyReportKeepsTheNearestRoad() {
        // 어른에게 턱은 비용만이라 붙일 곳을 바꾸지 않는다
        RouteResult r = twoRoads("curb");
        assertThat(r.routes().get(0).geometry().get(1)[1]).isLessThan(LAT);
        assertThat(r.explanation().notes()).noneMatch(n -> n.kind().equals("snap_through_report"));
    }

    @Test
    @DisplayName("다른 붙일 곳이 없으면 그대로 붙이고 곧은 줄의 신고를 알린다")
    void noOtherCandidateKeepsTheSnapAndWarns() {
        // 길 A(-10,-45)–B(10,-45)–C(10,-200)뿐, 신고는 (0, -22): 어느 노드·발로 가도 반경을 지나 너머로 간다.
        // 9번은 출발점이 범위 안이게 둔 외딴 노드
        RoadGraph g = graph(Map.of(1L, xy(-10, -45), 2L, xy(10, -45), 3L, xy(10, -200), 9L, xy(0, 10)),
                new long[][] {{1, 2}, {2, 3}}, null);
        RouteResult r = route(g, xy(10, -200), "adult", report("construction", xy(0, -22)));

        double[] at = r.routes().get(0).geometry().get(1);
        assertThat(at[1]).isCloseTo(xy(0, -45)[1], offset(1e-6));
        assertThat(at[0]).isCloseTo(xy(0, -45)[0], offset(1e-6));
        assertThat(r.explanation().notes())
                .contains(RouteResult.Note.warn("snap_through_report", "출발점 근처 곧은 연결 구간에 공사 신고가 있어요"));

        // 거꾸로 C에서 (0, 0)으로 가면 도착점 곧은 줄을 알린다
        double[] c = xy(10, -200);
        double[] o = xy(0, 0);
        RouteResult back = TestRoutes.service(g, TestRoutes.pois(List.of(), List.of(),
                List.of(report("construction", xy(0, -22)))), 12).route(new RouteRequest(new Place(c[1], c[0], "출발"),
                        new Place(o[1], o[0], "도착"), "auto", "adult"));
        assertThat(back.explanation().notes())
                .contains(RouteResult.Note.warn("snap_through_report", "도착점 근처 곧은 연결 구간에 공사 신고가 있어요"))
                .noneMatch(n -> n.text().startsWith("출발점"));
    }

    @Test
    @DisplayName("출발점이 신고 반경 안이면 피하지 않고 곧은 줄 알림 없이 알리기만 한다")
    void startInsideTheReportCircleIsNotAvoidedOrWarned() {
        // 그곳에서 떠나는 길이라서 (RouteService.compute)
        // 신고 (0, 15)는 출발점에서 15m(반경 안), 붙인 곳 (0, -28)에서는 43m
        // 9번은 출발점이 범위 안이게 둔 외딴 노드
        RoadGraph g = graph(Map.of(1L, xy(-50, -28), 2L, xy(250, -28), 9L, xy(0, 40)), new long[][] {{1, 2}}, null);
        RouteResult r = route(g, xy(200, -28), "adult", report("construction", xy(0, 15)));

        assertThat(r.explanation().notes()).noneMatch(n -> n.kind().equals("snap_through_report"));
        assertThat(r.routes().get(0).events()).singleElement().satisfies(e -> {
            assertThat(e.avoid()).isFalse();
            assertThat(e.near()).isEqualTo("start");
        });
    }

    @Test
    @DisplayName("휠체어는 광장 길로만 이어진 보행로에 붙지 않고 이어진 길에 붙는다")
    void wheelchairDoesNotSnapToAPlaceReachedOnlyAcrossAPlaza() {
        // 광장 길을 안 쓰므로 (RouteService.attempt, 2026-11-10)
        // 큰 길 (0,0)–(100,0)–(200,0). 출발 (20, 15)에서 5m 북쪽 보행로 (0,20)–(40,20)은 광장 길로만 (100, 0)과 이어진다
        Map<Long, double[]> nodes = Map.of(1L, xy(0, 0), 2L, xy(100, 0), 3L, xy(200, 0), 4L, xy(0, 20), 5L, xy(40, 20));
        List<RoadEdge> edges = List.of(edge(1, 1, 2, nodes.get(1L), nodes.get(2L), EdgePenalty.NONE),
                edge(2, 2, 3, nodes.get(2L), nodes.get(3L), EdgePenalty.NONE),
                edge(3, 4, 5, nodes.get(4L), nodes.get(5L), EdgePenalty.NONE),
                edge(4, 5, 2, nodes.get(5L), nodes.get(2L), new EdgePenalty(Map.of(Penalty.PLAZA, 1.0))));
        double[] from = xy(20, 15);
        RouteResult r = service(new RoadGraph(nodes, edges)).route(request(from, xy(200, 0), "wheelchair"));

        assertThat(r.explanation().notes()).noneMatch(n -> n.kind().equals("snap"));
        assertThat(r.routes().get(0).geometry().get(1)[1]).isCloseTo(xy(20, 0)[1], offset(1e-6));
    }

    /**
     * 휠체어 출발점이 계단으로만 나가는 길(섬) 위라 지날 수 있는 덩어리에 다시 붙인다 (문화전당역 5번 → 4번 출구, 2026-11-16).
     * 가장 가까운 곳은 북쪽 길 (0, 60)인데 곧은 줄이 북쪽 30m 공사 신고를 지난다. 서쪽 길 (westX, 0)은 지나지 않는다.
     * 섬 위 처음 붙인 곳은 (0, -3)
     * <pre>
     *   W(westX,60) ——— K(10,60) ——— E(100,60)
     *     |                | 계단
     *     |     신고(0,30)  |
     *     |     O  I1———I2(10,-3)
     *   SW(westX,-30)
     * </pre>
     */
    static RouteResult resnapPastReport(double westX) {
        RoadGraph g = graph(Map.of(1L, xy(westX, 60), 2L, xy(10, 60), 3L, xy(100, 60), 4L, xy(westX, -30),
                5L, xy(-10, -3), 6L, xy(10, -3)), new long[][] {{1, 2}, {2, 3}, {1, 4}, {5, 6}, {6, 2}}, STAIRS);
        return route(g, xy(100, 60), "wheelchair", report("construction", xy(0, 30)));
    }

    @Test
    @DisplayName("휠체어를 다시 붙일 때도 곧은 줄이 신고를 안 지나는 길에 먼저 붙인다")
    void wheelchairResnapSkipsTheConnectorThroughTheReport() {
        // 처음 붙인 곳보다 passable-snap-origin-extra-m 넘게 더 멀면 신고를 지나는 가까운 길에 붙이고 알린다
        // (route.yml, Snapper.place, 2026-11-16)
        double extra = TestRoutes.props().passableSnapOriginExtraM();
        // 서쪽 길 발 extra m, 처음 붙인 곳 3m: 3m를 뺀 거리로 재서 한도 안
        RouteResult in = resnapPastReport(-extra);
        assertThat(in.routes().get(0).geometry().get(1)[0]).isCloseTo(xy(-extra, 0)[0], offset(1e-6));
        assertThat(in.explanation().notes()).anyMatch(n -> n.kind().equals("snap"))
                .noneMatch(n -> n.kind().equals("snap_through_report"));

        RouteResult out = resnapPastReport(-(extra + 20));
        assertThat(out.routes().get(0).geometry().get(1)[0]).isCloseTo(xy(0, 60)[0], offset(1e-6));
        assertThat(out.explanation().notes())
                .contains(RouteResult.Note.warn("snap_through_report", "출발점 근처 곧은 연결 구간에 공사 신고가 있어요"));
    }

    // ── 휠체어가 인도 없는 골목에 붙었을 때 신고 (신고 (0, -22)는 출발점에서 남쪽으로 약 65° 안의 곧은 줄을 모두 막는다) ──

    /**
     * 출발 (0, 0) 남쪽 alleyM에 인도 없는 골목 AL–AR, 그 15m 남쪽에 보행로 RL–RR, 동쪽 clearM에 보행로 노드 CN.
     * 골목·남쪽 보행로로 가는 곧은 줄은 신고를 지나고, CN으로 가는 줄만 지나지 않는다. 9는 지도 범위를 넓히는 점
     * <pre>
     *   O                      CN(clearM, 0)
     *     신고(0,-22)            |
     *   AL(-20,-alleyM) —— AR    |
     *                      |     |
     *   RL ——————————————— RR —— CE(clearM, -(alleyM+15))  도착
     * </pre>
     */
    static RouteResult alleyBehindReport(double alleyM, double clearM) {
        double south = -(alleyM + 15);
        RoadGraph g = new RoadGraph(Map.of(1L, xy(-20, -alleyM), 2L, xy(20, -alleyM), 3L, xy(-20, south), 4L, xy(20, south),
                5L, xy(clearM, south), 6L, xy(clearM, 0), 9L, xy(0, 50)), List.of(
                TestRoutes.road(1, 1, 2, xy(-20, -alleyM), xy(20, -alleyM), "residential", "no"),
                edge(2, 3, 4, xy(-20, south), xy(20, south), EdgePenalty.NONE),
                edge(3, 2, 4, xy(20, -alleyM), xy(20, south), EdgePenalty.NONE),
                edge(4, 4, 5, xy(20, south), xy(clearM, south), EdgePenalty.NONE),
                edge(5, 5, 6, xy(clearM, south), xy(clearM, 0), EdgePenalty.NONE)));
        return route(g, xy(clearM, south), "wheelchair", report("construction", xy(0, -22)));
    }

    @Test
    @DisplayName("신고를 지나는 골목에 붙은 휠체어는 신고 없는 길이 가까우면 그 길에 붙는다")
    void wheelchairOnABlockedAlleyMovesToAClearRoadWithinTheExtraLimit() {
        // 골목보다 passable-snap-extra-m 이하로 멀 때 (Snapper.snap, 2026-11-16)
        // 그 길은 snap-max-m 밖에 둬 처음 붙일 때는 쓰이지 않고 골목에 붙게 한다
        double max = TestRoutes.props().snapMaxM();
        double extra = TestRoutes.props().passableSnapExtraM();
        double alley = max - 20;
        RouteResult in = alleyBehindReport(alley, alley + extra - 10);
        double[] at = in.routes().get(0).geometry().get(1);
        assertThat(at[0]).isCloseTo(xy(alley + extra - 10, 0)[0], offset(1e-6));
        assertThat(at[1]).isCloseTo(LAT, offset(1e-6));
        assertThat(in.explanation().notes()).noneMatch(n -> n.kind().equals("snap_through_report"));
    }

    @Test
    @DisplayName("신고 없는 길이 너무 멀면 휠체어는 신고를 지나는 가까운 길에 붙이고 알린다")
    void wheelchairOnABlockedAlleyTakesTheBlockedRoadWhenTheClearOneIsTooFar() {
        // 골목보다 passable-snap-extra-m 넘게 멀 때 (Snapper.snap)
        double max = TestRoutes.props().snapMaxM();
        double extra = TestRoutes.props().passableSnapExtraM();
        double alley = max - 20;
        RouteResult out = alleyBehindReport(alley, alley + extra + 10);
        // 골목을 나가는 끝 AR (20, -alley): 골목 안 발 (0, -alley)이 아니다
        double[] at = out.routes().get(0).geometry().get(1);
        assertThat(at[0]).isCloseTo(xy(20, -alley)[0], offset(1e-6));
        assertThat(at[1]).isCloseTo(xy(20, -alley)[1], offset(1e-6));
        assertThat(out.explanation().notes())
                .contains(RouteResult.Note.warn("snap_through_report", "출발점 근처 곧은 연결 구간에 공사 신고가 있어요"));
    }

    @Test
    @DisplayName("골목 점까지 곧은 줄이 신고를 안 지나면 옮기지 않고 골목을 따라 나간다")
    void wheelchairOnAClearAlleyDoesNotMoveToABlockedRoad() {
        // Snapper.snap
        // 동쪽 25m 남북 골목 (25, -150)–(25, 150), 신고 너머 남쪽 45m 보행로 (-10..10, -45). 도착 (100, 150)
        Map<Long, double[]> n = Map.of(1L, xy(25, -150), 2L, xy(25, 150), 3L, xy(-10, -45), 4L, xy(10, -45),
                5L, xy(100, 150));
        RoadGraph g = new RoadGraph(n, List.of(TestRoutes.road(1, 1, 2, n.get(1L), n.get(2L), "residential", "no"),
                edge(2, 3, 4, n.get(3L), n.get(4L), EdgePenalty.NONE), edge(3, 4, 1, n.get(4L), n.get(1L), EdgePenalty.NONE),
                edge(4, 2, 5, n.get(2L), n.get(5L), EdgePenalty.NONE)));
        RouteResult r = route(g, n.get(5L), "wheelchair", report("construction", xy(0, -22)));

        double[] at = r.routes().get(0).geometry().get(1);
        assertThat(at[0]).isCloseTo(xy(25, 0)[0], offset(1e-6));
        assertThat(at[1]).isCloseTo(LAT, offset(1e-6));
        assertThat(r.routes().get(0).alleyM()).isPositive();
        assertThat(r.explanation().notes()).noneMatch(n2 -> n2.kind().equals("snap_through_report"));
    }
}
