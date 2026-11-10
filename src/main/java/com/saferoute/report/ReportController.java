package com.saferoute.report;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.saferoute.common.ErrorResponse;

/**
 * 불편 신고 창구 (FR-49, 2차).
 * <ul>
 *   <li>POST /api/reports (multipart/form-data): type, lat, lon, mode, memo, accuracyM, photos(0~3장), guestId(선택, 해시로만 저장) → 201</li>
 *   <li>GET /api/reports?bbox=서,남,동,북 : 지도 범위 안 신고 (숨긴 것 빼고, 최근 순 300개까지)</li>
 *   <li>GET /api/reports/active?bbox=서,남,동,북 : 지금 유효한 신고 지점 (같은 곳 묶음·기간은 032 뷰가 계산)</li>
 *   <li>GET /api/reports/{id}/photos/{seq} : 사진 한 장</li>
 * </ul>
 * 처음에는 지도에 "신고된 곳"으로만 보이고 경로 계산에는 쓰지 않는다 (023 표 설명).
 */
@RestController
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @PostMapping(value = "/api/reports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReportCreated> create(@RequestParam(required = false) String type,
                                                @RequestParam(required = false) Double lat,
                                                @RequestParam(required = false) Double lon,
                                                @RequestParam(required = false) String mode,
                                                @RequestParam(required = false) String memo,
                                                @RequestParam(required = false) Integer accuracyM,
                                                @RequestParam(required = false) List<MultipartFile> photos,
                                                @RequestParam(required = false) String guestId) {
        NewReport r = reports.check(type, lat, lon, mode, memo, accuracyM, photos);
        return ResponseEntity.status(HttpStatus.CREATED).body(reports.create(r, guestId));
    }

    @GetMapping("/api/reports")
    public ResponseEntity<ReportsResponse> list(@RequestParam(required = false) String bbox) {
        double[] b = parseBbox(bbox);
        if (b == null) {
            throw ReportException.bad("bbox 는 서쪽경도,남쪽위도,동쪽경도,북쪽위도 네 숫자여야 해요");
        }
        return ResponseEntity.ok(reports.within(b[0], b[1], b[2], b[3]));
    }

    @GetMapping("/api/reports/active")
    public ResponseEntity<ActiveSpotsResponse> active(@RequestParam(required = false) String bbox) {
        double[] b = parseBbox(bbox);
        if (b == null) {
            throw ReportException.bad("bbox 는 서쪽경도,남쪽위도,동쪽경도,북쪽위도 네 숫자여야 해요");
        }
        return ResponseEntity.ok(reports.active(b[0], b[1], b[2], b[3]));
    }

    @GetMapping("/api/reports/{id}/photos/{seq}")
    public ResponseEntity<?> photo(@PathVariable long id, @PathVariable int seq) {
        NewReport.Photo p = reports.photo(id, seq);
        if (p == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("NOT_FOUND", "사진이 없어요"));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(p.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                .header("X-Content-Type-Options", "nosniff")
                .body(p.data());
    }

    @ExceptionHandler(ReportException.class)
    public ResponseEntity<ErrorResponse> bad(ReportException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> dbDown(DataAccessException e) {
        log.warn("신고 DB 오류: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("DB_UNAVAILABLE", "잠시 후 다시 시도해 주세요"));
    }

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
