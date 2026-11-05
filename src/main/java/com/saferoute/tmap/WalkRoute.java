package com.saferoute.tmap;

import java.util.List;

/**
 * TMAP 이 알려 준 보통 도보 경로 (우리 경로와 비교용, FR-13).
 * TMAP 약관상 결과는 24시간 넘게 저장하면 안 되므로 DB 에 넣지 않는다.
 *
 * @param distanceM 전체 거리 (m)
 * @param durationS 걸리는 시간 (초)
 * @param path      경로 좌표, 출발부터 도착까지 순서대로
 */
public record WalkRoute(int distanceM, int durationS, List<Coord> path) {

    public record Coord(double lat, double lon) {
    }
}
