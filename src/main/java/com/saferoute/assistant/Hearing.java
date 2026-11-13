package com.saferoute.assistant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 음성 받아쓰기 후보 고르기 (11-12 음성 인식 ②, 민수 앱 #161).
 * 앱은 첫 후보를 text, 나머지를 alternatives(좋은 순서)로 보낸다.
 * <ol>
 *   <li>급한 말("살려 줘")이 후보 어디에라도 있으면 그 후보 (놓치면 더 위험해서)</li>
 *   <li>아니면 길 말로 가장 잘 읽히는 후보 (출발지·들를 곳·도착을 몇 개 알아듣는지). 같으면 앞 후보</li>
 *   <li>그 후보로 답했는데 장소를 못 찾았으면, 길 말로 그만큼 읽히는 다른 후보로 다시 (AssistantService)</li>
 * </ol>
 * 카카오를 부르지 않고 글자만 보고 점수를 매긴다. 다시 보는 것은 장소를 못 찾았을 때만, 최대 {@link #MAX_RETRIES}번.
 */
final class Hearing {

    /** 앱이 보내는 후보는 5개까지 */
    static final int MAX_CANDIDATES = 5;
    /** 장소를 못 찾았을 때 다른 후보로 다시 보는 횟수 (AI를 부르면 그만큼 토큰이 들어서 줄임) */
    static final int MAX_RETRIES = 2;
    private static final int URGENT = 100;

    private Hearing() {
    }

    /**
     * 볼 후보 목록. 맨 앞은 늘 text. 버튼을 눌렀거나(choice) 오늘 제안("(...)")이면 text 하나만.
     * 빈 후보·같은 후보는 뺀다
     */
    static List<String> candidates(AssistantRequest req) {
        String text = req.text() == null ? "" : req.text().strip();
        List<String> one = List.of(text);
        if (req.choice() != null || text.isEmpty() || text.startsWith("(") || req.alternatives() == null) {
            return one;
        }
        Set<String> all = new LinkedHashSet<>();
        all.add(text);
        for (String a : req.alternatives()) {
            if (all.size() == MAX_CANDIDATES) {
                break;
            }
            if (a != null && !a.isBlank()) {
                all.add(a.strip());
            }
        }
        return new ArrayList<>(all);
    }

    /** 처음 쓸 후보 번호 (0 = text) */
    static int best(List<String> heard) {
        int best = 0;
        int bestScore = score(heard.get(0));
        for (int i = 1; i < heard.size(); i++) {
            int s = score(heard.get(i));
            if (s > bestScore) {
                best = i;
                bestScore = s;
            }
        }
        return best;
    }

    /** 길 말로 얼마나 잘 읽히나: 급한 말 100, 출발지 +2, 들를 곳 묶기 +2, 도착 +1 */
    static int score(String raw) {
        String s = SpeechFix.fix(Fillers.clean(raw == null ? "" : raw.strip()));
        if (s.isEmpty()) {
            return 0;
        }
        if (RuleBrain.urgent(s)) {
            return URGENT;
        }
        int score = 0;
        OriginReader.Split from = OriginReader.split(s);
        if (from != null) {
            score += 2;
            s = from.rest();
        }
        if (StopReader.read(s) != null) {
            score += 2;
        }
        if (RuleBrain.destination(s) != null) {
            score += 1;
        }
        return score;
    }
}
