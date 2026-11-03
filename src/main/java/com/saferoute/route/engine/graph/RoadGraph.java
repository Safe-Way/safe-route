package com.saferoute.route.engine.graph;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.Set;

import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.WeightedPseudograph;

import com.saferoute.route.engine.geo.Geo;

/** 범위 안의 보행 도로망. 노드는 OSM 번호, 길은 {@link RoadEdge} */
public final class RoadGraph {

    /** 가장 가까운 노드와 그 거리 */
    public record Snap(long node, double distanceM) {
    }

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
     * 가장 가까운 노드. 가장 큰 연결 덩어리 안에서만 고른다.
     * 도로망에는 다른 길과 이어지지 않은 조각(지하광장 계단 등)이 있어, 그 조각에 붙으면 경로를 못 찾는다.
     */
    public Snap nearest(double lon, double lat) {
        if (main == null) {
            main = new ConnectivityInspector<>(graph).connectedSets().stream()
                    .max(java.util.Comparator.comparingInt(Set::size)).orElse(Set.of());
        }
        long best = -1;
        double bestM = Double.MAX_VALUE;
        for (Map.Entry<Long, double[]> n : nodes.entrySet()) {
            double m = Geo.distance(lon, lat, n.getValue()[0], n.getValue()[1]);
            if (m < bestM && main.contains(n.getKey())) {
                best = n.getKey();
                bestM = m;
            }
        }
        return best < 0 ? null : new Snap(best, bestM);
    }
}
