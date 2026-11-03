package com.saferoute.common;

/**
 * 모든 오류 응답의 모양 (요구사항 8.3: 오류는 {code, message}).
 * message 는 앱이 그대로 사용자에게 보여 줄 수 있는 문장으로 쓴다.
 */
public record ErrorResponse(String code, String message) {
}
