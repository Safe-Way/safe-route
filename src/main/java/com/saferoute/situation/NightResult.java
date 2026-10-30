package com.saferoute.situation;

import java.time.LocalTime;

/**
 * 밤 판단 결과. 경로 응답의 conditions 칸에 그대로 들어간다.
 *
 * @param night       밤이면 true
 * @param nightSource "auto"(일몰 계산) 또는 "manual"(사용자가 켜고 끔)
 * @param sunrise     그날 해 뜨는 시각 (분 단위)
 * @param sunset      그날 해 지는 시각 (분 단위)
 */
public record NightResult(boolean night, String nightSource, LocalTime sunrise, LocalTime sunset) {
}
