package com.saferoute.transit;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.saferoute.route.api.RouteRequest;

/**
 * POST /api/transit 요청. /api/route 와 같은 모양에 stops(들를 곳)만 더 있다 (11-09 결정 ②, 앱 feat/route-stops 와 같은 칸).
 *
 * @param stops 들를 곳 [{name, lat, lon}] 들를 차례대로. 대중교통은 한 곳까지 (들를 곳 하나마다 ODsay 를 한 번 더 써서, 하루 30번).
 *              없으면 예전과 같음. 예전 이름 via 도 받는다
 */
public record TransitRequest(RouteRequest.Place origin, RouteRequest.Place destination, String night, String mode,
                             String guestId, @JsonAlias("via") List<RouteRequest.Place> stops) {

    RouteRequest route() {
        return new RouteRequest(origin, destination, night, mode, guestId);
    }
}
