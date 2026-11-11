package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 비서 점검 질문(docs/assistant/eval_questions.json) 모두, 고른 도구 안에 필요한 도구(expect_tools)가 다 들어 있는지
 * (비서 토큰 줄이기 R6, 다 됐다는 기준 5). 제미나이를 안 불러서 비용 없음.
 * <ul>
 * <li>"(오늘 제안)" 질문은 제미나이가 아니라 오늘 제안(TodayService)이 답하므로 뺀다.
 * <li>이어지는 말("context": "7번 바로 뒤")은 앞 질문 뒤 상태를 만든다. last_tools 칸이 있으면 그것을 바로 전 도구로 쓰고,
 * 없으면 앞 질문이 확인을 기다리게 한 저장 도구(PENDING)를 쓴다.
 * <li>expect_tools 중 제미나이에 보내는 도구(ToolPicker.ALL)만 본다. plan_stops 처럼 규칙이 제미나이 없이 직접 쓰는 도구는
 * 묶음에 없으므로 뺀다 (37~39번, 2026-11-11 재휘님 #193).
 * </ul>
 */
class ToolPickerEvalTests {

    /** 앞 질문이 "맞으세요?" 하고 확인을 기다리게 한 저장 도구 (7번 지도 표시, 14번 신고) */
    static final Map<Integer, String> PENDING = Map.of(8, "set_map_layers", 15, "report_problem");

    @Test
    void everyEvalQuestionGetsTheToolsItNeeds() {
        JsonNode root = JsonMapper.builder().build().readTree(new File("docs/assistant/eval_questions.json"));
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (JsonNode q : root.path("questions")) {
            String text = q.path("text").asString("");
            if (text.startsWith("(")) {
                continue;
            }
            List<String> expect = geminiTools(q);
            Conversation c = Conversation.start();
            List<String> last = new ArrayList<>();
            q.path("last_tools").forEach(n -> last.add(n.asString()));
            if (!last.isEmpty()) {
                c = c.withLastTools(last);
            }
            int id = q.path("id").asInt();
            if (q.has("context") && PENDING.containsKey(id)) {
                c = c.withPending(new Conversation.Pending(PENDING.get(id), Map.of(), "맞으세요?", null));
            }
            AssistantRequest req = new AssistantRequest(null, text, 35.148, 126.926, null, null, null, null, null, null);
            List<String> picked = ToolPicker.pick(new Turn(req, c, LocalDateTime.of(2026, 11, 9, 12, 0), false, null));
            checked++;
            if (!picked.containsAll(expect)) {
                missing.add(id + "번 \"" + text + "\": 필요 " + expect + ", 고름 " + picked);
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(33);
        assertThat(missing).as("고른 도구에 필요한 도구가 빠진 질문").isEmpty();
    }

    @Test
    void mostQuestionsSendFewerThanSeven() {
        // 줄이는 효과가 실제로 있는지: 도구가 필요한 질문은 7개보다 적게 보내야 한다
        JsonNode root = JsonMapper.builder().build().readTree(new File("docs/assistant/eval_questions.json"));
        int needTools = 0;
        int narrowed = 0;
        for (JsonNode q : root.path("questions")) {
            String text = q.path("text").asString("");
            if (text.startsWith("(") || geminiTools(q).isEmpty() || q.has("context")) {
                continue;
            }
            needTools++;
            AssistantRequest req = new AssistantRequest(null, text, 35.148, 126.926, null, null, null, null, null, null);
            Turn t = new Turn(req, Conversation.start(), LocalDateTime.of(2026, 11, 9, 12, 0), false, null);
            if (ToolPicker.pick(t).size() < ToolPicker.ALL.size()) {
                narrowed++;
            }
        }
        assertThat(narrowed).isEqualTo(needTools);
    }

    /** 이 질문의 expect_tools 중 제미나이에 보내는 도구만 (규칙 도구 plan_stops 등은 뺌) */
    static List<String> geminiTools(JsonNode q) {
        List<String> out = new ArrayList<>();
        q.path("expect_tools").forEach(n -> out.add(n.asString()));
        out.retainAll(ToolPicker.ALL);
        return out;
    }

    @Test
    void ruleOnlyToolsAreNotExpectedFromThePicker() {
        // 37~39번은 expect_tools 가 ["plan_stops"] 뿐: 들를 곳은 규칙(StopReader·StopPlanner)이 먼저 처리한다
        JsonNode root = JsonMapper.builder().build().readTree(new File("docs/assistant/eval_questions.json"));
        for (JsonNode q : root.path("questions")) {
            List<String> all = new ArrayList<>();
            q.path("expect_tools").forEach(n -> all.add(n.asString()));
            if (all.contains("plan_stops")) {
                assertThat(geminiTools(q)).doesNotContain("plan_stops");
            }
        }
        assertThat(ToolPicker.ALL).doesNotContain("plan_stops");
    }
}
