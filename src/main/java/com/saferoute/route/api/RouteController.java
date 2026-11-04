package com.saferoute.route.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.saferoute.common.ErrorResponse;
import com.saferoute.route.RouteService;

/**
 * 길찾기 창구 (요구사항 8.3). POST /api/route
 * 앱이 보낸 출발·도착·밤·모드를 B파트 경로 엔진(RouteService)에 넘기고, 결과를 그대로 JSON으로 돌려준다.
 * 경로를 못 주면 B가 정한 상태 번호와 문장을 {code, message}로 바꿔 돌려준다.
 * 사용자 위치를 남기지 않도록 출발·도착 좌표는 로그에 쓰지 않는다 (NFR-07).
 */
@RestController
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @PostMapping("/api/route")
    public ResponseEntity<?> route(@RequestBody RouteRequest request) {
        try {
            return ResponseEntity.ok(routeService.route(request));
        } catch (RouteException e) {
            RouteError error = e.error();
            return ResponseEntity.status(error.httpStatus())
                    .body(new ErrorResponse(error.name(), e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("BAD_PARAM", e.getMessage()));
        }
    }
}
