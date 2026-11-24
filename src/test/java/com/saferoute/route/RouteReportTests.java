package com.saferoute.route;

import static com.saferoute.route.TestRoutes.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.event.RoadEvent;
import com.saferoute.route.engine.event.RoadEvents;
import com.saferoute.route.engine.event.RoadReport;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 신고된 곳 피하기 (진행표 w11, 2026-11-10). 신고는 DB에 넣지 않고 가짜 PoiSource로 준다.
 * 다이아몬드 도로망의 지름길 S–M 위 S에서 60m 자리에 신고를 둔다. 북쪽 길은 그 자리에서 약 24m(20m 밖)다.
 */
class RouteReportTests {

    /** S–M 위, S에서 약 60m */
    static final double[] SPOT = {126.93066, LAT};

    static final List<String> ALL = List.of("adult", "child", "senior", "wheelchair");

    /** 뷰가 종류로 정하는 모드 목록과 같게 만든다 (migration 032) */
    static RoadReport report(String type, double[] at) {
        List<String> avoid = switch (type) {
            case "obstacle", "accident", "construction" -> ALL;
            case "curb", "steep", "broken" -> List.of("senior", "wheelchair");
            default -> List.of();
        };
        List<String> cost = switch (type) {
            case "curb", "steep", "broken" -> List.of("adult", "child");
            case "obstacle", "accident", "construction" -> List.of();
            default -> ALL;
        };
        return new RoadReport(3, type, at[0], at[1], LocalDate.of(2026, 11, 16), avoid, cost);
    }

    static RouteResult route(RoadGraph graph, String mode, List<RoadEvent> events, RoadReport... reports) {
        RouteService service = TestRoutes.service(graph, TestRoutes.pois(List.of(), events, List.of(reports)), 12);
        return service.route(new RouteRequest(new Place(LAT, S_P[0], "출발"), new Place(LAT, T_P[0], "도착"), "auto",
                mode));
    }

    static RouteResult route(String mode, RoadReport... reports) {
        return route(diamond(0.0004), mode, List.of(), reports);
    }

    @Test
    void roadWorkReportIsAvoidedWithinTheDetourLimit() {
        RouteResult r = route("adult", report("construction", SPOT));

        assertThat(r.routes()).extracting(RouteResult.Route::kind).containsExactly("shortest", "safe");
        assertThat(r.routes().get(1).events()).isEmpty();
        assertThat(r.routes().get(0).events()).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("신고");
            assertThat(e.title()).isEqualTo("공사");
            assertThat(e.message()).isNull();
            assertThat(e.avoid()).isTrue();
            assertThat(e.endsAt()).isEqualTo("2026-11-16 23:59");
            // 60m 지점 앞뒤 20m (10m 간격 표본이라 조금 안쪽)
            assertThat(e.fromM()).isBetween(35, 50);
            assertThat(e.toM()).isBetween(70, 85);
        });
        assertThat(r.explanation().text())
                .isEqualTo("%dm 더 돌지만 공사 신고 둘레를 피해 갑니다".formatted(r.explanation().extraM()));
    }

    @Test
    void walkingAroundOnARoadWithinTwentyMetersIsNotPassingTheReport() {
        // S에서 30m: 북쪽 길이 약 12m 떨어져 있다(길 건너 보도처럼). 신고된 길은 지름길 하나라 북쪽으로 돈 경로는 지나지 않은 것
        RouteResult r = route("adult", report("construction", new double[] {126.93033, LAT}));

        assertThat(r.routes()).hasSize(2);
        assertThat(r.routes().get(1).events()).isEmpty();
        assertThat(r.explanation().notes()).isEmpty();
        assertThat(r.explanation().text()).endsWith("공사 신고 둘레를 피해 갑니다");
    }

    @Test
    void tooFarToAvoidSoItWarnsAboutTheReport() {
        // 북쪽 길이 약 1.9배라 어른 한도(1.3배)를 넘는다
        RouteResult r = route(diamond(0.0015), "adult", List.of(), report("construction", SPOT));

        assertThat(r.routes()).singleElement().satisfies(safe -> assertThat(safe.events())
                .singleElement().satisfies(e -> assertThat(e.avoid()).isTrue()));
        assertThat(r.explanation().notes()).containsExactly(RouteResult.Note.warn("event", "앞에 공사 신고가 있는 곳을 지나요"));
    }

    @Test
    void curbIsAvoidedBySeniorAndWheelchairAndOnlyCostsForAdult() {
        for (String mode : List.of("senior", "wheelchair")) {
            RouteResult r = route(mode, report("curb", SPOT));
            assertThat(r.routes().get(0).events()).singleElement().satisfies(e -> assertThat(e.avoid()).isTrue());
            assertThat(r.routes().get(1).events()).as(mode).isEmpty();
            assertThat(r.explanation().text()).as(mode).endsWith("턱 신고 둘레를 피해 갑니다");
        }
        // 어른: 피하는 길이 18m(1분 = 80m 안)라 비용만으로도 돈다. 응답 avoid는 false지만 설명은 돈 이유를 말한다
        RouteResult small = route("adult", report("curb", SPOT));
        assertThat(small.routes()).hasSize(2);
        assertThat(small.routes().get(0).events()).singleElement().satisfies(e -> assertThat(e.avoid()).isFalse());
        assertThat(small.routes().get(1).events()).isEmpty();
        assertThat(small.explanation().text())
                .isEqualTo("%dm 더 돌지만 턱 신고 둘레를 피해 갑니다".formatted(small.explanation().extraM()));
        // 피하는 길이 한도를 넘으면 그대로 지나며 알리기만 한다
        RouteResult big = route(diamond(0.0015), "adult", List.of(), report("curb", SPOT));
        assertThat(big.routes()).hasSize(1);
        assertThat(big.explanation().notes()).containsExactly(RouteResult.Note.info("event", "앞에 턱 신고가 있는 곳을 지나요"));
    }

    @Test
    void otherReportOnlyCostsForEveryMode() {
        for (String mode : List.of("adult", "wheelchair")) {
            RouteResult r = route(mode, report("other", SPOT));
            assertThat(r.routes()).as(mode).hasSize(2);
            assertThat(r.routes().get(0).events()).singleElement().satisfies(e -> assertThat(e.avoid()).isFalse());
            // 휠체어 낮이 "비포장을 피하는 길로 갑니다"(비포장 아님)가 되지 않게
            assertThat(r.explanation().text()).as(mode).endsWith("기타 신고 둘레를 피해 갑니다");
        }
        // 기준이 있는 모드(어린이 낮)는 더 걷는 길이에도 기준 부담이 붙어, 1분 비용이 18m 우회보다 작다. 그대로 지나며 알린다
        RouteResult child = route("child", report("other", SPOT));
        assertThat(child.routes()).hasSize(1);
        assertThat(child.explanation().notes()).containsExactly(RouteResult.Note.info("event", "앞에 기타 신고가 있는 곳을 지나요"));
    }

    @Test
    void reportAtTheStartIsNotAvoided() {
        // 출발점에서 10m: 그 신고 바로 앞에서 출발하는 길이라 피하지 않고 알리기만 한다
        RouteResult r = route("adult", report("construction", new double[] {126.93011, LAT}));

        assertThat(r.routes()).singleElement().satisfies(safe -> assertThat(safe.events())
                .singleElement().satisfies(e -> {
                    assertThat(e.avoid()).isFalse();
                    assertThat(e.near()).isEqualTo("start");
                }));
        assertThat(r.explanation().notes()).containsExactly(RouteResult.Note.info("event", "출발지 근처에 공사 신고가 있어요"));
    }

    @Test
    void noReportKeepsTheSameRoute() {
        for (String mode : ALL) {
            RouteResult none = route(mode);
            // 범위 밖 신고(127.0, 35.2)는 가짜의 범위 필터가 먼저 거르므로, 운영 코드가 실제로 거르는지는
            // 읽는 범위(약 2km) 안이지만 길에서 약 170m 떨어진 남쪽 신고로 본다
            for (double[] at : List.of(new double[] {127.0, 35.2}, new double[] {126.93110, LAT - 0.0015})) {
                RouteResult far = route(mode, report("construction", at));
                assertThat(far.explanation()).as(mode).isEqualTo(none.explanation());
                assertThat(far.routes()).extracting(RouteResult.Route::distanceM)
                        .isEqualTo(none.routes().stream().map(RouteResult.Route::distanceM).toList());
                assertThat(far.routes()).as(mode).allSatisfy(x -> assertThat(x.events()).isEmpty());
            }
        }
    }

    /**
     * S —— P —— Q —— T 일직선 100m씩. S–P를 피하는 길(V)은 아주 멀고, P–Q를 피하는 길(U)은 가깝다.
     * <pre>
     *        V (북쪽 200m)
     *       / \
     *   S —— P —— Q —— T
     *          \ /
     *           U (남쪽 30m)
     * </pre>
     */
    static final long P = 21, Q = 22, U = 23, V = 24;

    static RoadGraph line() {
        double m = 0.0011 / 100;
        double lat = 1 / 111_195.0;
        Map<Long, double[]> nodes = new LinkedHashMap<>();
        nodes.put(S, S_P);
        nodes.put(P, new double[] {S_P[0] + 100 * m, LAT});
        nodes.put(Q, new double[] {S_P[0] + 200 * m, LAT});
        nodes.put(T, new double[] {S_P[0] + 300 * m, LAT});
        nodes.put(U, new double[] {S_P[0] + 150 * m, LAT - 30 * lat});
        nodes.put(V, new double[] {S_P[0] + 50 * m, LAT + 200 * lat});
        List<RoadEdge> edges = new ArrayList<>();
        long[][] pairs = {{S, P}, {P, Q}, {Q, T}, {P, U}, {U, Q}, {S, V}, {V, P}};
        long id = 30;
        for (long[] p : pairs) {
            edges.add(edge(id++, p[0], p[1], nodes.get(p[0]), nodes.get(p[1]), EdgePenalty.NONE));
        }
        return new RoadGraph(nodes, edges);
    }

    @Test
    void reportIsAvoidedEvenWhenTheRoadWorkEventCannotBe() {
        // 행사 장치(돌발 공사)는 S–P에, 신고는 P–Q에. 둘을 한 묶음으로 피하면 S–P 때문에 둘 다 못 피했다 (차례로 피하기)
        RoadGraph g = line();
        double[] sp = {S_P[0] + 50 * 0.0011 / 100, LAT};
        double[] pq = {S_P[0] + 150 * 0.0011 / 100, LAT};
        RoadEvent work = new RoadEvent(1, "공사", "금남로 보도 공사", null, null, sp[0], sp[1], 10, null);
        RouteService service = TestRoutes.service(g, TestRoutes.pois(List.of(), List.of(work),
                List.of(report("construction", pq))), 12);
        RouteResult r = service.route(new RouteRequest(new Place(LAT, S_P[0], "출발"),
                new Place(LAT, g.point(T)[0], "도착"), "auto", "adult"));

        RouteResult.Route safe = r.routes().get(r.routes().size() - 1);
        assertThat(safe.events()).extracting(RouteResult.Event::kind).containsExactly("공사");
        assertThat(r.explanation().text()).contains("공사 신고 둘레를 피해 갑니다",
                "금남로 보도 공사 구간을 지납니다. 피해 가는 길은 너무 멉니다");
    }

    @Test
    void reportIsAvoidedOnTheLegToAStop() {
        RoadGraph g = line();
        double[] q = g.point(Q);
        RouteService service = TestRoutes.service(g, TestRoutes.pois(List.of(), List.of(),
                List.of(report("construction", new double[] {S_P[0] + 150 * 0.0011 / 100, LAT}))), 12);
        RouteResult r = service.route(new RouteRequest(new Place(LAT, S_P[0], "출발"),
                new Place(LAT, g.point(T)[0], "도착"), "auto", "adult", null, null,
                List.of(new Place(q[1], q[0], "가게")), null));

        RouteResult.Route safe = r.routes().get(r.routes().size() - 1);
        assertThat(safe.events()).isEmpty();
        assertThat(safe.stops()).singleElement().satisfies(s -> assertThat(s.atM()).isGreaterThan(200));
        assertThat(r.explanation().text()).endsWith("공사 신고 둘레를 피해 갑니다");
    }

    @Test
    void reportZoneIsOnlyTheNearestRoadWithinTwentyMeters() {
        assertThat(RoadEvents.zoneNearest(diamond(0.0004), report("curb", SPOT).event())).isEqualTo(10L);
        // 가장 가까운 길이 20m 넘게 떨어지면 신고된 길이 없다
        assertThat(RoadEvents.zoneNearest(diamond(0.0004), report("curb", new double[] {SPOT[0], LAT - 0.0003}).event()))
                .isNull();
    }

    @Test
    void viewRowsBecomeEvents() {
        RoadEvent e = report("broken", SPOT).event();
        assertThat(e.id()).isEqualTo(-3);
        assertThat(e.kind()).isEqualTo("신고");
        assertThat(e.title()).isEqualTo("보도 파손");
        assertThat(e.radiusM()).isEqualTo(20);
        assertThat(RouteProperties.modeName(" Senior ")).isEqualTo("senior");
        assertThat(RouteProperties.modeName(null)).isEqualTo("adult");
    }

    /**
     * 우회 한도 걸림 회귀 (2026-11-10). S—T 지름길 150m 가운데에 턱 신고(어른은 비용만), 북쪽 N으로 도는 길 200m(1.33배, 어른 한도 1.3배 밖).
     * 신고 비용 80m가 50m 더 걷는 것보다 커서 비용을 넣으면 한도에 걸리지만, 기준(지붕·밝기)으로는 지름길이 이미 가장 좋은 길이다
     */
    static RoadGraph triangle() {
        double m = 0.0011 / 100;
        double lat = 1 / 111_195.0;
        Map<Long, double[]> nodes = new LinkedHashMap<>();
        nodes.put(S, S_P);
        nodes.put(T, new double[] {S_P[0] + 150 * m, LAT});
        nodes.put(N, new double[] {S_P[0] + 75 * m, LAT + 66.14 * lat});
        List<RoadEdge> edges = List.of(edge(40, S, T, nodes.get(S), nodes.get(T), EdgePenalty.NONE),
                edge(41, S, N, nodes.get(S), nodes.get(N), EdgePenalty.NONE),
                edge(42, N, T, nodes.get(N), nodes.get(T), EdgePenalty.NONE));
        return new RoadGraph(nodes, edges);
    }

    static RouteResult triangleRoute(List<com.saferoute.route.engine.facility.Poi> pois, int hour, String rain) {
        RoadGraph g = triangle();
        double[] t = g.point(T);
        RoadReport curb = report("curb", new double[] {S_P[0] + 75 * 0.0011 / 100, LAT});
        RouteService service = TestRoutes.service(g, TestRoutes.pois(pois, List.of(), List.of(curb)), hour);
        return service.route(new RouteRequest(new Place(LAT, S_P[0], "출발"), new Place(t[1], t[0], "도착"), "auto",
                "adult", null, rain));
    }

    @Test
    void costOnlyReportDoesNotBlameTheRoofOnARainyDay() {
        // 고치기 전: "지붕 있는 길은 너무 많이 돌아서 가장 짧은 길을 안내합니다" (지붕 길은 없다)
        RouteResult r = triangleRoute(List.of(), 12, "on");

        assertThat(r.routes()).singleElement();
        assertThat(r.explanation().text())
                .isEqualTo("지붕 있는 길이 따로 없어 가장 짧은 길을 안내합니다 (알림: 앞에 턱 신고가 있는 곳을 지나요)");
    }

    @Test
    void costOnlyReportDoesNotBlameTheNightCriteria() {
        // 모든 길이 밝고 CCTV·비상벨 안이라 밤 기준으로는 지름길이 가장 좋다.
        // 고치기 전: "더 안전한 길은 너무 많이 돌아서 가장 짧은 길을 안내합니다"
        RoadGraph g = triangle();
        List<com.saferoute.route.engine.facility.Poi> pois = new ArrayList<>();
        long id = 100;
        for (RoadEdge e : g.graph().edgeSet()) {
            double[] a = e.geometry().get(0);
            double[] b = e.geometry().get(1);
            for (double k = 0; k <= 1.0001; k += 0.1) {
                double[] p = {a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k};
                pois.add(plain("light", id++, p[0], p[1], 1));
                pois.add(plain("cctv", id++, p[0], p[1], 1));
            }
        }
        pois.add(outdoorBell(id, S_P[0] + 75 * 0.0011 / 100, LAT + 0.0002));
        RouteResult r = triangleRoute(pois, 21, null);

        assertThat(r.conditions().night()).isTrue();
        assertThat(r.routes()).singleElement();
        assertThat(r.explanation().text())
                .isEqualTo("가장 짧은 길이 가장 안전한 길입니다 (알림: 앞에 턱 신고가 있는 곳을 지나요)");
    }

    @Test
    void reportStaysWhenTheWheelchairEndIsMovedOntoTheReportedRoad() {
        // S —— A ———————— B 큰길(S–A 100m, A–B 200m). 도착 D는 B에서 계단으로만 드나드는 보행로 끝(A–B에서 북쪽 25m).
        // 휠체어는 D 쪽 길에 붙였다가 못 가서 A–B 위 X(A에서 100m)에 다시 붙인다. 그때 A–B가 나뉘어 번호가 바뀐다.
        // 공사 신고는 A–B 위 A에서 30m. 다시 붙인 뒤 신고된 길을 다시 찾지 않으면 이 신고가 피하기·알림에서 조용히 빠졌다
        double m = 0.0011 / 100;
        double lat = 1 / 111_195.0;
        long a = 51, b = 52, p = 53, d = 54;
        Map<Long, double[]> nodes = new LinkedHashMap<>();
        nodes.put(S, S_P);
        nodes.put(a, new double[] {S_P[0] + 100 * m, LAT});
        nodes.put(b, new double[] {S_P[0] + 300 * m, LAT});
        nodes.put(p, new double[] {S_P[0] + 300 * m, LAT + 10 * lat});
        nodes.put(d, new double[] {S_P[0] + 200 * m, LAT + 25 * lat});
        RoadGraph g = new RoadGraph(nodes, List.of(edge(60, S, a, nodes.get(S), nodes.get(a), EdgePenalty.NONE),
                edge(61, a, b, nodes.get(a), nodes.get(b), EdgePenalty.NONE),
                edge(62, b, p, nodes.get(b), nodes.get(p),
                        new EdgePenalty(Map.of(com.saferoute.route.engine.graph.Penalty.STAIRS, 1.0))),
                edge(63, p, d, nodes.get(p), nodes.get(d), EdgePenalty.NONE)));
        RouteService service = TestRoutes.service(g, TestRoutes.pois(List.of(), List.of(),
                List.of(report("construction", new double[] {S_P[0] + 130 * m, LAT}))), 12);
        double[] to = nodes.get(d);
        RouteResult r = service.route(new RouteRequest(new Place(LAT, S_P[0], "출발"), new Place(to[1], to[0], "도착"),
                "auto", "wheelchair"));

        // 피할 길이 없어 지나며 주의 (다시 붙인 주의와 함께)
        assertThat(r.routes()).singleElement().satisfies(safe -> assertThat(safe.events())
                .singleElement().satisfies(e -> assertThat(e.avoid()).isTrue()));
        assertThat(r.explanation().notes()).extracting(RouteResult.Note::text)
                .contains("앞에 공사 신고가 있는 곳을 지나요", "도착점 근처 약 25m는 휠체어로 이어진 길이 확인되지 않았습니다");
    }

    @Test
    void manyReportsGetTheRightParticle() {
        List<RouteResult.Event> passing = java.util.stream.Stream.of("공사", "턱", "사고")
                .map(t -> new RouteResult.Event("신고", t, null, null, null, 0, 10, true, null)).toList();
        // "외 1곳" 끝 글자 "곳"에 받침이 있어 "이"
        assertThat(com.saferoute.route.engine.explain.ExplanationBuilder.eventNotes("", List.of(), passing))
                .containsExactly(RouteResult.Note.warn("event", "앞에 공사 신고, 턱 신고 외 1곳이 있는 곳을 지나요"));
    }
}
