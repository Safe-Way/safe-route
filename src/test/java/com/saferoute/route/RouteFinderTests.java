package com.saferoute.route;

import static com.saferoute.route.TestRoutes.CURB;
import static com.saferoute.route.TestRoutes.LAT;
import static com.saferoute.route.TestRoutes.M;
import static com.saferoute.route.TestRoutes.M_P;
import static com.saferoute.route.TestRoutes.N;
import static com.saferoute.route.TestRoutes.S;
import static com.saferoute.route.TestRoutes.S_P;
import static com.saferoute.route.TestRoutes.STAIRS;
import static com.saferoute.route.TestRoutes.T;
import static com.saferoute.route.TestRoutes.T_P;
import static com.saferoute.route.TestRoutes.assertNoRoute;
import static com.saferoute.route.TestRoutes.diamond;
import static com.saferoute.route.TestRoutes.edge;
import static com.saferoute.route.TestRoutes.xy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToDoubleFunction;

import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.AsWeightedGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.search.RoadPath;
import com.saferoute.route.engine.search.RouteFinder;

/** 탐색 규칙: 우회 한도 안 최대 선호, 계단 조건부 제외, 지날 수 없는 길 제외 */
@DisplayName("경로 탐색 규칙")
class RouteFinderTests {

    /** 지름길만 비싸게 (어두운 길) */
    static final ToDoubleFunction<RoadEdge> DIRECT_IS_DARK = e -> e.id() <= 11 ? e.lengthM() * 13 : 0;

    /** 계단 조건부 제외만 켜고 끄는 찾기 (행사·신고 피하기 없음, 비용은 길이) */
    static RouteFinder.Found find(RoadGraph g, long from, long to, ToDoubleFunction<RoadEdge> extra, double maxDetour,
            java.util.function.Predicate<RoadEdge> passable, boolean avoidStairs) {
        return RouteFinder.find(g, from, to, extra, maxDetour, passable, avoidStairs ? RouteFinder::isStairs : null,
                List.of(), RoadEdge::lengthM);
    }

    static boolean goesNorth(RouteFinder.Found f) {
        return f.safe().edges().stream().anyMatch(e -> e.id() == 12);
    }

    @Test
    @DisplayName("선호 비용이 없으면 안전 경로가 최단 경로와 같다")
    void noPreferenceGivesShortest() {
        RouteFinder.Found f = find(diamond(0.0006), S, T, null, 1.3, e -> true, false);
        assertThat(f.safe().sameAs(f.shortest())).isTrue();
        assertThat(f.shortest().lengthM()).isCloseTo(200, within(1.0));
    }

    @Test
    @DisplayName("우회 한도 안이면 선호하는 길로 간다")
    void preferenceWinsWithinDetourLimit() {
        // 북쪽 길은 약 1.2배
        RouteFinder.Found f = find(diamond(0.0006), S, T, DIRECT_IS_DARK, 1.3, e -> true, false);
        assertThat(goesNorth(f)).isTrue();
        assertThat(f.detourCapped()).isFalse();
    }

    @Test
    @DisplayName("선호하는 길이 우회 한도를 넘으면 최단으로 가고 한도에 걸렸다고 표시한다")
    void overTheLimitKeepsShortestAndSaysCapped() {
        // detourCapped
        RouteFinder.Found f = find(diamond(0.0006), S, T, DIRECT_IS_DARK, 1.1, e -> true, false);
        assertThat(goesNorth(f)).isFalse();
        assertThat(f.detourCapped()).isTrue();
    }

    @Test
    @DisplayName("안전 경로 길이가 최단 × 우회 한도와 같아도 그 길로 간다")
    void detourLimitIsInclusive() {
        // 길이 ≤ 최단 × max-detour (RouteFinder.find)
        RoadGraph g = diamond(0.0006);
        RouteFinder.Found any = find(g, S, T, DIRECT_IS_DARK, 0, e -> true, false);
        double ratio = any.safe().lengthM() / any.shortest().lengthM();
        // 이 도로망에서는 최단 × ratio가 북쪽 길 길이와 정확히 같아 경계값 자체를 볼 수 있다
        assertThat(any.shortest().lengthM() * ratio).isEqualTo(any.safe().lengthM());

        RouteFinder.Found exact = find(g, S, T, DIRECT_IS_DARK, ratio, e -> true, false);
        RouteFinder.Found in = find(g, S, T, DIRECT_IS_DARK, ratioAbove(ratio), e -> true, false);
        RouteFinder.Found out = find(g, S, T, DIRECT_IS_DARK, ratioBelow(ratio), e -> true, false);
        assertThat(goesNorth(exact)).isTrue();
        assertThat(goesNorth(in)).isTrue();
        assertThat(goesNorth(out)).isFalse();
        assertThat(out.detourCapped()).isTrue();
    }

    /**
     * 세 길: 어두운 지름길 S–T 200m, 1/3만 어두운 남쪽 길 S–P–T 240m(1.2배), 다 밝은 북쪽 길 S–Q–T 300m(1.5배).
     * λ=1이면 북쪽 길이 가장 싸지만 한도 1.3을 넘으므로, 한도 안에서 가장 선호하는 남쪽 길을 골라야 한다
     */
    @Test
    @DisplayName("선호를 다 따른 길이 한도를 넘으면 λ를 줄여 한도 안 가장 선호하는 길을 고른다")
    void picksMostPreferredRouteWithinLimitWhenFullPreferenceIsTooLong() {
        // RouteFinder.find
        double[] s = xy(0, 0);
        double[] t = xy(200, 0);
        double[] p = xy(100, -Math.sqrt(120 * 120 - 100 * 100));
        double[] q = xy(100, Math.sqrt(150 * 150 - 100 * 100));
        RoadGraph g = new RoadGraph(Map.of(1L, s, 2L, t, 3L, p, 4L, q), List.of(
                edge(1, 1, 2, s, t, EdgePenalty.NONE),
                edge(2, 1, 3, s, p, EdgePenalty.NONE), edge(3, 3, 2, p, t, EdgePenalty.NONE),
                edge(4, 1, 4, s, q, EdgePenalty.NONE), edge(5, 4, 2, q, t, EdgePenalty.NONE)));
        ToDoubleFunction<RoadEdge> dark = e -> e.id() == 1 ? e.lengthM() : e.id() <= 3 ? 40 : 0;

        RouteFinder.Found f = find(g, 1, 2, dark, 1.3, e -> true, false);

        assertThat(f.safe().edges()).extracting(RoadEdge::id).containsExactly(2L, 3L);
        assertThat(f.detourCapped()).isFalse();
    }

    @Test
    @DisplayName("무작위 격자에서 A* 최단 경로 비용이 다익스트라와 같다")
    void shortestEqualsDijkstraOnRandomGrids() {
        // A*의 추정값(직선 0.99배)이 길 비용(길이 이상)보다 커지지 않아 다익스트라와 같은 비용을 준다
        for (long seed = 1; seed <= 5; seed++) {
            Random rnd = new Random(seed);
            RoadGraph g = TestRoutes.grid(20, 30, (r, c) -> false);
            Map<Long, Double> factor = new HashMap<>();
            g.graph().edgeSet().forEach(e -> factor.put(e.id(), 1 + 2 * rnd.nextDouble()));
            ToDoubleFunction<RoadEdge> cost = e -> e.lengthM() * factor.get(e.id());
            long from = rnd.nextInt(400);
            long to = rnd.nextInt(400);
            if (from == to) {
                continue;
            }

            RoadPath aStar = RouteFinder.find(g, from, to, null, 0, e -> true, null, List.of(), cost).shortest();
            double dijkstra = new DijkstraShortestPath<>(new AsWeightedGraph<>(g.graph(), cost::applyAsDouble, false, false))
                    .getPathWeight(from, to);

            assertThat(aStar.edges().stream().mapToDouble(cost).sum()).as("seed " + seed).isCloseTo(dijkstra, within(1e-6));
        }
    }

    @Test
    @DisplayName("무작위 격자에서도 안전 경로는 최단 × 우회 한도를 넘지 않는다")
    void safeRouteNeverExceedsDetourLimitOnRandomGrids() {
        // max-detour
        // 선호를 다 따른 길(한도 없음)이 한도를 넘어 λ를 줄여 찾은 경우가 적어도 하나 있어야 의미가 있다
        int bisected = 0;
        for (long seed = 1; seed <= 5; seed++) {
            Random rnd = new Random(seed);
            RoadGraph g = TestRoutes.grid(15, 30, (r, c) -> false);
            java.util.Set<Long> darkIds = new java.util.HashSet<>();
            g.graph().edgeSet().forEach(e -> {
                if (rnd.nextDouble() < 0.4) {
                    darkIds.add(e.id());
                }
            });
            ToDoubleFunction<RoadEdge> dark = e -> darkIds.contains(e.id()) ? e.lengthM() * 13 : 0;
            long from = rnd.nextInt(225);
            long to = rnd.nextInt(225);
            if (from == to) {
                continue;
            }
            for (double limit : new double[] {1.2, 1.3}) {
                RouteFinder.Found f = find(g, from, to, dark, limit, e -> true, false);

                assertThat(f.safe().lengthM()).as("seed %d, 한도 %s", seed, limit)
                        .isLessThanOrEqualTo(f.shortest().lengthM() * limit + 1e-9);
                if (find(g, from, to, dark, 0, e -> true, false).safe().lengthM() > f.shortest().lengthM() * limit) {
                    bisected++;
                }
            }
        }
        assertThat(bisected).isPositive();
    }

    @Test
    @DisplayName("계단 없는 길이 한도 안이면 계단 있는 지름길로 가지 않는다")
    void avoidedEdgesAreLeftOutWhenFreeRouteIsWithinLimit() {
        // 북쪽 길 약 1.09배 ≤ 1.2
        RouteFinder.Found f = find(diamond(0.0004, STAIRS), S, T, null, 1.2, e -> true, true);
        assertThat(goesNorth(f)).isTrue();
        assertThat(f.safe().edges()).noneMatch(RouteFinder::isStairs);
    }

    @Test
    @DisplayName("계단 없는 길이 모두 한도 밖이면 계단 길을 남긴다")
    void avoidedEdgesStayWhenEveryFreeRouteIsTooLong() {
        // 북쪽 길 약 1.34배 > 1.2 → 계단이 있어도 경로는 나온다
        RouteFinder.Found f = find(diamond(0.0008, STAIRS), S, T, null, 1.2, e -> true, true);
        assertThat(goesNorth(f)).isFalse();
    }

    @Test
    @DisplayName("계단 없는 길이 최단 × 우회 한도와 같아도 계단 길을 뺀다")
    void stairAvoidanceLimitIsInclusive() {
        // 이하면 뺀다 (route.yml avoid-stairs, RouteFinder.find)
        RoadGraph g = diamond(0.0006, STAIRS);
        RouteFinder.Found free = find(g, S, T, null, 0, e -> true, true);
        double ratio = free.safe().lengthM() / free.shortest().lengthM();
        // 이 도로망에서는 최단 × ratio가 계단 없는 길 길이와 정확히 같아 경계값 자체를 볼 수 있다
        assertThat(free.shortest().lengthM() * ratio).isEqualTo(free.safe().lengthM());

        RouteFinder.Found exact = find(g, S, T, null, ratio, e -> true, true);
        RouteFinder.Found in = find(g, S, T, null, ratioAbove(ratio), e -> true, true);
        RouteFinder.Found out = find(g, S, T, null, ratioBelow(ratio), e -> true, true);
        assertThat(exact.safe().edges()).noneMatch(RouteFinder::isStairs);
        assertThat(in.safe().edges()).noneMatch(RouteFinder::isStairs);
        assertThat(out.safe().edges()).anyMatch(RouteFinder::isStairs);
    }

    @Test
    @DisplayName("다른 선호가 있어도 계단 없는 길이 한도 안이면 계단 길을 뺀다")
    void stairAvoidanceIsNotWeakenedByOtherPreferences() {
        // 계단 길이 밝고(선호) 북쪽은 어두워도, 계단 없는 길이 한도 안이면 계단을 뺀다
        ToDoubleFunction<RoadEdge> northIsDark = e -> e.id() >= 12 ? e.lengthM() * 13 : 0;
        RouteFinder.Found f = find(diamond(0.0004, STAIRS), S, T, northIsDark, 1.2, e -> true, true);
        assertThat(goesNorth(f)).isTrue();
    }

    @Test
    @DisplayName("최단 경로에 계단이 없어도 안전 경로에서 계단 길을 뺀다")
    void stairsAreLeftOutEvenWhenShortestHasNone() {
        // 지름길은 계단 없고 어두움, 밝은 북쪽 길에 계단. 계단 없는 길(지름길)이 있으니 북쪽으로 가지 않는다
        double[] nP = {126.93110, LAT + 0.0004};
        RoadGraph g = new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P, N, nP), List.of(
                edge(10, S, M, S_P, M_P, EdgePenalty.NONE), edge(11, M, T, M_P, T_P, EdgePenalty.NONE),
                edge(12, S, N, S_P, nP, STAIRS), edge(13, T, N, T_P, nP, EdgePenalty.NONE)));
        RouteFinder.Found f = find(g, S, T, DIRECT_IS_DARK, 1.2, e -> true, true);
        assertThat(f.safe().edges()).noneMatch(RouteFinder::isStairs);

        // 어른(계단 조건부 제외 없음)은 밝은 북쪽 길로 간다
        assertThat(goesNorth(find(g, S, T, DIRECT_IS_DARK, 1.2, e -> true, false))).isTrue();
    }

    @Test
    @DisplayName("우회 한도는 지날 수 있는 길 중 최단을 기준으로 잰다")
    void detourIsMeasuredAmongPassableRoads() {
        // 지름길에 확인된 턱 → 지날 수 있는 길은 북쪽 길(약 1.58배)뿐이라 한도 1.2여도 그 길이 최단이다
        RouteFinder.Found f = find(diamond(0.0011, CURB), S, T, null, 1.2,
                e -> e.penalty().get(Penalty.CURB) < 1, false);
        assertThat(goesNorth(f)).isTrue();
        assertThat(f.shortest().sameAs(f.safe())).isTrue();
    }

    @Test
    @DisplayName("골목길은 인도 태그가 없거나 no인 골목급 차도다")
    void alleyIsSmallRoadWithoutSidewalkTag() {
        // 골목길 (2026-11-12): 골목급 차도 + 인도 태그 없음·no. 인도 태그가 있거나 걷는 길·큰 길이면 아니다
        assertThat(RouteFinder.isAlley(road("service", null))).isTrue();
        assertThat(RouteFinder.isAlley(road("living_street", null))).isTrue();
        assertThat(RouteFinder.isAlley(road("unclassified", "no"))).isTrue();
        assertThat(RouteFinder.isAlley(road("residential", "both"))).isFalse();
        assertThat(RouteFinder.isAlley(road("residential", "left"))).isFalse();
        assertThat(RouteFinder.isAlley(road("footway", null))).isFalse();
        assertThat(RouteFinder.isAlley(road("tertiary", null))).isFalse();
        assertThat(RouteFinder.isAlley(road(null, null))).isFalse();
    }

    private static RoadEdge road(String highway, String sidewalk) {
        return TestRoutes.road(1, S, M, S_P, M_P, highway, sidewalk);
    }

    /** 우회 한도 같은 비율 경계 바로 아래 */
    private static double ratioBelow(double r) {
        return r * (1 - 1e-9);
    }

    /** 비율 경계 바로 위 */
    private static double ratioAbove(double r) {
        return r * (1 + 1e-9);
    }

    @Test
    @DisplayName("지날 수 있는 길로 이어지지 않으면 NO_ROUTE다")
    void noPassableRouteIsNoRoute() {
        RoadGraph g = new RoadGraph(Map.of(S, S_P, M, M_P), List.of(edge(10, S, M, S_P, M_P, CURB)));
        assertNoRoute(() -> find(g, S, M, null, 0, e -> e.penalty().get(Penalty.CURB) < 1, false));
    }
}
