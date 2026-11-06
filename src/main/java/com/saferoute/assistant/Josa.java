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
