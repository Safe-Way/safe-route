package com.saferoute.me;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.saferoute.common.ErrorResponse;

/**
 * 내 정보·다닌 기록 창구 (팀 할 일 5번). 앱 설정 화면과 비서가 같이 쓴다.
 * GET    /api/me?guestId                    내 정보 보기 (+ 지도 설정, 기록 수)
 * PATCH  /api/me                            내 정보 고치기 (보낸 칸만)
 * DELETE /api/me?guestId                    내 기록 모두 지우기
 * PUT    /api/me/layers/{layer}             지도 시설 보이기·숨기기
 * DELETE /api/me/layers/{layer}?guestId     지도 시설 모드 기본으로
 * POST   /api/me/trips                      다닌 기록 시작 (동의한 사람만)
 * PATCH  /api/me/trips/{id}                 도착·그만둠과 한마디
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private static final Logger log = LoggerFactory.getLogger(MeController.class);

    private final MeService service;

    public MeController(MeService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<MeRequests.View> view(@RequestParam String guestId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.view(guestId));
    }

    @PatchMapping
    public MeRequests.View update(@RequestBody(required = false) MeRequests.Update in) {
        return service.update(in);
    }

    @DeleteMapping
    public MeRequests.Deleted deleteAll(@RequestParam String guestId) {
        return service.deleteAll(guestId);
    }

    @PutMapping("/layers/{layer}")
    public List<MeRequests.Layer> setLayer(@PathVariable String layer,
                                           @RequestBody(required = false) MeRequests.LayerSet in) {
        return service.setLayer(layer, in);
    }

    @DeleteMapping("/layers/{layer}")
    public List<MeRequests.Layer> resetLayer(@PathVariable String layer, @RequestParam String guestId) {
        return service.resetLayer(layer, guestId);
    }

    @PostMapping("/trips")
    public ResponseEntity<MeRequests.TripStarted> startTrip(@RequestBody(required = false) MeRequests.TripStart in) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.startTrip(in));
    }

    @PatchMapping("/trips/{id}")
    public ResponseEntity<Void> endTrip(@PathVariable long id, @RequestBody(required = false) MeRequests.TripEnd in) {
        service.endTrip(id, in);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(MeException.class)
    public ResponseEntity<ErrorResponse> bad(MeException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage()));
    }

    /** DB 를 못 쓸 때. 게스트 번호는 로그에 남기지 않는다 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> db(DataAccessException e) {
        log.warn("내 정보 DB 오류: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("DB_UNAVAILABLE", "잠시 후 다시 시도해 주세요"));
    }
}
