package com.saferoute.route.engine.graph;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 두 점 사이의 길 하나 (road_edge 표 한 줄). 걷는 길이라 방향은 없다.
 *
 * @param geometry from에서 to 순서의 선. 점은 {경도, 위도}
 */
public record RoadEdge(long id, long from, long to, double lengthM, String highway,
        List<double[]> geometry, EdgePenalty penalty) {

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
     * 길 하나의 감점 (edge_penalty 표 한 줄).
     *
     * @param values  감점 종류별 0~1 값. 없는 종류는 0
     * @param dataGap 그 지역에 시설 데이터가 부족해 감점을 중립값(0.5)으로 둔 길인지 (NFR-04)
     */
    public record EdgePenalty(Map<Penalty, Double> values, boolean dataGap) {

        public static final EdgePenalty NONE = new EdgePenalty(Map.of(), false);

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
