package com.saferoute.facility;

import java.util.List;

/**
 * GET /api/pois 응답 (FR-48 모드별 지도 표시).
 *
 * @param pois      지도 범위 안 시설, 범위 가운데에 가까운 순
 * @param truncated 개수 한도(limit)를 넘어 일부만 보냈으면 true. 앱은 "지도를 확대하면 더 보여요"를 띄우면 된다
 */
public record PoisResponse(List<Facility> pois, boolean truncated) {
}
