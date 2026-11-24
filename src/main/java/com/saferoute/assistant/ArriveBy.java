package com.saferoute.assistant;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "3시까지 문화전당 가야 해", "오후 2시 반에 광주역 도착해야 돼"처럼 도착할 시각을 말하면 떼어 두고,
 * 길을 고른 뒤 "2시 25분쯤 나가시면 돼요"라고 출발 시각을 알려 준다 (2026-11-23).
 * 출발 시각 = 도착 시각 − 걸리는 시간 − 여유(어른·어린이 5분, 어르신·휠체어 10분).
 */
final class ArriveBy {

    static final int BUFFER_MIN = 5;
    static final int BUFFER_SLOW_MIN = 10;

    private static final Map<String, Integer> WORD_HOURS = Map.ofEntries(
            Map.entry("한", 1), Map.entry("두", 2), Map.entry("세", 3), Map.entry("네", 4), Map.entry("다섯", 5),
            Map.entry("여섯", 6), Map.entry("일곱", 7), Map.entry("여덟", 8), Map.entry("아홉", 9), Map.entry("열", 10),
            Map.entry("열한", 11), Map.entry("열두", 12));

    /** (때) 시 (반 | N분) 뒤에 "까지"·"전까지"·"에" */
    private static final Pattern TIME = Pattern.compile(
            "(?:(오전|오후|아침|낮|저녁|밤|새벽)\\s*)?(\\d{1,2}|열두|열한|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*시"
                    + "(?:\\s*(반|\\d{1,2}\\s*분))?\\s*(전\\s*까지(?:는|만)?|까지(?:는|만)?|에)(?=\\s|$)\\s*");
    /** "에"로 끝날 때는 도착·약속 말이 있어야 도착 시각으로 본다 ("3시에 나갈래"는 출발 시각이라 안 봄) */
    private static final Pattern ARRIVE_WORD = Pattern.compile("도착|약속");
    /** 도착 말을 길 찾는 말로 ("광주역 도착해야 돼" → "광주역 가야 해") */
    private static final Pattern ARRIVE_TAIL = Pattern.compile(
            "\\s*(?:에\\s*)?(?:도착(?:해야|하려면|해|하게|할)?|약속(?:이)?\\s*(?:있어|있어요|있는데)?)\\s*(?:돼|해|되|요|거든|는데)?\\s*[.!?]?$");

    private ArriveBy() {
    }

    /**
     * @param at   도착할 시각 (지금보다 뒤)
     * @param rest 시각 말을 뗀 나머지 ("문화전당 가야 해"). "도착해야 해"만 남으면 "가야 해"로 바꿈
     */
    record Split(LocalDateTime at, String rest) {
    }

    /** 도착 시각을 말하지 않았으면 null */
    static Split split(String text, LocalDateTime now) {
        if (text == null || now == null) {
            return null;
        }
        Matcher m = TIME.matcher(text);
        if (!m.find()) {
            return null;
        }
        Integer h = hour(m.group(2));
        if (h == null || h > 24) {
            return null;
        }
        int min = 0;
        String part = m.group(3);
        if (part != null) {
            if (part.startsWith("반")) {
                min = 30;
            } else {
                min = Integer.parseInt(part.replaceAll("\\D", ""));
                if (min > 59) {
                    return null;
                }
            }
        }
        LocalDateTime at = resolve(m.group(1), h, min, now);
        if (at == null) {
            return null;
        }
        if (m.group(4).startsWith("에") && !ARRIVE_WORD.matcher(text).find()) {
            return null;
        }
        String rest = (text.substring(0, m.start()) + " " + text.substring(m.end())).replaceAll("\\s+", " ").strip();
        Matcher tail = ARRIVE_TAIL.matcher(rest);
        if (tail.find() && tail.start() > 0) {
            rest = rest.substring(0, tail.start()).strip().replaceFirst("(?:에서|에)$", "") + " 가야 해";
        }
        return rest.isEmpty() ? null : new Split(at, rest);
    }

    private static Integer hour(String s) {
        if (s.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(s);
        }
        return WORD_HOURS.get(s);
    }

    /**
     * 몇 시를 오늘·내일의 시각으로. 오전·오후를 말하지 않으면 지금 뒤에 오는 가장 가까운 때
     * (오전 11시에 "3시까지"면 오후 3시). 오늘 다 지났으면 내일
     */
    static LocalDateTime resolve(String when, int h, int min, LocalDateTime now) {
        if (h == 24) {
            h = 0;
        }
        if (when != null) {
            boolean pm = when.equals("오후") || when.equals("저녁") || when.equals("밤") || (when.equals("낮") && h < 6);
            int hh = pm && h < 12 ? h + 12 : (!pm && h == 12 ? 0 : h);
            if (when.equals("낮") && h == 12) {
                hh = 12;
            }
            LocalDateTime at = now.toLocalDate().atTime(LocalTime.of(hh, min));
            return at.isAfter(now) ? at : at.plusDays(1);
        }
        int[] tries = h >= 1 && h <= 11 ? new int[] {h, h + 12} : new int[] {h % 24};
        for (int hh : tries) {
            LocalDateTime at = now.toLocalDate().atTime(LocalTime.of(hh, min));
            if (at.isAfter(now)) {
                return at;
            }
        }
        // 오늘 다 지남 → 내일 (새벽 1~6시는 드물어서 오후로 봄)
        int hh = h >= 1 && h <= 6 ? h + 12 : h % 24;
        return now.toLocalDate().plusDays(1).atTime(LocalTime.of(hh, min));
    }

    /**
     * 출발 시각 안내 한 문장.
     *
     * @param minutes 고른 길로 걸리는 시간(분)
     * @param mode    지금 모드 (어르신·휠체어는 여유 10분)
     */
    static String sentence(LocalDateTime at, LocalDateTime now, int minutes, String mode) {
        int buffer = "senior".equals(mode) || "wheelchair".equals(mode) ? BUFFER_SLOW_MIN : BUFFER_MIN;
        String by = (at.toLocalDate().isAfter(now.toLocalDate()) ? "내일 " : "") + KoreanTime.clock(at.toLocalTime());
        LocalDateTime leave = at.minusMinutes(minutes + buffer);
        if (leave.isAfter(now.plusMinutes(1))) {
            String day = leave.toLocalDate().isAfter(now.toLocalDate()) ? "내일 " : "";
            return by + "까지 가시려면 " + day + KoreanTime.clock(leave.toLocalTime()) + "쯤 나가시면 돼요"
                    + " (가는 데 " + minutes + "분, 여유 " + buffer + "분).";
        }
        LocalDateTime arrive = now.plusMinutes(minutes);
        if (!arrive.isAfter(at)) {
            return "지금 바로 나가시면 " + by + " 전에 도착해요.";
        }
        long late = Math.max(1, Duration.between(at, arrive).toMinutes());
        return "지금 나가셔도 " + by + "보다 " + late + "분쯤 늦어요. 늦는다고 미리 알려 두시면 좋겠어요.";
    }
}
