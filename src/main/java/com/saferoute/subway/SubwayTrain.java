package com.saferoute.subway;

/**
 * 역에 곧 들어올 열차 하나 (시간표로 계산, 실시간 아님).
 *
 * @param direction  "평동행" 또는 "녹동행"
 * @param endStation 이 열차의 종착역 (예: 소태)
 * @param arriveTime 도착 예정 시각 "HH:mm"
 * @param inMin      몇 분 뒤
 * @param nowNear    지금 지나온 역 (시간표 기준). 첫 역에서 아직 안 떠났으면 null
 * @param stopsAway  몇 정거장 전 (시간표 기준). 모르면 null
 */
public record SubwayTrain(String direction, String endStation, String arriveTime, int inMin, String nowNear,
                          Integer stopsAway) {
}
