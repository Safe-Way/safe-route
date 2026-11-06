package com.saferoute.event;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.saferoute.common.ErrorResponse;

/**
 * 지도에 행사·돌발 영향 반경을 그리는 창구 (C 파트). GET /api/events?bbox=서,남,동,북
 * 지금 진행 중인 것만, 300개까지. 답: {"events": [...]}
 */
@RestController
public class EventController {

    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    public record EventsResponse(List<RoadEvent> events) {
    }

    @GetMapping("/api/events")
    public ResponseEntity<?> events(@RequestParam(required = false) String bbox) {
        double[] b = parseBbox(bbox);
        if (b == null) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("BAD_PARAM", "bbox 는 서쪽경도,남쪽위도,동쪽경도,북쪽위도 네 숫자여야 해요"));
        }
        try {
            return ResponseEntity.ok(new EventsResponse(service.within(b[0], b[1], b[2], b[3])));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("BAD_PARAM", e.getMessage()));
        } catch (DataAccessException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("DB_UNAVAILABLE", "잠시 후 다시 시도해 주세요"));
        }
    }

    /** "서,남,동,북" 네 숫자. 모양이 틀리면 null (/api/reports 와 같은 규칙) */
    static double[] parseBbox(String bbox) {
        if (bbox == null) {
            return null;
        }
        String[] p = bbox.split(",");
        if (p.length != 4) {
            return null;
        }
        double[] out = new double[4];
        try {
            for (int i = 0; i < 4; i++) {
                out[i] = Double.parseDouble(p[i].trim());
                if (!Double.isFinite(out[i])) {
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
