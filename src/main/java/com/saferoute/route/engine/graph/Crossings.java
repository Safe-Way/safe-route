package com.saferoute.route.engine.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jgrapht.Graph;

import com.saferoute.route.engine.geo.Geo;

/**
 * 큰길은 횡단보도·신호에서만 건너게 하는, 탐색에만 쓰는 두 번째 도로망 (2026-11-11, specs/011).
 * 큰길이 지도에 가운데 선 하나라, 양쪽 골목이 그 선에 붙은 아무 점에서나 건너편으로 넘어갔다.
 * <ul>
 *   <li>큰길 길이 정확히 2개 지나는 노드를 왼쪽(L)·오른쪽(R) 두 노드로 나눈다. 옆 길(큰길 아닌 길)은 붙은 쪽 노드에만 잇는다</li>
 *   <li>큰길 길은 L 줄·R 줄 두 벌로 잇는다. 노드마다 쪽은 "큰길 in → 노드 → 큰길 out" 방향 기준이라, 길을 따라 걸을 때
 *       같은 쪽이 이어지도록 맞춘다 ({@link #label}). 그래서 큰길을 따라 걷다가 반대쪽 골목으로 빠지지 못한다</li>
 *   <li>나누지 않는 노드(= 거기서 건널 수 있음): 노드 {@link #NODE_M} 안이면서 그 노드의 큰길 선 {@link #LINE_M} 안인
 *       횡단보도·신호 점이 있음, 횡단보도 길(footway=crossing)이 붙음, 큰길 길이 1개·3개 이상, 쪽을 못 정함, 자기 루프</li>
 * </ul>
 * 커버율·행사·신고·붙이기·응답 조립은 나누기 전 도로망을 쓴다 (커버율은 등·CCTV를 가장 가까운 길 하나에 붙여, 두 벌에서
 * 계산하면 한쪽이 어두워진다). 복사 길은 {@link RoadGraph#original}로 원래 길이 된다.
 * shortcut: 길 중간 횡단보도(큰길 노드 15m 밖, 광주 12곳)는 건널 곳으로 안 본다. 쓰려면 커버율 계산 전에 그 자리에서 길을 잘라야 한다
 */
public final class Crossings {

    /** 복사 번호 = 원래 + OFFSET(L 쪽·옆 길) 또는 + 2×OFFSET(R 쪽). 음수 임시 번호(−1…, −10억대)를 더해도 OSM 번호(~1e10)와 안 겹친다 */
    static final long OFFSET = 1_000_000_000_000_000L;

    /** 이 거리(m) 안 횡단보도·신호 점을 그 노드에서 건널 수 있는 점으로 본다. OSM 신호등 점은 교차로 노드에서 몇 m 떨어져 찍힌다 */
    public static final double NODE_M = 15;

    /** 점이 그 노드의 큰길 선에서 이보다(m) 멀면 옆 소로 쪽 횡단보도라 큰길 건너기에 안 쓴다 (Fable 검토) */
    static final double LINE_M = 5;

    /** 쪽은 노드에서 길을 따라 이만큼(m) 간 점으로 잰다. 첫 선분이 큰길과 거의 평행하면 다음 꼭짓점 하나로는 쪽이 임의가 된다 */
    static final double ALONG_M = 4;

    /** 큰길 두 길의 그 점 사이가 이보다(m) 짧으면(같은 이웃으로 가는 중복 선·머리핀) 쪽을 못 정해 안 나눈다. 안전한 쪽으로 실패 */
    static final double MIN_TANGENT_M = 1;

    /** 나눈 노드의 큰길 두 길과 그 길을 따라 {@link #ALONG_M} 간 점. 쪽은 a → b 방향 기준 */
    private record Split(RoadEdge in, RoadEdge out, double[] a, double[] b) {
    }

    private final RoadGraph unsplit;
    private final Map<Long, Split> split = new HashMap<>();
    private final RoadGraph graph;

    /** points: 건널 수 있는 곳 점 {경도, 위도} (횡단보도·신호등) */
    public Crossings(RoadGraph unsplit, List<double[]> points) {
        this.unsplit = unsplit;
        Graph<Long, RoadEdge> g = unsplit.graph();
        for (Long n : g.vertexSet()) {
            Split s = split(n, g.edgesOf(n), points);
            if (s != null) {
                split.put(n, s);
            }
        }
        if (split.isEmpty()) {
            graph = unsplit;
            return;
        }
        Map<Long, double[]> nodes = new HashMap<>(unsplit.nodes());
        for (Long n : split.keySet()) {
            double[] p = nodes.remove(n);
            nodes.put(copy(n, true), p);
            nodes.put(copy(n, false), p);
        }
        List<RoadEdge> edges = new ArrayList<>();
        Map<Long, RoadEdge> originals = new HashMap<>();
        for (RoadEdge e : g.edgeSet()) {
            boolean a = split.containsKey(e.from());
            boolean b = split.containsKey(e.to());
            if (!a && !b) {
                edges.add(e);
            } else if (big(e)) {
                // left: from → to로 걸을 때 왼쪽 줄
                for (boolean left : new boolean[] {true, false}) {
                    long u = a ? copy(e.from(), label(e.from(), e, left, true)) : e.from();
                    long v = b ? copy(e.to(), label(e.to(), e, left, false)) : e.to();
                    edges.add(copy(e, left ? e.id() + OFFSET : e.id() + 2 * OFFSET, u, v, originals));
                }
            } else {
                long u = a ? copy(e.from(), left(e.from(), ahead(e, e.from()))) : e.from();
                long v = b ? copy(e.to(), left(e.to(), ahead(e, e.to()))) : e.to();
                edges.add(copy(e, e.id() + OFFSET, u, v, originals));
            }
        }
        graph = new RoadGraph(nodes, edges, originals);
    }

    /** 탐색용 도로망. 나눈 노드가 없으면 나누기 전 도로망 그대로 */
    public RoadGraph graph() {
        return graph;
    }

    /** 출발·도착을 붙인 노드가 나눈 노드면 요청 점(gps)이 있는 쪽 노드, 아니면 그대로 */
    public long place(long node, double[] gps) {
        return split.containsKey(node) ? copy(node, left(node, gps)) : node;
    }

    /** 나눌 노드면 그 큰길 두 길, 아니면 null */
    private Split split(long n, Set<RoadEdge> at, List<double[]> points) {
        List<RoadEdge> big = new ArrayList<>();
        for (RoadEdge e : at) {
            if (e.from() == e.to() || e.penalty().get(Penalty.CROSSWALK) >= 1) {
                return null;
            }
            if (big(e)) {
                big.add(e);
            }
        }
        if (big.size() != 2) {
            return null;
        }
        double[] a = ahead(big.get(0), n);
        double[] b = ahead(big.get(1), n);
        if (Geo.distance(a, b) < MIN_TANGENT_M) {
            return null;
        }
        double[] p = unsplit.point(n);
        for (double[] q : points) {
            // 위도 차이로 먼저 걸러 하버사인 계산을 줄인다 (나눌 후보 노드 × 범위 안 점이라 요청마다 수십만 번)
            if (Math.abs(q[1] - p[1]) * 111_000 <= NODE_M && Geo.distance(p, q) <= NODE_M && (Geo.distanceToLine(q, big.get(0).geometry()) <= LINE_M
                    || Geo.distanceToLine(q, big.get(1).geometry()) <= LINE_M)) {
                return null;
            }
        }
        return new Split(big.get(0), big.get(1), a, b);
    }

    /**
     * 길 e의 from → to로 걸을 때 쪽 left를 노드 n의 쪽으로. leaving: n에서 e로 나감.
     * 걷는 방향이 n의 in → out 방향과 같으면(out으로 나가거나 in으로 들어옴) 그대로, 반대면 뒤집는다
     */
    private boolean label(long n, RoadEdge e, boolean left, boolean leaving) {
        Split s = split.get(n);
        boolean same = leaving ? s.out().equals(e) : s.in().equals(e);
        return same == left;
    }

    /** 점 q가 노드 n의 큰길 in → out 방향 왼쪽인지 (선 위면 오른쪽) */
    private boolean left(long n, double[] q) {
        Split s = split.get(n);
        double[] p = unsplit.point(n);
        double k = Math.cos(Math.toRadians(p[1]));
        double tx = (s.b()[0] - s.a()[0]) * k;
        double ty = s.b()[1] - s.a()[1];
        return tx * (q[1] - p[1]) - ty * (q[0] - p[0]) * k > 0;
    }

    /** 노드 n에서 길 e를 따라 {@link #ALONG_M} 간 점. 길이 더 짧으면 다른 끝 */
    private static double[] ahead(RoadEdge e, long n) {
        List<double[]> g = new ArrayList<>(e.geometry());
        if (e.from() != n) {
            java.util.Collections.reverse(g);
        }
        double walked = 0;
        for (int i = 1; i < g.size(); i++) {
            double seg = Geo.distance(g.get(i - 1), g.get(i));
            if (walked + seg >= ALONG_M) {
                return RoadGraph.lerp(g.get(i - 1), g.get(i), (ALONG_M - walked) / seg);
            }
            walked += seg;
        }
        return g.get(g.size() - 1);
    }

    private static boolean big(RoadEdge e) {
        return e.highway() != null && RoadGraph.BIG_ROADS.contains(e.highway());
    }

    /** 나눈 노드 n의 L(left)·R 쪽 번호 */
    private static long copy(long n, boolean left) {
        return n + (left ? OFFSET : 2 * OFFSET);
    }

    /** 양 끝만 바꾼 복사 길 (선·길이·성질은 그대로) */
    private static RoadEdge copy(RoadEdge e, long id, long from, long to, Map<Long, RoadEdge> originals) {
        RoadEdge c = new RoadEdge(id, from, to, e.lengthM(), e.highway(), e.geometry(), e.penalty(), e.sidewalk(),
                e.name(), e.lanes(), e.oneway(), e.widthM());
        originals.put(id, e);
        return c;
    }
}
