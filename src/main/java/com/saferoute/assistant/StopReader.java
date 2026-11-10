package com.saferoute.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.saferoute.assistant.Conversation.Stop;

/**
 * "퇴근길에 다이소 들르고 택배 찾고 집 갈래" 같은 말에서 들를 곳과 마지막 도착을 읽는다 (말로 들를 곳 묶기, 11-09 결정).
 * 택배는 시연 지역에 무인택배함이 거의 없어서(가장 가까운 곳 3km) 편의점 택배로 찾는다.
 */
final class StopReader {

    /** 걷기로 같이 갈 수 있는 들를 곳 수 */
    static final int MAX_STOPS = 3;

    /** 들르는 말. 이 말이 있어야 들를 곳 묶기로 본다 (택배는 "택배 찾고 집 가자"처럼 도착이 따로 있을 때만) */
    private static final Pattern STOP_WORD = Pattern.compile("들르|들러|들렀|들려|들리고");
    /** 들를 곳 사이를 나누는 말 */
    private static final Pattern SPLIT = Pattern.compile(
            "\\s*(?:갔다가|갔다 가서?|다녀와서|다녀오고|들르고서?|들렀다가?|들렀다 가|들러서|들러|들려서|들리고|찾고|찾아서|부치고|보내고|사고|그다음에?|그 다음에?"
                    + "|다음에|그리고|,|→)\\s*");
    /** 도착 뒤에 붙는 가자는 말 */
    private static final Pattern GO_TAIL = Pattern.compile("\\s*(가자|갈래|가 줘|가줘|갈게|가고 싶어|가야 해|갈 거야|가려고|가요)\\s*$");
    /** 도착 자리에 남은 말 끝 조각 ("야 해", "서 가") */
    private static final Pattern TAIL_BITS = Pattern.compile("^(야|서|도|요|줘|봐|볼|다|고)(\\s|$)");
    /** 들를 곳 앞에 붙는 말 */
    private static final List<String> FILLER = List.of("먼저", "우선", "일단", "잠깐", "잠깐만", "좀", "나", "저", "우리",
            "오늘", "지금", "가는", "오는", "길에", "가서", "그리고", "또");

    private StopReader() {
    }

    /**
     * @param stops    들를 곳 (말한 차례대로, 4곳 넘게 말했어도 다 담음)
     * @param ordered  순서를 말했으면 true ("먼저", "그다음")
     * @param dest     마지막 도착 이름 ("집"). 말하지 않았으면 null
     */
    record Read(List<Stop> stops, boolean ordered, String dest) {

        boolean tooMany() {
            return stops.size() > MAX_STOPS;
        }
    }

    /** 들를 곳 묶기 말이 아니면 null */
    static Read read(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flat = text.replace(" ", "");
        List<String> parts = new ArrayList<>();
        for (String p : SPLIT.split(text.strip())) {
            if (!p.isBlank()) {
                parts.addAll(joined(p.strip()));
            }
        }
        boolean stopWord = STOP_WORD.matcher(flat).find();
        boolean parcel = flat.contains("택배") && parts.size() >= 2;
        if (!stopWord && !parcel || parts.isEmpty()) {
            return null;
        }
        // 마지막 조각이 도착 ("집 갈래"). 말이 "약국 들러"처럼 들르는 말로 끝나면 도착은 아직 모름
        String dest = null;
        String last = parts.get(parts.size() - 1);
        boolean endsWithStop = SPLIT.matcher(text.strip()).replaceAll("|").endsWith("|");
        if (!endsWithStop && (parts.size() >= 2 || goes(last))) {
            dest = RuleBrain.destination(last);
            if (dest == null) {
                dest = name(GO_TAIL.matcher(last).replaceAll(""));
            }
            if (dest != null && TAIL_BITS.matcher(dest).find()) {
                return null; // "화장실 들러야 해"처럼 말 끝 조각이면 들를 곳 묶기가 아님
            }
            parts.remove(parts.size() - 1);
        }
        List<Stop> stops = new ArrayList<>();
        for (String p : parts) {
            String n = name(p);
            if (n == null) {
                continue;
            }
            stops.add(n.contains("택배") ? new Stop("편의점", "택배") : new Stop(n, n));
        }
        if (stops.isEmpty()) {
            return null;
        }
        boolean ordered = flat.contains("먼저") || flat.contains("그다음") || flat.contains("다음에")
                || flat.contains("순서대로") || flat.contains("부터");
        return new Read(List.copyOf(stops), ordered, dest == null || dest.isBlank() ? null : dest);
    }

    /** 이어 주는 말 ("다이소랑 약국", "약국하고 편의점", "약국과 편의점") */
    private static final Pattern AND = Pattern.compile("(\\S+?)(이랑|랑|하고|와|과)\\s+");
    /** 과·와로 끝나지만 이어 주는 말이 아닌 이름 (안과, 학과 등. 치과·내과·사과는 받침 규칙으로 걸러짐) */
    private static final List<String> NOT_AND = List.of("안과", "학과", "결과", "성과", "효과", "통과");

    /**
     * "다이소랑 약국이랑 편의점이랑 서점" → [다이소, 약국, 편의점, 서점] (점검 40번, 11-10 재휘).
     * 와·과는 받침에 맞을 때만 나눈다 (받침 없으면 와, 있으면 과). 그래서 "치과 약국"의 치과는 그대로.
     */
    static List<String> joined(String part) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = AND.matcher(part);
        int from = 0;
        while (m.find()) {
            String word = m.group(1);
            String and = m.group(2);
            String whole = word + and;
            if (!fits(word, and) || NOT_AND.stream().anyMatch(whole::endsWith)) {
                continue;
            }
            String piece = (part.substring(from, m.start()) + word).strip();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            from = m.end();
        }
        String rest = part.substring(from).strip();
        if (!rest.isEmpty()) {
            out.add(rest);
        }
        return out;
    }

    /** 이어 주는 말이 앞 글자 받침에 맞는지 (와·랑은 받침 없을 때, 과·이랑은 받침 있을 때, 하고는 늘) */
    private static boolean fits(String word, String and) {
        char c = word.charAt(word.length() - 1);
        if (c < '가' || c > '힣') {
            return !"과".equals(and) && !"와".equals(and); // 영어·숫자 이름 뒤 와·과는 나누지 않음
        }
        boolean batchim = (c - '가') % 28 != 0;
        return switch (and) {
            case "와", "랑" -> !batchim;
            case "과", "이랑" -> batchim;
            default -> true;
        };
    }

    /** "퇴근길에 다이소" → 다이소, "약국에" → 약국. 남는 것이 없으면 null */
    static String name(String part) {
        List<String> kept = new ArrayList<>();
        for (String w : part.strip().split("\\s+")) {
            if (w.isEmpty() || FILLER.contains(w) || w.endsWith("길에") || w.endsWith("길엔")) {
                continue;
            }
            kept.add(w);
        }
        if (kept.isEmpty()) {
            return null;
        }
        String n = RuleReader.stripParticle(String.join(" ", kept));
        if (n.length() > 2 && (n.endsWith("을") || n.endsWith("를"))) {
            n = n.substring(0, n.length() - 1);
        }
        n = n.strip();
        return n.isEmpty() || n.length() > 20 ? null : n;
    }

    private static boolean goes(String s) {
        String f = s.replace(" ", "");
        return f.endsWith("가자") || f.endsWith("갈래") || f.endsWith("가줘") || f.endsWith("갈게") || f.contains("가고싶")
                || f.endsWith("가야해") || f.endsWith("갈거야") || f.endsWith("가려고") || f.endsWith("가요");
    }
}
