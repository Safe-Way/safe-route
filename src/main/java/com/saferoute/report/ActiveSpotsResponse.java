package com.saferoute.report;

import java.util.List;

/** GET /api/reports/active 응답. truncated 가 true 면 더 있지만 잘랐다는 뜻 (지도를 확대하면 다 보임) */
public record ActiveSpotsResponse(List<ActiveSpot> spots, boolean truncated) {
}
