package com.saferoute.report;

import java.util.LinkedHashMap;
import java.util.Map;

/** 불편 신고 유형 (A파트 023_road_report.sql 의 report_type 설명과 같은 값, 사고는 028에서 더함) */
final class ReportType {

    /** 유형 코드 → 앱에 보여 줄 이름 */
    static final Map<String, String> NAMES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("steep", "급경사");
        m.put("construction", "공사");
        m.put("curb", "턱");
        m.put("broken", "보도 파손");
        m.put("obstacle", "막는 물건");
        m.put("accident", "사고");
        m.put("other", "기타");
        NAMES = Map.copyOf(m);
    }

    private ReportType() {
    }

    static boolean known(String type) {
        return type != null && NAMES.containsKey(type);
    }

    static String name(String type) {
        return NAMES.getOrDefault(type, "기타");
    }
}
