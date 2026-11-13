package com.saferoute.place;

import java.util.ArrayList;
import java.util.List;

import com.saferoute.bus.BusStop;
import com.saferoute.bus.BusStopRepository;
import com.saferoute.common.ErrorResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장소 검색 (FR-01). 예: /api/places?query=조선대학교&lat=35.1401&lon=126.9336
 * query 는 2자 이상, lat·lon 은 선택 (주면 가까운 순). 결과는 최대 10개.
 * "근처 버스", "버스 정류장"처럼 정류장을 찾는 말이고 위치가 있으면 카카오 대신 DB 정류장 표에서
 * 1km 안을 가까운 순으로 준다 (11-13: 카카오는 이름에 '버스'가 든 가게·먼 정류장을 섞어 줌).
 * DB가 안 되거나 1km 안에 없으면 예전처럼 카카오.
 */
@RestController
public class PlaceController {

    static final int BUS_RADIUS_M = 1000;
    static final int MAX_PLACES = 10;

    private static final Logger log = LoggerFactory.getLogger(PlaceController.class);

    private final KakaoLocalClient kakao;
    private final BusStopRepository busStops;

    public PlaceController(KakaoLocalClient kakao) {
        this(kakao, null);
    }

    @Autowired
    public PlaceController(KakaoLocalClient kakao, BusStopRepository busStops) {
        this.kakao = kakao;
        this.busStops = busStops;
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
        if (lat != null && busStops != null && BusStopQuery.asks(q)) {
            List<Place> stops = nearStops(lat, lon);
            if (!stops.isEmpty()) {
                return ResponseEntity.ok(new PlacesResponse(stops));
            }
        }
        try {
            return ResponseEntity.ok(new PlacesResponse(kakao.search(q, lat, lon)));
        } catch (PlaceSearchException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("SEARCH_UNAVAILABLE", "검색이 잠시 안 됩니다. 지도를 길게 눌러 고를 수 있어요"));
        }
    }

    /** DB 정류장 1km 안 가까운 순. 주소 칸에는 방향(다음 정류장)을 넣어 길 건너 같은 이름 정류장을 나눈다 */
    private List<Place> nearStops(double lat, double lon) {
        List<Place> out = new ArrayList<>();
        try {
            for (BusStop s : busStops.near(lat, lon, BUS_RADIUS_M, MAX_PLACES)) {
                String way = s.nextStop() == null || s.nextStop().isBlank() ? "버스 정류장" : s.nextStop().strip() + " 방면";
                out.add(new Place("bus:" + s.id(), BusStopQuery.stopName(s.name()), way, s.lat(), s.lon(), s.distanceM()));
            }
        } catch (DataAccessException e) {
            log.warn("정류장 검색 DB 실패, 카카오로: {}", e.getMostSpecificCause().getMessage());
        }
        return out;
    }
}
