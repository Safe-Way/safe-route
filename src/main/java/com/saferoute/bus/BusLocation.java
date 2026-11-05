package com.saferoute.bus;

/**
 * 노선 위를 달리는 버스 한 대가 지금 있는 곳 (광주버스정보 busLocationInfo 한 줄 + bus_stop 표 좌표).
 * 버스 좌표는 API가 주지 않아서, 버스가 지금 있는 정류장의 좌표를 쓴다 (정류장 단위 위치).
 *
 * @param busId    버스 고유 번호 (같은 버스를 다음 새로고침에서 알아보는 용도)
 * @param lowFloor 저상버스면 true (LOW_BUS = "1")
 * @param stopId   버스가 지금 있는 정류장 번호 (bus_stop.busstop_id)
 * @param stopName 그 정류장 이름. 정류장 표에 없으면 null
 * @param lat      그 정류장 위도. 정류장 표에 없으면 null
 * @param lon      그 정류장 경도. 정류장 표에 없으면 null
 * @param seq      노선에서 몇 번째 정류장인지 (작을수록 출발점에 가까움)
 */
public record BusLocation(String busId, boolean lowFloor, int stopId, String stopName, Double lat, Double lon,
                          int seq) {
}
