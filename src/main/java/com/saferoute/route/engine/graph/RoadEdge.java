package com.saferoute.route.engine.graph;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 두 점 사이의 길 하나 (road_edge 표 한 줄). 걷는 길이라 방향은 없다.
 *
 * @param geometry from에서 to 순서의 선. 점은 {경도, 위도}
 * @param sidewalk 차도 옆 인도가 어느 쪽에 있는지 (from에서 to로 볼 때): left, right, both, no. 모르면 null
 * @param name     road_edge.name. 지하 길 "문화전당역 3번 출구 계단"·"금남지하상가"를 설명에 쓴다. 모르면 null
 * @param lanes    차로 수 (tags.lanes). 모르면 null
 * @param oneway   차가 다니는 방향 (tags.oneway): 1 그린 방향, -1 반대 방향, 0 양방, 태그 없으면 null. 사람은 어느 쪽으로든 걷는다
 * @param widthM   차도 폭(m, tags.width). 모르면 null
 */
public record RoadEdge(long id, long from, long to, double lengthM, String highway,
        List<double[]> geometry, EdgePenalty penalty, String sidewalk, String name, Integer lanes, Integer oneway,
        Double widthM) {

    public RoadEdge(long id, long from, long to, double lengthM, String highway,
            List<double[]> geometry, EdgePenalty penalty, String sidewalk, String name) {
        this(id, from, to, lengthM, highway, geometry, penalty, sidewalk, name, null, null, null);
    }

    public RoadEdge(long id, long from, long to, double lengthM, String highway,
            List<double[]> geometry, EdgePenalty penalty, String sidewalk) {
        this(id, from, to, lengthM, highway, geometry, penalty, sidewalk, null);
    }

    public RoadEdge(long id, long from, long to, double lengthM, String highway,
            List<double[]> geometry, EdgePenalty penalty) {
        this(id, from, to, lengthM, highway, geometry, penalty, null);
    }

    /** 그래프 안에서 길을 id로만 구별한다 (선 좌표까지 비교하지 않도록) */
    @Override
    public boolean equals(Object o) {
        return o instanceof RoadEdge other && other.id == id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    /**
     * 길 하나의 성질 (road_edge·edge_stat에서 읽은 계단·확인된 턱·못 지나는 육교).
     *
     * @param values         성질별 값. 1 = 확실히 해당, 없거나 모르면 0
     * @param wheelchairRamp 계단의 휠체어 경사로 (road_edge.wheelchair_ramp). true 있음, false 없음, null 모름
     */
    public record EdgePenalty(Map<Penalty, Double> values, Boolean wheelchairRamp) {

        public static final EdgePenalty NONE = new EdgePenalty(Map.of());

        public EdgePenalty(Map<Penalty, Double> values) {
            this(values, null);
        }

        /** 휠체어 경사로가 있다고 확인된 계단 */
        public boolean hasRamp() {
            return Boolean.TRUE.equals(wheelchairRamp);
        }

        public EdgePenalty {
            EnumMap<Penalty, Double> copy = new EnumMap<>(Penalty.class);
            copy.putAll(values);
            values = Collections.unmodifiableMap(copy);
        }

        public double get(Penalty p) {
            return values.getOrDefault(p, 0.0);
        }
    }
}
