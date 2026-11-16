package com.saferoute.route.data;

import java.util.List;
import java.util.Map;

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

    /**
     * 지하 시설 점 번호 → 이름 (road_node_excluded reason = underground 중 출구가 아닌 점: "금남지하상가" 통로 점, "문화전당역 대합실").
     * 도착지 이름이 지하 시설이면 들어가는 출구까지 안내할 때 쓴다 (2026-11-16)
     */
    default Map<Long, String> undergroundPlaces() {
        return Map.of();
    }

    /** 범위 안 횡단보도 점 {경도, 위도}. 응답 crossAt의 큰 길 건너는 자리에 쓴다 (specs/014) */
    default List<double[]> crosswalkPoints(BBox box) {
        return List.of();
    }
}
