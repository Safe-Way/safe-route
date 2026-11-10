package com.saferoute.route.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.graph.UndergroundLinks;

/**
 * 미리 만들어 둔 그래프 하나를 그대로 주는 도로망. 테스트용이고,
 * 나중에 시연 지역을 서버가 켜질 때 메모리에 올려 둘 때도 쓸 수 있다 (요구사항 4.3 #2).
 */
public class InMemoryRoadGraphSource implements RoadGraphSource {

    private final RoadGraph graph;
    private final List<UndergroundLinks.Exit> exits;
    private final BBox area;

    public InMemoryRoadGraphSource(RoadGraph graph) {
        this(graph, List.of());
    }

    /** exits: 지하 길의 역 출구. 비 올 때 붙일 지상 길에 잇는다 */
    public InMemoryRoadGraphSource(RoadGraph graph, List<UndergroundLinks.Exit> exits) {
        this.graph = graph;
        this.exits = exits;
        this.area = BBox.around(graph.nodes().values().stream().toList(), 0);
    }

    @Override
    public boolean covers(double lon, double lat) {
        return area.contains(lon, lat);
    }

    @Override
    public RoadGraph load(BBox box) {
        return load(box, false);
    }

    /** 요청마다 출발·도착 자리에서 길을 나누므로 복사본을 준다. 지하 길 규칙은 MysqlRoadGraphSource와 같다 */
    @Override
    public RoadGraph load(BBox box, boolean underground) {
        List<RoadEdge> edges = new ArrayList<>(graph.graph().edgeSet());
        if (!underground) {
            edges.removeIf(e -> e.penalty().get(Penalty.UNDERGROUND) >= 1);
            return new RoadGraph(graph.nodes(), edges);
        }
        edges = UndergroundLinks.attach(edges, exits);
        Map<Long, double[]> nodes = new HashMap<>(graph.nodes());
        for (RoadEdge e : edges) {
            nodes.putIfAbsent(e.from(), e.geometry().get(0));
            nodes.putIfAbsent(e.to(), e.geometry().get(e.geometry().size() - 1));
        }
        return new RoadGraph(nodes, edges);
    }
}
