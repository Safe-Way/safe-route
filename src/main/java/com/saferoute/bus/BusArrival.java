package com.saferoute.bus;

/**
 * 정류장에 곧 오는 버스 한 대 (광주버스정보 arriveInfo 한 줄).
 *
 * @param lineId     노선 번호(LINE_ID). 이 버스들이 지금 어디 있는지 물을 때 쓴다 (/api/bus/locations). 모르면 null
 * @param lineName   버스 번호 (예: 01, 첨단09)
 * @param remainMin  몇 분 뒤 도착. 모르면 null
 * @param remainStop 몇 정거장 전. 모르면 null
 * @param lowFloor   저상버스면 true (LOW_BUS = "1")
 * @param direction  어느 쪽으로 가는 버스인지 (종점 이름)
 * @param nowAt      버스가 지금 있는 정류장 이름
 */
public record BusArrival(Integer lineId, String lineName, Integer remainMin, Integer remainStop, boolean lowFloor,
                         String direction, String nowAt) {
}
