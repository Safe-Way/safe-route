package com.saferoute.walk;

import org.springframework.http.HttpStatus;

/** 보행 속도 값이 틀렸을 때. 창구가 {code, message} 로 바꿔 돌려준다 */
public class WalkSpeedException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public WalkSpeedException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static WalkSpeedException bad(String message) {
        return new WalkSpeedException(HttpStatus.BAD_REQUEST, "BAD_PARAM", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
