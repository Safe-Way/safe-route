package com.saferoute.route.engine.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.saferoute.route.api.RouteResult;
import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.search.RoadPath;

/**
 * 큰 길을 건너는 곳과 실제로 건너는 횡단보도 자리 (2026-11-12, specs/014, 진행표 w17 1안).
 * 경로 선은 차도 가운데라 앱이 교차로 한가운데에서 쪽을 바꿔 그렸다. 경로는 그대로 두고 자리만 알려 준다.
 * <ul>
 *   <li>건너는 곳: 앞뒤로 붙은 두 큰 길 길에서 걷는 방향 기준 인도 쪽({@link Sidewalks}와 같은 값)이 left ↔ right로 바뀌는 노드.
 *       한쪽이 both면 쪽으로는 알 수 없어, 큰 길 교차로(큰 길 길 3개 이상)에서 {@link #TURN_DEG}보다 크게 꺾을 때만 본다
 *       (곧장 지나면 안 보냄, 민수 요청 ②)</li>
 *   <li>횡단보도: 노드 {@link #NEAR_M} 안이면서 앞·뒤 큰 길 선 {@link #LINE_M} 안인 가장 가까운 횡단보도 점 →
 *       없으면 그 선과 만나거나 닿는 횡단보도 길(footway=crossing)의 가운데({@link #NEAR_M} 안) → 없으면 보내지 않는다(1안)</li>
 * </ul>
 */
public final class SideChanges {

    /** 노드에서 이 거리(m) 안 횡단보도만. 더 멀면 다음 교차로·골목 입구 횡단보도일 수 있어 엉뚱한 곳에서 건너게 그린다 */
    static final double NEAR_M = 30;
    /** 큰 길 선에서 이 거리(m) 안이어야 그 큰 길을 건너는 횡단보도. 옆 골목을 건너는 횡단보도가 잡히면 안 된다 (민수 요청 ③) */
    static final double LINE_M = 5;
    /** 앞뒤 진행 방향이 이보다 크게 바뀌면 꺾은 것 (꺾는 곳 turns와 같은 45°) */
    static final double TURN_DEG = 45;

    private static final Set<String> LEFT_RIGHT = Set.of("left", "right");

    private SideChanges() {
    }

    /**
     * @param startM     요청 출발에서 경로 첫 점까지(m). 기존 crossings·시설 along.atM과 같은 기준
     * @param graph      요청 도로망. 노드의 큰 길 수와 횡단보도 길을 찾는다
     * @param crosswalks 횡단보도 점 {경도, 위도}
     */
    static List<RouteResult.Crossing> along(RoadPath path, double startM, RoadGraph graph, List<double[]> crosswalks) {
        List<RouteResult.Crossing> out = new ArrayList<>();
        List<RoadEdge> edges = path.edges();
        boolean[] forward = path.forward();
        double at = startM;
        for (int i = 0; i + 1 < edges.size(); i++) {
            RoadEdge a = edges.get(i);
            RoadEdge b = edges.get(i + 1);
            at += a.lengthM();
            // 들를 곳에서 같은 길로 되돌아가면 걷는 방향만 바뀌어 쪽이 뒤집힌다. 건넌 것이 아니다
            if (!big(a) || !big(b) || a.id() == b.id()) {
                continue;
            }
            List<double[]> in = forward[i] ? a.geometry() : a.geometry().reversed();
            List<double[]> next = forward[i + 1] ? b.geometry() : b.geometry().reversed();
            String from = Sidewalks.side(Sidewalks.drawnSide(a), forward[i]);
            String to = Sidewalks.side(Sidewalks.drawnSide(b), forward[i + 1]);
            long node = forward[i] ? a.to() : a.from();
            boolean change = LEFT_RIGHT.contains(from) && LEFT_RIGHT.contains(to) ? !from.equals(to)
                    : ("both".equals(from) || "both".equals(to)) && bigJunction(graph, node) && turned(in, next);
            if (!change) {
                continue;
            }
            Spot s = crosswalk(next.get(0), List.of(a.geometry(), b.geometry()), graph, crosswalks);
            if (s != null) {
                int m = (int) Math.round(at);
                out.add(new RouteResult.Crossing(m, m, s.p()[1], s.p()[0], s.kind(), from, to));
            }
        }
        return out;
    }

    /** 찾은 횡단보도 자리 {경도, 위도}와 kind (crossing·footway) */
    private record Spot(double[] p, String kind) {
    }

    /** 노드 p 가까이에서 큰 길 선 lines를 건너는 횡단보도. 없으면 null */
    private static Spot crosswalk(double[] p, List<List<double[]>> lines, RoadGraph graph, List<double[]> crosswalks) {
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
        return lines.stream().anyMatch(l -> Geo.distanceToLine(q, l) <= LINE_M);
    }

    /** 횡단보도 길 g가 큰 길 선과 엇갈리거나 꼭짓점이 선 {@link #LINE_M} 안에 닿는지 */
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

    /** 큰 길 길 3개 이상이 만나는 노드 */
    private static boolean bigJunction(RoadGraph graph, long node) {
        return graph.graph().containsVertex(node)
                && graph.graph().edgesOf(node).stream().filter(SideChanges::big).count() >= 3;
    }

    /** 노드에 들어오는 선 in의 마지막 조각과 나가는 선 next의 첫 조각 방향이 {@link #TURN_DEG}보다 크게 다른지 */
    private static boolean turned(List<double[]> in, List<double[]> next) {
        double d = Turns.heading(next.get(0), next.get(1)) - Turns.heading(in.get(in.size() - 2), in.get(in.size() - 1));
        return Math.abs(Turns.normalize(d)) > TURN_DEG;
    }

    private static boolean big(RoadEdge e) {
        return e.highway() != null && RoadGraph.BIG_ROADS.contains(e.highway());
    }
}
