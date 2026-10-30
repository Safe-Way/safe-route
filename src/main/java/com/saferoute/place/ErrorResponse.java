package com.saferoute.place;

/** 오류 응답 모양 (요구사항 8.3): { "code": "SEARCH_UNAVAILABLE", "message": "…" } */
public record ErrorResponse(String code, String message) {
}
