package com.saferoute.transit;

import org.springframework.http.HttpStatus;

/** 대중교통 길찾기를 못 할 때. 창구가 {code, message} 로 바꿔 돌려준다 */
public class TransitException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public TransitException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static TransitException bad(String message) {
        return new TransitException(HttpStatus.BAD_REQUEST, "BAD_PARAM", message);
    }

    /** 가까워서 걷는 게 나음 (ODsay -98: 출발·도착 700m 이내) */
    static TransitException tooClose() {
        return new TransitException(HttpStatus.UNPROCESSABLE_CONTENT, "TOO_CLOSE",
                "출발지와 도착지가 가까워요. 걷는 길찾기를 써 주세요");
    }

    static TransitException noRoute() {
        return new TransitException(HttpStatus.NOT_FOUND, "NO_ROUTE", "대중교통으로 가는 길을 찾지 못했어요");
    }

    static TransitException dailyLimit() {
        return new TransitException(HttpStatus.TOO_MANY_REQUESTS, "DAILY_LIMIT",
                "오늘 대중교통 검색을 다 썼어요. 내일 다시 시도해 주세요");
    }

    static TransitException unavailable() {
        return new TransitException(HttpStatus.SERVICE_UNAVAILABLE, "TRANSIT_UNAVAILABLE",
                "대중교통 정보를 잠시 받을 수 없어요");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
