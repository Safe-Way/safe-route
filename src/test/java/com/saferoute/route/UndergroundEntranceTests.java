package com.saferoute.route;

import static com.saferoute.route.RainRouteTests.UNDER;
import static com.saferoute.route.RainRouteTests.safe;
import static com.saferoute.route.RainRouteTests.xy;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.InMemoryRoadGraphSource;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.graph.UndergroundLinks;

/**
 * 도착지가 지하 시설이면 들어가는 출구까지 (2026-11-16). 금남지하상가를 줄인 도로망:
 * <pre>
 *   서쪽 역 대합실 CW ── M2 ── M1 ── CE 동쪽 역 대합실        (지하, 북쪽 40m)
 *     │계단  │계단·엘리베이터        │계단·엘리베이터 │계단
 *    XW3   XW1                    XE4           XE5            (출구, 북쪽 20m)
 *   1 ───────────── 2 ───────────────── 3 ─────────── 4        (지상 길, 0 · 400 · 800 · 900m)
 * </pre>
 * 카카오 지하상가 점은 서쪽 끝(15m, XW3 옆)이고, 출발은 동쪽 끝(900m)
 */
class UndergroundEntranceTests {

    static final EdgePenalty UNDER_STAIRS = new EdgePenalty(
            Map.of(Penalty.COVERED, 1.0, Penalty.UNDERGROUND, 1.0, Penalty.STAIRS, 1.0));
    static final long CW = -1000015, CE = -1000016, M1 = -2001001, M2 = -2001002;
    static final long XW3 = -3001503, XW1 = -3001501, XE4 = -3001604, XE5 = -3001605;
    static final double[] FROM = xy(900, 0);
    static final double[] MALL = xy(15, 0);

    static final RoadGraph GRAPH;
    static final List<UndergroundLinks.Exit> EXITS = List.of(new UndergroundLinks.Exit(XW3, xy(20, 20), 10),
            new UndergroundLinks.Exit(XW1, xy(60, 20), 10), new UndergroundLinks.Exit(XE4, xy(700, 20), 11),
            new UndergroundLinks.Exit(XE5, xy(760, 20), 11));
    static final Map<Long, String> PLACES = Map.of(M1, "금남지하상가", M2, "금남지하상가", CW, "금남로4가역 대합실",
            CE, "문화전당역 대합실");

    static {
        Map<Long, double[]> nodes = Map.ofEntries(Map.entry(1L, xy(0, 0)), Map.entry(2L, xy(400, 0)),
                Map.entry(3L, xy(800, 0)), Map.entry(4L, FROM), Map.entry(CW, xy(40, 40)), Map.entry(CE, xy(730, 40)),
                Map.entry(M1, xy(600, 40)), Map.entry(M2, xy(200, 40)), Map.entry(XW3, xy(20, 20)),
                Map.entry(XW1, xy(60, 20)), Map.entry(XE4, xy(700, 20)), Map.entry(XE5, xy(760, 20)));
        GRAPH = new RoadGraph(nodes, List.of(
                TestRoutes.edge(10, 1, 2, nodes.get(1L), nodes.get(2L), EdgePenalty.NONE),
                TestRoutes.edge(11, 2, 3, nodes.get(2L), nodes.get(3L), EdgePenalty.NONE),
                TestRoutes.edge(12, 3, 4, nodes.get(3L), FROM, EdgePenalty.NONE),
                under(20, XW3, CW, "steps", "금남로4가역 3번 출구 계단", nodes),
                under(21, XW1, CW, "steps", "금남로4가역 1번 출구 계단", nodes),
                under(22, XW1, CW, "elevator", "금남로4가역 1번 출구 엘리베이터", nodes),
                under(23, XE4, CE, "steps", "문화전당역 4번 출구 계단", nodes),
                under(24, XE4, CE, "elevator", "문화전당역 4번 출구 엘리베이터", nodes),
                under(25, XE5, CE, "steps", "문화전당역 5번 출구 계단", nodes),
                under(30, CE, M1, "corridor", "금남지하상가", nodes),
                under(31, M1, M2, "corridor", "금남지하상가", nodes),
                under(32, M2, CW, "corridor", "금남지하상가", nodes)));
    }

    static RoadEdge under(long id, long a, long b, String highway, String name, Map<Long, double[]> nodes) {
        List<double[]> line = List.of(nodes.get(a), nodes.get(b));
        return new RoadEdge(id, a, b, Geo.length(line), highway, line, "steps".equals(highway) ? UNDER_STAIRS : UNDER,
                null, name);
    }

    static RouteResult route(String name, String mode, String rain) {
        RouteService s = RainRouteTests.service(new InMemoryRoadGraphSource(GRAPH, EXITS, PLACES), null);
        return s.route(new RouteRequest(new Place(FROM[1], FROM[0], null), new Place(MALL[1], MALL[0], name), "off", mode,
                null, rain));
    }

    static double[] end(RouteResult r) {
        List<double[]> g = safe(r).geometry();
        return g.get(g.size() - 1);
    }

    static List<RouteResult.Note> entrance(RouteResult r) {
        return r.explanation().notes().stream().filter(n -> n.kind().equals("entrance")).toList();
    }

    @Test
    void clearDayEndsAtTheNearestExitTheModeCanUse() {
        // 어른: 동쪽에서 오면 가장 가까운 계단 출구(XE5)에서 끝난다. 서쪽 끝 카카오 점까지 지상으로 700m 넘게 걷지 않는다
        RouteResult adult = route("금남지하상가", "adult", "off");
        assertThat(end(adult)).containsExactly(xy(760, 20));
        assertThat(safe(adult).distanceM()).isLessThan(200);
        assertThat(entrance(adult)).containsExactly(
                RouteResult.Note.info("entrance", "금남지하상가는 문화전당역 5번 출구(계단)로 들어가요"));
        assertThat(adult.explanation().notes()).noneMatch(n -> n.kind().equals("underground"));

        // 휠체어: 계단뿐인 XE5는 빼고 엘리베이터 출구(XE4)
        RouteResult wheel = route("금남 지하상가", "wheelchair", "off");
        assertThat(end(wheel)).containsExactly(xy(700, 20));
        assertThat(entrance(wheel)).containsExactly(
                RouteResult.Note.info("entrance", "금남 지하상가는 문화전당역 4번 출구(엘리베이터)로 들어가요"));

        // 역 이름(카카오 "X역 광주1호선")은 그 역 대합실의 출구만
        RouteResult station = route("문화전당역 광주1호선", "adult", "off");
        assertThat(end(station)).containsExactly(xy(760, 20));
        assertThat(entrance(station)).containsExactly(
                RouteResult.Note.info("entrance", "문화전당역 광주1호선은 문화전당역 5번 출구(계단)로 들어가요"));
        // 서쪽 역은 지하상가를 지나 동쪽 역 출구로 가지 않고 그 역 출구(동쪽에서 더 가까운 XW1)
        RouteResult west = route("금남로4가역 광주1호선", "adult", "off");
        assertThat(end(west)).containsExactly(xy(60, 20));
        assertThat(entrance(west)).containsExactly(
                RouteResult.Note.info("entrance", "금남로4가역 광주1호선은 금남로4가역 1번 출구(엘리베이터)로 들어가요"));
    }

    @Test
    void noOrOtherNameKeepsTheDestination() {
        for (String name : new String[] {null, "스타벅스 금남로점"}) {
            RouteResult r = route(name, "adult", "off");
            assertThat(end(r)).containsExactly(MALL);
            assertThat(entrance(r)).isEmpty();
        }
    }

    @Test
    void rainArrivesInsideWithoutComingUpAgain() {
        // 비 오고 운행 시간 안: 지하상가 점 어디든 닿으면 도착. 동쪽 출구로 내려가 가까운 지하상가 점(M1)에서 끝나고 서쪽 출구로 올라오지 않는다
        RouteResult adult = route("금남지하상가", "adult", "on");
        assertThat(end(adult)).containsExactly(xy(600, 40));
        assertThat(adult.explanation().notes()).contains(
                RouteResult.Note.info("underground", "지하로 지납니다: 문화전당역 5번 출구(계단) → 금남지하상가"),
                RouteResult.Note.info("entrance", "금남지하상가는 문화전당역 5번 출구(계단)로 들어가요"));

        RouteResult wheel = route("금남지하상가", "wheelchair", "on");
        assertThat(end(wheel)).containsExactly(xy(600, 40));
        assertThat(wheel.explanation().notes()).contains(
                RouteResult.Note.info("underground", "지하로 지납니다: 문화전당역 4번 출구(엘리베이터) → 금남지하상가"),
                RouteResult.Note.info("entrance", "금남지하상가는 문화전당역 4번 출구(엘리베이터)로 들어가요"));
    }

    @Test
    void placeNamesMatchWithoutSpacesOrByStation() {
        assertThat(RouteService.undergroundPlace("금남 지하상가", PLACES)).containsExactlyInAnyOrder(M1, M2);
        assertThat(RouteService.undergroundPlace("금남로4가역 광주1호선", PLACES)).containsExactly(CW);
        assertThat(RouteService.undergroundPlace("금남로5가역", PLACES)).isEmpty();
        assertThat(RouteService.undergroundPlace(null, PLACES)).isEmpty();
    }
}
