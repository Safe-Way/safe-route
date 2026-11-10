package com.saferoute.report;

import java.util.List;

/**
 * 지금 유효한 신고 지점 하나 (road_report_active 뷰 한 줄, 032 결정 57).
 * 같은 곳 묶기·기간 계산은 뷰가 하고 서버는 칸을 그대로 옮긴다.
 *
 * @param spotId      묶음 번호 (같은 종류·30m 안 신고 중 가장 먼저 들어온 신고 번호)
 * @param reportType  신고 종류 코드 (steep, construction, curb, broken, obstacle, accident, other)
 * @param startsOn    이번 기간 첫날 (예: 2026-11-10)
 * @param endsOn      이번 기간 마지막 날. 공사는 마지막 날 신고가 또 오면 하루씩 늘어남
 * @param reportCount 이번 기간 신고 수
 * @param photoCount  이번 기간 사진 수
 * @param confirmed   2건 이상이거나 팀이 확인했으면 true
 * @param avoidModes  피하는 모드 (예: [senior, wheelchair])
 * @param costModes   조금 돌아가게만 하는 모드
 * @param lastAt      마지막 신고 시각 (한국 시각, 예: 2026-11-10T11:15)
 * @param lastMemo    마지막 신고 메모. 없으면 null
 */
public record ActiveSpot(long spotId, String reportType, double lat, double lon, String startsOn, String endsOn,
                         int reportCount, int photoCount, boolean confirmed, List<String> avoidModes,
                         List<String> costModes, String lastAt, String lastMemo) {
}
