package com.saferoute.bus;

import java.util.List;

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
 * 버스 노선도 창구 (11-13). GET /api/bus/lines/stops?number=03
 * 번호가 똑같은 노선(없으면 이름 끝이 같은 첫 노선)의 기점 → 종점 정류장을 순서대로. 지도에 선으로 그릴 때 쓴다.
 * 정류장 파일(line_stops.json)이 없거나 그 노선이 없으면 stops 는 빈 목록, ready=false.
 */
@RestController
public class BusLineController {

    private static final Logger log = LoggerFactory.getLogger(BusLineController.class);

    private final BusLineRepository lines;
    private final BusLineStops lineStops;

    public BusLineController(BusLineRepository lines, BusLineStops lineStops) {
        this.lines = lines;
        this.lineStops = lineStops;
    }

    public record Stop(int seq, String name, double lat, double lon) {
    }

    public record Route(int lineId, String name, String from, String to, boolean ready, List<Stop> stops) {
    }

    @GetMapping("/api/bus/lines/stops")
    public ResponseEntity<?> stops(@RequestParam(required = false) String number) {
        String n = number == null ? "" : number.strip();
        if (!n.matches("[가-힣]{0,4}\\d{1,4}")) {
            return ResponseEntity.badRequest().body(new ErrorResponse("BAD_PARAM", "number 에 버스 번호를 보내야 합니다 (예: 03)"));
        }
        try {
            List<BusLine> found = lines.find(n);
            if (found.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("NOT_FOUND", n + "번 버스를 찾지 못했습니다"));
            }
            BusLine l = found.get(0);
            List<Stop> stops = lineStops.route(l.id(), l.to()).stream()
                    .map(s -> new Stop(s.seq(), s.name(), s.lat(), s.lon())).toList();
            return ResponseEntity.ok(new Route(l.id(), l.name(), l.from(), l.to(), !stops.isEmpty(), stops));
        } catch (DataAccessException e) {
            log.warn("노선도 조회 중 DB 오류: {}", e.getMostSpecificCause().getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("DB_UNAVAILABLE", "잠시 후 다시 시도해 주세요"));
        }
    }
}
