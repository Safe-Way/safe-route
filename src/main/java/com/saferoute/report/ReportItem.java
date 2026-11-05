package com.saferoute.report;

import java.util.List;

/**
 * 지도에 보여 줄 신고 한 건.
 *
 * @param typeName  유형 이름 (예: 급경사)
 * @param status    open(올라옴) 또는 confirmed(확인됨). 숨긴 신고(hidden)는 나오지 않는다
 * @param createdAt 올린 시각 (한국 시각, 예: 2026-11-05T14:30)
 * @param photoUrls 사진 주소들 (예: /api/reports/12/photos/1), 없으면 빈 목록
 */
public record ReportItem(long id, String type, String typeName, String mode, String memo, double lat, double lon,
                         String status, String createdAt, List<String> photoUrls) {
}
