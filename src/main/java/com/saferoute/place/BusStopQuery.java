package com.saferoute.place;

import java.util.regex.Pattern;

/**
 * "근처 버스", "가까운 버스 정류장", "버스 타는 곳"처럼 가까운 버스 정류장을 찾는 말인지 본다 (11-13).
 * 카카오 키워드 검색에 "버스"를 넣으면 이름에 '버스'가 든 가게(예: ○버스 베이크샵)나 먼 정류장이 섞여서,
 * 이런 말이면 카카오 대신 팀 DB bus_stop 표(광주 버스정보 정류장)에서 가까운 순으로 찾는다.
 */
public final class BusStopQuery {

    /** 빼도 뜻이 같은 말: 근처·가까운·보여 줘·어디야 등 */
    private static final Pattern FILLER = Pattern.compile(
            "지금|여기|내|제일|가장|근처|가까운|가까이|가까|주변|있는|에있는|어디야|어디있어|어딨어|어디|어딨|"
                    + "보여줘|보여|알려줘|알려|찾아줘|찾아|좀|요|\\?|!|\\.|,");
    /** 남은 말이 이것뿐이면 정류장 찾기: 버스, 정류장, 버스 정류장, 버스 정류소, 버스 승강장, 버스 타는 곳 */
    private static final Pattern STOP = Pattern.compile("버스(정류장|정류소|승강장|타는곳|탈수있는곳|타는데)?|정류장|정류소");

    private BusStopQuery() {
    }

    public static boolean asks(String text) {
        if (text == null) {
            return false;
        }
        String rest = FILLER.matcher(text.replaceAll("\\s+", "")).replaceAll("");
        return STOP.matcher(rest).matches();
    }

    /** 정류장 이름으로 쓸 말: "조선대해오름관" → "조선대해오름관 정류장" (이미 정류장·승강장이면 그대로) */
    public static String stopName(String name) {
        String n = name == null ? "" : name.strip();
        return n.endsWith("정류장") || n.endsWith("정류소") || n.endsWith("승강장") ? n : n + " 정류장";
    }
}
