package com.saferoute.place;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장소 검색 (FR-01). 예: /api/places?query=조선대학교&lat=35.1401&lon=126.9336
 * query 는 2자 이상, lat·lon 은 선택 (주면 가까운 순). 결과는 최대 10개.
 */
@RestController
public class PlaceController {

    private final KakaoLocalClient kakao;

    public PlaceController(KakaoLocalClient kakao) {
        this.kakao = kakao;
    }

    @GetMapping("/api/places")
    public ResponseEntity<?> places(@RequestParam(required = false) String query,
                                    @RequestParam(required = false) Double lat,
                                    @RequestParam(required = false) Double lon) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("BAD_PARAM", "검색어는 2자 이상 입력해 주세요"));
        }
        if ((lat == null) != (lon == null)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("BAD_PARAM", "lat 과 lon 은 함께 보내야 합니다"));
        }
        try {
            return ResponseEntity.ok(new PlacesResponse(kakao.search(q, lat, lon)));
        } catch (PlaceSearchException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("SEARCH_UNAVAILABLE", "검색이 잠시 안 됩니다. 지도를 길게 눌러 고를 수 있어요"));
        }
    }
}
