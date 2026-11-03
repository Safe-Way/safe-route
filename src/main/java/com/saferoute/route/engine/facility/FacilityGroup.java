package com.saferoute.route.engine.facility;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.saferoute.ingest.FacilityTable;

/**
 * 차이 설명에서 세는 시설 묶음 (요구사항 8.3의 counts).
 * light는 가로등+보안등, help는 경찰·편의점·아동안전지킴이집이다.
 * 이름·단위·지도 아이콘 순서처럼 묶음마다 다른 값은 모두 여기 둔다.
 */
public enum FacilityGroup {

    // markOrder: 지도 아이콘이 많을 때 먼저 남기는 순서. 등은 너무 많아서 맨 뒤
    LIGHT("light", "가로등", "개", 3, EnumSet.of(FacilityTable.STREETLIGHT, FacilityTable.SECURITY_LIGHT)),
    CCTV("cctv", "CCTV", "대", 2, EnumSet.of(FacilityTable.CCTV)),
    BELL("bell", "비상벨", "개", 1, EnumSet.of(FacilityTable.EMERGENCY_BELL)),
    HELP("help", "도움 지점", "곳", 0, EnumSet.of(
            FacilityTable.POLICE, FacilityTable.CONVENIENCE_STORE, FacilityTable.CHILD_SAFETY_HOUSE));

    private final String key;
    private final String title;
    private final String unit;
    private final int markOrder;
    private final Set<FacilityTable> tables;

    FacilityGroup(String key, String title, String unit, int markOrder, Set<FacilityTable> tables) {
        this.key = key;
        this.title = title;
        this.unit = unit;
        this.markOrder = markOrder;
        this.tables = tables;
    }

    /** 응답 JSON의 이름 */
    public String key() {
        return key;
    }

    /** 설명 문장의 한 항목. 예: "CCTV +12대" */
    public String gain(int n) {
        return title + " +" + n + unit;
    }

    public int markOrder() {
        return markOrder;
    }

    public static FacilityGroup of(FacilityTable table) {
        for (FacilityGroup g : values()) {
            if (g.tables.contains(table)) {
                return g;
            }
        }
        return null;
    }

    /** 세는 시설 표 전부 */
    public static Set<FacilityTable> allTables() {
        Set<FacilityTable> all = EnumSet.noneOf(FacilityTable.class);
        for (FacilityGroup g : values()) {
            all.addAll(g.tables);
        }
        return all;
    }

    /** 묶음별 숫자를 응답 JSON 이름(light, cctv, ...)으로 바꾼다. 없는 묶음은 0 */
    public static Map<String, Integer> keyed(Map<FacilityGroup, Integer> values) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (FacilityGroup g : values()) {
            out.put(g.key, values.getOrDefault(g, 0));
        }
        return out;
    }
}
