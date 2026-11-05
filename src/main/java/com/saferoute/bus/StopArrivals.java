package com.saferoute.bus;

import java.util.List;

/**
 * 앱에 보내는 정류장 하나와 곧 오는 버스들.
 *
 * @param available 도착 정보를 받아 왔으면 true. false면 arrivals는 빈 목록 (버스가 없다는 뜻이 아님)
 * @param arrivals  곧 오는 버스, 빨리 오는 순
 */
public record StopArrivals(int id, String name, String nextStop, double lat, double lon, int distanceM,
                           boolean available, List<BusArrival> arrivals) {
}
