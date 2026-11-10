package com.saferoute.route.engine.search;

import java.util.ArrayList;
import java.util.List;

import org.jgrapht.GraphPath;

import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 찾은 경로 하나.
 *
 * @param edges 지나는 길 순서대로
 * @param line  경로 선. 점은 {경도, 위도}, 출발 노드에서 도착 노드 순서
 */
public record RoadPath(List<RoadEdge> edges, List<double[]> line, double lengthM) {

    /** JGraphT 경로를 걸어가는 방향으로 이어 붙인 선으로 바꾼다 */
    static RoadPath of(GraphPath<Long, RoadEdge> path, RoadGraph graph) {
        List<double[]> line = new ArrayList<>();
        long at = path.getStartVertex();
        line.add(graph.point(at));
        double length = 0;
        for (RoadEdge e : path.getEdgeList()) {
            List<double[]> g = e.geometry();
            boolean forward = e.from() == at;
            for (int i = 1; i < g.size(); i++) {
                line.add(forward ? g.get(i) : g.get(g.size() - 1 - i));
            }
            at = forward ? e.to() : e.from();
            length += e.lengthM();
        }
        return new RoadPath(List.copyOf(path.getEdgeList()), line, length);
    }

    /**
     * 들를 곳 구간들을 차례로 이은 경로 (2026-11-10). 앞 구간의 끝 노드가 다음 구간의 첫 노드라 그 점은 한 번만 넣는다
     */
    public static RoadPath join(List<RoadPath> parts) {
        List<RoadEdge> edges = new ArrayList<>();
        List<double[]> line = new ArrayList<>(parts.get(0).line());
        double length = 0;
        for (int i = 0; i < parts.size(); i++) {
            RoadPath p = parts.get(i);
            edges.addAll(p.edges());
            if (i > 0) {
                line.addAll(p.line().subList(1, p.line().size()));
            }
            length += p.lengthM();
        }
        return new RoadPath(List.copyOf(edges), line, length);
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
