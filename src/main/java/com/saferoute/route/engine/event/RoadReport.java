package com.saferoute.route.engine.event;

import java.time.LocalDate;
import java.util.List;

/**
 * 지금 유효한 신고 지점 하나 (A파트 뷰 road_report_active, migration 032. 진행표 w11, 2026-11-10).
 * 기간·같은 곳(같은 종류·30m 안) 묶음은 뷰가 계산한다. 엔진은 행사·돌발과 같은 장치로 다루려고 {@link #event()}로 바꾼다.
 *
 * @param spotId     같은 곳 묶음 번호 (그 묶음 첫 신고 번호)
 * @param type       obstacle, accident, construction, curb, steep, broken, other
 * @param lon, lat   묶음 첫 신고 자리
 * @param endsOn     이날까지 유효
 * @param avoidModes 둘레를 조건부로 피할 모드 (뷰가 종류로 정함)
 * @param costModes  피하지는 않고 길에 비용만 더할 모드
 */
public record RoadReport(long spotId, String type, double lon, double lat, LocalDate endsOn,
        List<String> avoidModes, List<String> costModes) {

    /** 응답 events[].kind. 설명은 "공사 신고"처럼 title(종류) 뒤에 붙여 말한다 */
    public static final String KIND = "신고";

    /**
     * 신고 자리에서 이 거리 안의 가장 가까운 길 하나를 신고된 길로 본다 (2026-11-10 사용자 확정).
     * 앱 신고 GPS 정확도가 보통 10~20m이고, 신고 대상(턱·공사)은 길 위 한 점이다. 30m(같은 곳 묶음)면 좁은 골목에서 옆 길까지 걸린다
     */
    public static final double RADIUS_M = 20;

    public RoadReport {
        avoidModes = List.copyOf(avoidModes);
        costModes = List.copyOf(costModes);
    }

    /**
     * 행사·돌발과 같은 흐름에 넣을 모양. 번호는 행사 번호와 겹치지 않게 음수, title은 종류 이름만("공사").
     * 쪽지(last_memo)는 넣지 않는다: 다른 사용자가 쓴 자유 글이 모든 사람 응답에 실리지 않게
     */
    public RoadEvent event() {
        return new RoadEvent(-spotId, KIND, typeName(type), null, null, lon, lat, RADIUS_M,
                endsOn == null ? null : endsOn.atTime(23, 59));
    }

    /** 뷰의 report_type을 한국어 이름으로. 모르는 종류는 기타 */
    static String typeName(String type) {
        return switch (type == null ? "" : type) {
            case "obstacle" -> "막는 물건";
            case "accident" -> "사고";
            case "construction" -> "공사";
            case "curb" -> "턱";
            case "steep" -> "급경사";
            case "broken" -> "보도 파손";
            default -> "기타";
        };
    }
}
