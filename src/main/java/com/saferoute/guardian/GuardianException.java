package com.saferoute.guardian;

import org.springframework.http.HttpStatus;

/** 보호자 위치 공유 요청이 틀렸거나 끝난 공유일 때. 창구가 {code, message} 로 바꿔 돌려준다 */
public class GuardianException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public GuardianException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static GuardianException bad(String message) {
        return new GuardianException(HttpStatus.BAD_REQUEST, "BAD_PARAM", message);
    }

    static GuardianException notFound() {
        return new GuardianException(HttpStatus.NOT_FOUND, "SHARE_NOT_FOUND", "링크가 끝났거나 없어요");
    }

    static GuardianException ended() {
        return new GuardianException(HttpStatus.GONE, "SHARE_ENDED", "위치 공유가 이미 끝났어요");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
