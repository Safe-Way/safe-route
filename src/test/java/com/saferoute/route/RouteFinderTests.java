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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteError;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.search.RouteFinder;

/** 탐색 규칙: 우회 한도 안 최대 선호, 계단 조건부 제외, 지날 수 없는 길 제외 */
class RouteFinderTests {

    /** S–M–T 지름길(200m)과 S–N–T 북쪽 길. directPenalty는 S–M 길의 성질 */
    static RoadGraph diamond(double northLatOffset, EdgePenalty directPenalty) {
        double[] nP = {126.93110, LAT + northLatOffset};
        return new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P, N, nP), List.of(
                edge(10, S, M, S_P, M_P, directPenalty), edge(11, M, T, M_P, T_P, EdgePenalty.NONE),
                edge(12, S, N, S_P, nP, EdgePenalty.NONE), edge(13, T, N, T_P, nP, EdgePenalty.NONE)));
    }

    static final EdgePenalty STAIRS = new EdgePenalty(Map.of(Penalty.STAIRS, 1.0));
    static final EdgePenalty CURB = new EdgePenalty(Map.of(Penalty.CURB, 1.0));

    /** 지름길만 비싸게 (어두운 길) */
    static final ToDoubleFunction<RoadEdge> DIRECT_IS_DARK = e -> e.id() <= 11 ? e.lengthM() * 13 : 0;

    static boolean goesNorth(RouteFinder.Found f) {
        return f.safe().edges().stream().anyMatch(e -> e.id() == 12);
    }

    @Test
    void noPreferenceGivesShortest() {
        RouteFinder.Found f = RouteFinder.find(diamond(0.0006, EdgePenalty.NONE), S, T, null, 1.3, e -> true, false);
        assertThat(f.safe().sameAs(f.shortest())).isTrue();
        assertThat(f.shortest().lengthM()).isCloseTo(200, org.assertj.core.api.Assertions.within(1.0));
    }

    @Test
    void preferenceWinsWithinDetourLimit() {
        // 북쪽 길은 약 1.2배
        RouteFinder.Found f = RouteFinder.find(diamond(0.0006, EdgePenalty.NONE), S, T, DIRECT_IS_DARK, 1.3, e -> true, false);
        assertThat(goesNorth(f)).isTrue();
        assertThat(f.detourCapped()).isFalse();
    }

    @Test
    void overTheLimitKeepsShortestAndSaysCapped() {
        RouteFinder.Found f = RouteFinder.find(diamond(0.0006, EdgePenalty.NONE), S, T, DIRECT_IS_DARK, 1.1, e -> true, false);
        assertThat(goesNorth(f)).isFalse();
        assertThat(f.detourCapped()).isTrue();
    }

    @Test
    void seniorAvoidsStairsWhenStairFreeRouteIsWithinLimit() {
        // 북쪽 길 약 1.09배 ≤ 1.2
        RouteFinder.Found f = RouteFinder.find(diamond(0.0004, STAIRS), S, T, null, 1.2, e -> true, true);
        assertThat(goesNorth(f)).isTrue();
        assertThat(f.safe().edges()).noneMatch(RouteFinder::isStairs);
    }

    @Test
    void seniorKeepsStairsWhenEveryStairFreeRouteIsTooLong() {
        // 북쪽 길 약 1.34배 > 1.2 → 계단이 있어도 경로는 나온다
        RouteFinder.Found f = RouteFinder.find(diamond(0.0008, STAIRS), S, T, null, 1.2, e -> true, true);
        assertThat(goesNorth(f)).isFalse();
    }

    @Test
    void stairAvoidanceIsNotWeakenedByOtherPreferences() {
        // 계단 길이 밝고(선호) 북쪽은 어두워도, 계단 없는 길이 한도 안이면 계단을 뺀다
        ToDoubleFunction<RoadEdge> northIsDark = e -> e.id() >= 12 ? e.lengthM() * 13 : 0;
        RouteFinder.Found f = RouteFinder.find(diamond(0.0004, STAIRS), S, T, northIsDark, 1.2, e -> true, true);
        assertThat(goesNorth(f)).isTrue();
    }

    @Test
    void seniorDoesNotFollowPreferenceOntoStairsWhenShortestHasNone() {
        // 지름길은 계단 없고 어두움, 밝은 북쪽 길에 계단. 계단 없는 길(지름길)이 있으니 북쪽으로 가지 않는다
        // (운영 테스트 시드 20261104에서 찾은 경우)
        double[] nP = {126.93110, LAT + 0.0004};
        RoadGraph g = new RoadGraph(Map.of(S, S_P, M, M_P, T, T_P, N, nP), List.of(
                edge(10, S, M, S_P, M_P, EdgePenalty.NONE), edge(11, M, T, M_P, T_P, EdgePenalty.NONE),
                edge(12, S, N, S_P, nP, STAIRS), edge(13, T, N, T_P, nP, EdgePenalty.NONE)));
        RouteFinder.Found f = RouteFinder.find(g, S, T, DIRECT_IS_DARK, 1.2, e -> true, true);
        assertThat(f.safe().edges()).noneMatch(RouteFinder::isStairs);

        // 어른(계단 조건부 제외 없음)은 밝은 북쪽 길로 간다
        assertThat(goesNorth(RouteFinder.find(g, S, T, DIRECT_IS_DARK, 1.2, e -> true, false))).isTrue();
    }

    @Test
    void wheelchairHasNoDetourLimit() {
        // 지름길에 확인된 턱 → 북쪽 길(약 1.58배)이라도 그 길로
        RouteFinder.Found f = RouteFinder.find(diamond(0.0011, CURB), S, T, null, 0,
                e -> e.penalty().get(Penalty.CURB) < 1, false);
        assertThat(goesNorth(f)).isTrue();
        assertThat(f.shortest().sameAs(f.safe())).isTrue();
    }

    @Test
    void noPassableRouteIsNoRoute() {
        RoadGraph g = new RoadGraph(Map.of(S, S_P, M, M_P), List.of(edge(10, S, M, S_P, M_P, CURB)));
        assertThatThrownBy(() -> RouteFinder.find(g, S, M, null, 0, e -> e.penalty().get(Penalty.CURB) < 1, false))
                .isInstanceOfSatisfying(RouteException.class, e -> assertThat(e.error()).isEqualTo(RouteError.NO_ROUTE));
    }
}
