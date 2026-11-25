package com.saferoute.route;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;

import tools.jackson.databind.json.JsonMapper;

/** 앱이 보내는 요청 JSON의 칸이 버려지지 않는다 (호환 생성자가 여럿이라 확인) */
@DisplayName("요청 JSON 읽기")
class RouteRequestJsonTests {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("앱 요청 JSON의 rain·mode 칸을 읽는다")
    void appJsonCarriesRain() {
        RouteRequest r = JSON.readValue("""
                { "origin": { "lat": 35.147, "lon": 126.919 }, "destination": { "lat": 35.150, "lon": 126.915 },
                  "night": "auto", "mode": "senior", "rain": "on" }
                """, RouteRequest.class);
        assertThat(r.rain()).isEqualTo("on");
        assertThat(r.mode()).isEqualTo("senior");
    }

    @Test
    @DisplayName("앱 요청 JSON의 reroute(길안내 중 다시 찾기) 칸을 읽는다")
    void appJsonCarriesReroute() {
        RouteRequest r = JSON.readValue("""
                { "origin": { "lat": 35.16, "lon": 126.879 }, "destination": { "lat": 35.15, "lon": 126.87 },
                  "mode": "wheelchair", "reroute": true }
                """, RouteRequest.class);
        assertThat(r.reroute()).isTrue();
    }

    @Test
    @DisplayName("요청 JSON의 stops를 읽고 없으면 null로 둔다")
    void requestJsonReadsStops() {
        RouteRequest req = JSON.readValue("""
                {"origin":{"lat":35.1466,"lon":126.9204},"destination":{"lat":35.1467,"lon":126.9198},
                 "stops":[{"name":"다이소","lat":35.1480,"lon":126.9170}]}
                """, RouteRequest.class);

        assertThat(req.stops()).containsExactly(new Place(35.1480, 126.9170, "다이소"));
        assertThat(JSON.readValue("""
                {"origin":{"lat":35.1466,"lon":126.9204},"destination":{"lat":35.1467,"lon":126.9198}}
                """, RouteRequest.class).stops()).isNull();
    }
}
