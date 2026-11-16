package com.saferoute.route;

import java.util.List;
import java.util.Set;

import com.saferoute.route.api.RouteError;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.search.RouteFinder;

/**
 * 출발·들를 곳·도착을 길에 붙인다 ({@link RouteService}가 쓴다). 처음 붙이기, 휠체어 인도 없는 골목 안에서 나가는 골목,
 * 지날 수 있는 길로 이어진 곳에 다시 붙이기
 */
final class Snapper {

    private final RouteProperties props;

    Snapper(RouteProperties props) {
        this.props = props;
    }

    /** 길에 붙인 곳. moved: 모든 길 기준으로 가장 가까운 곳이 아닌 곳에 붙였는지 (휠체어) */
    record Placed(RoadGraph.Snap snap, boolean moved) {
    }

    /** 가장 가까운 길 노드. 너무 멀면 길 위가 아니라고 본다. surface가 있으면(지하를 넣은 도로망) 그 안 지상 길에만 붙인다 */
    RoadGraph.Snap snap(RoadGraph graph, double[] p, RouteProperties.Profile profile, Set<Long> surface) {
        // 인도 없는 골목에도 붙인다: 그 골목 안에서 출발·도착하면 골목을 따라 나간다 (endAlleys, 2026-11-13)
        RoadGraph.Snap s = surface == null ? graph.nearest(p[0], p[1], e -> profile.passable(e, true))
                : graph.nearest(p[0], p[1], surface, e -> profile.passable(e, true) && !RouteFinder.isUnderground(e));
        if (s == null || s.distanceM() > props.snapMaxM()) {
            throw new RouteException(RouteError.NO_ROUTE);
        }
        // 붙인 곳이 인도 없는 골목뿐이면, 지날 수 있는 길이 그보다 passable-snap-extra-m(50m) 안으로만 멀면 그 길에 붙인다 (place와 같은 방법).
        // 4번 출구처럼 골목과 큰 길이 비슷하게 가까우면 골목에 붙어 크게 돌았다 (2026-11-13)
        Set<RoadEdge> at = graph.graph().edgesOf(s.node());
        if (at.stream().anyMatch(RouteFinder::isNoSidewalkAlley) && at.stream().noneMatch(profile::passable)) {
            java.util.function.Predicate<RoadEdge> base = e -> profile.passable(e)
                    && !(surface != null && RouteFinder.isUnderground(e));
            RoadGraph.Snap b = graph.nearest(p[0], p[1], graph.mainPart(base), base);
            if (b != null && b.distanceM() - s.distanceM() <= props.passableSnapExtraM()) {
                return b;
            }
        }
        return s;
    }

    /**
     * 붙인 점이 휠체어가 빼는 인도 없는 골목 위면(그 노드에 지날 수 있는 길이 없으면), 거기서 그런 골목만 따라 maxM 안의 길 번호.
     * 지날 수 있는 길에 닿은 노드(골목을 나가는 곳)에서는 더 가지 않는다. 다른 모드는 그런 골목이 없어 빈 집합
     */
    static Set<Long> endAlleys(RoadGraph graph, List<RoadGraph.Snap> snaps, RouteProperties.Profile profile, double maxM) {
        Set<Long> open = new java.util.HashSet<>();
        for (RoadGraph.Snap s : snaps) {
            java.util.Map<Long, Double> dist = new java.util.HashMap<>(java.util.Map.of(s.node(), 0.0));
            java.util.Deque<Long> todo = new java.util.ArrayDeque<>(List.of(s.node()));
            while (!todo.isEmpty()) {
                long v = todo.poll();
                Set<RoadEdge> around = graph.graph().edgesOf(v);
                if (around.stream().anyMatch(profile::passable)) {
                    continue;
                }
                for (RoadEdge e : around) {
                    double d = dist.get(v) + e.lengthM();
                    if (d > maxM || !profile.passable(e, true)) {
                        continue;
                    }
                    open.add(e.id());
                    long w = org.jgrapht.Graphs.getOppositeVertex(graph.graph(), e, v);
                    if (d < dist.getOrDefault(w, Double.MAX_VALUE)) {
                        dist.put(w, d);
                        todo.add(w);
                    }
                }
            }
        }
        return open;
    }

    /**
     * 지날 수 있는 길로 이어진 덩어리(part) 안에 다시 붙인다 (휠체어, 등산로를 피할 때).
     * 가장 가까운 길이 계단·등산로로만 드나드는 구역이면 옆 길에 붙는데, 모든 길 기준보다
     * extraM(보통 passable-snap-extra-m 50m, 휠체어 출발·도착점은 passable-snap-origin-extra-m 100m) 넘게 멀어지면
     * 붙이지 않는다 (2026-11-04, 출발·도착점 2026-11-13)
     */
    Placed place(RoadGraph graph, double[] p, RoadGraph.Snap any, Set<Long> part,
            java.util.function.Predicate<RoadEdge> passable, double extraM) {
        if (part.contains(any.node())) {
            return new Placed(any, false);
        }
        RoadGraph.Snap s = graph.nearest(p[0], p[1], part, passable);
        if (s == null || s.distanceM() - any.distanceM() > extraM) {
            throw new RouteException(RouteError.NO_ROUTE);
        }
        return new Placed(s, true);
    }

    /** 옆 길에 붙였으면 남은 거리를 알린다. 계단·등산로로만 드나드는 곳이라 그 길(way: "휠체어로" 등)이 확인되지 않은 구간이다 */
    static java.util.Optional<RouteResult.Note> placeNote(String which, Placed p, String way) {
        return p.moved() ? java.util.Optional.of(RouteResult.Note.warn("snap",
                "%s 근처 약 %dm는 %s 이어진 길이 확인되지 않았습니다".formatted(which,
                        Math.round(p.snap().distanceM()), way))) : java.util.Optional.empty();
    }
}
