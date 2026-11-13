package com.saferoute.bus;

/**
 * 노선이 지나는 정류장 하나 (광주버스정보 lineStationInfo, 11-13).
 *
 * @param seq    노선 안 순서 (1부터)
 * @param stopId bus_stop 표의 busstop_id
 */
public record LineStop(int seq, int stopId, String name, double lat, double lon) {
}
