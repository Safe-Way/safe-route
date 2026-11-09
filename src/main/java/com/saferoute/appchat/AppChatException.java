package com.saferoute.appchat;

import org.springframework.http.HttpStatus;

/** 앱 화면 AI 창구 오류. 창구가 {code, message} 로 바꿔 돌려준다 */
public class AppChatException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    AppChatException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static AppChatException bad(String message) {
        return new AppChatException(HttpStatus.BAD_REQUEST, "BAD_PARAM", message);
    }

    /** 키가 없거나 제미나이가 못 쓸 답을 줌. 앱은 자기 규칙으로 대신 답하면 된다 */
    static AppChatException unavailable() {
        return new AppChatException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                "AI가 지금 답하지 못했어요. 앱의 규칙으로 대신 답해 주세요");
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
