package com.saferoute.facility;

import java.util.List;

/**
 * GET /api/places/nearby 응답 (FR-50 가까운 곳 바로 가기).
 *
 * @param radiusM       찾은 범위 (직선 m)
 * @param places        고를 곳, 가까운 순 최대 5곳. 지금 이용 가능한 곳(또는 시간과 상관없는 곳)이 먼저이고,
 *                      그런 곳이 없으면 "확인 안 됨"인 곳
 * @param closestClosed 이용 가능한 곳이 없을 때 가장 가까운 닫힌 곳 (여는 시간을 보여 주려고). 아니면 null
 * @param message       앱이 그대로 띄울 안내. 이용 가능한 곳이 있으면 null
 */
public record NearbyResponse(int radiusM, List<Facility> places, Facility closestClosed, String message) {
}
