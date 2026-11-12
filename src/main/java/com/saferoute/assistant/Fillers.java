package com.saferoute.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 말로 할 때 끼는 뜸 들이는 말을 뺀다 ("어.. 어디더라 잠시만.. 아 남광주시장으로 갔다 …", 11-12 음성 시험).
 * 낱말 하나가 통째로 뜸 말일 때만 빼서 "아파", "어린이" 같은 말은 그대로 둔다. 빼고 남는 게 없으면 원래 말 그대로.
 * "아니"·"맞다"는 저장 전 확인의 답일 수 있어서 빼지 않는다.
 */
final class Fillers {

    private static final Set<String> WORDS = Set.of("어", "어어", "음", "음음", "으음", "흠", "아", "아아", "에", "에이", "저",
            "저기", "저기요", "그", "그게", "그러니까", "그니까", "있잖아", "있잖아요", "뭐더라", "어디더라", "뭐지", "어디지",
            "잠시만", "잠시만요", "잠깐", "잠깐만", "잠깐만요", "글쎄");

    private Fillers() {
    }

    static String clean(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        List<String> kept = new ArrayList<>();
        for (String w : text.strip().split("\\s+")) {
            // 끝에 붙은 점·느낌표·물음표·물결은 떼고 본다 ("어..", "잠시만..", "갈거야!")
            String bare = w.replaceAll("[.。…!！?？~,]+$", "").replaceAll("^[.。…]+", "");
            if (bare.isEmpty() || WORDS.contains(bare)) {
                continue;
            }
            kept.add(bare);
        }
        String out = String.join(" ", kept).strip();
        return out.isEmpty() ? text : out;
    }
}
