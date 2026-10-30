package com.saferoute.situation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 의 situation 칸에 적힌 상황 판단 기준값.
 * 다른 코드는 숫자를 직접 쓰지 않고 이 값을 물어본다.
 *
 * @param heatCelsius 이 기온(℃) 이상이면 폭염
 * @param coldCelsius 이 기온(℃) 이하면 한파
 * @param pm10Bad     미세먼지(PM10, ㎍/㎥)가 이 값 이상이면 나쁨
 * @param pm25Bad     초미세먼지(PM2.5, ㎍/㎥)가 이 값 이상이면 나쁨
 */
@ConfigurationProperties("situation")
public record SituationProperties(
        double heatCelsius,
        double coldCelsius,
        int pm10Bad,
        int pm25Bad) {

    public boolean isHeat(double celsius) {
        return celsius >= heatCelsius;
    }

    public boolean isCold(double celsius) {
        return celsius <= coldCelsius;
    }

    /** 둘 중 하나라도 나쁨이면 나쁨. 값이 없으면(null) 그 값은 보지 않는다 */
    public boolean isDustBad(Integer pm10, Integer pm25) {
        return (pm10 != null && pm10 >= pm10Bad) || (pm25 != null && pm25 >= pm25Bad);
    }
}
