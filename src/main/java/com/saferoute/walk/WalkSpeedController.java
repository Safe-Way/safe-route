package com.saferoute.walk;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.saferoute.common.ErrorResponse;

/**
 * 개인 보행 속도 창구 (FR-52).
 * POST   /api/walk-speed                     한 번 잰 값 올리기 → 새 속도
 * GET    /api/walk-speed?guestId=&mode=      지금 속도 받기
 * DELETE /api/walk-speed?guestId=            내 기록 지우기 (모든 모드)
 */
@RestController
@RequestMapping("/api/walk-speed")
public class WalkSpeedController {

    private static final Logger log = LoggerFactory.getLogger(WalkSpeedController.class);

    private final WalkSpeedService service;

    public WalkSpeedController(WalkSpeedService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<WalkSpeed> add(@RequestBody WalkSample sample) {
        return ResponseEntity.ok(service.add(sample));
    }

    @GetMapping
    public ResponseEntity<WalkSpeed> get(@RequestParam(required = false) String guestId,
                                         @RequestParam(required = false) String mode) {
        return ResponseEntity.ok(service.get(guestId, mode));
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Integer>> delete(@RequestParam(required = false) String guestId) {
        return ResponseEntity.ok(Map.of("deletedModes", service.delete(guestId)));
    }

    @ExceptionHandler(WalkSpeedException.class)
    public ResponseEntity<ErrorResponse> bad(WalkSpeedException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage()));
    }

    /** DB 를 못 쓸 때. 게스트 번호는 로그에 남기지 않는다 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> db(DataAccessException e) {
        log.warn("보행 속도 DB 오류: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("DB_UNAVAILABLE", "잠시 후 다시 시도해 주세요"));
    }
}
