package com.saferoute.report;

import org.springframework.http.HttpStatus;

/** 신고 값이 틀렸을 때. 창구가 {code, message} 로 바꿔 돌려준다 */
public class ReportException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ReportException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static ReportException bad(String message) {
        return new ReportException(HttpStatus.BAD_REQUEST, "BAD_PARAM", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
