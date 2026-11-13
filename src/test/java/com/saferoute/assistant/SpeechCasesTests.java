package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 음성 길 말 시험 모음 (11-12, src/test/resources/assistant/speech_cases.json).
 * 비서와 같은 순서(뜸 말 빼기 → 받아쓰기 고치기 → 출발지 떼기 → 들를 곳·도착 읽기)로 읽어서
 * 출발지·들를 곳·도착 이름이 맞는지 본다. 카카오·AI는 부르지 않는다 (장소를 찾는지는 따로 시험).
 */
class SpeechCasesTests {

    /** 알아들은 것 */
    record Read(String origin, List<String> stops, String destination) {
    }

    static Read read(String said) {
        String s = SpeechFix.fix(Fillers.clean(said));
        String origin = null;
        OriginReader.Split from = OriginReader.split(s);
        if (from != null) {
            origin = from.origin();
            s = from.rest();
        }
        StopReader.Read r = StopReader.read(s);
        if (r != null) {
            return new Read(origin, r.stops().stream().map(Conversation.Stop::query).toList(), r.dest());
        }
        return new Read(origin, List.of(), RuleBrain.destination(s));
    }

    @Test
    void spokenTripsAreUnderstood() throws Exception {
        JsonNode all;
        try (InputStream in = getClass().getResourceAsStream("/assistant/speech_cases.json")) {
            all = JsonMapper.builder().build().readTree(in);
        }
        List<String> wrong = new ArrayList<>();
        int n = 0;
        for (JsonNode c : all.get("cases")) {
            n++;
            String say = c.get("say").asString();
            List<String> stops = new ArrayList<>();
            if (c.has("stops")) {
                c.get("stops").forEach(x -> stops.add(x.asString()));
            }
            Read want = new Read(text(c, "origin"), stops, text(c, "destination"));
            Read got = read(say);
            if (!Objects.equals(want, got)) {
                wrong.add(c.get("id").asInt() + "번 [" + c.get("group").asString() + "] \"" + say + "\"\n    기대 " + want
                        + "\n    실제 " + got);
            }
        }
        System.out.println("음성 길 말 시험: " + (n - wrong.size()) + "/" + n + " 통과");
        wrong.forEach(System.out::println);
        assertThat(wrong).isEmpty();
    }

    private static String text(JsonNode c, String key) {
        return c.has(key) && !c.get(key).isNull() ? c.get(key).asString() : null;
    }
}
