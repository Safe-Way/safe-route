package com.saferoute.route.data;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 도로망 표가 아직 없을 때 쓰는 자리. 모든 요청에 OUT_OF_AREA를 준다.
 * route.road-source가 none일 때 쓴다 (route.yml 기본은 mysql). DB 없이 서버만 띄울 때 --route.road-source=none
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
