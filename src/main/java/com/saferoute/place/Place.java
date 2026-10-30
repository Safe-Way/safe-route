package com.saferoute.place;

/**
 * 장소 검색 결과 한 개 (요구사항 8.3 GET /api/places).
 *
 * @param name      장소 이름 (예: "조선대학교")
 * @param address   도로명 주소, 없으면 지번 주소
 * @param lat       위도
 * @param lon       경도
 * @param distanceM 요청에 lat·lon 을 줬을 때 거기서부터의 거리(m). 안 줬으면 null
 */
public record Place(String name, String address, double lat, double lon, Integer distanceM) {
}
