package com.saferoute.route.api;

import java.util.concurrent.CompletableFuture;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.saferoute.common.ErrorResponse;
import com.saferoute.route.RouteService;
import com.saferoute.situation.WeatherDust;
import com.saferoute.situation.WeatherDustService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 길찾기 창구 (요구사항 8.3). POST /api/route
 * 앱이 보낸 출발·도착·밤·모드를 B파트 경로 엔진(RouteService)에 넘기고, 결과를 JSON으로 돌려준다.
 * 경로를 못 주면 B가 정한 상태 번호와 문장을 {code, message}로 바꿔 돌려준다.
 * 응답의 conditions에는 출발점의 날씨·미세먼지 판단(C파트)을 더한다. 화면 안내용이고 길 계산은 바꾸지 않는다.
 * 사용자 위치를 남기지 않도록 출발·도착 좌표는 로그에 쓰지 않는다 (NFR-07).
 */
@RestController
public class RouteController {

    private final RouteService routeService;
    private final WeatherDustService weatherDust;
    private final JsonMapper json;

    public RouteController(RouteService routeService, WeatherDustService weatherDust, JsonMapper json) {
        this.routeService = routeService;
        this.weatherDust = weatherDust;
        this.json = json;
    }

    @PostMapping("/api/route")
    public ResponseEntity<?> route(@RequestBody RouteRequest request) {
        // 날씨·미세먼지는 길 계산과 동시에 묻기 시작한다 (기다리는 시간을 줄이려고)
        CompletableFuture<WeatherDust> wd = startWeatherDust(request);
        try {
            RouteResult result = routeService.route(request);
            return ResponseEntity.ok(withWeatherDust(result, weatherDust.await(wd)));
        } catch (RouteException e) {
            RouteError error = e.error();
            return ResponseEntity.status(error.httpStatus())
                    .body(new ErrorResponse(error.name(), e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("BAD_PARAM", e.getMessage()));
        }
    }

    /** 출발 좌표가 있을 때만 묻는다. 없으면 엔진이 400을 낼 것이라 묻지 않는다 */
    private CompletableFuture<WeatherDust> startWeatherDust(RouteRequest request) {
        RouteRequest.Place o = request == null ? null : request.origin();
        if (o == null || o.lat() == null || o.lon() == null) {
            return null;
        }
        return weatherDust.start(o.lat(), o.lon());
    }

    /** B의 응답은 그대로 두고, conditions 칸에 날씨·미세먼지 값만 더한다 */
    JsonNode withWeatherDust(RouteResult result, WeatherDust w) {
        ObjectNode root = (ObjectNode) json.valueToTree(result);
        JsonNode c = root.get("conditions");
        ObjectNode cond = c instanceof ObjectNode o ? o : root.putObject("conditions");
        cond.put("rain", w.rain());
        cond.put("heat", w.heat());
        cond.put("cold", w.cold());
        if (w.tempC() == null) {
            cond.putNull("tempC");
        } else {
            cond.put("tempC", w.tempC());
        }
        cond.put("weatherAvailable", w.weatherAvailable());
        cond.put("dustBad", w.dustBad());
        cond.put("dustAvailable", w.dustAvailable());
        return root;
    }
}
