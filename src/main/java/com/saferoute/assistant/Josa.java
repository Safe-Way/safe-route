package com.saferoute.assistant;

/** 한국어 조사 고르기: 앞말 끝 글자에 받침이 있으면 "은·이", 없으면 "는·가". */
final class Josa {

    private Josa() {
    }

    /** "화장실" → "화장실은", "약국 근처" → "약국 근처는" */
    static String eun(String word) {
        return word + (batchim(word) ? "은" : "는");
    }

    /** "화장실" → "화장실이", "카페" → "카페가" */
    static String iGa(String word) {
        return word + (batchim(word) ? "이" : "가");
    }

    /** "육교" → "육교를", "화장실" → "화장실을" */
    static String eul(String word) {
        return word + (batchim(word) ? "을" : "를");
    }

    /** "학교" → "학교로", "지팡이" → "지팡이로", "버스 30번" → "버스 30번으로", "휠체어"·"충장로"(ㄹ 받침) → "…로" */
    static String ro(String word) {
        return word + (batchim(word) && !rieul(word) ? "으로" : "로");
    }

    private static final java.util.regex.Pattern PICK = java.util.regex.Pattern.compile(
            "([가-힣A-Za-z0-9]+)(['\"’)]?)(을\\(를\\)|\\(으\\)로|이\\(가\\)|\\(이\\)가|은\\(는\\))");

    /** 문장 안의 "을(를)", "(으)로", "이(가)", "은(는)"을 앞말에 맞는 조사 하나로 바꾼다 (AI 문장에도 씀) */
    static String fix(String text) {
        if (text == null || text.indexOf('(') < 0) {
            return text;
        }
        java.util.regex.Matcher m = PICK.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String w = m.group(1);
            boolean b = batchim(w);
            String j = switch (m.group(3)) {
                case "을(를)" -> b ? "을" : "를";
                case "(으)로" -> b && !rieul(w) ? "으로" : "로";
                case "은(는)" -> b ? "은" : "는";
                default -> b ? "이" : "가";
            };
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(w + m.group(2) + j));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 마지막 한글이 ㄹ 받침이면 true ("로"가 붙음) */
    static boolean rieul(String word) {
        for (int i = word.length() - 1; i >= 0; i--) {
            char c = word.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) {
                return (c - 0xAC00) % 28 == 8;
            }
            if (Character.isDigit(c)) {
                return c == '1' || c == '7' || c == '8'; // 일·칠·팔
            }
            if (Character.isLetter(c)) {
                return c == 'l' || c == 'L';
            }
        }
        return false;
    }

    /** 끝의 따옴표·괄호는 건너뛰고 마지막 글자를 본다. 한글이 아니면 숫자 읽기로 판단한다. */
    static boolean batchim(String word) {
        if (word == null) {
            return false;
        }
        for (int i = word.length() - 1; i >= 0; i--) {
            char c = word.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) {
                return (c - 0xAC00) % 28 != 0;
            }
            if (Character.isDigit(c)) {
                return "013678".indexOf(c) >= 0; // 영·일·삼·육·칠·팔
            }
            if (Character.isLetter(c)) {
                return "lmnLMN".indexOf(c) >= 0;
            }
        }
        return false;
    }
}
