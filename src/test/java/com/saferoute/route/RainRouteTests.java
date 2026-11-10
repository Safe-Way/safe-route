package com.saferoute.route;

import static com.saferoute.route.TestRoutes.LAT;
import static com.saferoute.route.TestRoutes.M;
import static com.saferoute.route.TestRoutes.M_P;
import static com.saferoute.route.TestRoutes.N;
import static com.saferoute.route.TestRoutes.S;
import static com.saferoute.route.TestRoutes.S_P;
import static com.saferoute.route.TestRoutes.T;
import static com.saferoute.route.TestRoutes.T_P;
import static com.saferoute.route.TestRoutes.at;
import static com.saferoute.route.TestRoutes.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.InMemoryRoadGraphSource;
import com.saferoute.route.data.PreferenceSource;
import com.saferoute.route.data.RoadGraphSource;
import com.saferoute.route.data.UserPreferences;
import com.saferoute.route.engine.explain.ExplanationBuilder;
import com.saferoute.route.engine.explain.RouteResultAssembler;
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.graph.UndergroundLinks;
import com.saferoute.route.engine.score.Coverage;
import com.saferoute.route.engine.score.Criterion;
import com.saferoute.route.engine.score.RouteScorer;
import com.saferoute.route.engine.search.RoadPath;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.WeatherDust;
import com.saferoute.situation.WeatherDustService;

import tools.jackson.databind.json.JsonMapper;

/**
 * 비 오는 날 기준 (2026-11-09): 지붕 기준(ROOF), 비 올 때 우회 한도, 지하 길 넣기(운행 시간), 지하는 어둡지 않음, 설명·알림.
 * 작은 마름모 도로망: S–M–T 지름길(약 200m)과 S–N–T 북쪽 길(길 12·13). 외부 날씨는 부르지 않는다 (목만)
 */
class RainRouteTests {

    static final EdgePenalty ROOF = new EdgePenalty(Map.of(Penalty.COVERED, 1.0));
    /** A파트가 그린 지하 길처럼 지붕 있고 지하 */
    static final EdgePenalty UNDER = new EdgePenalty(Map.of(Penalty.COVERED, 1.0, Penalty.UNDERGROUND, 1.0));

    /** 북쪽 길(12: S–N, 13: T–N)이 성질 p인 마름모. off 0.0007이면 약 1.27배, 0.0015면 약 1.9배 */
    static RoadGraph diamond(double off, EdgePenalty p) {
        double[] nP = {126.93110, LAT + off};
        return new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P, N, nP), List.of(
                TestRoutes.edge(10, S, M, S_P, M_P, EdgePenalty.NONE), TestRoutes.edge(11, M, T, M_P, T_P, EdgePenalty.NONE),
                new RoadEdge(12, S, N, Geo.distance(S_P, nP), "corridor", List.of(S_P, nP), p, null, "금남지하상가"),
                new RoadEdge(13, T, N, Geo.distance(T_P, nP), "corridor", List.of(T_P, nP), p, null, "금남지하상가")));
    }

    static RouteResult route(RoadGraph g, int hour, String mode, String night, String rain, String... overrides) {
        return route(TestRoutes.service(g, TestRoutes.pois(List.of()), hour, props(overrides)), mode, night, rain);
    }

    static RouteResult route(RouteService s, String mode, String night, String rain) {
        return s.route(new RouteRequest(new Place(LAT, S_P[0], null), new Place(LAT, T_P[0], null), night, mode, null,
                rain));
    }

    static RouteResult.Route safe(RouteResult r) {
        return r.routes().get(r.routes().size() - 1);
    }

    static boolean north(RouteResult r) {
        return safe(r).geometry().stream().anyMatch(p -> p[1] > LAT + 0.0001);
    }

    @Test
    void rainPrefersTheRoofedWayWithinTheDetourLimit() {
        RoadGraph g = diamond(0.0007, ROOF);
        RouteResult r = route(g, 12, "adult", "off", "on");

        assertThat(north(r)).isTrue();
        assertThat(r.conditions().roof()).isTrue();
        assertThat(r.conditions().roofSource()).isEqualTo("manual");
        double roofM = Geo.distance(S_P, g.point(N)) + Geo.distance(T_P, g.point(N));
        assertThat(safe(r).covered().get("roof")).isCloseTo((int) Math.round(roofM), within(1));
        assertThat(r.routes().get(0).covered()).containsEntry("roof", 0);
        assertThat(r.explanation().summary()).contains("더 돌지만 비가 와서 지붕 있는 길 +");

        RouteResult dry = route(g, 12, "adult", "off", "off");
        assertThat(dry.routes()).hasSize(1);
        assertThat(north(dry)).isFalse();
        assertThat(safe(dry).covered()).doesNotContainKey("roof");
        assertThat(dry.conditions().roof()).isFalse();
        assertThat(dry.conditions().roofSource()).isEqualTo("manual");
    }

    /** S에서 동쪽 eastM, 북쪽 northM(m) 떨어진 점 */
    static double[] xy(double eastM, double northM) {
        return new double[] {S_P[0] + eastM / (111_195 * Math.cos(Math.toRadians(LAT))), LAT + northM / 111_195};
    }

    /** 지붕 길이 하나도 없는 도로망: S–M–T 지름길(200m)과 S–P–Q–T 사다리꼴 우회 길(P 5, Q 6). pq = P–Q 길이, side = S–P·Q–T 길이 */
    static RoadGraph trapezoid(double pq, double side) {
        double h = Math.sqrt(side * side - Math.pow((200 - pq) / 2, 2));
        double[] p = xy((200 - pq) / 2, h);
        double[] q = xy((200 + pq) / 2, h);
        return new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P, 5L, p, 6L, q), List.of(
                TestRoutes.edge(10, S, M, S_P, M_P, EdgePenalty.NONE), TestRoutes.edge(11, M, T, M_P, T_P, EdgePenalty.NONE),
                TestRoutes.edge(20, S, 5, S_P, p, EdgePenalty.NONE), TestRoutes.edge(21, 5, 6, p, q, EdgePenalty.NONE),
                TestRoutes.edge(22, 6, T, q, T_P, EdgePenalty.NONE)));
    }

    @Test
    void rainDoesNotWeakenTheModeCriteria() {
        // 지붕 길이 없으면 비가 와도 그 모드 기준으로 고른 길이 맑은 날과 같다 (F1 곱하기: 지상 길끼리는 같은 배수).
        // 옛 더하기(지붕 비중 2를 더함)는 지상에서 거리 벌점처럼 작동해 기준이 묽어져 지름길로 바뀌었다
        // 어른 밤: 우회 길 230m 중 P–Q 60m만 가로등 범위 안. λ=1 비용 맑은 날 우회 1770 < 지름길 1800, 옛 더하기 우회 2230 > 2200
        RoadGraph night = trapezoid(60, 85);
        double[] nightMid = {(night.point(5)[0] + night.point(6)[0]) / 2, night.point(5)[1]};
        RouteService adult = TestRoutes.service(night,
                TestRoutes.pois(List.of(TestRoutes.plain("light", 1, nightMid[0], nightMid[1], 1))), 21, props());
        assertThat(north(route(adult, "adult", "auto", "off"))).isTrue();
        assertThat(north(route(adult, "adult", "auto", "on"))).isTrue();

        // 노인 낮: 우회 길 234m 중 P–Q 50m만 쉼 곳(화장실, 반경 30m) 안. 맑은 날 우회 970 < 1000, 옛 더하기 우회 1438 > 1400
        RoadGraph day = trapezoid(50, 92);
        double[] dayMid = {(day.point(5)[0] + day.point(6)[0]) / 2, day.point(5)[1] + 20 / 111_195.0};
        Poi toilet = new Poi("toilet", "공원", 0, dayMid[0], dayMid[1], 0, 24 * 60, "매일", null, null, null, null, null,
                null, 2, 1);
        RouteService senior = TestRoutes.service(day, TestRoutes.pois(List.of(toilet)), 12,
                props("route.profiles.senior.day.rest-m=30"));
        assertThat(north(route(senior, "senior", "off", "off"))).isTrue();
        assertThat(north(route(senior, "senior", "off", "on"))).isTrue();
    }

    @Test
    void roofedWayTooFarKeepsTheShortestAndSaysWhy() {
        // 1.9배 지붕 길은 λ=1에서도 지름길(200m × 1.4)보다 비싸다. 곱하기에서는 (1 + walk-more)배까지만 값어치가 있어 한도에 걸리지 않는다
        RouteResult r = route(diamond(0.0015, ROOF), 12, "adult", "off", "on");
        assertThat(r.routes()).hasSize(1);
        assertThat(north(r)).isFalse();
        assertThat(r.explanation().summary()).isEqualTo("지붕 있는 길이 따로 없어 가장 짧은 길을 안내합니다");

        // 그 사람이 정한 우회 한도(1.1)가 1.4보다 낮으면, 1.27배 지붕 길은 값어치는 있지만 한도에 걸린다
        RouteProperties p = props();
        ZonedDateTime now = at(12);
        RouteService picky = new RouteService(new InMemoryRoadGraphSource(diamond(0.0007, ROOF)), TestRoutes.pois(List.of()),
                new NightJudge(), new RouteScorer(p), new RouteResultAssembler(new FacilityCounter(p), p), p,
                Clock.fixed(now.toInstant(), now.getZone()), id -> new UserPreferences(null, 1.1, null));
        RouteResult capped = picky.route(new RouteRequest(new Place(LAT, S_P[0], null), new Place(LAT, T_P[0], null),
                "off", "adult", "guest-1", "on"));
        assertThat(north(capped)).isFalse();
        assertThat(capped.explanation().summary()).isEqualTo("지붕 있는 길은 너무 많이 돌아서 가장 짧은 길을 안내합니다");
    }

    @Test
    void seniorGetsTheRainDetourLimit() {
        // 노인 한도 1.2로는 1.27배 길을 못 가지만, 비 올 때 한도 max(1.2, 1 + walk-more)가 1.4면 지붕 길로 간다
        RoadGraph g = diamond(0.0007, ROOF);
        assertThat(north(route(g, 12, "senior", "off", "on"))).isTrue();
        assertThat(north(route(g, 12, "senior", "off", "on", "route.rain.walk-more=0.2"))).isFalse();
    }

    @Test
    void undergroundIsInTheNetworkOnlyWhenRainingAndOpen() {
        RoadGraph g = diamond(0.0007, UNDER);
        RouteResult open = route(g, 12, "adult", "off", "on");
        assertThat(north(open)).isTrue();
        assertThat(open.explanation().notes()).contains(
                RouteResult.Note.info("underground", "지하로 지납니다: 금남지하상가"));
        // 운행 시간(05:30~24:00) 밖이면 지하 길이 도로망에 없다
        assertThat(north(route(g, 2, "adult", "off", "on"))).isFalse();
        // 맑은 날에는 넣지 않는다 (지하에서 GPS가 끊김)
        RouteResult dry = route(g, 12, "adult", "off", "off");
        assertThat(north(dry)).isFalse();
        assertThat(dry.explanation().notes()).noneMatch(n -> n.kind().equals("underground"));
    }

    @Test
    void exitsAreJoinedToTheStreetOnlyWhenRaining() {
        // S–M–T 지상 길 옆 북쪽 20m에 출구 둘(5, 6)이 있고, 두 출구 사이에 지하상가(14). 출구는 지상 길과 이어져 있지 않다 (A파트 데이터)
        double[] x1 = {126.93005, LAT + 0.00018};
        double[] x2 = {126.93215, LAT + 0.00018};
        RoadGraph g = new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P, 5L, x1, 6L, x2), List.of(
                TestRoutes.edge(10, S, M, S_P, M_P, EdgePenalty.NONE), TestRoutes.edge(11, M, T, M_P, T_P, EdgePenalty.NONE),
                new RoadEdge(14, 5, 6, Geo.distance(x1, x2), "corridor", List.of(x1, x2), UNDER, null, "금남지하상가")));
        List<UndergroundLinks.Exit> exits = List.of(new UndergroundLinks.Exit(5, x1, 10), new UndergroundLinks.Exit(6, x2, 11));
        RouteService s = service(new InMemoryRoadGraphSource(g, exits), null);

        // 출구 → 지상 길 연결 길(약 20m씩)로 이어져 지하상가로 간다 (240m, 1.2배)
        RouteResult rain = route(s, "adult", "off", "on");
        assertThat(north(rain)).isTrue();
        assertThat(safe(rain).covered().get("roof")).isCloseTo((int) Math.round(Geo.distance(x1, x2)), within(1));
        assertThat(rain.explanation().notes()).contains(RouteResult.Note.info("underground", "지하로 지납니다: 금남지하상가"));
        assertThat(north(route(s, "adult", "off", "off"))).isFalse();
    }

    @Test
    void startAboveAStationJoinsTheStreetNotTheHall() {
        // 요청 좌표가 대합실(31) 바로 위라도, 지하를 넣은 도로망에서는 지상 길(출구 포함)에만 붙인다 (F2).
        // 가역 1번 출구(21)는 계단만, 나역 1번 출구(22)는 엘리베이터. 대합실에서 바로 출발하면 내려가는 출구가 경로에서 빠진다
        double[] x1 = xy(30, 13);
        double[] c1 = xy(30, 33);
        double[] c2 = xy(175, 33);
        double[] x2 = xy(175, 13);
        EdgePenalty underStairs = new EdgePenalty(Map.of(Penalty.COVERED, 1.0, Penalty.UNDERGROUND, 1.0, Penalty.STAIRS, 1.0));
        RoadGraph g = new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P, 21L, x1, 22L, x2, 31L, c1, 32L, c2), List.of(
                TestRoutes.edge(10, S, M, S_P, M_P, EdgePenalty.NONE), TestRoutes.edge(11, M, T, M_P, T_P, EdgePenalty.NONE),
                new RoadEdge(40, 21, 31, Geo.distance(x1, c1), "steps", List.of(x1, c1), underStairs, null, "가역 1번 출구 계단"),
                new RoadEdge(41, 31, 32, Geo.distance(c1, c2), "corridor", List.of(c1, c2), UNDER, null, "테스트지하상가"),
                new RoadEdge(42, 22, 32, Geo.distance(x2, c2), "elevator", List.of(x2, c2), UNDER, null, "나역 1번 출구 엘리베이터")));
        RouteService s = service(new InMemoryRoadGraphSource(g,
                List.of(new UndergroundLinks.Exit(21, x1, 10), new UndergroundLinks.Exit(22, x2, 11))), null);
        Place hall = new Place(c1[1], c1[0], null);
        Place end = new Place(LAT, T_P[0], null);

        // 어른: 계단 출구로 내려가 지하상가를 지난다
        RouteResult adult = s.route(new RouteRequest(hall, end, "off", "adult", null, "on"));
        assertThat(adult.explanation().notes()).contains(RouteResult.Note.info("underground",
                "지하로 지납니다: 가역 1번 출구(계단) → 테스트지하상가 → 나역 1번 출구(엘리베이터)"));
        // 휠체어: 계단 출구로는 못 내려가니 지상으로 간다 (대합실에 붙으면 지하상가로 갔다)
        RouteResult wheel = s.route(new RouteRequest(hall, end, "off", "wheelchair", null, "on"));
        assertThat(wheel.explanation().notes()).noneMatch(n -> n.kind().equals("underground"));
        assertThat(safe(wheel).stairs()).isEmpty();
    }

    @Test
    void lightsAboveTheUndergroundAreNotPassed() {
        // 지하상가(길 12·13) 바로 위 지상 가로등은 지하를 걷는 사람이 지나지 않는다 (F3). 지상 지름길에서는 78m 떨어져 있다
        RoadGraph g = diamond(0.0007, UNDER);
        Poi lamp = TestRoutes.plain("light", 9, g.point(N)[0], g.point(N)[1], 1);
        RouteResult r = TestRoutes.service(g, TestRoutes.pois(List.of(lamp)), 12, props()).route(new RouteRequest(
                new Place(LAT, S_P[0], null), new Place(LAT, T_P[0], null), "off", "adult", null, "on"));

        assertThat(north(r)).isTrue();
        assertThat(safe(r).counts().getOrDefault("light", 0)).isZero();
        assertThat(r.facilities()).isEmpty();
    }

    @Test
    void undergroundIsNotDarkAtNight() {
        // 등이 하나도 없는 밤: 지상 지름길은 다 어둡고, 지하 길은 자료가 없어 어둡다고 하지 않는다
        RouteResult r = route(diamond(0.0007, UNDER), 21, "adult", "auto", "on");

        assertThat(r.conditions().night()).isTrue();
        assertThat(north(r)).isTrue();
        assertThat(safe(r).dark()).isEmpty();
        assertThat(safe(r).covered().get("light")).isEqualTo(safe(r).distanceM());
        assertThat(r.routes().get(0).dark()).isNotEmpty();
    }

    @Test
    void coverageTreatsUndergroundAsInsideAndRoofAsAProperty() {
        double[] a = {126.93000, LAT};
        double[] b = {126.93100, LAT};
        double[] c = {126.93000, LAT + 0.00015};
        double[] d = {126.93100, LAT + 0.00015};
        RoadEdge roofed = TestRoutes.edge(1, 1, 2, a, b, ROOF);
        RoadEdge under = TestRoutes.edge(2, 3, 4, c, d, UNDER);
        RoadGraph g = new RoadGraph(Map.of(1L, a, 2L, b, 3L, c, 4L, d), List.of(roofed, under));
        Map<Criterion, Coverage.Layer> noLights = Map.of(Criterion.LIGHT, new Coverage.Layer(List.of(), 30, true));

        Coverage cov = Coverage.compute(g, noLights, 20, 10);
        assertThat(cov.covered(under, Criterion.LIGHT)).isEqualTo(1);
        assertThat(cov.covered(under, Criterion.ROOF)).isEqualTo(1);
        boolean[] inside = cov.sampled(under, Criterion.LIGHT).inside();
        assertThat(inside).isNotEmpty();
        assertThat(inside).containsOnly(true);
        assertThat(cov.covered(roofed, Criterion.ROOF)).isEqualTo(1);
        assertThat(cov.covered(roofed, Criterion.LIGHT)).isZero();

        // 지하 길 바로 위(3m)의 등도 지상 길(13m)에 붙는다. 지하 선이 지상의 등을 가져가지 않는다
        double[] light = {126.93050, LAT + 0.00012};
        Coverage lit = Coverage.compute(g, Map.of(Criterion.LIGHT, new Coverage.Layer(List.of(light), 30, true)), 20, 10);
        assertThat(lit.covered(roofed, Criterion.LIGHT)).isGreaterThan(0.5);
    }

    @Test
    void undergroundNoteNamesTheWayInAndOut() {
        double[] p = {126.93, LAT};
        RoadEdge link = new RoadEdge(1, 1, 2, 30, "footway", List.of(p, p), EdgePenalty.NONE);
        RoadEdge stairs = new RoadEdge(2, 2, 3, 68, "steps", List.of(p, p), UNDER, null, "문화전당역 3번 출구 계단");
        RoadEdge hall1 = new RoadEdge(3, 3, 4, 93, "corridor", List.of(p, p), UNDER, null, "금남지하상가");
        RoadEdge hall2 = new RoadEdge(4, 4, 5, 93, "corridor", List.of(p, p), UNDER, null, "금남지하상가");
        RoadEdge lift = new RoadEdge(5, 5, 6, 65, "elevator", List.of(p, p), UNDER, null, "금남로4가역 1번 출구 엘리베이터");

        assertThat(ExplanationBuilder.undergroundNote(new RoadPath(List.of(link, stairs, hall1, hall2, lift), List.of(p), 349)))
                .contains(RouteResult.Note.info("underground",
                        "지하로 지납니다: 문화전당역 3번 출구(계단) → 금남지하상가 → 금남로4가역 1번 출구(엘리베이터)"));
        assertThat(ExplanationBuilder.undergroundNote(new RoadPath(List.of(link), List.of(p), 30))).isEmpty();
    }

    @Test
    void appJsonCarriesRain() {
        // 앱이 보내는 요청 JSON의 rain이 버려지지 않는다 (호환 생성자가 여럿이라 확인)
        RouteRequest r = JsonMapper.builder().build().readValue("""
                { "origin": { "lat": 35.147, "lon": 126.919 }, "destination": { "lat": 35.150, "lon": 126.915 },
                  "night": "auto", "mode": "senior", "rain": "on" }
                """, RouteRequest.class);
        assertThat(r.rain()).isEqualTo("on");
        assertThat(r.mode()).isEqualTo("senior");
    }

    @Test
    void undergroundOpenHours() {
        RouteProperties.Underground day = new RouteProperties.Underground("05:30", "24:00");
        assertThat(day.openAt(LocalTime.of(5, 29))).isFalse();
        assertThat(day.openAt(LocalTime.of(5, 30))).isTrue();
        assertThat(day.openAt(LocalTime.of(23, 59))).isTrue();
        assertThat(day.openAt(LocalTime.MIDNIGHT)).isFalse();
        RouteProperties.Underground early = new RouteProperties.Underground("05:30", "22:00");
        assertThat(early.openAt(LocalTime.of(21, 59))).isTrue();
        assertThat(early.openAt(LocalTime.of(22, 0))).isFalse();
        assertThat(RouteProperties.Underground.NONE.openAt(LocalTime.NOON)).isFalse();
    }

    @Test
    void rainOptionReadsAutoOnOff() {
        assertThat(RouteService.rainOption(null)).isNull();
        assertThat(RouteService.rainOption("")).isNull();
        assertThat(RouteService.rainOption("auto")).isNull();
        assertThat(RouteService.rainOption("ON")).isTrue();
        assertThat(RouteService.rainOption("off")).isFalse();
        assertThatThrownBy(() -> RouteService.rainOption("x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wideDetourOnlyForRoof() {
        // 노인 한도 1.2, 비 올 때 넓힌 한도 1.4. 1.3배 우회 길(diamond 0.00075)
        // 지붕 없는 우회 길 쪽에 쉼 곳: 넓힌 한도로 쉼 곳만 더 챙기면 비를 맞으며 더 걷게 되므로 맑은 날(한도 1.2)과 같은 길
        Poi toilet = new Poi("toilet", "공원", 0, 126.93110, LAT + 0.00075, 0, 24 * 60, "매일", null, null, null, null, null,
                null, 3, 1);
        RouteService rest = TestRoutes.service(diamond(0.00075, EdgePenalty.NONE), TestRoutes.pois(List.of(toilet)), 12,
                props("route.profiles.senior.day.rest-m=40"));
        assertThat(north(route(rest, "senior", "off", "off"))).isFalse();
        assertThat(north(route(rest, "senior", "off", "on"))).isFalse();
        // 우회 길이 지붕 아래면 비 올 때 넓힌 한도로 그쪽으로 간다
        assertThat(north(route(diamond(0.00075, ROOF), 12, "senior", "off", "on"))).isTrue();
    }

    @Test
    void wideDetourOnlyWhenRoofPaysForTheExtraWalk() {
        // 노인 한도 1.2, 넓힌 한도 1.4. 1.3배 북쪽 우회 길에 쉼 곳이 있고, 그중 S 쪽 10m만 지붕
        // (a) 넓힌 쪽은 지붕 10m 이득에 약 60m 더 걷는다: 0.4 × 10 < 1.4 × 60 → 원래 한도 쪽 (예전 1m 조건이면 넓힌 쪽)
        double[] nP = {126.93110, LAT + 0.00075};
        double sn = Geo.distance(S_P, nP);
        double[] k = {S_P[0] + (nP[0] - S_P[0]) * 10 / sn, S_P[1] + (nP[1] - S_P[1]) * 10 / sn};
        RoadGraph g = new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P, N, nP, 5L, k), List.of(
                TestRoutes.edge(10, S, M, S_P, M_P, EdgePenalty.NONE), TestRoutes.edge(11, M, T, M_P, T_P, EdgePenalty.NONE),
                TestRoutes.edge(14, S, 5, S_P, k, ROOF), TestRoutes.edge(15, 5, N, k, nP, EdgePenalty.NONE),
                TestRoutes.edge(13, T, N, T_P, nP, EdgePenalty.NONE)));
        Poi toilet = new Poi("toilet", "공원", 0, nP[0], nP[1], 0, 24 * 60, "매일", null, null, null, null, null, null, 3, 1);
        RouteService s = TestRoutes.service(g, TestRoutes.pois(List.of(toilet)), 12, props("route.profiles.senior.day.rest-m=40"));
        assertThat(north(route(s, "senior", "off", "on"))).isFalse();
        // (b) 우회 길 전부가 지붕이면 지붕 이득 260m가 60m 더 걷는 부담을 갚는다: 0.4 × 260 ≥ 1.4 × 60 → 넓힌 쪽
        assertThat(north(route(diamond(0.00075, ROOF), 12, "senior", "off", "on"))).isTrue();
    }

    @Test
    void walkMoreFromPoint3ToPoint5GivesTheSameRoute() {
        // 1.27배 지붕 길은 walk-more 0.3·0.4·0.5 모두 비용(253m < 200m × 1.3 이상)과 한도 안이라 같은 길이다
        RoadGraph g = diamond(0.0007, ROOF);
        for (String r : List.of("0.3", "0.4", "0.5")) {
            assertThat(north(route(g, 12, "adult", "off", "on", "route.rain.walk-more=" + r))).as("walk-more " + r).isTrue();
        }
    }

    @Test
    void autoAsksTheWeatherAndManualDoesNot() {
        WeatherDust raining = new WeatherDust(true, false, false, 12.0, true, false, true);
        WeatherDustService weather = mock(WeatherDustService.class);
        when(weather.start(anyDouble(), anyDouble())).thenReturn(CompletableFuture.completedFuture(raining));
        when(weather.await(any())).thenReturn(raining);
        RouteService s = service(new InMemoryRoadGraphSource(diamond(0.0007, ROOF)), weather);

        RouteResult auto = route(s, "adult", "off", null);
        assertThat(auto.conditions().roof()).isTrue();
        assertThat(auto.conditions().roofSource()).isEqualTo("auto");
        assertThat(north(auto)).isTrue();
        verify(weather).start(anyDouble(), anyDouble());

        WeatherDustService unused = mock(WeatherDustService.class);
        RouteService manual = service(new InMemoryRoadGraphSource(diamond(0.0007, ROOF)), unused);
        assertThat(route(manual, "adult", "off", "off").conditions().roof()).isFalse();
        verify(unused, never()).start(anyDouble(), anyDouble());
    }

    /** 12시, 시설 없음, 9인자 엔진 (weather가 null이면 날씨를 묻지 않음) */
    static RouteService service(RoadGraphSource roads, WeatherDustService weather) {
        RouteProperties p = props();
        ZonedDateTime now = at(12);
        return new RouteService(roads, TestRoutes.pois(List.of()), new NightJudge(), new RouteScorer(p),
                new RouteResultAssembler(new FacilityCounter(p), p), p, Clock.fixed(now.toInstant(), now.getZone()),
                PreferenceSource.NONE, weather);
    }
}
