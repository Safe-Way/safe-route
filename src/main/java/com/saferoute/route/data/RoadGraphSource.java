package com.saferoute.route.data;

import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 도로망을 읽는 곳. 서버는 A파트의 road_node·road_edge·edge_stat 표를 읽는다 ({@link MysqlRoadGraphSource}).
 */
public interface RoadGraphSource {

    /** 이 점이 도로망을 넣은 지역 안인지. 아니면 OUT_OF_AREA */
    boolean covers(double lon, double lat);

    /** 범위 안의 길과 감점 */
    RoadGraph load(BBox box);

    /** underground: 지하 길(역 출구·대합실·지하상가)도 넣고 출구를 지상 길에 잇는지. 비 올 때 운행 시간 안에만 true (2026-11-09) */
    default RoadGraph load(BBox box, boolean underground) {
        return load(box);
    }
}
