package com.saferoute.subway;

import java.util.List;

/**
 * GET /api/subway/nearby 답.
 *
 * @param basis    "timetable" = 실시간이 아니라 시간표로 계산한 값 (광주 지하철은 실시간 공개 API가 없음)
 * @param dayType  쓴 시간표 (평일·토요일·휴일·명절)
 * @param stations 가까운 역 순 (최대 3곳). 근처에 없으면 빈 목록
 */
public record SubwayNearbyResponse(String basis, String dayType, List<StationTrains> stations) {
}
