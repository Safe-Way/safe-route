package com.saferoute.place;

import java.util.Map;

/**
 * 사람들이 부르는 이름 → 카카오에 등록된 이름 (11-13 현장 시험).
 * "문화전당"은 카카오 가까운 순에서 "문화전당오피스텔"이 먼저 나오고, "금남 지하상가"는 띄어 쓰면 나주 아파트가 나왔다.
 * 띄어쓰기는 무시하고 비교한다. 새로 넣을 때는 카카오에서 이름이 똑같이 나오는지 먼저 확인할 것.
 */
public final class PlaceAlias {

    private static final Map<String, String> ALIAS = Map.of(
            "문화전당", "국립아시아문화전당",
            "아시아문화전당", "국립아시아문화전당",
            "아문당", "국립아시아문화전당",
            "금남지하상가", "금남지하상가",
            "금남지하도상가", "금남지하상가",
            "금남로지하상가", "금남지하상가");

    private PlaceAlias() {
    }

    /** 등록된 이름이 있으면 그 이름, 없으면 받은 그대로 */
    public static String of(String name) {
        if (name == null) {
            return null;
        }
        String to = ALIAS.get(name.replaceAll("\\s+", ""));
        return to == null ? name : to;
    }

    /** 이름이 똑같은지 (띄어쓰기 무시) */
    public static boolean same(String a, String b) {
        return a != null && b != null && a.replaceAll("\\s+", "").equals(b.replaceAll("\\s+", ""));
    }
}
