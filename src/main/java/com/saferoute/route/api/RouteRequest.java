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
 */
public record RouteRequest(Place origin, Place destination, String night, String mode, String guestId, String rain) {

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
