package com.saferoute.route.engine.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.Set;
import java.util.function.Predicate;

import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.WeightedPseudograph;

import com.saferoute.route.engine.geo.Geo;

/** 범위 안의 보행 도로망. 노드는 OSM 번호, 길은 {@link RoadEdge} */
public final class RoadGraph {

    /** 출발·도착을 붙인 길 위 노드와 그 거리. 길 중간이면 그 요청에만 있는 임시 노드(음수 번호) */
    public record Snap(long node, double distanceM) {
    }

    /** 수직의 발이 길 끝 노드에서 이보다 가까우면 길을 나누지 않고 그 노드에 붙인다 */
    static final double SPLIT_MIN_M = 1.0;

    /** 점에서 이 거리 안의 길을 모두 붙일 후보로 본다 (2026-11-11, 진행표 w14 요청 값) */
    static final double SNAP_CANDIDATE_M = 30;

    /**
     * 걸어서 아무 데서나 못 건너는 큰 길과 그 연결로. 붙일 때 점과 길 사이에 있으면 건너편 길로 안 붙인다.
     * 인도 쪽 규칙({@code Sidewalks})과 건너는 곳 알리기({@code SideChanges})도 같은 목록을 쓴다 (tertiary 포함, 2026-11-12)
     */
    public static final Set<String> BIG_ROADS = Set.of("trunk", "trunk_link", "primary", "primary_link", "secondary",
            "secondary_link", "tertiary", "tertiary_link");

    /** 길을 나눌 때 새로 만드는 노드·길 번호. OSM 번호(양수)와 겹치지 않게 음수 */
    private long nextVirtual = -1;

    private final Graph<Long, RoadEdge> graph = new WeightedPseudograph<>(RoadEdge.class);
    private final Map<Long, double[]> nodes = new HashMap<>();

    /** @param nodes 노드 번호 → {경도, 위도} */
    public RoadGraph(Map<Long, double[]> nodes, List<RoadEdge> edges) {
        nodes.forEach((id, p) -> {
            this.nodes.put(id, p);
            graph.addVertex(id);
        });
        for (RoadEdge e : edges) {
            // 범위 끝에서 한쪽 노드만 들어온 길은 쓸 수 없으므로 뺀다
            if (this.nodes.containsKey(e.from()) && this.nodes.containsKey(e.to())) {
                graph.addEdge(e.from(), e.to(), e);
            }
        }
    }

    public Graph<Long, RoadEdge> graph() {
        return graph;
    }

    /** {경도, 위도} */
    public double[] point(long node) {
        return nodes.get(node);
    }

    public Map<Long, double[]> nodes() {
        return Collections.unmodifiableMap(nodes);
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** 가장 큰 연결 덩어리의 노드들. 처음 찾을 때 한 번 계산한다 */
    private Set<Long> main;

    /**
     * 가장 가까운 길 위 지점(수직의 발)에 붙인다. 가장 큰 연결 덩어리 안의 길에서만 고른다.
     * 도로망에는 다른 길과 이어지지 않은 조각(지하광장 계단 등)이 있어, 그 조각에 붙으면 경로를 못 찾는다.
     * 길 중간이면 그 길을 둘로 나눠 임시 노드를 넣는다 (예전엔 가장 가까운 노드에 붙여, 도착지를 지나쳐 길 끝까지 갔다
     * 되돌아오는 경로가 됐다. 해오름관 정류장 442m → 392m, 2026-11-09).
     * 큰 길 건너편 길에는 붙이지 않는다 ({@link #nearest(double, double, Set, Predicate)}).
     * usable: 수직의 발을 찾을 길 (휠체어는 지날 수 있는 길만. 계단 중간에 붙으면 휠체어 길이 끊긴다)
     */
    public Snap nearest(double lon, double lat, Predicate<RoadEdge> usable) {
        return nearest(lon, lat, usable, at -> true);
    }

    /** clear: 점에서 그 발까지 곧은 줄로 이어도 되는지 ({@link #nearest(double, double, Set, Predicate, Predicate)}) */
    public Snap nearest(double lon, double lat, Predicate<RoadEdge> usable, Predicate<double[]> clear) {
        if (main == null) {
            main = largest(graph);
        }
        return nearest(lon, lat, main, usable, clear);
    }

    /**
     * 지날 수 있는 길만으로 이은 가장 큰 연결 덩어리. 휠체어는 계단으로만 드나드는 구역(아파트 단지 안 보행로,
     * 계단 사이 층계참 등)이 덩어리 밖이 되어, {@link #nearest(double, double, Set)}가 그곳에 붙지 않는다
     */
    public Set<Long> mainPart(Predicate<RoadEdge> passable) {
        // 길마다 두 끝을 한 묶음으로 합친다 (MaskSubgraph로 연결 덩어리를 구하면 요청마다 0.3초 넘게 걸렸다)
        Map<Long, Long> parent = new HashMap<>();
        for (RoadEdge e : graph.edgeSet()) {
            if (passable.test(e)) {
                long a = root(parent, graph.getEdgeSource(e));
                long b = root(parent, graph.getEdgeTarget(e));
                if (a != b) {
                    parent.put(a, b);
                }
            }
        }
        Map<Long, Set<Long>> parts = new HashMap<>();
        for (Long v : graph.vertexSet()) {
            parts.computeIfAbsent(root(parent, v), k -> new java.util.HashSet<>()).add(v);
        }
        return parts.values().stream().max(java.util.Comparator.comparingInt(Set::size)).orElse(Set.of());
    }

    private static long root(Map<Long, Long> parent, long v) {
        long r = v;
        while (parent.containsKey(r)) {
            r = parent.get(r);
        }
        // 다음에 빨리 찾도록 지나온 노드를 뿌리에 바로 붙인다
        while (parent.containsKey(v) && parent.get(v) != r) {
            long next = parent.get(v);
            parent.put(v, r);
            v = next;
        }
        return r;
    }

    /**
     * 점에서 {@link #SNAP_CANDIDATE_M}(30m) 안의 usable 길(양 끝이 within)을 모두 후보로 보고, 각 길 위 수직의 발 중
     * 가장 가까운 곳에 붙인다 (휠체어는 지날 수 있는 길만). 점에서 발까지 곧은 줄이 큰 길({@link #BIG_ROADS})을
     * 가로지르는 후보는 뺀다. 예전엔 가장 가까운 노드의 길에만 붙여, 필문대로 서쪽 차로에서 11m인 도착점이 노드가 가까운
     * 동쪽 차로에 24m로 붙고 횡단보도 없는 4차로를 곧게 건너는 경로가 됐다 (현장 시험 2026-11-11 ④, 진행표 w14).
     * 30m 안에 남은 후보가 없으면 예전대로 within 안 가장 가까운 노드를 찾고, 그 노드에 이어진 usable 길 위의 발이
     * 더 가까우면 그 길 중간에 붙인다. 노드에 이어진 길만 보는 것은, 직선으로 더 가까운 다른 길(담장 안 단지 길·막다른 길)에
     * 붙어 크게 도는 것을 막으려던 것이다. 없으면 null. 길을 나눠 생긴 새 노드도 within에 넣는다
     */
    public Snap nearest(double lon, double lat, Set<Long> within, Predicate<RoadEdge> usable) {
        return nearest(lon, lat, within, usable, at -> true);
    }

    /**
     * clear가 false인 발(점에서 그 발까지 곧은 줄이 피할 신고·행사 반경을 지남, 2026-11-16)은 후보에서 뺀다. 노드로 찾을 때도 같다.
     * shortcut: 길마다 가장 가까운 발 하나만 보므로 그 발이 막히면 같은 길의 다른 자리는 보지 않는다, 막힌 길에 꼭 붙어야 하는 사례가 나오면 길 위를 따라 찾기
     */
    public Snap nearest(double lon, double lat, Set<Long> within, Predicate<RoadEdge> usable, Predicate<double[]> clear) {
        double[] p = {lon, lat};
        RoadEdge best = null;
        Foot bestFoot = null;
        for (RoadEdge e : graph.edgeSet()) {
            if (!usable.test(e) || !within.contains(e.from()) || !within.contains(e.to())) {
                continue;
            }
            Foot f = closest(p, e);
            if (f.m() <= (bestFoot == null ? SNAP_CANDIDATE_M : bestFoot.m()) && clear.test(f.at(e))
                    && !crossesBigRoad(p, f.at(e), e)) {
                best = e;
                bestFoot = f;
            }
        }
        if (best == null) {
            return nearestByNode(p, within, usable, clear);
        }
        return snapOn(p, best, bestFoot, within);
    }

    /** 30m 안 후보가 없을 때의 예전 방식: 가장 가까운 노드와 그 노드에 이어진 길 */
    private Snap nearestByNode(double[] p, Set<Long> within, Predicate<RoadEdge> usable, Predicate<double[]> clear) {
        Long near = null;
        double nearM = Double.MAX_VALUE;
        for (Map.Entry<Long, double[]> n : nodes.entrySet()) {
            double m = Geo.distance(p, n.getValue());
            if (m < nearM && within.contains(n.getKey()) && clear.test(n.getValue())) {
                near = n.getKey();
                nearM = m;
            }
        }
        if (near == null) {
            return null;
        }
        RoadEdge best = null;
        Foot bestFoot = null;
        for (RoadEdge e : graph.edgesOf(near)) {
            if (!usable.test(e) || !within.contains(e.from()) || !within.contains(e.to())) {
                continue;
            }
            Foot f = closest(p, e);
            if (f.m() < (bestFoot == null ? nearM : bestFoot.m()) && clear.test(f.at(e))) {
                best = e;
                bestFoot = f;
            }
        }
        if (best == null) {
            return new Snap(near, nearM);
        }
        return snapOn(p, best, bestFoot, within);
    }

    /** 길 위에서 점에 가장 가까운 발: geometry의 seg번째 선분(seg-1 → seg) 안 t 위치, 점에서 m(미터) */
    private record Foot(int seg, double t, double m) {
        double[] at(RoadEdge e) {
            return lerp(e.geometry().get(seg - 1), e.geometry().get(seg), t);
        }
    }

    /** 길 e 위에서 점 p에 가장 가까운 발 */
    private static Foot closest(double[] p, RoadEdge e) {
        List<double[]> g = e.geometry();
        Foot best = new Foot(1, 0, Double.MAX_VALUE);
        for (int i = 1; i < g.size(); i++) {
            double t = foot(p, g.get(i - 1), g.get(i));
            double m = Geo.distance(p, lerp(g.get(i - 1), g.get(i), t));
            if (m < best.m()) {
                best = new Foot(i, t, m);
            }
        }
        return best;
    }

    /** 길 e의 발 f에 붙인다 */
    private Snap snapOn(double[] p, RoadEdge e, Foot f, Set<Long> within) {
        long node = split(e, f.seg(), f.t());
        within.add(node);
        // 길 끝 노드에 붙였으면(나누지 않음) 그 노드까지 거리
        return new Snap(node, node < 0 ? f.m() : Geo.distance(p, nodes.get(node)));
    }

    /** 점 p에서 at까지 곧은 줄이 큰 길(at이 놓인 길 skip은 빼고)을 가로지르는지 */
    private boolean crossesBigRoad(double[] p, double[] at, RoadEdge skip) {
        for (RoadEdge e : graph.edgeSet()) {
            if (e.equals(skip) || e.highway() == null || !BIG_ROADS.contains(e.highway())) {
                continue;
            }
            List<double[]> g = e.geometry();
            for (int i = 1; i < g.size(); i++) {
                if (crosses(p, at, g.get(i - 1), g.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 선분 a-b와 c-d가 엄밀히 엇갈리는지. 끝점이 다른 선분 위에 닿기만 하면 아니다 (발이 큰 길과 만나는 교차로 노드에
     * 놓여도 건넘이 아니다). 경도에 cos(위도)를 곱한 평면에서 잰다
     */
    public static boolean crosses(double[] a, double[] b, double[] c, double[] d) {
        double k = Math.cos(Math.toRadians(a[1]));
        return side(a, b, c, k) * side(a, b, d, k) < 0 && side(c, d, a, k) * side(c, d, b, k) < 0;
    }

    /** o→q 줄에서 r이 왼쪽이면 양수, 오른쪽이면 음수, 줄 위면 0 */
    private static double side(double[] o, double[] q, double[] r, double k) {
        double s = (q[0] - o[0]) * k * (r[1] - o[1]) - (q[1] - o[1]) * (r[0] - o[0]) * k;
        // 1e-15도²(11m 선분에서 1µm쯤) 안은 줄 위로 본다. 교차로 노드에 놓인 발의 반올림 오차
        return Math.abs(s) < 1e-15 ? 0 : s;
    }

    /**
     * 길 e를 geometry의 seg번째 선분(seg-1 → seg) 안 t 위치에서 둘로 나누고 그 노드를 돌려준다.
     * 길 끝 노드에서 {@link #SPLIT_MIN_M} 안이면 나누지 않고 그 끝 노드를 돌려준다
     */
    private long split(RoadEdge e, int seg, double t) {
        List<double[]> g = e.geometry();
        double[] at = lerp(g.get(seg - 1), g.get(seg), t);
        List<double[]> head = new ArrayList<>(g.subList(0, seg));
        head.add(at);
        List<double[]> tail = new ArrayList<>();
        tail.add(at);
        tail.addAll(g.subList(seg, g.size()));
        double headM = Geo.length(head);
        double total = headM + Geo.length(tail);
        if (headM < SPLIT_MIN_M) {
            return e.from();
        }
        if (total - headM < SPLIT_MIN_M) {
            return e.to();
        }
        long node = nextVirtual--;
        nodes.put(node, at);
        graph.addVertex(node);
        graph.removeEdge(e);
        // 저장된 길이를 선 길이 비율로 나눈다 (성질·인도 쪽·이름·차로·일방·폭·인도 폭은 그대로)
        double share = total == 0 ? 0.5 : headM / total;
        graph.addEdge(e.from(), node, new RoadEdge(nextVirtual--, e.from(), node, e.lengthM() * share, e.highway(),
                head, e.penalty(), e.sidewalk(), e.name(), e.lanes(), e.oneway(), e.widthM(), e.sidewalkWidthM()));
        graph.addEdge(node, e.to(), new RoadEdge(nextVirtual--, node, e.to(), e.lengthM() * (1 - share), e.highway(),
                tail, e.penalty(), e.sidewalk(), e.name(), e.lanes(), e.oneway(), e.widthM(), e.sidewalkWidthM()));
        return node;
    }

    /** 선분 a-b에 점 p의 수직의 발이 있는 위치 (0~1, 선분 밖이면 가까운 끝) */
    static double foot(double[] p, double[] a, double[] b) {
        double k = Math.cos(Math.toRadians(p[1]));
        double ax = (a[0] - p[0]) * k;
        double ay = a[1] - p[1];
        double dx = (b[0] - a[0]) * k;
        double dy = b[1] - a[1];
        double len2 = dx * dx + dy * dy;
        return len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
    }

    static double[] lerp(double[] a, double[] b, double t) {
        return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
    }

    private static Set<Long> largest(Graph<Long, RoadEdge> g) {
        return new java.util.HashSet<>(new ConnectivityInspector<>(g).connectedSets().stream()
                .max(java.util.Comparator.comparingInt(Set::size)).orElse(Set.of()));
    }
}
