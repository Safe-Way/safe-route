package com.saferoute.air;

/**
 * 에어코리아 측정소 한 곳의 지금 값.
 *
 * @param stationName 측정소 이름 (예: 서석동)
 * @param pm10        미세먼지(PM10, ㎍/㎥). 측정 안 됐으면 null
 * @param pm25        초미세먼지(PM2.5, ㎍/㎥). 측정 안 됐으면 null
 * @param dataTime    측정 시각 (예: 2026-11-02 14:00)
 */
public record StationAir(String stationName, Integer pm10, Integer pm25, String dataTime) {
}
