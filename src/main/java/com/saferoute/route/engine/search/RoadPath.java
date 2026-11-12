package com.saferoute.route.engine.search;

import java.util.ArrayList;
import java.util.List;

import org.jgrapht.GraphPath;

import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Crossings;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 찾은 경로 하나.
 *
 * @param edges   지나는 길 순서대로
 * @param line    경로 선. 점은 {경도, 위도}, 출발 노드에서 도착 노드 순서
 * @param crosses 큰길 갈래를 건넌 곳 ({@link Crossings#isConnector}). 원래 길로 되돌리면 잃는 값이라 탐색 결과에서 담아 둔다 (specs/013)
 */
public record RoadPath(List<RoadEdge> edges, List<double[]> line, double lengthM, List<Cross> crosses) {

    /**
     * 큰길 건넌 곳 하나.
     *
     * @param atM      경로 첫 점부터 그 노드까지(m)
     * @param node     건넌 노드 (나누기 전 번호)
     * @param arm      건넌 큰길 갈래의 원래 길 번호 ({@link Crossings#base}). 횡단보도 자리를 그 갈래 위에서만 찾는다
     * @param fromSide 바로 앞 길이 큰길이면 걸은 쪽 (걷는 방향 기준 left·right, {@link Crossings#walkSide}), 아니면 null
     * @param toSide   바로 뒤 길이 큰길이면 걸은 쪽, 아니면 null
     */
    public record Cross(double atM, long node, long arm, String fromSide, String toSide) {
    }

    /** 건넌 곳을 모르는 경로 (나누지 않은 도로망·시험) */
    public RoadPath(List<RoadEdge> edges, List<double[]> line, double lengthM) {
        this(edges, line, lengthM, List.of());
    }

    /**
     * JGraphT 경로를 걸어가는 방향으로 이어 붙인 선으로 바꾼다. 길은 원래 길로 ({@link RoadGraph#original}).
     * 칸을 잇는 길은 경로에서 빼고 건넌 곳({@link #crosses})으로 남긴다. 걷는 큰길이 아닌 가로 갈래를 건넌 것은 안 남긴다
     * ({@link Crossings#crossesSideArm}). 앞뒤 길은 잇는 길을 건너뛴 가장 가까운 길 (교차로 대각선은 잇는 길 둘)
     */
    static RoadPath of(GraphPath<Long, RoadEdge> path, RoadGraph graph) {
        List<RoadEdge> all = path.getEdgeList();
        boolean[] fwd = new boolean[all.size()];
        long at = path.getStartVertex();
        for (int i = 0; i < all.size(); i++) {
            fwd[i] = all.get(i).from() == at;
            at = fwd[i] ? all.get(i).to() : all.get(i).from();
        }
        java.util.function.IntFunction<String> side = i -> i < 0 || i >= all.size() ? null
                : Crossings.walkSide(all.get(i), graph.original(all.get(i)), fwd[i]);
        List<double[]> line = new ArrayList<>();
        line.add(graph.point(path.getStartVertex()));
        double length = 0;
        List<RoadEdge> edges = new ArrayList<>();
        List<Cross> crosses = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            RoadEdge e = all.get(i);
            if (Crossings.isConnector(e)) {
                int b = skip(all, i, -1);
                int a = skip(all, i, 1);
                RoadEdge before = b < 0 ? null : graph.original(all.get(b));
                RoadEdge after = a >= all.size() ? null : graph.original(all.get(a));
                if (!Crossings.crossesSideArm(e, before, after)) {
                    crosses.add(new Cross(length, Crossings.base(e.from()), Crossings.base(e.id()), side.apply(b),
                            side.apply(a)));
                }
                continue;
            }
            List<double[]> g = e.geometry();
            for (int k = 1; k < g.size(); k++) {
                line.add(fwd[i] ? g.get(k) : g.get(g.size() - 1 - k));
            }
            length += e.lengthM();
            edges.add(graph.original(e));
        }
        return new RoadPath(java.util.Collections.unmodifiableList(edges), line, length,
                java.util.Collections.unmodifiableList(crosses));
    }

    /** i에서 d(±1) 방향으로 잇는 길이 아닌 첫 길의 번호. 없으면 범위 밖 */
    private static int skip(List<RoadEdge> all, int i, int d) {
        int k = i + d;
        while (k >= 0 && k < all.size() && Crossings.isConnector(all.get(k))) {
            k += d;
        }
        return k;
    }

    /**
     * 들를 곳 구간들을 차례로 이은 경로 (2026-11-10). 앞 구간의 끝 노드가 다음 구간의 첫 노드라 그 점은 한 번만 넣는다
     */
    public static RoadPath join(List<RoadPath> parts) {
        List<RoadEdge> edges = new ArrayList<>();
        List<Cross> crosses = new ArrayList<>();
        List<double[]> line = new ArrayList<>(parts.get(0).line());
        double length = 0;
        for (int i = 0; i < parts.size(); i++) {
            RoadPath p = parts.get(i);
            edges.addAll(p.edges());
            for (Cross c : p.crosses()) {
                crosses.add(new Cross(length + c.atM(), c.node(), c.arm(), c.fromSide(), c.toSide()));
            }
            if (i > 0) {
                line.addAll(p.line().subList(1, p.line().size()));
            }
            length += p.lengthM();
        }
        return new RoadPath(List.copyOf(edges), line, length, List.copyOf(crosses));
    }

    /**
     * 길마다 from → to 방향으로 지나는지. 첫 길은 경로 선의 첫 점이 그 길 선의 처음과 끝 중 어디에 가까운지로 정하고,
     * 그다음부터는 앞 길에서 나온 노드로 정한다 (어두운 구간·꺾는 곳·인도 쪽 계산이 같이 쓴다)
     */
    public boolean[] forward() {
        boolean[] out = new boolean[edges.size()];
        if (edges.isEmpty()) {
            return out;
        }
        RoadEdge first = edges.get(0);
        List<double[]> g = first.geometry();
        long at = Geo.distance(line.get(0), g.get(0)) <= Geo.distance(line.get(0), g.get(g.size() - 1))
                ? first.from() : first.to();
        for (int i = 0; i < edges.size(); i++) {
            RoadEdge e = edges.get(i);
            out[i] = e.from() == at;
            at = out[i] ? e.to() : e.from();
        }
        return out;
    }

    public boolean sameAs(RoadPath other) {
        return edges.equals(other.edges);
    }

    /** lineFrom(start, …)에서 line의 첫 점이 몇 번째인지. 출발 점을 앞에 붙이면 1 */
    public int startOffset(double[] start) {
        return Geo.distance(start, line.get(0)) > 0 ? 1 : 0;
    }

    /** 요청한 출발·도착 점을 길 위 노드 앞뒤에 붙인 선. 노드와 같은 점이면 한 번만 */
    public List<double[]> lineFrom(double[] start, double[] end) {
        List<double[]> out = new ArrayList<>(line.size() + 2);
        if (startOffset(start) == 1) {
            out.add(start);
        }
        out.addAll(line);
        if (Geo.distance(end, line.get(line.size() - 1)) > 0) {
            out.add(end);
        }
        return out;
    }

    /**
     * 지상 조각 선들: {@link #lineFrom}의 선을 지하 길에서 끊은 것 (2026-11-09). 지나는 시설을 셀 때 쓴다
     * (지하를 걷는 사람은 바로 위 지상 시설을 지나지 않는다). 점 하나뿐인 조각은 버린다. 지하 길이 없으면 lineFrom 하나
     */
    public List<List<double[]>> surfaceLines(double[] start, double[] end) {
        List<List<double[]>> out = new ArrayList<>();
        List<double[]> cur = new ArrayList<>();
        if (startOffset(start) == 1) {
            cur.add(start);
        }
        cur.add(line.get(0));
        boolean[] fwd = forward();
        for (int i = 0; i < edges.size(); i++) {
            List<double[]> g = edges.get(i).geometry();
            if (RouteFinder.isUnderground(edges.get(i))) {
                if (cur.size() > 1) {
                    out.add(cur);
                }
                cur = new ArrayList<>();
                cur.add(fwd[i] ? g.get(g.size() - 1) : g.get(0));
                continue;
            }
            for (int k = 1; k < g.size(); k++) {
                cur.add(fwd[i] ? g.get(k) : g.get(g.size() - 1 - k));
            }
        }
        if (Geo.distance(end, line.get(line.size() - 1)) > 0) {
            cur.add(end);
        }
        if (cur.size() > 1) {
            out.add(cur);
        }
        return out;
    }

    /** 이 경로에서 조건에 맞는 구간 길이 합(m) */
    public double lengthM(java.util.function.Predicate<RoadEdge> which) {
        return edges.stream().filter(which).mapToDouble(RoadEdge::lengthM).sum();
    }
}
