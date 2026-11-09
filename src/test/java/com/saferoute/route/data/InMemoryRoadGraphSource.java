package com.saferoute.route.data;

import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 미리 만들어 둔 그래프 하나를 그대로 주는 도로망. 테스트용이고,
 * 나중에 시연 지역을 서버가 켜질 때 메모리에 올려 둘 때도 쓸 수 있다 (요구사항 4.3 #2).
 */
public class InMemoryRoadGraphSource implements RoadGraphSource {

    private final RoadGraph graph;
    private final BBox area;

    public InMemoryRoadGraphSource(RoadGraph graph) {
        this.graph = graph;
        this.area = BBox.around(graph.nodes().values().stream().toList(), 0);
    }

    @Override
    public boolean covers(double lon, double lat) {
        return area.contains(lon, lat);
    }

    /** 요청마다 출발·도착 자리에서 길을 나누므로 복사본을 준다 */
    @Override
    public RoadGraph load(BBox box) {
        return new RoadGraph(graph.nodes(), new java.util.ArrayList<>(graph.graph().edgeSet()));
    }
}
