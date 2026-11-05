package com.saferoute.transit;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.saferoute.common.ErrorResponse;
import com.saferoute.route.api.RouteRequest;

/**
 * 대중교통 탭 창구 (FR-37).
 * POST /api/transit  본문은 /api/route 와 같음: {origin:{lat,lon,name}, destination:{...}, mode, night}
 */
@RestController
public class TransitController {

    private final TransitService service;

    public TransitController(TransitService service) {
        this.service = service;
    }

    @PostMapping("/api/transit")
    public ResponseEntity<TransitResult> search(@RequestBody RouteRequest req) {
        return ResponseEntity.ok(service.search(req));
    }

    @ExceptionHandler(TransitException.class)
    public ResponseEntity<ErrorResponse> failed(TransitException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage()));
    }
}
