package com.saferoute.transit;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.saferoute.common.ErrorResponse;

/**
 * 대중교통 탭 창구 (FR-37).
 * POST /api/transit  본문은 /api/route 와 같음: {origin:{lat,lon,name}, destination:{...}, mode, night, guestId}
 * via:[{lat,lon,name}] 를 주면 들를 곳까지 앞 구간, 들를 곳부터 뒤 구간을 따로 찾아 그 사이에 들르기 구간(type stop)을 끼운다
 */
@RestController
public class TransitController {

    private final TransitService service;

    public TransitController(TransitService service) {
        this.service = service;
    }

    @PostMapping("/api/transit")
    public ResponseEntity<TransitResult> search(@RequestBody TransitRequest req) {
        if (req == null) {
            throw TransitException.bad("요청 내용이 비어 있어요");
        }
        return ResponseEntity.ok(service.search(req.route(), req.via()));
    }

    @ExceptionHandler(TransitException.class)
    public ResponseEntity<ErrorResponse> failed(TransitException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage()));
    }
}
