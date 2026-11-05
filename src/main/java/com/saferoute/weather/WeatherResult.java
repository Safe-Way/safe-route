package com.saferoute.weather;

/**
 * 비·폭염·한파 판단 결과. 경로 응답의 conditions 칸에 들어갈 값.
 *
 * @param rain       비(또는 눈비)가 오면 true
 * @param heat       폭염이면 true (기온 33℃ 이상)
 * @param cold       한파면 true (기온 -10℃ 이하)
 * @param tempC      기온(℃). 모르면 null
 * @param rain1hMm   1시간 강수량(mm). 모르면 null
 * @param observedAt 관측 기준 시각. 모르면 null
 * @param available  값을 받아 왔으면 true. 못 받았으면 false 이고 나머지 판단도 false
 */
public record WeatherResult(boolean rain, boolean heat, boolean cold, Double tempC, Double rain1hMm,
                            String observedAt, boolean available) {

    static WeatherResult unavailable() {
        return new WeatherResult(false, false, false, null, null, null, false);
    }
}
