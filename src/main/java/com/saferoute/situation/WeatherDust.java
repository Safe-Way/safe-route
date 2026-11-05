package com.saferoute.situation;

/**
 * 지금 날씨·미세먼지 판단 결과. 길찾기 응답의 conditions 칸에 더해진다 (화면 안내용, 길 계산에는 안 씀).
 *
 * @param rain             비(또는 눈비)가 오면 true
 * @param heat             폭염이면 true (33℃ 이상)
 * @param cold             한파면 true (-10℃ 이하)
 * @param tempC            기온(℃). 모르면 null
 * @param weatherAvailable 기상청 값을 받아 왔으면 true. false면 rain·heat·cold도 false
 * @param dustBad          미세먼지 나쁨이면 true
 * @param dustAvailable    에어코리아 값을 받아 왔으면 true. false면 dustBad도 false
 */
public record WeatherDust(boolean rain, boolean heat, boolean cold, Double tempC, boolean weatherAvailable,
                          boolean dustBad, boolean dustAvailable) {

    /** 둘 다 모를 때 */
    public static WeatherDust unknown() {
        return new WeatherDust(false, false, false, null, false, false, false);
    }
}
