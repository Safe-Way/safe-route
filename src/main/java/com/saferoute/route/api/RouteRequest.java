package com.saferoute.route.api;

/**
 * POST /api/route 요청 (요구사항 8.3).
 *
 * @param night auto(기본, 일몰 계산), on, off
 * @param mode  누가 걷는지: adult(기본, 청년·중년), child(어린이·청소년), senior(노인), wheelchair(휠체어).
 *              2차에 요청에 추가되는 값 (8.3)
 */
public record RouteRequest(Place origin, Place destination, String night, String mode) {

    /** mode 없이 (1차 요청 모양) */
    public RouteRequest(Place origin, Place destination, String night) {
        this(origin, destination, night, null);
    }

    /**
     * 좌표 이름은 lat·lon. name은 화면 표시용이고 계산에는 쓰지 않는다.
     * 숫자가 빠진 요청을 (0, 0)으로 잘못 읽지 않도록 Double로 받는다.
     */
    public record Place(Double lat, Double lon, String name) {
    }
}
