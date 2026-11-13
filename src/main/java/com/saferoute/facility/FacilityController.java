package com.saferoute.facility;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.saferoute.common.ErrorResponse;

/**
 * 지도·가까운 곳에 보여 줄 시설 (요구사항 8.3, A파트 poi_status 뷰).
 * <ul>
 *   <li>GET /api/places/nearby?lat=&lon=&kind=toilet&mode=senior : 지금 이용 가능한 가장 가까운 곳 (FR-50)</li>
 *   <li>GET /api/pois?bbox=서,남,동,북&kinds=cctv,police,shelter : 지도 범위 안 시설 (FR-48)</li>
 *   <li>kind·kinds 에 elevator 를 주면 엘리베이터 육교 + 엘리베이터 있는 지하철 출구 (11-13). 답의 kind 는 원래 종류
 *       (footbridge, subway_entrance) 그대로</li>
 * </ul>
 * 앱은 고른 곳의 좌표로 POST /api/route를 불러 경로를 받는다 (경로 계산은 여기서 하지 않음).
 */
@RestController
public class FacilityController {

    /** 가까운 곳에서 고를 수 있는 종류 (FR-50, 앱 설정의 "가까운 곳" 목록) */
    static final Set<String> NEARBY_KINDS = Set.of("toilet", "police", "guard_house", "school_zone", "shelter",
            "bell", "store", "subway", FacilityRepository.ELEVATOR);
    /** 지도에 띄울 수 있는 종류. 등(light)은 8만 개가 넘어 점으로 보내지 않는다 (밝기는 경로 계산에만) */
    static final Set<String> MAP_KINDS = Set.of("cctv", "police", "bell", "store", "guard_house", "school_zone",
            "shelter", "toilet", "footbridge", "crosswalk", "stairs", "subway", "subway_entrance",
            FacilityRepository.ELEVATOR);
    static final Set<String> MODES = Set.of("adult", "child", "senior", "wheelchair");
    /** 장애인 모드에서 휠체어로 쓸 수 있는 것만 고를 종류 (장애인 화장실, 엘리베이터 있는 역) */
    static final Set<String> WHEELCHAIR_FILTERED = Set.of("toilet", "subway");
    static final int NEARBY_RADIUS_M = 1000;
    static final int NEARBY_PICK = 5;
    static final int MAP_LIMIT_DEFAULT = 500;
    static final int MAP_LIMIT_MAX = 1000;
    /** 지도 범위 한 변의 최대 (약 20km). 넘으면 확대해 달라고 한다 */
    static final double MAP_MAX_SPAN_DEG = 0.2;

    private static final Logger log = LoggerFactory.getLogger(FacilityController.class);

    private final FacilityRepository repo;

    public FacilityController(FacilityRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/api/places/nearby")
    public ResponseEntity<?> nearby(@RequestParam(required = false) Double lat,
                                    @RequestParam(required = false) Double lon,
                                    @RequestParam(required = false) String kind,
                                    @RequestParam(required = false) String mode) {
        if (lat == null || lon == null || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            return bad("lat 과 lon 을 함께 보내야 합니다");
        }
        if (kind == null || !NEARBY_KINDS.contains(kind)) {
            return bad("kind 는 " + String.join("·", sorted(NEARBY_KINDS)) + " 중 하나여야 합니다");
        }
        String m = mode == null || mode.isBlank() ? "adult" : mode;
        if (!MODES.contains(m)) {
            return bad("mode 는 adult·child·senior·wheelchair 중 하나여야 합니다");
        }
        boolean wheelchairOnly = "wheelchair".equals(m) && WHEELCHAIR_FILTERED.contains(kind);
        try {
            return ResponseEntity.ok(pick(repo.near(lat, lon, kind, wheelchairOnly, NEARBY_RADIUS_M)));
        } catch (DataAccessException e) {
            return dbDown(e);
        }
    }

    @GetMapping("/api/pois")
    public ResponseEntity<?> pois(@RequestParam(required = false) String bbox,
                                  @RequestParam(required = false) String kinds,
                                  @RequestParam(required = false) Integer limit) {
        double[] b = parseBbox(bbox);
        if (b == null) {
            return bad("bbox 는 서쪽경도,남쪽위도,동쪽경도,북쪽위도 네 숫자여야 합니다");
        }
        if (b[2] - b[0] > MAP_MAX_SPAN_DEG || b[3] - b[1] > MAP_MAX_SPAN_DEG) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("BBOX_TOO_LARGE", "지도를 조금 더 확대해 주세요"));
        }
        Set<String> want = new LinkedHashSet<>();
        for (String k : (kinds == null ? "" : kinds).split(",")) {
            String t = k.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (!MAP_KINDS.contains(t)) {
                return bad("kinds 에 모르는 종류가 있어요: " + t + " (가능: " + String.join(",", sorted(MAP_KINDS)) + ")");
            }
            want.add(t);
        }
        if (want.isEmpty()) {
            return bad("kinds 에 시설 종류를 하나 이상 보내야 합니다");
        }
        int n = limit == null ? MAP_LIMIT_DEFAULT : Math.max(1, Math.min(MAP_LIMIT_MAX, limit));
        try {
            List<Facility> found = repo.within(b[0], b[1], b[2], b[3], want, n + 1);
            boolean truncated = found.size() > n;
            return ResponseEntity.ok(new PoisResponse(truncated ? found.subList(0, n) : found, truncated));
        } catch (DataAccessException e) {
            return dbDown(e);
        }
    }

    /**
     * 가까운 순 목록에서 고를 곳을 정한다 (FR-50).
     * 지금 이용 가능한 곳(시간과 상관없는 곳 포함)을 최대 5곳, 없으면 "확인 안 됨"인 곳, 그것도 없으면 빈 목록.
     * 이용 가능한 곳이 없을 때는 가장 가까운 닫힌 곳을 같이 준다.
     */
    static NearbyResponse pick(List<Facility> byDistance) {
        List<Facility> open = new ArrayList<>();
        List<Facility> unknown = new ArrayList<>();
        Facility closed = null;
        for (Facility f : byDistance) {
            String s = f.nowStatus();
            if (s == null || "이용 가능".equals(s)) {
                open.add(f);
            } else if ("확인 안 됨".equals(s)) {
                unknown.add(f);
            } else if (closed == null) {
                closed = f;
            }
        }
        if (!open.isEmpty()) {
            return new NearbyResponse(NEARBY_RADIUS_M, first(open), null, null);
        }
        if (!unknown.isEmpty()) {
            return new NearbyResponse(NEARBY_RADIUS_M, first(unknown), closed,
                    "지금 열려 있는지 확인이 안 된 곳이에요. 가기 전에 확인해 주세요");
        }
        String msg = closed == null ? "1km 안에 이 시설이 없어요" : "가까운 곳에 열린 곳이 없어요";
        return new NearbyResponse(NEARBY_RADIUS_M, List.of(), closed, msg);
    }

    /** "126.91,35.14,126.93,35.15" → {서, 남, 동, 북}. 형식이 틀리면 null */
    static double[] parseBbox(String bbox) {
        if (bbox == null) {
            return null;
        }
        String[] p = bbox.split(",");
        if (p.length != 4) {
            return null;
        }
        double[] b = new double[4];
        try {
            for (int i = 0; i < 4; i++) {
                b[i] = Double.parseDouble(p[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        boolean ok = b[0] < b[2] && b[1] < b[3] && Math.abs(b[0]) <= 180 && Math.abs(b[2]) <= 180
                && Math.abs(b[1]) <= 90 && Math.abs(b[3]) <= 90;
        return ok ? b : null;
    }

    private static List<Facility> first(List<Facility> list) {
        return List.copyOf(list.subList(0, Math.min(NEARBY_PICK, list.size())));
    }

    private static List<String> sorted(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        l.sort(null);
        return l;
    }

    private static ResponseEntity<ErrorResponse> bad(String message) {
        return ResponseEntity.badRequest().body(new ErrorResponse("BAD_PARAM", message));
    }

    private static ResponseEntity<ErrorResponse> dbDown(DataAccessException e) {
        log.warn("시설 조회 중 DB 오류: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("DB_UNAVAILABLE", "잠시 후 다시 시도해 주세요"));
    }
}
