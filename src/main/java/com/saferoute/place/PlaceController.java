package com.saferoute.place;

import java.util.ArrayList;
import java.util.List;

import com.saferoute.bus.BusLine;
import com.saferoute.bus.BusLineRepository;
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
    private static final java.util.regex.Pattern GOING_BUS = java.util.regex.Pattern.compile(
            "^(.+?)\\s*(가는|행|방면)\\s*(버스|지하철|차)(.*)$");
    static final int MAX_PLACES = 10;

    private static final Logger log = LoggerFactory.getLogger(PlaceController.class);

    private final KakaoLocalClient kakao;
    private final BusStopRepository busStops;
    private BusLineRepository busLines;

    /** 노선 표 (DB 없는 시험에서는 안 넣음) */
    @Autowired(required = false)
    void setBusLines(BusLineRepository busLines) {
        this.busLines = busLines;
    }

    private com.saferoute.bus.BusLineStops lineStops;

    /** 노선마다 지나는 정류장 (DB 없는 테스트에서는 안 넣음 → 기점·종점으로만) */
    @Autowired(required = false)
    void setLineStops(com.saferoute.bus.BusLineStops lineStops) {
        this.lineStops = lineStops;
    }

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
        // "버스 03", "03번 버스": 노선 정보 (기점↔종점, 첫차·막차, 배차). 누르면 기점 정류장으로 (11-13)
        String line = busLines == null ? null : BusLineQuery.number(q);
        if (line != null) {
            try {
                List<Place> lines = busLines.find(line).stream().filter(l -> l.lat() != null)
                        .map(l -> new Place("line:" + l.id(), l.name() + "번 버스", l.summary(), l.lat(), l.lon(),
                                lat == null ? null : meters(lat, lon, l.lat(), l.lon())))
                        .toList();
                if (!lines.isEmpty()) {
                    return ResponseEntity.ok(new PlacesResponse(lines));
                }
            } catch (DataAccessException e) {
                log.warn("노선 검색 DB 실패, 카카오로: {}", e.getMostSpecificCause().getMessage());
            }
        }
        // "수완동으로 가는 버스", "수완동 가는 버스", "수완동행 버스": 장소만 찾는다 (11-13).
        // 그곳을 지나는 노선과 그쪽이 기점·종점인 노선만 보인다 (아래에 그 거리의 가게까지 섞이면 헷갈림, 11-13 시험).
        // 노선이 하나도 없을 때만 장소를 보여 줘서 [도착]으로 고르면 갈아타는 길까지 나오게 한다
        java.util.regex.Matcher to = GOING_BUS.matcher(q);
        List<Place> lineFirst = List.of();
        if (to.matches()) {
            String place = dropParticle(to.group(1).strip());
            if (place.length() >= 2) {
                q = place;
                lineFirst = linesEndingAt(place, lat, lon);
                if (!lineFirst.isEmpty()) {
                    return ResponseEntity.ok(new PlacesResponse(lineFirst));
                }
                // 가는 버스가 없으면 그 장소 하나만 (그 이름이 든 가게·교회 목록 대신). [도착]으로 고르면 갈아타는 길이 나옴
                Place only = gwangjuPlace(BusLineQuery.anchor(place));
                if (only != null) {
                    return ResponseEntity.ok(new PlacesResponse(List.of(new Place(only.id(), only.name(),
                            only.address(), only.lat(), only.lon(),
                            lat == null ? null : meters(lat, lon, only.lat(), only.lon())))));
                }
            }
        }
        // "광주역 근처 정류장": 광주역을 먼저 찾고 그 근처 정류장 (11-13, 통째로 카카오에 넘기면 경기 광주가 나옴)
        String at = busStops == null ? null : BusStopQuery.anchor(q);
        if (at != null) {
            Place anchor = gwangjuPlace(at);
            if (anchor != null) {
                List<Place> stops = nearStops(anchor.lat(), anchor.lon());
                if (!stops.isEmpty()) {
                    return ResponseEntity.ok(new PlacesResponse(stops));
                }
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
            // 광주 안 결과가 있으면 광주 밖(경기 광주 등)은 뺀다. 시연·서비스는 광주만
            if (found.stream().anyMatch(PlaceController::inGwangju)) {
                found = found.stream().filter(PlaceController::inGwangju).toList();
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

    /** 광주 네모 범위 (비서 AssistantTools.GWANGJU와 같은 값) */
    static boolean inGwangju(Place p) {
        return p.lon() >= 126.64 && p.lat() >= 35.04 && p.lon() <= 127.03 && p.lat() <= 35.27;
    }

    /** "수완동", "서석동", "충장동", "월곡1동" 같은 동네 이름 */
    static final java.util.regex.Pattern DONG = java.util.regex.Pattern.compile("[가-힣]{1,5}\\d?(동|읍|면)");

    /** 이름으로 광주 안 장소 하나: 이름이 똑같은 곳 먼저, 없으면 광주 안 첫 곳. 못 찾으면 null */
    private Place gwangjuPlace(String spoken) {
        String name = PlaceAlias.of(spoken);
        try {
            if (DONG.matcher(name.replaceAll("\\s+", "")).matches()) {
                // "수완동": 키워드로 찾으면 동 이름이 든 교회·가게가 나와서, 주소 검색으로 동 한가운데를 쓴다 (11-13 시험)
                List<Place> dong = kakao.searchAddress("광주 " + name).stream().filter(PlaceController::inGwangju)
                        .toList();
                if (!dong.isEmpty()) {
                    Place d = dong.get(0);
                    return new Place(d.id(), name, d.address(), d.lat(), d.lon(), d.distanceM());
                }
            }
            List<Place> in = kakao.search(name, null, null).stream().filter(PlaceController::inGwangju).toList();
            if (in.isEmpty() && !name.startsWith("광주")) {
                // 전국에서 찾으면 경기 고양·성남 같은 곳이 앞을 채울 때가 있어 "광주 "를 붙여 한 번 더 (11-13 터미널 확인)
                in = kakao.search("광주 " + name, null, null).stream().filter(PlaceController::inGwangju).toList();
            }
            return in.stream().filter(p -> PlaceAlias.same(p.name(), name)).findFirst()
                    .orElse(in.isEmpty() ? null : in.get(0));
        } catch (PlaceSearchException e) {
            log.warn("정류장 기준 장소 찾기 실패: {}", e.getMessage());
            return null;
        }
    }

    /** "수완동으로"→수완동, "충장로로"→충장로, "광주역까지"→광주역. "충장로"처럼 이름 끝 '로'는 그대로 (받침 ㅇ 뒤 '로'는 조사가 아님) */
    static String dropParticle(String w) {
        for (String p : new String[] {"으로", "까지", "에"}) {
            if (w.endsWith(p) && w.length() > p.length() + 1) {
                return w.substring(0, w.length() - p.length());
            }
        }
        if (w.endsWith("로") && w.length() >= 3) {
            char before = w.charAt(w.length() - 2);
            if (before >= '가' && before <= '힣') {
                int jong = (before - '가') % 28;
                if (jong == 0 || jong == 8) { // 받침 없음 또는 ㄹ
                    return w.substring(0, w.length() - 1);
                }
            }
        }
        return w;
    }

    /** place 를 지나거나 그쪽이 기점·종점인 노선을 검색 결과 모양으로. 좌표는 지나는 정류장(없으면 기점), 모르면 뺌 */
    private List<Place> linesEndingAt(String place, Double lat, Double lon) {
        if (busLines == null) {
            return List.of();
        }
        try {
            if (lineStops != null) {
                Place at = gwangjuPlace(BusLineQuery.anchor(place));
                return lineStops.to(place, at == null ? null : at.lat(), at == null ? null : at.lon()).stream()
                        .map(h -> lineAsPlace(h.line(), h.via(), lat, lon)).filter(java.util.Objects::nonNull)
                        .limit(5).toList();
            }
            return busLines.endingAt(BusLineQuery.stem(place)).stream()
                    .map(l -> lineAsPlace(l, null, lat, lon)).filter(java.util.Objects::nonNull)
                    .limit(5).toList();
        } catch (DataAccessException e) {
            log.warn("노선 검색 DB 실패: {}", e.getMostSpecificCause().getMessage());
            return List.of();
        }
    }

    /** "03번 버스" · "수완지구 지나요 · 첨단 ↔ 조선대 · 첫차…". 좌표는 지나는 정류장, 없으면 기점 (둘 다 없으면 null) */
    private static Place lineAsPlace(com.saferoute.bus.BusLine l, com.saferoute.bus.LineStop via, Double lat,
                                     Double lon) {
        Double y = via != null ? Double.valueOf(via.lat()) : l.lat();
        Double x = via != null ? Double.valueOf(via.lon()) : l.lon();
        if (y == null || x == null) {
            return null;
        }
        String address = via == null ? l.summary() : via.name() + " 지나요 · " + l.summary();
        return new Place("line:" + l.id(), l.name() + "번 버스", address, y, x,
                lat == null ? null : meters(lat, lon, y, x));
    }
}
