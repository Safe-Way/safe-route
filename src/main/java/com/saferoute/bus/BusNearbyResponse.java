package com.saferoute.bus;

import java.util.List;

/** GET /api/bus/nearby 답. 가까운 정류장 순 */
public record BusNearbyResponse(List<StopArrivals> stops) {
}
