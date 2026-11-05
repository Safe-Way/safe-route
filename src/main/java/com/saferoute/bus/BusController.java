package com.saferoute.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.saferoute.common.ErrorResponse;

/**
 * 내 주변 버스 창구 (2차, 저상버스). GET /api/bus/nearby?lat=35.1401&lon=126.9336
 * 반경 500m 안 정류장(가까운 순 최대 5곳)과 정류장마다 곧 오는 버스(몇 분 뒤, 몇 정거장 전, 저상인지)를 돌려준다.
 * 근처에 정류장이 없으면 빈 목록. 도착 정보를 못 받은 정류장은 available=false.
 * 노선 버스 위치 창구 GET /api/bus/locations?lineId=2 도 여기 있다.
 */
@RestController
public class BusController {

    private static final Logger log = LoggerFactory.getLogger(BusController.class);

    private final BusService bus;

    public BusController(BusService bus) {
        this.bus = bus;
    }

    @GetMapping("/api/bus/nearby")
    public ResponseEntity<?> nearby(@RequestParam(required = false) Double lat,
                                    @RequestParam(required = false) Double lon) {
        if (lat == null || lon == null || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            return ResponseEntity.badRequest().body(new ErrorResponse("BAD_PARAM", "lat 과 lon 을 함께 보내야 합니다"));
        }
        try {
            return ResponseEntity.ok(bus.nearby(lat, lon));
        } catch (DataAccessException e) {
            log.warn("버스 정류장 조회 중 DB 오류: {}", e.getMostSpecificCause().getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("DB_UNAVAILABLE", "잠시 후 다시 시도해 주세요"));
        }
    }

    /**
     * 노선 하나의 버스들이 지금 어디 있는지. GET /api/bus/locations?lineId=2
     * lineId는 /api/bus/nearby 의 arrivals[].lineId 를 그대로 쓰면 된다.
     */
    @GetMapping("/api/bus/locations")
    public ResponseEntity<?> locations(@RequestParam(required = false) Integer lineId) {
        if (lineId == null || lineId < 1) {
            return ResponseEntity.badRequest().body(new ErrorResponse("BAD_PARAM", "lineId 를 보내야 합니다"));
        }
        try {
            return ResponseEntity.ok(bus.locations(lineId));
        } catch (DataAccessException e) {
            log.warn("버스 위치 정류장 조회 중 DB 오류: {}", e.getMostSpecificCause().getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("DB_UNAVAILABLE", "잠시 후 다시 시도해 주세요"));
        }
    }
}
