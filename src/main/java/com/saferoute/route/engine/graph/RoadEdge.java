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
     * 길 하나의 성질 (road_edge·edge_stat에서 읽은 계단·확인된 턱·못 지나는 육교).
     *
     * @param values  성질별 값. 1 = 확실히 해당, 없거나 모르면 0
     * @param dataGap 그 지역에 시설 데이터가 부족한 길인지. 감점에는 쓰지 않고 "확인 안 됨" 표시에만 쓴다 (NFR-04)
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
