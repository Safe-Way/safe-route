package com.saferoute.bus;

/**
 * DB의 버스 정류장 한 곳 (A파트 bus_stop 표, 019_transit.sql).
 *
 * @param id        도착 API에 넣는 정류장 번호 (BUSSTOP_ID)
 * @param name      정류장 이름
 * @param nextStop  다음 정류장 이름. 길 건너 같은 이름 정류장과 방향을 구분할 때 쓴다
 * @param distanceM 내 위치에서 몇 m
 */
public record BusStop(int id, String name, String nextStop, double lat, double lon, int distanceM) {
}
