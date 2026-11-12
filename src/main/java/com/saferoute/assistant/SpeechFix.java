package com.saferoute.assistant;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 폰 받아쓰기가 자주 틀리는 글자를 맞는 글자로 바꾼다 (11-12 음성 인식 ③).
 * 소리 나는 대로 적은 받침("갓다가", "들럿다")과, 주소 번지를 말 숫자로 적은 것("서석동 삼 번지")만 고친다.
 * 말을 읽기 전에 한 번 (Fillers 다음).
 */
final class SpeechFix {

    /** 틀린 글자 → 맞는 글자 (말 안 어디에 있어도 바꿈. 다른 낱말에 들어갈 일이 없는 것만) */
    private static final Map<String, String> TYPO = new LinkedHashMap<>();

    static {
        TYPO.put("갓다가", "갔다가");
        TYPO.put("갓다 가", "갔다 가");
        TYPO.put("갓다 ", "갔다 ");
        TYPO.put("들럿다", "들렀다");
        TYPO.put("들렷다", "들렀다");
        TYPO.put("들럿", "들렀");
        TYPO.put("다녀와써", "다녀와서");
        TYPO.put("갈꺼", "갈거");
        TYPO.put("갈께", "갈게");
        TYPO.put("가께", "갈게");
        TYPO.put("들를께", "들를게");
    }

    /** 동·로·길·가 뒤 말 숫자 번지 ("서석동 삼", "충장로 사십오 번지", "서석동 삼번지로") */
    private static final Pattern SPOKEN_NUMBER = Pattern.compile(
            "(\\S(?:동|로|길|가))\\s*([일이삼사오육칠팔구십백천]+)(?:\\s*번지|\\s*번)?(?=$|\\s|으로|로|에|까지)");

    private SpeechFix() {
    }

    static String fix(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = text;
        for (Map.Entry<String, String> e : TYPO.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        // "3 번지", "3번지" → 3 (번지는 주소 검색에 안 붙는 게 잘 찾음)
        out = out.replaceAll("(\\d)\\s*번지", "$1");
        Matcher m = SPOKEN_NUMBER.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Integer n = number(m.group(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(n == null ? m.group() : m.group(1) + " " + n));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 일·이·삼 … 구천구백구십구 → 숫자. 말 숫자가 아니면 null ("이" 하나는 조사일 수 있어서 null) */
    static Integer number(String s) {
        if (s == null || s.isEmpty() || "이".equals(s)) {
            return null;
        }
        String digits = "일이삼사오육칠팔구";
        int total = 0;
        int cur = 0;
        for (char c : s.toCharArray()) {
            int d = digits.indexOf(c);
            if (d >= 0) {
                if (cur != 0) {
                    return null; // "삼사"처럼 숫자가 이어지면 말 숫자가 아님
                }
                cur = d + 1;
                continue;
            }
            int unit = switch (c) {
                case '십' -> 10;
                case '백' -> 100;
                case '천' -> 1000;
                default -> -1;
            };
            if (unit < 0) {
                return null;
            }
            total += (cur == 0 ? 1 : cur) * unit;
            cur = 0;
        }
        return total + cur;
    }
}
