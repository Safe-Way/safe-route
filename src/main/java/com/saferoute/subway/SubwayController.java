package com.saferoute.subway;

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
 * 내 주변 지하철역 창구 (2차). GET /api/subway/nearby?lat=35.1464&lon=126.9205
 * 반경 1km 안 역(가까운 순 최대 3곳), 역마다 엘리베이터·휠체어 리프트·장애인 화장실 수와
 * 방향마다 다음 열차 2대(몇 분 뒤, 지금 어느 역 근처)를 시간표로 계산해 돌려준다.
 */
@RestController
public class SubwayController {

    private static final Logger log = LoggerFactory.getLogger(SubwayController.class);

    private final SubwayService subway;

    public SubwayController(SubwayService subway) {
        this.subway = subway;
    }

    @GetMapping("/api/subway/nearby")
    public ResponseEntity<?> nearby(@RequestParam(required = false) Double lat,
                                    @RequestParam(required = false) Double lon) {
        if (lat == null || lon == null || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            return ResponseEntity.badRequest().body(new ErrorResponse("BAD_PARAM", "lat 과 lon 을 함께 보내야 합니다"));
        }
        try {
            return ResponseEntity.ok(subway.nearby(lat, lon));
        } catch (DataAccessException e) {
            log.warn("지하철 조회 중 DB 오류: {}", e.getMostSpecificCause().getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("DB_UNAVAILABLE", "잠시 후 다시 시도해 주세요"));
        }
    }
}
