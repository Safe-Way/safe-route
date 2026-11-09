package com.saferoute.transit;

import java.util.List;

import com.saferoute.route.api.RouteRequest;

/**
 * POST /api/transit 요청. /api/route 와 같은 모양에 via(들를 곳)만 더 있다 (11-09 결정 ②).
 *
 * @param via 들를 곳 [{lat, lon, name}]. 대중교통은 한 곳까지 (경유지 하나마다 ODsay 를 한 번 더 써서, 하루 30번).
 *            없으면 예전과 같음
 */
public record TransitRequest(RouteRequest.Place origin, RouteRequest.Place destination, String night, String mode,
                             String guestId, List<RouteRequest.Place> via) {

    RouteRequest route() {
        return new RouteRequest(origin, destination, night, mode, guestId);
    }
}
