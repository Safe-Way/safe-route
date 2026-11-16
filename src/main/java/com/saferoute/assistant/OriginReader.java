package com.saferoute.assistant;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "광주역에서 다이소 갔다가 집 갈래", "충장로에서 문화전당 가자"처럼 맨 앞에 말한 출발지를 뗀다 (11-12 폰 시험: 출발지를 못 읽음).
 * 뗀 나머지가 길 찾는 말(도착이나 들를 곳이 읽히는 말)일 때만 출발지로 본다. "여기서"·"지금 위치에서"는 지금 위치 그대로.
 */
final class OriginReader {

    /** 맨 앞 "○○에서 " (○○는 다섯 낱말·20자까지, "광주 동구 서석동 3에서"처럼 주소도) */
    private static final Pattern FROM = Pattern.compile("^\\s*(\\S+(?:\\s+\\S+){0,4}?)(?:에서부터|에서)\\s+(.+)$");
    /**
     * 들를 곳을 잇는 말 ("들렀다가", "갔다가", "들르고"). "다이소 들렀다가 편의점에서 택배 찾고"의 "…편의점에서"는
     * 출발지가 아니라 들를 곳이다 (11-16 시험 38번: "다이소 들렀다가 편의점"을 출발지로 찾아 다이소가 빠짐)
     */
    private static final Pattern STOP_WORD = Pattern.compile("다가|들르|들러|들렀|찾고|사고");
    /** "여기서 문화전당 가자"처럼 지금 위치에서 간다는 말 */
    private static final Pattern HERE_FROM = Pattern.compile(
            "^\\s*(?:지금\\s*)?(?:여기서|여기에서|이곳에서|이 곳에서|(?:내|현재|지금)\\s*위치에서)(?:부터)?\\s+(.+)$");
    /** 지금 위치라는 말 (출발지를 따로 찾지 않음) */
    private static final List<String> HERE = List.of("여기", "지금여기", "이곳", "지금위치", "현재위치", "내위치", "지금있는곳",
            "제가있는곳", "내가있는곳");

    private OriginReader() {
    }

    /**
     * @param origin 출발지 이름 ("광주역"). 지금 위치라고 말했으면 null
     * @param rest   출발지를 뗀 나머지 말 ("다이소 갔다가 집 갈래")
     */
    record Split(String origin, String rest) {
    }

    /** 출발지를 말하지 않았으면 null */
    static Split split(String text) {
        if (text == null) {
            return null;
        }
        Matcher h = HERE_FROM.matcher(text);
        if (h.matches()) {
            String rest = h.group(1).strip();
            return StopReader.read(rest) == null && RuleBrain.destination(rest) == null ? null : new Split(null, rest);
        }
        Matcher m = FROM.matcher(text);
        if (!m.matches()) {
            return null;
        }
        String name = m.group(1).strip();
        String rest = m.group(2).strip();
        String flat = name.replace(" ", "");
        if (flat.isEmpty() || flat.length() > 20 || flat.contains("어디") || flat.contains("거기")
                || STOP_WORD.matcher(flat).find()) {
            return null;
        }
        // 뗀 나머지가 길 찾는 말이어야 출발지 ("학교에서 넘어졌어"는 아님)
        if (StopReader.read(rest) == null && RuleBrain.destination(rest) == null) {
            return null;
        }
        if (HERE.contains(flat)) {
            return new Split(null, rest);
        }
        return new Split(name, rest);
    }
}
