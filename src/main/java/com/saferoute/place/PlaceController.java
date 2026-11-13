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
        // "현재 위치", "내 위치": 지금 있는 곳의 주소 한 줄 (11-13)
        if (lat != null && HereQuery.asks(q)) {
            try {
                Place here = kakao.addressAt(lat, lon);
                Place one = new Place("here", "현재 위치",
                        here == null ? "주소를 찾을 수 없는 곳이에요" : here.address() + (here.name().equals(here.address()) ? "" : " (" + here.name() + ")"),
                        lat, lon, 0);
                return ResponseEntity.ok(new PlacesResponse(List.of(one)));
            } catch (PlaceSearchException e) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(new ErrorResponse("SEARCH_UNAVAILABLE", "지금은 주소를 찾을 수 없어요"));
            }
        }
        if (lat != null && busStops != null && BusStopQuery.asks(q)) {
            List<Place> stops = nearStops(lat, lon);
            if (!stops.isEmpty()) {
                return ResponseEntity.ok(new PlacesResponse(stops));
            }
        }
        try {
            String name = PlaceAlias.of(q);
            List<Place> found = kakao.search(name, lat, lon);
            // 가까운 순 10곳에 이름이 똑같은 곳이 없으면 정확도 순으로 한 번 더 보고 맨 앞에 (11-13 "금남지하상가")
            if (lat != null && found.stream().noneMatch(p -> PlaceAlias.same(p.name(), name))) {
                for (Place p : kakao.search(name, null, null)) {
                    if (PlaceAlias.same(p.name(), name)) {
                        List<Place> out = new ArrayList<>();
                        out.add(new Place(p.id(), p.name(), p.address(), p.lat(), p.lon(), meters(lat, lon, p.lat(), p.lon())));
                        out.addAll(found);
                        found = out.subList(0, Math.min(MAX_PLACES, out.size()));
                        break;
                    }
                }
            }
            return ResponseEntity.ok(new PlacesResponse(found));
        } catch (PlaceSearchException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("SEARCH_UNAVAILABLE", "검색이 잠시 안 됩니다. 지도를 길게 눌러 고를 수 있어요"));
        }
    }

    /**
     * 좌표 → 주소 (11-13). 예: /api/places/here?lat=35.1401&lon=126.9336
     * → {"name":"조선대학교 본관","address":"광주 동구 필문대로 309","lat":..,"lon":..}. 주소 없는 곳이면 404.
     * 위치는 로그에 남기지 않는다 (NFR-07)
     */
    @GetMapping("/api/places/here")
    public ResponseEntity<?> here(@RequestParam double lat, @RequestParam double lon) {
        try {
            Place p = kakao.addressAt(lat, lon);
            if (p == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("NO_ADDRESS", "주소를 찾을 수 없는 곳이에요"));
            }
            return ResponseEntity.ok(p);
        } catch (PlaceSearchException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("SEARCH_UNAVAILABLE", "지금은 주소를 찾을 수 없어요"));
        }
    }

    /**
     * DB 정류장 1km 안 가까운 순. 이름은 정류장 이름 그대로(구글처럼 "조선대해오름관"),
     * 아래 작은 글씨(주소 칸)는 "버스 정류장 · 조선대장미원 방면" — 길 건너 같은 이름 정류장을 나눈다
     */
    private List<Place> nearStops(double lat, double lon) {
        List<Place> out = new ArrayList<>();
        try {
            for (BusStop s : busStops.near(lat, lon, BUS_RADIUS_M, MAX_PLACES)) {
                String way = s.nextStop() == null || s.nextStop().isBlank()
                        ? "버스 정류장" : "버스 정류장 · " + s.nextStop().strip() + " 방면";
                out.add(new Place("bus:" + s.id(), s.name().strip(), way, s.lat(), s.lon(), s.distanceM()));
            }
        } catch (DataAccessException e) {
            log.warn("정류장 검색 DB 실패, 카카오로: {}", e.getMostSpecificCause().getMessage());
        }
        return out;
    }

    /** 곧은 거리(m) */
    static int meters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return (int) Math.round(2 * r * Math.asin(Math.sqrt(a)));
    }
}
