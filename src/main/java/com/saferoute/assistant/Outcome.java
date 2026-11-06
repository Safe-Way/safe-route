package com.saferoute.assistant;

import java.util.List;

/**
 * 비서가 할 말 (규칙이든 AI든 같은 모양).
 *
 * @param say     말풍선 글
 * @param ask     답을 기다리는 질문. 없으면 null
 * @param choices 질문에 붙는 버튼
 * @param ai      AI가 만든 말이면 true
 */
record Outcome(String say, String ask, List<AssistantReply.Choice> choices, boolean ai) {

    static Outcome say(String s) {
        return new Outcome(s, null, List.of(), false);
    }

    static Outcome ask(String q) {
        return new Outcome(q, q, List.of(), false);
    }

    static Outcome ask(String q, List<AssistantReply.Choice> choices) {
        return new Outcome(q, q, choices, false);
    }
}
