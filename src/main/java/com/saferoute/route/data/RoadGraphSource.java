package com.saferoute.route.data;

import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 도로망을 읽는 곳. A파트의 road_node·road_edge·edge_penalty 표가 생기면 MySQL 구현을 붙인다.
 */
public interface RoadGraphSource {

    /** 이 점이 도로망을 넣은 지역 안인지. 아니면 OUT_OF_AREA */
    boolean covers(double lon, double lat);

    /** 범위 안의 길과 감점 */
    RoadGraph load(BBox box);
}
