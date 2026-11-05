package com.saferoute.report;

/** POST /api/reports 응답: 새 신고 번호, 상태(open), 저장한 사진 수 */
public record ReportCreated(long id, String status, int photoCount) {
}
