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

    public boolean sameAs(RoadPath other) {
        return edges.equals(other.edges);
    }

    /** 요청한 출발·도착 점을 길 위 노드 앞뒤에 붙인 선. 노드와 같은 점이면 한 번만 */
    public List<double[]> lineFrom(double[] start, double[] end) {
        List<double[]> out = new ArrayList<>(line.size() + 2);
        if (Geo.distance(start, line.get(0)) > 0) {
            out.add(start);
        }
        out.addAll(line);
        if (Geo.distance(end, line.get(line.size() - 1)) > 0) {
            out.add(end);
        }
        return out;
    }
}
