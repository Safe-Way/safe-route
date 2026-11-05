package com.saferoute.route.api;

/**
 * 경로를 못 줄 때의 이유 (요구사항 8.3). HTTP 상태는 C파트 컨트롤러가 그대로 쓰면 된다.
 */
public enum RouteError {

    SAME_POINT(400, "출발과 도착이 너무 가까워요"),
    TOO_FAR(400, "걸어가기엔 멀어요"),
    OUT_OF_AREA(422, "아직 광주만 길찾기가 됩니다"),
    NO_ROUTE(404, "경로를 찾지 못했어요"),
    DB_UNAVAILABLE(503, "잠시 후 다시 시도해 주세요");

    private final int httpStatus;
    private final String message;

    RouteError(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** 앱에 띄우는 말 */
    public String message() {
        return message;
    }
}
