package com.saferoute.bus;

import java.util.List;

/**
 * 노선 하나의 버스 위치 답 (GET /api/bus/locations?lineId=2).
 *
 * @param lineId    물어본 노선 번호
 * @param available 위치 정보를 받아 왔는지. false면 buses가 빈 목록 (버스가 없다는 뜻은 아님)
 * @param buses     지금 달리는 버스들, 노선 순서(seq) 순
 */
public record BusLocationResponse(int lineId, boolean available, List<BusLocation> buses) {
}
