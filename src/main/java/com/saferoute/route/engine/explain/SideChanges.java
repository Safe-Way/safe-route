package com.saferoute.route.engine.explain;

import java.util.ArrayList;
import java.util.List;

import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Crossings;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.search.RoadPath;

/**
 * 큰 길을 건너는 곳과 실제로 건너는 횡단보도 자리 (2026-11-11, specs/013, 진행표 w17).
 * 경로 선은 차도 가운데라 앱이 교차로 한가운데(신호등 점)에서 쪽을 바꿔 그렸다. 경로는 그대로 두고 자리만 알려 준다.
 * <ul>
 *   <li>건넌 곳: 탐색이 칸을 잇는 길로 큰길 갈래를 건넌 곳마다 하나 ({@link RoadPath#crosses}). 걷는 쪽을 비교하지 않는다:
 *       모퉁이를 돌면 건너지 않아도 걷는 방향 기준 쪽이 바뀐다</li>
 *   <li>횡단보도: 건넌 큰 길 갈래 선 {@link Crossings#LINE_M} 안이면서 노드에서 {@link #NEAR_M} 안인 가장 가까운 점 →
 *       없으면 그 선과 만나거나 닿는 횡단보도 길의 가운데 → 없으면 자리 없이 쪽만. 큰 길 2개 노드는 두 갈래 선 모두
 *       (두 잇는 길이 같은 두 칸을 이어, 어느 갈래로 건넜는지는 탐색이 임의로 고른다)</li>
 * </ul>
 */
public final class SideChanges {

    /**
     * 노드에서 이 거리(m) 안 횡단보도만 쓴다. 더 멀면 그 교차로가 아닌 다음 교차로·골목 입구 횡단보도일 수 있어
     * 엉뚱한 곳에서 건너게 그린다 (재휘 전달 요청 값, specs/013 D4)
     */
    static final double NEAR_M = 30;

    private SideChanges() {
    }

    /**
     * @param startM     요청 출발에서 경로 첫 점까지(m). 거리를 시설 along.atM과 같은 기준으로 맞춘다
     * @param graph      나누기 전 요청 도로망. 노드의 큰 길과 횡단보도 길을 찾는다
     * @param crosswalks 횡단보도 점 {경도, 위도} (신호등 점은 교차로 가운데라 안 씀)
     */
    static List<RouteResult.Crossing> along(RoadPath path, double startM, RoadGraph graph, List<double[]> crosswalks) {
        List<RouteResult.Crossing> out = new ArrayList<>();
        for (RoadPath.Cross c : path.crosses()) {
            Spot at = crosswalk(graph, c.node(), c.arm(), crosswalks);
            int m = (int) Math.round(startM + c.atM());
            out.add(at == null ? new RouteResult.Crossing(m, m, null, null, null, c.fromSide(), c.toSide())
                    : new RouteResult.Crossing(m, m, at.p()[1], at.p()[0], at.kind(), c.fromSide(), c.toSide()));
        }
        return out;
    }

    /** 찾은 횡단보도 자리 {경도, 위도}와 kind (crossing·footway) */
    private record Spot(double[] p, String kind) {
    }

    /**
     * 노드 n에서 큰 길 갈래 arm을 건너는 횡단보도 자리. 못 찾으면 null.
     * 건넌 갈래 선 위만 본다: 옆 골목·다른 갈래를 건너는 횡단보도가 잡히면 안 된다 (민수 요청 ③)
     */
    private static Spot crosswalk(RoadGraph graph, long n, long arm, List<double[]> crosswalks) {
        double[] p = graph.point(n);
        if (p == null) {
            return null;
        }
        List<RoadEdge> arms = graph.graph().edgesOf(n).stream().filter(SideChanges::big).toList();
        List<List<double[]>> lines = arms.stream().filter(e -> arms.size() <= 2 || e.id() == arm)
                .map(RoadEdge::geometry).toList();
        double[] best = null;
        double bestM = NEAR_M;
        for (double[] q : crosswalks) {
            double d = Geo.distance(p, q);
            if (d <= bestM && near(q, lines)) {
                best = q;
                bestM = d;
            }
        }
        if (best != null) {
            return new Spot(best, "crossing");
        }
        for (RoadEdge e : graph.graph().edgeSet()) {
            List<double[]> g = e.geometry();
            if (e.penalty().get(Penalty.CROSSWALK) < 1 || !touches(g, lines)) {
                continue;
            }
            // 횡단보도 길은 차도를 가로지르는 짧은 곧은 선이라 두 끝의 가운데가 선 가운데와 같다
            double[] mid = {(g.get(0)[0] + g.get(g.size() - 1)[0]) / 2, (g.get(0)[1] + g.get(g.size() - 1)[1]) / 2};
            double d = Geo.distance(p, mid);
            if (d <= bestM) {
                best = mid;
                bestM = d;
            }
        }
        return best == null ? null : new Spot(best, "footway");
    }

    private static boolean near(double[] q, List<List<double[]>> lines) {
        return lines.stream().anyMatch(l -> Geo.distanceToLine(q, l) <= Crossings.LINE_M);
    }

    /** 횡단보도 길 g가 큰 길 선과 엇갈리거나 꼭짓점이 선 {@link Crossings#LINE_M} 안에 닿는지 */
    private static boolean touches(List<double[]> g, List<List<double[]>> lines) {
        if (g.stream().anyMatch(q -> near(q, lines))) {
            return true;
        }
        for (List<double[]> l : lines) {
            for (int i = 1; i < g.size(); i++) {
                for (int k = 1; k < l.size(); k++) {
                    if (RoadGraph.crosses(g.get(i - 1), g.get(i), l.get(k - 1), l.get(k))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 큰 길: 인도 쪽 규칙과 같은 trunk~tertiary와 연결로 */
    private static boolean big(RoadEdge e) {
        return e.highway() != null && Sidewalks.SIDE_BY_RULE.contains(e.highway());
    }
}
