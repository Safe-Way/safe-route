package com.saferoute.assistant;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.saferoute.common.ErrorResponse;

/**
 * 오늘 제안 창구 (팀 할 일 7번). GET /api/suggestions/today?guestId&lat&lon&mode
 * 같은 요일·시간대에 3번 이상 간 곳 + 날씨 → 오늘의 길 추천. 위치(lat, lon)는 선택(없으면 길·날씨 없이 습관만).
 * 기록이 없거나 DB를 못 읽어도 200 으로 답하고 say 로 "어디 가실 곳이 있으세요?"라고 묻는다.
 */
@RestController
public class TodayController {

    private final TodayService service;

    public TodayController(TodayService service) {
        this.service = service;
    }

    @GetMapping("/api/suggestions/today")
    public ResponseEntity<TodaySuggestion> today(@RequestParam String guestId,
                                                 @RequestParam(required = false) Double lat,
                                                 @RequestParam(required = false) Double lon,
                                                 @RequestParam(required = false) String mode) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.today(guestId, lat, lon, mode));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> bad(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("BAD_PARAM", e.getMessage()));
    }
}
