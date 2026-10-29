package com.saferoute.ingest;

import java.util.Arrays;

/**
 * 시설 종류와 그 종류를 담는 표. 종류 이름이 곧 표 이름이다 (schema.sql 참고).
 * SQL에 표 이름을 그대로 넣으므로, 여기 있는 이름만 쓸 수 있게 막는다.
 */
public enum FacilityTable {

    STREETLIGHT("streetlight", "가로등", null),
    SECURITY_LIGHT("security_light", "보안등", "lamp_count"),
    CCTV("cctv", "CCTV", "camera_count"),
    POLICE("police", "경찰 시설", null),
    EMERGENCY_BELL("emergency_bell", "안전 비상벨", null),
    CONVENIENCE_STORE("convenience_store", "편의점", null),
    CHILD_SAFETY_HOUSE("child_safety_house", "아동안전지킴이집", null),
    HEAT_SHELTER("heat_shelter", "무더위쉼터", null),
    COLD_SHELTER("cold_shelter", "한파쉼터", null),
    PUBLIC_TOILET("public_toilet", "공중화장실", null);

    private final String table;
    private final String title;
    private final String countColumn;

    FacilityTable(String table, String title, String countColumn) {
        this.table = table;
        this.title = title;
        this.countColumn = countColumn;
    }

    /** 표 이름 (= 시설 종류) */
    public String table() {
        return table;
    }

    /** 사람이 읽는 이름 */
    public String title() {
        return title;
    }

    /** 개수 칸 이름 (CCTV 카메라 대수, 보안등 개수). 없으면 null */
    public String countColumn() {
        return countColumn;
    }

    public static FacilityTable of(String type) {
        return Arrays.stream(values())
                .filter(t -> t.table.equals(type))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("모르는 시설 종류: " + type));
    }
}
