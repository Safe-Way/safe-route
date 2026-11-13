package com.saferoute.route.engine.search;

import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.AStarAdmissibleHeuristic;
import org.jgrapht.alg.shortestpath.AStarShortestPath;
import org.jgrapht.graph.AsWeightedGraph;
import org.jgrapht.graph.MaskSubgraph;

import com.saferoute.route.api.RouteError;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 최단 경로와 안전 경로를 찾는다 (FR-04·05).
 * <ol>
 *   <li>지날 수 없는 길(휠체어의 계단 등)은 처음부터 뺀다. 최단 경로와 우회율도 "지날 수 있는 길 중에서" 잰다</li>
 *   <li>조건부 제외(노인 계단, 휠체어 비포장): 피할 길이 없는 경로가 우회 한도 안에 있으면 그 길을 뺀다. 없으면 허용한다</li>
 *   <li>좁은 인도(휠체어), 행사·돌발상황 둘레, 신고된 길도 같은 방식으로 피한다. 계단·비포장 피하기를 먼저 지키고, 그 위에서 하나씩 차례로
 *       한도 안이면 뺀다 (하나를 못 피해도 나머지는 피한다)</li>
 *   <li>선호(밝은 길, 도움 시설 등): 길 비용 = 길이 + λ × 추가 비용. 우회 한도를 넘지 않는 가장 큰 λ(0~1)를 이분 탐색으로 찾는다.
 *       예전처럼 가중치를 반씩 줄이면 계단 회피까지 같이 약해져 계단 길이 다시 나왔다</li>
 * </ol>
 */
public final class RouteFinder {

    /** 이분 탐색 횟수. λ를 1/128 단위까지 찾는다 */
    private static final int STEPS = 7;

    /**
     * @param detourCapped 더 안전한 길이 우회 한도를 넘어서, 기준에 맞춘 길을 줄 수 없었는지
     * @param avoidFree    조건부 제외에서 피할 길이 없는 가장 짧은 경로. 그런 경로가 없으면 null
     *                     (조건부 제외가 없거나 최단에 피할 길이 없으면 최단과 같다)
     */
    public record Found(RoadPath shortest, RoadPath safe, boolean detourCapped, RoadPath avoidFree) {

        public Found(RoadPath shortest, RoadPath safe, boolean detourCapped) {
            this(shortest, safe, detourCapped, shortest);
        }
    }

    private RouteFinder() {
    }

    /**
     * @param extra       길마다 더하는 비용(m). null이면 선호가 없다
     * @param maxDetour   안전 경로 길이 ÷ 최단 경로 길이의 한도. 0 이하면 한도 없음
     * @param passable    지날 수 있는 길
     * @param avoidStairs 계단 조건부 제외
     */
    public static Found find(RoadGraph graph, long from, long to, ToDoubleFunction<RoadEdge> extra,
            double maxDetour, Predicate<RoadEdge> passable, boolean avoidStairs) {
        return find(graph, from, to, extra, maxDetour, passable, avoidStairs ? RouteFinder::isStairs : null);
    }

    /**
     * @param avoid 조건부 제외할 길 (노인 계단, 휠체어 비포장). null이면 없음
     */
    public static Found find(RoadGraph graph, long from, long to, ToDoubleFunction<RoadEdge> extra,
            double maxDetour, Predicate<RoadEdge> passable, Predicate<RoadEdge> avoid) {
        return find(graph, from, to, extra, maxDetour, passable, avoid, List.of());
    }

    /**
     * @param avoid  조건부 제외할 길 (노인 계단, 휠체어 비포장). null이면 없음
     * @param asides 그다음으로 차례로 조건부 제외할 길 묶음 (행사·돌발 둘레 하나, 피할 신고마다 하나, 2026-11-10).
     *               한 Set으로 합치면 하나라도 못 피할 때 전부 안 피하게 된다
     */
    public static Found find(RoadGraph graph, long from, long to, ToDoubleFunction<RoadEdge> extra,
            double maxDetour, Predicate<RoadEdge> passable, Predicate<RoadEdge> avoid,
            List<Predicate<RoadEdge>> asides) {
        return find(graph, from, to, extra, maxDetour, passable, avoid, asides, RoadEdge::lengthM);
    }

    /**
     * @param cost 길 기본 비용(m, 길이 이상). 보통 길이. 최단·피하기·안전 찾기 모두 이 비용으로 고르고, 우회 한도와 응답 거리는 실제 길이로 잰다.
     *             휠체어가 인도 없는 골목을 넣어 다시 찾을 때 그 골목만 길이 × 배수 (2026-11-13)
     */
    public static Found find(RoadGraph graph, long from, long to, ToDoubleFunction<RoadEdge> extra,
            double maxDetour, Predicate<RoadEdge> passable, Predicate<RoadEdge> avoid,
            List<Predicate<RoadEdge>> asides, ToDoubleFunction<RoadEdge> cost) {
        Graph<Long, RoadEdge> usable = mask(graph, passable);
        RoadPath shortest = search(graph, usable, from, to, cost);
        if (shortest == null) {
            throw new RouteException(RouteError.NO_ROUTE);
        }
        double limit = maxDetour > 0 ? shortest.lengthM() * maxDetour : Double.MAX_VALUE;

        Graph<Long, RoadEdge> pool = usable;
        Predicate<RoadEdge> allowed = passable;
        RoadPath base = shortest;
        RoadPath free = shortest;
        if (avoid != null) {
            // 최단 경로에 피할 길이 없어도 피할 길은 뺀다. 빼지 않으면 선호(쉼 곳·밝은 길)를 따라가다 그 길로 갈 수 있다
            Predicate<RoadEdge> clear = passable.and(avoid.negate());
            Graph<Long, RoadEdge> without = mask(graph, clear);
            free = shortest.edges().stream().anyMatch(avoid)
                    ? search(graph, without, from, to, cost) : shortest;
            if (free != null && free.lengthM() <= limit) {
                pool = without;
                allowed = clear;
                base = free;
            }
        }
        for (Predicate<RoadEdge> aside : asides) {
            Predicate<RoadEdge> clear = allowed.and(aside.negate());
            Graph<Long, RoadEdge> without = mask(graph, clear);
            RoadPath away = base.edges().stream().anyMatch(aside)
                    ? search(graph, without, from, to, cost) : base;
            if (away != null && away.lengthM() <= limit) {
                pool = without;
                allowed = clear;
                base = away;
            }
        }
        if (extra == null) {
            return new Found(shortest, base, false, free);
        }

        Graph<Long, RoadEdge> g = pool;
        DoubleFunction<RoadPath> at = lambda -> search(graph, g, from, to,
                e -> cost.applyAsDouble(e) + lambda * extra.applyAsDouble(e));
        RoadPath full = at.apply(1);
        if (full.lengthM() <= limit) {
            return new Found(shortest, full, false, free);
        }
        double lo = 0;
        double hi = 1;
        RoadPath best = base;
        for (int i = 0; i < STEPS; i++) {
            double mid = (lo + hi) / 2;
            RoadPath r = at.apply(mid);
            if (r.lengthM() <= limit) {
                lo = mid;
                best = r;
            } else {
                hi = mid;
            }
        }
        return new Found(shortest, best, best.sameAs(base), free);
    }

    public static boolean isStairs(RoadEdge e) {
        return e.penalty().get(Penalty.STAIRS) >= 1;
    }

    /** 확인된 비포장 */
    public static boolean isUnpaved(RoadEdge e) {
        return e.penalty().get(Penalty.UNPAVED) >= 1;
    }

    /** 등산로 (OSM sac_scale이 붙은 산길·계단) */
    public static boolean isTrail(RoadEdge e) {
        return e.penalty().get(Penalty.TRAIL) >= 1;
    }

    /** 재질을 모르는 산길 */
    public static boolean isSurfaceUnknown(RoadEdge e) {
        return e.penalty().get(Penalty.SURFACE_UNKNOWN) >= 1;
    }

    /** 골목길이 될 수 있는 길 종류 (OSM highway) */
    static final java.util.Set<String> ALLEY_ROADS = java.util.Set.of("service", "residential", "unclassified",
            "living_street");

    /**
     * 골목길: 골목급 차도(service·residential·unclassified·living_street)인데 인도 태그가 없거나 no.
     * 인도가 없어 차와 사람이 같이 쓰는 길이라 걷다 보면 아무 데서나 차도를 건너게 된다 (2026-11-12 카카오맵 현장 비교).
     * 보도·보행로·횡단보도 길(footway 등)과 인도 태그(left·right·both)가 있는 길은 아니다
     */
    public static boolean isAlley(RoadEdge e) {
        return e.highway() != null && ALLEY_ROADS.contains(e.highway()) && (e.sidewalk() == null || "no".equals(e.sidewalk()));
    }

    /** 좁은 인도 (국토지리정보원 보도 자료 폭이 narrow-sidewalk-m보다 좁음, 2026-11-13) */
    public static boolean isNarrowSidewalk(RoadEdge e) {
        return e.penalty().get(Penalty.NARROW_SIDEWALK) >= 1;
    }

    /** 인도가 없다고 확인된 골목길 (sidewalk = no). 인도 태그가 없는(모르는) 골목은 아니다. 휠체어는 뺀다 (2026-11-13) */
    public static boolean isNoSidewalkAlley(RoadEdge e) {
        return isAlley(e) && "no".equals(e.sidewalk());
    }

    /** A파트가 그린 지하 길 (역 출구 계단·엘리베이터, 대합실, 지하상가 통로, 2026-11-09) */
    public static boolean isUnderground(RoadEdge e) {
        return e.penalty().get(Penalty.UNDERGROUND) >= 1;
    }

    private static Graph<Long, RoadEdge> mask(RoadGraph graph, Predicate<RoadEdge> passable) {
        return new MaskSubgraph<>(graph.graph(), v -> false, e -> !passable.test(e));
    }

    private static RoadPath search(RoadGraph graph, Graph<Long, RoadEdge> usable, long from, long to,
            ToDoubleFunction<RoadEdge> cost) {
        // 요청마다 비용을 덧씌운 보기만 만들고, 여러 요청이 함께 쓰는 그래프는 그대로 둔다
        var weighted = new AsWeightedGraph<>(usable, cost::applyAsDouble, false, false);
        // 비용은 길이보다 작아지지 않으므로 직선거리를 A* 추정값으로 쓸 수 있다.
        // 도로망 길이를 다른 지구 반지름으로 쟀을 수 있어 1% 낮춰 잡는다 (넘게 잡으면 최단을 놓친다)
        AStarAdmissibleHeuristic<Long> straight =
                (a, b) -> 0.99 * Geo.distance(graph.point(a), graph.point(b));
        GraphPath<Long, RoadEdge> p = new AStarShortestPath<>(weighted, straight).getPath(from, to);
        return p == null ? null : RoadPath.of(p, graph);
    }
}
