package com.saferoute.place;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "버스 03", "03버스", "03번 버스", "수완03 버스", "첨단09번" → 노선 번호 (11-13). 아니면 null */
public final class BusLineQuery {

    private static final Pattern LINE = Pattern.compile(
            "^(?:버스\\s*)?([가-힣]{0,4}\\d{1,4})\\s*(?:번)?\\s*(?:버스)?\\s*(?:노선|정보|어디로\\s*가|어디\\s*가)?\\s*\\??$");

    private BusLineQuery() {
    }

    public static String number(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        // '버스'라는 말이 있어야 노선으로 본다 ("서석동 3" 주소, 고르기 버튼 "2번"과 헷갈리지 않게)
        if (!t.contains("버스")) {
            return null;
        }
        Matcher m = LINE.matcher(t);
        return m.matches() ? m.group(1) : null;
    }

    /**
     * 버스를 찾을 때 기준으로 삼을 곳 (11-13). 충장로처럼 긴 거리는 카카오가 길 한가운데 한 점만 줘서 근처 정류장이 빠지니,
     * 그 거리의 중심 건물로 바꿔 찾는다. 없으면 말한 그대로
     */
    public static String anchor(String place) {
        String p = place == null ? "" : place.replaceAll("\\s+", "");
        if (p.startsWith("충장로") || p.equals("구도청") || p.equals("옛도청") || p.equals("구전남도청") || p.equals("구시청")
                || p.equals("금남로") || p.startsWith("금남로") && p.endsWith("가")) {
            return "국립아시아문화전당";
        }
        return place;
    }

    /**
     * 그곳으로 가는 버스로 볼 정류장 이름 앞부분 (11-13). 충장로는 "충장로…" 정류장이 거의 없고
     * 구시청·문화전당역·금남로 정류장에서 내려서 걸어감 (현장 확인: 구 시청 정류장)
     */
    public static java.util.List<String> stopNames(String place) {
        if (!"국립아시아문화전당".equals(anchor(place)) || place.replaceAll("\\s+", "").equals("국립아시아문화전당")) {
            return java.util.List.of();
        }
        return java.util.List.of("국립아시아문화전당", "구시청", "문화전당", "금남로", "충장로", "구도청");
    }

    /** 노선 끝 이름과 맞춰 볼 말: "수완동" → "수완", "광주역" → "광주역" (동네 이름 끝 '동'은 종점 이름에 잘 안 붙어서 뺌) */
    public static String stem(String place) {
        String p = place.replaceAll("\\s+", "");
        return p.length() >= 3 && p.endsWith("동") ? p.substring(0, p.length() - 1) : p;
    }

    private static final Pattern GOING = Pattern.compile(
            "^(.+?)\\s*(가는|행)\\s*버스\\s*(알려\\s*줘|뭐야|뭐\\s*있어|있어|어디서\\s*타|좀)?\\s*[?.!]?$");

    /** "수완동으로 가는 버스 알려줘" → "수완동". 아니면 null */
    public static String goingTo(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = GOING.matcher(text.strip());
        if (!m.matches()) {
            return null;
        }
        String p = PlaceController.dropParticle(m.group(1).strip());
        return p.length() >= 2 ? p : null;
    }
}
