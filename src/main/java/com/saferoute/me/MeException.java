package com.saferoute.me;

import org.springframework.http.HttpStatus;

/** 내 정보·다닌 기록 창구에서 값이 틀렸을 때. 창구가 {code, message} 로 바꿔 돌려준다 */
public class MeException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public MeException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static MeException bad(String message) {
        return new MeException(HttpStatus.BAD_REQUEST, "BAD_PARAM", message);
    }

    static MeException noConsent() {
        return new MeException(HttpStatus.FORBIDDEN, "NO_CONSENT",
                "다닌 기록 남기기에 동의하지 않아서 서버에 남기지 않아요");
    }

    static MeException tripNotFound() {
        return new MeException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "그 기록이 없어요");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
