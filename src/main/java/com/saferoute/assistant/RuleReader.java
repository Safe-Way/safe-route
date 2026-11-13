package com.saferoute.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * AI 없이 낱말로 모드와 목적지를 알아듣는다 (RuleBrain 이 씀).
 * 예: "할머니랑 양동시장 가고 싶어" → 어르신(senior), 도착 "양동시장"
 */
final class RuleReader {

    /** 이 낱말이 들어 있으면 그 모드. 위에 있는 것부터 본다 (휠체어가 가장 먼저) */
    private static final Map<String, List<String>> MODE_WORDS = new java.util.LinkedHashMap<>();
    static {
        MODE_WORDS.put("wheelchair", List.of("휠체어"));
        MODE_WORDS.put("senior", List.of("할머니", "할아버지", "어르신", "노인", "할매", "할배"));
        MODE_WORDS.put("child", List.of("어린이", "아이", "애기", "아기", "아들", "딸", "초등", "유치원", "꼬마", "애랑", "애들"));
        MODE_WORDS.put("adult", List.of("어른", "혼자", "성인"));
    }

    /** "○○에 가고 싶어", "○○로 갈래" 처럼 가고 싶다는 말. 이 말 앞이 장소 이름 */
    private static final List<String> GO_WORDS = List.of("가고", "가는", "갈래", "갈게", "가려", "가자", "가야",
            "데려다", "데려가", "가요", "갈까", "갈거", "갈꺼", "갈께", "가려구", "가", "갈");

    /** 도착 이름에서 뺄 말 (같이 가는 사람, 높임말 등) */
    private static final Pattern WITH = Pattern.compile("^.*(랑|이랑|하고|와|과|께서|가|이|는|은)$");

    private static final Pattern NUMBER_PARTICLE = Pattern.compile("^(.*\\d)(?:번지)?(?:으로|로|까지|에)$");

    private RuleReader() {
    }

    static String mode(String t) {
        for (Map.Entry<String, List<String>> e : MODE_WORDS.entrySet()) {
            for (String w : e.getValue()) {
                if (t.contains(w)) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    static String destination(String t, boolean askedDestination) {
        String raw = beforeGoWord(t);
        if (raw == null && askedDestination) {
            raw = t;
        }
        if (raw == null) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String word : raw.strip().split("\\s+")) {
            if (word.isEmpty() || isModeWord(word) || isFiller(word) || isFiller(stripParticle(word))) {
                continue;
            }
            if (kept.isEmpty() && WITH.matcher(word).matches() && word.length() > 1 && isPersonish(word)) {
                continue;
            }
            kept.add(stripParticle(word));
        }
        String name = String.join(" ", kept).strip();
        return name.isEmpty() ? null : name;
    }

    /** 낱말 단위로 보고 가고 싶다는 말이 처음 나오기 전까지의 글. 없으면 null */
    private static String beforeGoWord(String t) {
        String[] words = t.split("\\s+");
        for (int i = 1; i < words.length; i++) {
            String w = words[i];
            for (String g : GO_WORDS) {
                if (w.startsWith(g) && (g.length() > 1 || w.length() <= 2)) {
                    return String.join(" ", java.util.Arrays.copyOfRange(words, 0, i));
                }
            }
        }
        // "집갈거야", "문화전당가자"처럼 마지막 낱말에 가는 말이 붙어 있으면 그 앞까지 (11-12 음성 시험 모음 21번)
        String last = words[words.length - 1];
        for (int k = 1; k < last.length() - 1; k++) {
            String tail = last.substring(k);
            for (String g : GO_WORDS) {
                if (g.length() > 1 && !"가는".equals(g) && tail.startsWith(g)) {
                    String head = last.substring(0, k);
                    return words.length == 1 ? head
                            : String.join(" ", java.util.Arrays.copyOfRange(words, 0, words.length - 1)) + " " + head;
                }
            }
        }
        return null;
    }

    private static boolean isModeWord(String w) {
        return MODE_WORDS.values().stream().flatMap(List::stream).anyMatch(w::startsWith);
    }

    private static boolean isFiller(String w) {
        return List.of("나", "저", "우리", "지금", "오늘", "같이", "좀", "빨리", "길", "알려줘", "알려", "주세요", "싶어", "싶어요",
                "쪽", "쪽으로", "쪽에", "방향", "방면")
                .contains(w);
    }

    /** "엄마랑", "친구하고" 처럼 같이 가는 사람 */
    private static boolean isPersonish(String w) {
        return w.endsWith("랑") || w.endsWith("하고");
    }

    /**
     * 끝에 붙은 "까지", "으로", "에", "로" 를 뗀다.
     * "로"는 받침 없는 글자나 ㄹ 받침 뒤에만 붙으므로 그때만 뗀다 ("학교로" → 학교, "충장로"·"금남로"는 그대로).
     */
    static String stripParticle(String w) {
        // 번지 뒤 조사 ("서석동 3으로", "충장로 45-1로"): 숫자 뒤는 받침과 상관없이 뗀다 (11-12 주소로 말하기)
        java.util.regex.Matcher num = NUMBER_PARTICLE.matcher(w);
        if (num.matches()) {
            return num.group(1);
        }
        for (String p : List.of("까지", "으로", "에")) {
            // 한 글자 이름도 뗀다 ("집까지" → 집, 시험 모음 16번)
            if (w.endsWith(p) && w.length() > p.length()) {
                return w.substring(0, w.length() - p.length());
            }
        }
        if (w.endsWith("로") && w.length() > 2) {
            char before = w.charAt(w.length() - 2);
            if (before >= '가' && before <= '힣') {
                int batchim = (before - '가') % 28;
                if (batchim == 0 || batchim == 8) { // 0 = 받침 없음, 8 = ㄹ
                    return w.substring(0, w.length() - 1);
                }
            }
        }
        return w;
    }
}
