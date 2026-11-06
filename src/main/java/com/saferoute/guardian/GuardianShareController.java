package com.saferoute.guardian;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.saferoute.common.ErrorResponse;

/**
 * 보호자 위치 공유 창구 (029).
 * POST /api/guardian/shares                    시작 → {token, url, expiresAt}. 앱이 url 을 보호자에게 문자로 보냄
 * PUT  /api/guardian/shares/{token}/location   걷는 동안 10초마다 위치 → {status}
 * POST /api/guardian/shares/{token}/end        끝내기 {reason: arrived·stopped·sos}
 * GET  /api/guardian/shares/{token}            보호자 화면이 10초마다 읽는 값
 * GET  /s/{token}                              보호자가 여는 웹 지도 페이지
 */
@RestController
public class GuardianShareController {

    private static final Logger log = LoggerFactory.getLogger(GuardianShareController.class);

    private final GuardianShareService service;
    private final String page;

    public GuardianShareController(GuardianShareService service) throws IOException {
        this.service = service;
        try (InputStream in = new ClassPathResource("guardian/share.html").getInputStream()) {
            this.page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @PostMapping("/api/guardian/shares")
    public ResponseEntity<GuardianRequests.Started> start(@RequestBody(required = false) GuardianRequests.Start in) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.start(in));
    }

    @PutMapping("/api/guardian/shares/{token}/location")
    public ResponseEntity<Map<String, String>> move(@PathVariable String token,
                                                    @RequestBody(required = false) GuardianRequests.Location in) {
        return ResponseEntity.ok(Map.of("status", service.move(token, in)));
    }

    @PostMapping("/api/guardian/shares/{token}/end")
    public ResponseEntity<Void> end(@PathVariable String token,
                                    @RequestBody(required = false) GuardianRequests.End in) {
        service.end(token, in);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/guardian/shares/{token}")
    public ResponseEntity<GuardianRequests.View> view(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.view(token));
    }

    /** 보호자 웹 지도. 공유 번호는 페이지가 주소에서 읽어 위 GET 을 10초마다 부른다 */
    @GetMapping(value = "/s/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex")
                .body(page);
    }

    @ExceptionHandler(GuardianException.class)
    public ResponseEntity<ErrorResponse> bad(GuardianException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage()));
    }

    /** DB 를 못 쓸 때. 공유 번호·좌표는 로그에 남기지 않는다 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> db(DataAccessException e) {
        log.warn("보호자 공유 DB 오류: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("DB_UNAVAILABLE", "잠시 후 다시 시도해 주세요"));
    }
}
