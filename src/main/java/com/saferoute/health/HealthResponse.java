package com.saferoute.health;

/**
 * GET /api/health 응답. 시연 전에 서버·DB가 살아 있는지 보는 용도 (요구사항 8.3).
 *
 * @param status   서버 전체 상태. DB까지 되면 "UP", DB가 안 되면 "DOWN"
 * @param db       DB 연결 상태 "UP" 또는 "DOWN"
 * @param dataDate 시설 데이터를 마지막으로 넣은 날짜 (예: "2026-10-28"). 모르면 null
 * @param roadArea 길찾기가 되는 지역 (예: "광주")
 */
public record HealthResponse(String status, String db, String dataDate, String roadArea) {
}
