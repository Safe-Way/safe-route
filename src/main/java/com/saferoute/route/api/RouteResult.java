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
     * 지도 아이콘용 시설 (FR-07, 결정 40).
     *
     * @param type      poi 종류: light, cctv, bell, police, store, guard_house, toilet, shelter
     *                  (가까운 곳 API의 kind와 같은 이름)
     * @param on        safe, shortest, both
     * @param nowStatus 화장실·쉼터·경찰·비상벨·지킴이집만: 이용 가능 / 닫힘 / 운영 기간 아님 / 확인 안 됨. 그 밖에는 null
     * @param nowNote   nowStatus의 근거 (예: "개방 시간 매일 09:00~18:00"). 바탕 데이터는 실시간이 아니다
     */
    public record FacilityMark(String type, double lat, double lon, String on, String nowStatus, String nowNote) {
    }

    /**
     * @param status   OK, PARTIAL(경로 일부가 데이터 없는 곳), NO_DATA
     * @param dataDate 시설 데이터 기준일 "2026-10-28"
     */
    public record DataQuality(String status, String message, String dataDate) {
    }
}
