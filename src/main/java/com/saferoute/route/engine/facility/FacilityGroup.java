package com.saferoute.route.engine.facility;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 차이 설명에서 세는 시설 묶음 (요구사항 8.3의 counts).
 * light는 가로등+보안등, help는 경찰·편의점·아동안전지킴이집이다. 묶음은 poi 종류(kind)로 정한다.
 * 이름·단위처럼 묶음마다 다른 값은 모두 여기 둔다.
 */
public enum FacilityGroup {

    LIGHT("light", "가로등", "개", Set.of("light")),
    CCTV("cctv", "CCTV", "대", Set.of("cctv")),
    BELL("bell", "비상벨", "개", Set.of("bell")),
    HELP("help", "도움 지점", "곳", Set.of("police", "store", "guard_house"));

    private final String key;
    private final String title;
    private final String unit;
    private final Set<String> kinds;

    FacilityGroup(String key, String title, String unit, Set<String> kinds) {
        this.key = key;
        this.title = title;
        this.unit = unit;
        this.kinds = kinds;
    }

    /** 응답 JSON의 이름 */
    public String key() {
        return key;
    }

    /** 설명 문장의 한 항목. 예: "CCTV +12대" */
    public String gain(int n) {
        return title + " +" + n + unit;
    }

    /** poi 종류가 속한 묶음. 세지 않는 종류(화장실·쉼터 등)면 null */
    public static FacilityGroup of(String kind) {
        for (FacilityGroup g : values()) {
            if (g.kinds.contains(kind)) {
                return g;
            }
        }
        return null;
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
