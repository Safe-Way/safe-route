package com.saferoute.air;

/**
 * 미세먼지 판단 결과. 경로 응답의 conditions 칸에 들어갈 값.
 *
 * @param dustBad   먼지 나쁨이면 true
 * @param pm10      시도 측정소 평균 미세먼지(㎍/㎥). 모르면 null
 * @param pm25      시도 측정소 평균 초미세먼지(㎍/㎥). 모르면 null
 * @param sido      어느 시도 값인지 (예: 광주)
 * @param dataTime  측정 시각. 모르면 null
 * @param available 값을 받아 왔으면 true. 못 받았으면 false 이고 dustBad 도 false
 */
public record DustResult(boolean dustBad, Integer pm10, Integer pm25, String sido, String dataTime,
                         boolean available) {

    static DustResult unavailable(String sido) {
        return new DustResult(false, null, null, sido, null, false);
    }
}
