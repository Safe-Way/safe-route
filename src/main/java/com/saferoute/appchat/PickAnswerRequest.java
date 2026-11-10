package com.saferoute.appchat;

import java.util.List;

/**
 * POST /api/ai/pick-answer 요청 (민수님 부탁 11/10).
 *
 * @param question 앱이 물은 말 (예: "계단은 어떠세요?")
 * @param choices  답 버튼 글자들, 2~6개 (예: ["못 가요", "몇 칸은 괜찮아요", "괜찮아요"])
 * @param said     사용자가 한 말 (예: "무릎 때문에 계단은 좀…")
 */
public record PickAnswerRequest(String question, List<String> choices, String said) {
}
