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
     * @param dark     밤에 조명이 적은 구간 (FR-31). 낮이면 빈 목록
     * @param turns    꺾는 곳 (FR-34)
     * @param crossings 횡단보도를 건너는 구간. 꺾지 않고 곧장 건너는 곳도 있다
     */
    public record Route(String kind, int distanceM, int durationMin, List<double[]> geometry,
            Map<String, Integer> counts, List<Stretch> dark, List<Turn> turns, List<Stretch> crossings) {
    }

    /**
     * 꺾는 곳. 걷는 동안 앱이 내 위치와 비교해 "80m 앞 왼쪽", 30m 앞에서 "잠시 후 왼쪽입니다"를 만든다.
     *
     * @param atM       출발부터 경로를 따라 꺾는 곳까지 거리(m). 시설 along.atM과 같은 기준
     * @param direction "left" 또는 "right"
     * @param angle     방향이 바뀌는 정도(도, 45~180). 화살표 모양(살짝·크게 꺾음)에 쓴다
     * @param crossing  꺾은 뒤 다음 꺾는 곳(없으면 도착)까지 가는 동안 횡단보도를 건너면 true
     * @param thenM     다음 꺾는 곳이 20m 안에 이어지면 그 거리(m), 아니면 null. 앱은 둘을 한 문장으로 말한다
     *                  ("잠시 후 오른쪽 횡단보도를 건너 왼쪽입니다")
     */
    public record Turn(int atM, String direction, int angle, boolean crossing, Integer thenM) {
    }

    /**
     * 경로 위 구간. 걷는 동안 앱이 내 위치를 경로 위로 내린 거리와 비교해 "앞으로 150m는 조명이 적습니다"를 만든다.
     *
     * @param fromM 출발부터 경로를 따라 구간이 시작하는 곳(m). 시설 along.atM과 같은 기준
     * @param toM   구간이 끝나는 곳(m)
     */
    public record Stretch(int fromM, int toM) {
    }

    /** @param diff 안전 − 최단 시설 수 */
    public record Explanation(String text, int extraM, int extraMin, Map<String, Integer> diff) {
    }

    /**
     * 지도 아이콘용 시설.
     *
     * @param type 시설 표 종류 (CCTV, EMERGENCY_BELL, CONVENIENCE_STORE, ...)
     * @param on    safe, shortest, both
     * @param along 경로 종류(safe, shortest) → 그 경로에서의 자리. on에 든 경로만 있다
     */
    public record FacilityMark(String type, double lat, double lon, String on, Map<String, Along> along) {
    }

    /**
     * 시설이 경로의 어디에 있는지 (FR-30). 걷는 동안 "50m 앞 오른쪽 비상벨" 안내에 쓴다.
     *
     * @param atM  출발부터 경로를 따라 그 시설 옆까지 거리(m)
     * @param side 걷는 방향 기준 "left" 또는 "right"
     */
    public record Along(int atM, String side) {
    }

    /**
     * @param status   OK, PARTIAL(경로 일부가 데이터 없는 곳), NO_DATA
     * @param dataDate 시설 데이터 기준일 "2026-10-28"
     */
    public record DataQuality(String status, String message, String dataDate) {
    }
}
