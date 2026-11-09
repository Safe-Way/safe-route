package com.saferoute.assistant;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.json.JsonMapper;

/**
 * 말에 맞는 도구만 골라 제미나이에게 보낸다 (비서 토큰 줄이기 R2, docs/assistant/tool_selection_rag.md).
 * 도구 설명 7개를 늘 다 보내면 왕복마다 입력 토큰이 늘어서, 키워드 표(assistant/tool_groups.json)로 묶음을 고르고
 * 그 묶음의 도구만 보낸다. 키워드 표는 A파트가 점검 결과를 보고 고치므로 코드에 박지 않는다.
 * <ul>
 * <li>띄어쓰기를 지운 말에 키워드가 들어 있으면 그 묶음이 걸린다 (형태소 분석 안 함). 여러 묶음이면 합친다.
 * <li>이어지는 말이면 바로 전 질문에 부른 도구와, 확인을 기다리는 저장 일의 도구가 든 묶음을 더한다.
 * <li>아무것도 안 걸리면 7개 다 (지금과 같음). 잘못 골랐으면 GeminiBrain 이 7개로 한 번 다시 묻는다 (R5).
 * </ul>
 */
final class ToolPicker {

    static final List<String> ALL = List.of("search_place", "recommend_trip", "find_nearby", "get_my_info",
            "update_my_info", "set_map_layers", "report_problem");

    static final String FILE = "/assistant/tool_groups.json";

    /**
     * 키워드 표 한 줄.
     *
     * @param group 묶음 이름 (길, 가까운 곳, 가게, 내 정보, 지도 표시, 신고)
     * @param words 걸리는 말 (띄어쓰기 없이)
     * @param tools 보낼 도구
     * @param rules 같이 보낼 규칙 조각 (R3에서 씀)
     */
    record Group(String group, List<String> words, List<String> tools, List<String> rules) {

        Group {
            words = words == null ? List.of() : List.copyOf(words);
            tools = tools == null ? List.of() : List.copyOf(tools);
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    static final List<Group> GROUPS = load();

    private ToolPicker() {
    }

    private static List<Group> load() {
        try (InputStream in = ToolPicker.class.getResourceAsStream(FILE)) {
            if (in == null) {
                throw new IllegalStateException(FILE + " 이 없어요");
            }
            List<Group> groups = List.of(JsonMapper.builder().build().readValue(in, Group[].class));
            for (Group g : groups) {
                for (String tool : g.tools()) {
                    if (!ALL.contains(tool)) {
                        throw new IllegalStateException(FILE + " 의 '" + g.group() + "'에 없는 도구 " + tool);
                    }
                }
            }
            return groups;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 이번 질문에 걸린 묶음 */
    static List<Group> groups(Turn t) {
        String flat = t.text == null ? "" : t.text.replace(" ", "");
        Set<String> follow = new LinkedHashSet<>(t.convo.lastTools());
        if (t.convo.pending() != null) {
            follow.add(t.convo.pending().tool());
        }
        // 대화가 이어지는 중이면 길 흐름 (후보 고르기 "두 번째 거", 목적지가 정해진 뒤 "응 가자")
        if (!t.convo.candidates().isEmpty() || t.convo.destination() != null || "destination".equals(t.convo.asked())) {
            follow.add("recommend_trip");
        }
        List<Group> out = new ArrayList<>();
        for (Group g : GROUPS) {
            boolean word = g.words().stream().anyMatch(flat::contains);
            boolean before = g.tools().stream().anyMatch(follow::contains);
            if (word || before) {
                out.add(g);
            }
        }
        return out;
    }

    /** 이번 질문에 보낼 도구 이름 (ALL 의 차례대로). 아무 묶음도 안 걸리면 ALL */
    static List<String> pick(Turn t) {
        List<Group> groups = groups(t);
        if (groups.isEmpty()) {
            return ALL;
        }
        Set<String> names = new LinkedHashSet<>();
        for (Group g : groups) {
            names.addAll(g.tools());
        }
        return ALL.stream().filter(names::contains).toList();
    }

    /** 도구 설명 목록에서 고른 것만 */
    static List<Map<String, Object>> declarations(List<Map<String, Object>> all, List<String> names) {
        return all.stream().filter(d -> names.contains(String.valueOf(d.get("name")))).toList();
    }
}
