package com.saferoute.route.api;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import com.saferoute.route.engine.condition.Conditions;

/**
 * POST /api/route 응답 (요구사항 8.3). 이 모양 그대로 JSON으로 내보내면 된다.
 */
public record RouteResult(
        Conditions conditions,
        List<Route> routes,
        Explanation explanation,
        List<FacilityMark> facilities,
        DataQuality dataQuality) {

    /** @param sunset "17:41" 형식 */
    public record Conditions(boolean night, String nightSource, String sunset) {

        public static Conditions of(com.saferoute.route.engine.condition.Conditions c) {
            return new Conditions(c.night(), c.nightSource(),
                    c.sunset() == null ? null : c.sunset().format(DateTimeFormatter.ofPattern("HH:mm")));
        }
    }

    /**
     * @param kind     shortest(지도에 회색) 또는 safe(초록). 두 경로가 같으면 safe 하나만 온다
     * @param geometry [경도, 위도] 배열 (GeoJSON 순서)
     * @param counts   light·cctv·bell·help → 경로 주변 시설 수
     */
    public record Route(String kind, int distanceM, int durationMin, List<double[]> geometry,
            Map<String, Integer> counts) {
    }

    /** @param diff 안전 − 최단 시설 수 */
    public record Explanation(String text, int extraM, int extraMin, Map<String, Integer> diff) {
    }

    /**
     * 지도 아이콘용 시설.
     *
     * @param type 시설 표 종류 (CCTV, EMERGENCY_BELL, CONVENIENCE_STORE, ...)
     * @param on   safe, shortest, both
     */
    public record FacilityMark(String type, double lat, double lon, String on) {
    }

    /**
     * @param status   OK, PARTIAL(경로 일부가 데이터 없는 곳), NO_DATA
     * @param dataDate 시설 데이터 기준일 "2026-10-28"
     */
    public record DataQuality(String status, String message, String dataDate) {
    }
}
