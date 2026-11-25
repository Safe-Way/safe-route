package com.saferoute.route;

import static com.saferoute.route.TestRoutes.UNDER;
import static com.saferoute.route.TestRoutes.safe;
import static com.saferoute.route.TestRoutes.xy;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.InMemoryRoadGraphSource;
import com.saferoute.route.data.PreferenceSource;
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
@DisplayName("지하 시설 들어가는 출구")
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
        RouteService s = TestRoutes.service(new InMemoryRoadGraphSource(GRAPH, EXITS, PLACES), TestRoutes.pois(List.of()),
                TestRoutes.at(12), TestRoutes.props(), PreferenceSource.NONE, null);
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
    @DisplayName("맑은 날은 그 모드가 쓸 수 있는 가장 가까운 출구에서 끝난다")
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
        RouteResult bare = route("문화전당역", "adult", "off");
        assertThat(end(bare)).containsExactly(xy(760, 20));
        assertThat(entrance(bare)).containsExactly(
                RouteResult.Note.info("entrance", "문화전당역은 문화전당역 5번 출구(계단)로 들어가요"));
        // 서쪽 역은 지하상가를 지나 동쪽 역 출구로 가지 않고 그 역 출구(동쪽에서 더 가까운 XW1)
        RouteResult west = route("금남로4가역 광주1호선", "adult", "off");
        assertThat(end(west)).containsExactly(xy(60, 20));
        assertThat(entrance(west)).containsExactly(
                RouteResult.Note.info("entrance", "금남로4가역 광주1호선은 금남로4가역 1번 출구(엘리베이터)로 들어가요"));
    }

    @Test
    @DisplayName("이름이 없거나 다른 장소 이름이면 고른 좌표로 간다")
    void noOrOtherNameKeepsTheDestination() {
        // 역 이름 뒤에 다른 말이 붙으면(출구·주차장) 사용자가 고른 그 좌표로 간다
        for (String name : new String[] {null, "스타벅스 금남로점", "문화전당역 4번 출구", "문화전당역4번출구",
                "문화전당역 공영주차장"}) {
            RouteResult r = route(name, "adult", "off");
            assertThat(end(r)).containsExactly(MALL);
            assertThat(entrance(r)).isEmpty();
        }
    }

    @Test
    @DisplayName("비 오는 날은 지하로 들어가 가까운 지하 점에서 끝나고 다시 올라오지 않는다")
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
    @DisplayName("휠체어 출발점을 다시 붙여도 도착은 들어가는 출구이고 출발점 알림만 붙는다")
    void wheelchairStartBehindStairsIsResnappedAndStillEndsAtTheEntrance() {
        // 도착점 근처 알림은 없다 (RouteService.attempt, 2026-11-16)
        // 출발점은 FROM 북쪽 30m 보행로(계단으로만 FROM과 이어짐)에서 3m 위
        double[] w = xy(880, 30), e = xy(920, 30);
        RoadGraph g = TestRoutes.plus(GRAPH, Map.of(41L, w, 42L, e), TestRoutes.edge(40, 41, 42, w, e, EdgePenalty.NONE),
                TestRoutes.edge(41, 42, 4, e, FROM, TestRoutes.STAIRS));
        double[] start = xy(900, 33);
        RouteService s = TestRoutes.service(new InMemoryRoadGraphSource(g, EXITS, PLACES),
                TestRoutes.pois(List.of()), TestRoutes.at(12), TestRoutes.props(), PreferenceSource.NONE, null);

        RouteResult r = s.route(new RouteRequest(new Place(start[1], start[0], null), new Place(MALL[1], MALL[0], "금남지하상가"),
                "off", "wheelchair", null, "off"));
        assertThat(end(r)).containsExactly(xy(700, 20));
        assertThat(entrance(r)).hasSize(1);
        assertThat(r.explanation().notes()).filteredOn(n -> n.kind().equals("snap")).singleElement()
                .satisfies(n -> assertThat(n.text()).startsWith("출발점"));
    }

    @Test
    @DisplayName("들어가는 곳은 길 비용과 모드 기준 비용 합이 가장 적은 출구다")
    void entranceIsTheExitWithTheLeastRouteAndCriteriaCost() {
        // 앞 점에서 잰 비용, λ = 1 (RouteService.entrance, 2026-11-16)
        // 출발 (100, 0) → 노드 3 (0, 0). 3에서 북쪽 골목 50m 끝 출구 XA(연결 10m), 남쪽 보행로 55m 끝 출구 XB(연결 10m).
        // 길이로는 북쪽(60m)이 가깝지만, 어린이 낮 기준을 보호구역 3·도움 시설 1·골목길 1로 두면(시설 없음)
        // 북쪽 50×6 + 10×5 = 350, 남쪽 65×5 = 325라 남쪽이다
        long xa = -3002001, xb = -3002002, hall = -1002000;
        Map<Long, double[]> nodes = Map.of(1L, xy(100, 0), 3L, xy(0, 0), 5L, xy(0, 50), 6L, xy(0, -55),
                xa, xy(10, 50), xb, xy(10, -55), hall, xy(30, 0));
        RoadGraph g = new RoadGraph(nodes, List.of(
                TestRoutes.edge(50, 1, 3, nodes.get(1L), nodes.get(3L), EdgePenalty.NONE),
                TestRoutes.road(51, 3, 5, nodes.get(3L), nodes.get(5L), "residential", null),
                TestRoutes.edge(52, 3, 6, nodes.get(3L), nodes.get(6L), EdgePenalty.NONE),
                under(53, xa, hall, "steps", "문화전당역 1번 출구 계단", nodes),
                under(54, xb, hall, "steps", "문화전당역 2번 출구 계단", nodes)));
        List<UndergroundLinks.Exit> exits = List.of(new UndergroundLinks.Exit(xa, nodes.get(xa), 51),
                new UndergroundLinks.Exit(xb, nodes.get(xb), 52));
        RouteProperties props = TestRoutes.props("route.profiles.child.day.weights.school=3",
                "route.profiles.child.day.weights.help=1", "route.profiles.child.day.weights.alley=1");
        RouteService s = TestRoutes.service(new InMemoryRoadGraphSource(g, exits, Map.of(hall, "문화전당역 대합실")),
                TestRoutes.pois(List.of()), TestRoutes.at(12), props, PreferenceSource.NONE, null);
        double[] from = nodes.get(1L);
        double[] to = xy(30, 0);

        RouteResult r = s.route(new RouteRequest(new Place(from[1], from[0], null), new Place(to[1], to[0], "문화전당역"),
                "off", "child", null, "off"));
        assertThat(end(r)).containsExactly(nodes.get(xb));
        assertThat(entrance(r)).containsExactly(
                RouteResult.Note.info("entrance", "문화전당역은 문화전당역 2번 출구(계단)로 들어가요"));
    }

    @Test
    @DisplayName("지하 장소 이름은 띄어쓰기를 무시하고 역 이름으로도 찾는다")
    void placeNamesMatchWithoutSpacesOrByStation() {
        // 출구 번호까지 쓴 이름이나 없는 역은 맞추지 않는다
        assertThat(RouteService.undergroundPlace("금남 지하상가", PLACES)).containsExactlyInAnyOrder(M1, M2);
        assertThat(RouteService.undergroundPlace("금남로4가역 광주1호선", PLACES)).containsExactly(CW);
        assertThat(RouteService.undergroundPlace("문화전당역", PLACES)).containsExactly(CE);
        assertThat(RouteService.undergroundPlace("문화전당역 4번 출구", PLACES)).isEmpty();
        assertThat(RouteService.undergroundPlace("금남로5가역", PLACES)).isEmpty();
        assertThat(RouteService.undergroundPlace(null, PLACES)).isEmpty();
    }
}
