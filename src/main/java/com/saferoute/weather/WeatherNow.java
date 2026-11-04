package com.saferoute.weather;

/**
 * 기상청 초단기실황 한 번의 관측값.
 *
 * @param tempC    기온(℃, T1H). 없으면 null
 * @param rain1hMm 1시간 강수량(mm, RN1). 없으면 null
 * @param pty      강수형태(PTY) 0 없음, 1 비, 2 비/눈, 3 눈, 5 빗방울, 6 빗방울눈날림, 7 눈날림. 없으면 null
 * @param observedAt 관측 기준 시각 (예: 202611031000)
 */
public record WeatherNow(Double tempC, Double rain1hMm, Integer pty, String observedAt) {
}
