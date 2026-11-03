package com.saferoute.route.data;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 도로망 표가 아직 없을 때 쓰는 자리. 모든 요청에 OUT_OF_AREA를 준다.
 * route.yml의 route.road-source가 none(기본)일 때 쓴다. A파트 도로망 표가 들어오면 mysql로 바꾼다.
 */
@Component
@ConditionalOnProperty(name = "route.road-source", havingValue = "none", matchIfMissing = true)
public class NoRoadGraphSource implements RoadGraphSource {

    @Override
    public boolean covers(double lon, double lat) {
        return false;
    }

    @Override
    public RoadGraph load(BBox box) {
        throw new IllegalStateException("도로망이 아직 적재되지 않았다");
    }
}
