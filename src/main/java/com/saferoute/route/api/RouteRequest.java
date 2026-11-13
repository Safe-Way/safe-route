package com.saferoute.route.api;

/**
 * POST /api/route 요청 (요구사항 8.3).
 *
 * @param night auto(기본, 일몰 계산), on, off
 * @param mode  누가 걷는지: adult(기본, 청년·중년), child(어린이·청소년), senior(노인), wheelchair(휠체어).
 *              2차에 요청에 추가되는 값 (8.3)
 * @param guestId 앱이 처음 실행 때 만든 게스트 번호. 있으면 그 사람이 정한 선호(user_profile의 계단·우회 한도·쉬는 간격)를
 *                모드 기준 대신 쓴다. 없으면 모드 기준 (2026-11-09)
 * @param rain  auto(기본, 기상청 지금 날씨)·on·off. 비면 지붕 있는 길을 먼저 찾는다 (2026-11-09). 시연 때 on
 * @param stops 들를 곳 최대 3곳, 이 순서대로 거친다 (2026-11-10). 없으면 null 또는 빈 목록
 * @param allowTrail 등산로(OSM sac_scale이 붙은 산길)를 지나도 되는지. null·false면 등산로 없는 길이 있는 한 그 길로 간다 (한도 없음).
 *                   휠체어는 true여도 등산로를 지나지 않는다 (2026-11-12)
 * @param reroute 길안내 중 다시 찾기인지. true면 휠체어 출발점도 이어진 길에 50m까지만 다시 붙인다 (보통은 100m, 건물 안 출발).
 *                null·false면 보통 찾기 (2026-11-13)
 */
public record RouteRequest(Place origin, Place destination, String night, String mode, String guestId, String rain,
        java.util.List<Place> stops, Boolean allowTrail, Boolean reroute) {

    /** 길안내 중 다시 찾기 표시 없이 */
    public RouteRequest(Place origin, Place destination, String night, String mode, String guestId, String rain,
            java.util.List<Place> stops, Boolean allowTrail) {
        this(origin, destination, night, mode, guestId, rain, stops, allowTrail, null);
    }

    /** 들를 곳 없이 */
    public RouteRequest(Place origin, Place destination, String night, String mode, String guestId, String rain) {
        this(origin, destination, night, mode, guestId, rain, null, null);
    }

    /** 비 판단 없이 (auto, 날씨를 따름) */
    public RouteRequest(Place origin, Place destination, String night, String mode, String guestId) {
        this(origin, destination, night, mode, guestId, null);
    }

    /** mode 없이 (1차 요청 모양) */
    public RouteRequest(Place origin, Place destination, String night) {
        this(origin, destination, night, null, null);
    }

    /** 게스트 번호 없이 (모드 기준만) */
    public RouteRequest(Place origin, Place destination, String night, String mode) {
        this(origin, destination, night, mode, null);
    }

    /**
     * 좌표 이름은 lat·lon. name은 화면 표시용이고 계산에는 쓰지 않는다.
     * 숫자가 빠진 요청을 (0, 0)으로 잘못 읽지 않도록 Double로 받는다.
     */
    public record Place(Double lat, Double lon, String name) {
    }
}
